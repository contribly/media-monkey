package services.video

import java.io.File
import java.time.Instant
import java.time.Duration
import akka.actor.ActorSystem
import io.micrometer.core.instrument.{MeterRegistry, Timer}

import javax.inject.Inject
import model.Track
import services.mediainfo.{MediainfoInterpreter, MediainfoService}
import utils.GlobalLogger.logger

import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process.{ProcessLogger, _}

class VideoService @Inject()(
                              val akkaSystem: ActorSystem, mediainfoService: MediainfoService, meterRegistry: MeterRegistry
                            ) extends MediainfoInterpreter with AvconvPadding {

  val processLogger = ProcessLogger(l => logger.debug("avconv: " + l))

  private val thumbnailMeter = Timer.builder("contribly.mediamonkey.video_thumbnail")
    .description("MediaMonkey video thumbnail")
    .publishPercentileHistogram()
    .withRegistry(meterRegistry)
  private val audioMeter = Timer.builder("contribly.mediamonkey.video_extraction")
    .description("MediaMonkey video audio extraction")
    .publishPercentileHistogram()
    .withRegistry(meterRegistry)
  private val transcodeMeter = Timer.builder("contribly.mediamonkey.video_transcode")
    .description("MediaMonkey video transcode")
    .publishPercentileHistogram()
    .withRegistry(meterRegistry)

  def thumbnail(
                 input: File,
                 outputFormat: String,
                 width: Option[Int],
                 height: Option[Int],
                 sourceAspectRatio: Option[Double],
                 rotation: Option[Int]
               ): Future[Option[File]] = {

    implicit val videoProcessingExecutionContext: ExecutionContext = akkaSystem.dispatchers.lookup("video-processing-context")

    logger.debug(s"Generating thumbnail for input file: ${input.getAbsolutePath}")

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

        val startTime = Instant.now
        logger.debug("ffmpeg command: " + avconvCmd)

        val sample = Timer.start(meterRegistry)
        val process: Process = avconvCmd.run(processLogger)
        val exitValue: Int = process.exitValue() // Blocks until the process completes


        if (exitValue == 0) {
          sample.stop(thumbnailMeter.withTags("size", s"${width.getOrElse(0)}x${height.getOrElse(0)}"))
          logger.debug("Thumbnail:" + outputSize + " - to: " + output.getAbsolutePath)
          val duration = Duration.between(startTime, Instant.now).toMillis
          logger.info(s"Thumbnail: $outputSize creation took $duration ms")
          Some(output)

        } else {
          logger.warn("avconv process failed: " + avconvCmd)
          output.delete
          None
        }
      }
    }
  }

  def audio(input: File): Future[Option[File]] = {
    implicit val videoProcessingExecutionContext: ExecutionContext = akkaSystem.dispatchers.lookup("video-processing-context")

    logger.debug(s"Extracting audio from input file: ${input.getAbsolutePath}")

    mediainfoService.mediainfo(input).map { mediainfo =>
      val output = File.createTempFile("audio", "." + "wav")

      val avconvCmd = avconvInput(input, mediainfo) ++ Seq("-vn", output.getAbsolutePath)
      val startTime = Instant.now
      logger.debug("Processing video audio track")
      logger.debug("avconv command: " + avconvCmd.mkString(" "))

      val sample = Timer.start(meterRegistry)

      if (avconvCmd.run(processLogger).exitValue() == 0) {
        sample.stop(audioMeter.withTags())
        logger.info("Transcoded audio output to: " + output.getAbsolutePath)
        val duration = Duration.between(startTime, Instant.now).toMillis
        logger.info(s"Audio extraction took $duration ms")
        Some(output)

      } else {
        logger.warn("avconv process failed: " + avconvCmd)
        output.delete
        None
      }

    }
  }

  def transcode(
                 input: File,
                 outputFormat: String,
                 outputSize: Option[(Int, Int)],
                 sourceAspectRatio: Option[Double],
                 rotation: Option[Int]
               ): Future[Option[File]] = {
    implicit val videoProcessingExecutionContext: ExecutionContext = akkaSystem.dispatchers.lookup("video-processing-context")

    logger.debug(s"Transcoding input file: ${input.getAbsolutePath}")

    mediainfoService.mediainfo(input).flatMap { mediainfo =>
      val rotationToApply = rotation.getOrElse(0)
      val sourceDimensions: Option[(Int, Int)] = videoDimensions(mediainfo)
      val possiblePadding = padding(sourceDimensions, outputSize, sourceAspectRatio, rotationToApply)

      Future {
        val outputFile = File.createTempFile("transcoded", "." + outputFormat)
        val avconvCmd = avconvInput(input, mediainfo) ++
          vfParametersFor(rotationToApply, outputSize) ++
          Seq("-b:a", "128k", "-strict", "experimental", outputFile.getAbsolutePath)

        val startTime = Instant.now
        logger.debug("avconv command: " + avconvCmd.mkString(" "))

        val sample = Timer.start(meterRegistry)
        val process: Process = avconvCmd.run(processLogger)
        val exitValue: Int = process.exitValue() // Blocks until the process completes


        if (exitValue == 0) {
          sample.stop(transcodeMeter.withTags("size", s"${outputSize.map(_._1).getOrElse(0)}x${outputSize.map(_._2).getOrElse(0)}"))
          logger.debug("Transcoded video output to: " + outputFile.getAbsolutePath)
          val duration = Duration.between(startTime, Instant.now).toMillis
          logger.info(s"Video Transcoding took $duration ms")
          Some(outputFile)

        } else {
          logger.warn("avconv process failed: " + avconvCmd)
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
    Seq("ffmpeg", "-y") ++
      videoCodec(mediainfo).flatMap(c => if (c == "WMV3") Some(Seq("-c:v", "wmv3")) else None).getOrElse(Seq()) ++
      Seq("-i", input.getAbsolutePath)
  }

}
