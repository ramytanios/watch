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
import scala.concurrent.duration.FiniteDuration
import cats.effect.implicits._
import fs2.concurrent.SignallingRef
import cats.syntax.all._
import fs2.io.process.Processes
import fs2.io.process.ProcessBuilder
import fs2.io.process
import cats.data.NonEmptyList

object Main
    extends CommandIOApp(
      name = "watch-and-execute",
      header = "Watch and execute command",
      version = "0.0.1"
    ) {

  case class Cli(
      paths: NonEmptyList[Path],
      cmd: String,
      throttling: Option[FiniteDuration]
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

  override def main: Opts[IO[ExitCode]] = ???
}
