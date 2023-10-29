package main

import com.monovore.decline.effect.CommandIOApp
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.Temporal
import cats.effect.Resource
import com.monovore.decline.Opts
import fs2.io.file.Files
import fs2.io.file.Path
import cats.effect.std.Console
import scala.concurrent.duration._
import cats.effect.implicits._
import cats.effect.syntax.all._
import fs2.concurrent.SignallingRef
import cats.syntax.all._
import fs2.io.process.Processes
import fs2.io.process.ProcessBuilder
import fs2.io.process
import cats.effect.kernel.Outcome

object Main
    extends CommandIOApp(
      name = "watch-and-execute",
      header = "Watch and execute command",
      version = "0.0.1"
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

  def execCommand[F[_]: Processes](
      cmd: String
  ): Resource[F, process.Process[F]] = ProcessBuilder(cmd, Nil).spawn

  def run[F[_]: Processes: Temporal: Files: Console](
      cli: Cli
  ): Resource[F, Unit] =
    cats.effect.std.Queue.unbounded[F, Unit].toResource.flatMap { queue =>
      fs2.Stream
        .emit(cli.path)
        .covary[F]
        .flatMap(Files[F].walk(_))
        .parEvalMapUnordered(16)(path =>
          PathWatcher(path, cli.throttling)
            .use(_.changes.evalMap(queue.offer(_)).compile.drain)
        )
        .concurrently(
          fs2.Stream
            .fromQueueUnterminated(queue)
            .debounce(cli.throttling)
            .evalMap(_ => execCommand(cli.cmd).use_)
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
      .option[Int]("wait", "Wait before execute (ms)")
      .withDefault(1000)
      .map(_.milliseconds)

    val cli = (path, cmd, throttling).mapN(Cli.apply)

    cli.map(run[IO](_).useForever.as(ExitCode.Success))
}
