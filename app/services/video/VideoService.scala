package services.video

import java.io.File

import akka.actor.ActorSystem
import javax.inject.Inject
import model.Track
import org.im4java.core.{ConvertCmd, IMOperation}
import play.api.Logger
import services.mediainfo.{MediainfoInterpreter, MediainfoService}

import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process.{ProcessLogger, _}

class VideoService @Inject()(val akkaSystem: ActorSystem, mediainfoService: MediainfoService) extends MediainfoInterpreter with AvconvPadding {

  val logger = ProcessLogger(l => Logger.info("avconv: " + l))

  def thumbnail(input: File, outputFormat: String, width: Option[Int], height: Option[Int], sourceAspectRatio: Option[Double], rotation: Option[Int]): Future[Option[File]] = {

    implicit val videoProcessingExecutionContext: ExecutionContext = akkaSystem.dispatchers.lookup("video-processing-context")

    mediainfoService.mediainfo(input).flatMap { mediainfo =>
      val rotationToApply = rotation.getOrElse(0)

      Future {
        val output: File = File.createTempFile("thumbnail", "." + outputFormat)

        val outputSize = width.flatMap(w =>
          height.map { h =>
            (w, h)
          }
        )

        val avconvCmd = avconvInput(input, mediainfo) ++
          vfParametersFor(rotationToApply, outputSize) ++
          Seq("-ss", "00:00:00", "-r", "1", "-an", "-vframes", "1", output.getAbsolutePath)

        Logger.info("ffmpeg command: " + avconvCmd)

        val process: Process = avconvCmd.run(logger)
        val exitValue: Int = process.exitValue() // Blocks until the process completes

        if (exitValue == 0) {
          Logger.info("Thumbnail output to: " + output.getAbsolutePath)
          Some(output)

        } else {
          Logger.warn("avconv process failed: " + avconvCmd)
          output.delete
          None
        }
      }
    }
  }

  def audio(input: File): Future[Option[File]] = {
    implicit val videoProcessingExecutionContext: ExecutionContext = akkaSystem.dispatchers.lookup("video-processing-context")

    mediainfoService.mediainfo(input).map { mediainfo =>
      val output = File.createTempFile("audio", "." + "wav")

      val avconvCmd = avconvInput(input, mediainfo) ++ Seq("-vn", output.getAbsolutePath)
      Logger.info("Processing video audio track")
      Logger.info("avconv command: " + avconvCmd.mkString(" "))

      if (avconvCmd.run(logger).exitValue() == 0) {
        Logger.info("Transcoded video output to: " + output.getAbsolutePath)
        Some(output)

      } else {
        Logger.warn("avconv process failed: " + avconvCmd)
        output.delete
        None
      }

    }
  }

  def transcode(input: File, outputFormat: String, outputSize: Option[(Int, Int)], sourceAspectRatio: Option[Double], rotation: Option[Int]): Future[Option[File]] = {
    implicit val videoProcessingExecutionContext: ExecutionContext = akkaSystem.dispatchers.lookup("video-processing-context")

    mediainfoService.mediainfo(input).flatMap { mediainfo =>
      val rotationToApply = rotation.getOrElse(0)
      val sourceDimensions: Option[(Int, Int)] = videoDimensions(mediainfo)
      val possiblePadding = padding(sourceDimensions, outputSize, sourceAspectRatio, rotationToApply)

      Future {
        val outputFile = File.createTempFile("transcoded", "." + outputFormat)
        val avconvCmd = avconvInput(input, mediainfo) ++
          vfParametersFor(rotationToApply, outputSize) ++
          Seq("-b:a", "128k", "-strict", "experimental", outputFile.getAbsolutePath)

        Logger.info("avconv command: " + avconvCmd.mkString(" "))

        val process: Process = avconvCmd.run(logger)
        val exitValue: Int = process.exitValue() // Blocks until the process completes

        if (exitValue == 0) {
          Logger.info("Transcoded video output to: " + outputFile.getAbsolutePath)
          Some(outputFile)

        } else {
          Logger.warn("avconv process failed: " + avconvCmd)
          outputFile.delete
          None
        }
      }
    }
  }

  private def sizeParameters(width: Option[Int], height: Option[Int]): Seq[String] = {
    val map: Option[Seq[String]] = width.flatMap(w =>
      height.map(h => Seq("-s", w + "x" + h))
    )
    map.fold(Seq[String]())(s => s)
  }

  private def vfParametersFor(rotation: Int, outputSize: Option[(Int, Int)]): Seq[String] = {

    val RotationTransforms = Map(
      90 -> "transpose=1",
      180 -> "hflip,vflip",
      270 -> "transpose=2"
    )

    val possibleRotation: Option[String] = RotationTransforms.get(rotation)
    val scaleToOutputSize: Option[String] = outputSize.map { os =>
      "scale=" + os._1 + ":" + os._2 + ":force_original_aspect_ratio=decrease"
    }
    val padding: Option[String] = outputSize.map { os =>
      val paddingColour = "black"
      "pad=" + os._1 + ":" + os._2 + ":(ow-iw)/2:(oh-ih)/2:" + paddingColour
    }
    val vfParameters: Seq[String] = Seq(possibleRotation, scaleToOutputSize, padding).flatten

    if (vfParameters.nonEmpty) Seq("-vf", vfParameters.mkString(",")) else Seq()
  }

  private def avconvInput(input: File, mediainfo: Option[Seq[Track]]): Seq[String] = {
    Seq("ffmpeg", "-y") ++ videoCodec(mediainfo).flatMap(c => if (c == "WMV3") Some(Seq("-c:v", "wmv3")) else None).getOrElse(Seq()) ++ Seq("-i", input.getAbsolutePath)
  }

}