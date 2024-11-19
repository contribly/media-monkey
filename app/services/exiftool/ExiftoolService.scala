package services.exiftool

import java.io.File
import java.nio.charset.Charset

import javax.inject.Inject
import org.apache.commons.io.FileUtils
import play.api.Logger
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process.{ProcessLogger, _}

class ExiftoolService @Inject()(val temporaryFileCreator: TemporaryFileCreator) {

  // TODO this function  can be replaced with tika /detect/stream
  def contentType(f: File)(implicit ec: ExecutionContext): Future[Option[String]] = {

    def parse(json: String): Option[String] = {
      Json.parse(json).\\("MIMEType").headOption.map { j =>
        j.as[String]
      }
    }

    Future {
      val cmd = Seq("exiftool", "-json", f.getAbsolutePath)

      val out = new StringBuilder()
      val logger = ProcessLogger(l => {
        out.append(l)
      })

      val process = cmd.run(logger)

      val exitValue = process.exitValue()
      if (exitValue == 0) {
        parse(out.mkString)

      } else {
        Logger.warn("exiftool process failed for file: " + f.getAbsolutePath + " / " + out.mkString)
        None
      }

    }.recover {
      case t: Throwable =>
        Logger.error("exiftool call failed", t)
        None
    }
  }

  def extractXmp(f: File)(implicit ec: ExecutionContext): Future[Option[String]] = {
    Future {
      val cmd = Seq("exiftool", "-xmp", "-b", f.getAbsolutePath)

      val out = new StringBuilder()
      val logger = ProcessLogger(l => {
        out.append(l)
      })

      val process = cmd.run(logger)

      val exitValue = process.exitValue()
      if (exitValue == 0) {
        Some(out.mkString)

      } else {
        Logger.warn("exiftool process failed for file: " + f.getAbsolutePath + " / " + out.mkString)
        None
      }

    }.recover {
      case t: Throwable =>
        Logger.error("exiftool call failed", t)
        None
    }
  }

  def addMeta(f: File, tags: Seq[(String, String, String)])(implicit ec: ExecutionContext): Future[Option[File]] = {
    val outputFile = temporaryFileCreator.create("meta", ".tmp")
    FileUtils.copyFile(f, outputFile.file)

    val UTF8 = Charset.forName("UTF-8")

    Future {
      val tagArguments = tags.map { f =>
        val fieldFile = temporaryFileCreator.create("meta", ".field")
        FileUtils.writeStringToFile(fieldFile.file, f._3, UTF8)
        ("-" + f._1 + ":" + f._2 + "<=" + fieldFile.file.getAbsolutePath, fieldFile)
      }

      val cmd = Seq("exiftool") ++ tagArguments.map(_._1) :+ outputFile.file.getAbsolutePath
      Logger.debug("Exiftool command: " + cmd)

      val out = new StringBuilder()
      val logger = ProcessLogger(l => {
        out.append(l)
      })

      val process = cmd.run(logger)
      val exitValue = process.exitValue()

      Logger.debug("Clearing down " + tagArguments.size + " temp files after exiftool")
      tagArguments.map { ta =>
        ta._2.file.delete()
      }

      if (exitValue == 0) {
        Some(outputFile.file)

      } else {
        Logger.warn("exiftool process failed for file: " + f.getAbsolutePath + " / " + out.mkString)
        None
      }

    }.recover { // TODO clear down files
      case t: Throwable =>
        Logger.error("exiftool call failed with an exception", t)
        None
      case _ =>
        None
    }
  }
  
}