package services.mediainfo

import java.io.File

import javax.inject.Inject
import model.Track
import play.api.Logger

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.{global => ec}
import scala.sys.process.{ProcessLogger, _}

class MediainfoService @Inject()(val mediainfoParser: MediainfoParser) {

  Logger.info("media info service called")

  def mediainfo(f: File): Future[Option[Seq[Track]]] = {
    Future {
      Logger.info("inside future")
      Logger.info(f.toString)
      val mediainfoCmd = Seq("mediainfo", "--Output=XML", f.getAbsolutePath)

      val out: StringBuilder = new StringBuilder()
      val logger: ProcessLogger = ProcessLogger(l => {
        out.append(l)
      })

      Logger.info("run command media info")

      val process: Process = mediainfoCmd.run(logger)

      Logger.info("after run command media info")

      val exitValue: Int = process.exitValue() // Blocks until the process completes

      Logger.info("after exit value")

      if (exitValue == 0) {
        Logger.info("exit value 0")
        Some(mediainfoParser.parse(out.mkString))

      } else {
        Logger.warn("mediainfo process failed")
        None
      }
    }.recover {
      case t: Throwable =>
        Logger.error("exiftool call failed", t)
        None
    }
  }

}
