package main

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Resource
import cats.effect.Temporal
import cats.effect.kernel.Outcome
import cats.effect.std.Console
import cats.effect.syntax.all._
import cats.syntax.all._
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.concurrent.SignallingRef
import fs2.io.file.Files
import fs2.io.file.Path
import fs2.io.process
import fs2.io.process.ProcessBuilder
import fs2.io.process.Processes
import scala.cli.build.BuildInfo
import scala.concurrent.duration._
import scala.io.AnsiColor
import cats.MonadThrow

object Main
    extends CommandIOApp(
      name = "watch-and-execute",
      header = "Watch and execute command",
      version = BuildInfo.projectVersion.getOrElse("NA")
    ) {

  case class Cli(
      path: Path,
      cmd: String,
      throttling: FiniteDuration
  )

  trait PathWatcher[F[_]] {

    /** stream of notifications */
    def changes: fs2.Stream[F, Unit]
  }

  object PathWatcher {
    def apply[F[_]: Temporal: Files: Console](
        p: Path,
        watchEvery: FiniteDuration
    )(implicit C: Console[F]): Resource[F, PathWatcher[F]] =
      for {
        currentLmt <- Files[F].getLastModifiedTime(p).toResource

        lmt <- SignallingRef
          .of[F, Option[FiniteDuration]](currentLmt.some)
          .toResource

        _ <- fs2.Stream
          .fixedDelay(watchEvery)
          .evalMap(_ =>
            Temporal[F].ifM(Files[F].exists(p))(
              Files[F]
                .getLastModifiedTime(p)
                .flatMap(dur => lmt.set(dur.some)),
              lmt.set(None)
            )
          )
          .interruptWhen(lmt.map(_.isEmpty))
          .compile
          .drain
          .background

      } yield new PathWatcher[F] {

        override def changes: fs2.Stream[F, Unit] =
          lmt.changes.discrete.as(()).tail
      }
  }

  def run[F[_]: Processes: Temporal: Files: Console: MonadThrow](
      cli: Cli
  ): Resource[F, Unit] =
    Console[F]
      .print(
        s"""|👀 Started watching path ${cli.path} with following with settings:
      |👉${AnsiColor.CYAN}Path: ${cli.path}
      |👉Command: `${cli.cmd}`
      |👉${AnsiColor.CYAN}Throttling: ${cli.throttling}${AnsiColor.RESET}\n""".stripMargin
      )
      .toResource *>
      cats.effect.std.Queue.unbounded[F, Unit].toResource.flatMap { queue =>
        fs2.Stream
          .emit(cli.path)
          .covary[F]
          .flatMap(
            Files[F]
              .walk(_)
              .onError(err =>
                fs2.Stream.eval(
                  Console[F].errorln(
                    s"${AnsiColor.RED}Error: ${err.getMessage}${AnsiColor.RESET}"
                  )
                )
              )
          )
          .parEvalMapUnordered(16)(path =>
            PathWatcher(path, cli.throttling)
              .use(_.changes.evalMap(queue.offer(_)).compile.drain)
          )
          .concurrently(
            fs2.Stream
              .fromQueueUnterminated(queue)
              .debounce(cli.throttling)
              .evalMap(_ =>
                Console[F].print(
                  s"💻 Executing command ${AnsiColor.MAGENTA}`${cli.cmd}`${AnsiColor.RESET}\n"
                ) *> ProcessBuilder(cli.cmd, Nil).spawn.use_ // TODO: stream std out
              )
          )
          .compile
          .drain
          .background
          .as(())
      }

  override def main: Opts[IO[ExitCode]] =

    val path = Opts
      .option[String]("path", "Path to watch")
      .map(Path(_))

    val cmd = Opts.option[String]("cmd", "Command to execute")

    val throttling = Opts
      .option[Int]("wait", "Wait before execution (ms)")
      .withDefault(1000)
      .map(_.milliseconds)

    val cli = (path, cmd, throttling).mapN(Cli.apply)

    cli.map(run[IO](_).useForever.as(ExitCode.Success))
}
