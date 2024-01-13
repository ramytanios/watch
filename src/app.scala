package watch

import cats.ApplicativeThrow
import cats.data.NonEmptyList
import cats.effect.*
import cats.effect.std.*
import cats.effect.syntax.all.*
import cats.syntax.all._
import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp
import fs2.concurrent.SignallingRef
import fs2.io.file.*
import fs2.io.process
import fs2.io.process.*

import scala.cli.build.BuildInfo
import scala.concurrent.duration.*
import scala.io.AnsiColor
import scala.util.control.NoStackTrace

// stream of notifications
trait PathWatcher[F[_]]:
  def changes: fs2.Stream[F, Unit]

object PathWatcher:
  def apply[F[_]](p: Path, watchEvery: FiniteDuration)(using
      C: Console[F],
      T: Temporal[F],
      F: Files[F]
  ): Resource[F, PathWatcher[F]] =
    for
      currentLmt <- F.getLastModifiedTime(p).toResource

      lmt <- SignallingRef
        .of[F, Option[FiniteDuration]](currentLmt.some)
        .toResource

      _ <- fs2.Stream
        .fixedDelay(watchEvery)
        .evalMap(_ =>
          T.ifM(F.exists(p))(
            Files[F]
              .getLastModifiedTime(p)
              .flatMap: dur =>
                lmt.set(dur.some),
            lmt.set(None)
          )
        )
        .interruptWhen(lmt.map(_.isEmpty))
        .compile
        .drain
        .background
    yield new PathWatcher[F]:
      override def changes: fs2.Stream[F, Unit] =
        lmt.changes.discrete.as(()).tail

opaque type Command = String
object Command:
  def apply(t: String): Command = t
  extension (t: Command) def value: String = t

opaque type Argument = String
object Argument:
  def apply(t: String): Argument = t
  extension (t: Argument) def value: String = t

class MissingCommand(override val getMessage: String) extends NoStackTrace

class AppImpl[F[_]: Processes: Temporal: Files: Console]:

  val C = Console[F]

  def ensureCommand(cmd: Command): F[Unit] =
    ProcessBuilder("which", cmd.value).spawn.use: proc =>
      proc.exitValue.flatMap: exv =>
        ApplicativeThrow[F].raiseWhen(exv != 0)(
          new MissingCommand(s"Command ${cmd.value} is not available")
        )

  def run(
      path: Path,
      cmd: Command,
      args: Option[NonEmptyList[Argument]],
      debounceRate: FiniteDuration
  ): Resource[F, Unit] = ensureCommand(cmd).toResource *> C
    .print(
      s"""|👀 Started watching path ${path} with following with settings:${AnsiColor.CYAN}
      |👉Path: ${path}
      |👉Command: ${cmd} ${args
           .map(_.toList.mkString(" "))
           .getOrElse("")}
      |👉Args: ${args.map(_.toList.mkString(" ")).getOrElse("None")}
      |👉Throttling: ${debounceRate}${AnsiColor.RESET}\n""".stripMargin
    )
    .toResource *>
    Queue
      .unbounded[F, Unit]
      .toResource
      .flatMap: queue =>
        fs2.Stream
          .emit(path)
          .covary[F]
          .flatMap(
            Files[F]
              .walk(_)
              .onError: err =>
                fs2.Stream.eval(
                  C.errorln(
                    s"${AnsiColor.RED}Error: ${err.getMessage}${AnsiColor.RESET}"
                  )
                )
          )
          .parEvalMapUnordered(16): path =>
            PathWatcher(path, debounceRate)
              .use(_.changes.evalMap(queue.offer(_)).compile.drain)
          .concurrently(
            fs2.Stream
              .fromQueueUnterminated(queue)
              .debounce(debounceRate)
              .evalMap(_ =>
                C.print(
                  s"💻 Executing command ${AnsiColor.MAGENTA}`${cmd}`${AnsiColor.RESET}\n"
                ) *> ProcessBuilder(
                  cmd.value,
                  args.map(_.toList.map(_.value)).orEmpty
                ).spawn
                  .use: proc =>
                    proc.stdout
                      .through(fs2.text.utf8.decode)
                      .through(fs2.text.lines)
                      .evalMap(Console[F].println)
                      .concurrently(
                        proc.stderr
                          .through(fs2.text.utf8.decode)
                          .through(fs2.text.lines)
                          .evalMap(Console[F].errorln)
                      )
                      .compile
                      .drain
              )
          )
          .compile
          .drain
          .background
          .as(())

object Main
    extends CommandIOApp(
      name = "watch",
      header = "Watch a directory and execute a command on change.",
      version = BuildInfo.projectVersion.getOrElse("NA")
    ):

  val path = Opts
    .option[String]("path", "Path to watch", "p")
    .map(Path(_))

  val cmd = Opts
    .option[String]("command", "Command to execute", "c")
    .map: s =>
      Command(s)

  val args = Opts
    .options[String]("args", "Args of command", "a")
    .map(_.map(s => Argument(s)))
    .orNone

  val debounceRate = Opts
    .option[Int]("debounce-rate", "Wait before execution (ms)", "d")
    .withDefault(1000)
    .map(_.milliseconds)

  override def main: Opts[IO[ExitCode]] =
    (path, cmd, args, debounceRate).mapN: (path, cmd, args, dr) =>
      AppImpl[IO]
        .run(path, cmd, args, dr)
        .useForever
        .as(ExitCode.Success)
