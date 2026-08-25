package services.mediainfo

import model.Track
import utils.GlobalLogger.logger

import java.io.File
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.{global => ec}
import scala.concurrent.Future
import scala.sys.process.{ProcessLogger, _}

class MediainfoService @Inject()(val mediainfoParser: MediainfoParser) {

  def mediainfo(f: File): Future[Option[Seq[Track]]] = {
    Future {
      logger.debug(f.toString)
      val mediainfoCmd = Seq("mediainfo", "--Output=XML", f.getAbsolutePath)

      val out: StringBuilder = new StringBuilder()
      val processLogger: ProcessLogger = ProcessLogger(l => {
        out.append(l)
      })

      val process: Process = mediainfoCmd.run(processLogger)

      val exitValue: Int = process.exitValue() // Blocks until the process completes

      if (exitValue == 0) {
        Some(mediainfoParser.parse(out.mkString))

      } else {
        logger.warn("mediainfo process failed")
        None
      }
    }.recover {
      case t: Throwable =>
        logger.error("exiftool call failed", t)
        None
    }
  }

}
