package services.images

import akka.actor.ActorSystem
import io.micrometer.core.instrument.{MeterRegistry, Timer}
import org.joda.time.DateTime
import utils.GlobalLogger.logger
import app.photofox.vipsffm._
import app.photofox.vipsffm.enums._

import java.io.File
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ImageService @Inject()(akkaSystem: ActorSystem, meterRegistry: MeterRegistry) {

  private val cropMeter = Timer.builder("contribly.mediamonkey.image_crop")
    .description("MediaMonkey image crop")
    .publishPercentileHistogram()
    .withRegistry(meterRegistry)
  private val workResizeMeter = Timer.builder("contribly.mediamonkey.image_work_resize")
    .description("MediaMonkey image resize for processing")
    .publishPercentileHistogram()
    .withRegistry(meterRegistry)
  private val resizeMeter = Timer.builder("contribly.mediamonkey.image_resize")
    .description("MediaMonkey image resize")
    .publishPercentileHistogram()
    .withRegistry(meterRegistry)

  def info(input: File): Future[(Int, Int)] = {
    implicit val imageProcessingExecutionContext = akkaSystem.dispatchers.lookup("image-processing-context")

    Future {
      var dimensions: (Int, Int) = null
      Vips.run { arena =>
        val image = VImage.newFromFile(arena, input.getAbsolutePath)
        dimensions = (image.getWidth, image.getHeight)
      }
      dimensions
    }
  }

  def cropImage(input: File, width: Int, height: Int, x: Int, y: Int, outputFormat: String): Future[Option[File]] = {
    implicit val imageProcessingExecutionContext = akkaSystem.dispatchers.lookup("image-processing-context")
    Future {
      val outputFile = File.createTempFile("image", "." + outputFormat)
      logger.debug("Applying vips operation to output file: " + outputFile.getAbsoluteFile)
      try {
        val start = DateTime.now
        val sample = Timer.start(meterRegistry)
        Vips.run { arena =>
          val image = VImage.newFromFile(arena, input.getAbsolutePath, VipsOption.Boolean("autorotate", true))
          val cropped = image.extractArea(x, y, width, height)
          cropped.writeToFile(outputFile.getAbsolutePath, VipsOption.Boolean("strip", true))
        }
        sample.stop(cropMeter.withTags("size", s"${width}x$height"))

        val duration = DateTime.now.getMillis - start.getMillis
        logger.info("Completed vips crop operation " + Seq(width, height, x, y) + " output to: " + outputFile.getAbsolutePath() + " in " + duration + "ms")
        Some(outputFile)

      } catch {
        case e: Exception => {
          logger.error("Exception while executing vips operation", e)
          outputFile.delete()
          None
        }
      }
    }

  }

  def workingSize(input: File)(implicit ec: ExecutionContext): Future[Option[File]] = {
    Future {
      logger.debug("Applying vips operation to input file: " + input.getAbsoluteFile + ": " + input.canRead)

      val outputFile = File.createTempFile("workingimage", "." + "jpg")
      try {
        val start = DateTime.now
        val sample = Timer.start(meterRegistry)
        Vips.run { arena =>
          val thumb = VImage.thumbnail(arena, input.getAbsolutePath, 800, VipsOption.Boolean("auto-rotate", true))
          thumb.writeToFile(outputFile.getAbsolutePath, VipsOption.Boolean("strip", true))
        }
        sample.stop(workResizeMeter.withTags())

        val duration = DateTime.now.getMillis - start.getMillis
        logger.info("Completed vips working image operation output to: " + outputFile.getAbsolutePath() + " in " + duration + "ms")
        Some(outputFile)

      } catch {
        case e: Exception => {
          logger.error("Exception while executing vips operation", e)
          outputFile.delete()
          None
        }
      }
    }
  }

  def resizeImage(input: File, width: Option[Int], height: Option[Int], rotate: Double, outputFormat: String, fill: Boolean, gravity: Option[String]): Future[Option[File]] = {
    implicit val imageProcessingExecutionContext = akkaSystem.dispatchers.lookup("image-processing-context")

    Future {
      val outputFile = File.createTempFile("image", "." + outputFormat)
      logger.debug("Applying vips operation to output file: " + outputFile.getAbsoluteFile)
      try {
        val start = DateTime.now
        val sample = Timer.start(meterRegistry)

        Vips.run { arena =>
          val cropStrategy = if (fill) {
            gravity match {
              case Some("North") => VipsInteresting.INTERESTING_LOW
              case _ => VipsInteresting.INTERESTING_ATTENTION
            }
          } else {
            VipsInteresting.INTERESTING_NONE
          }

          val image = if (rotate != 0) {
            val loaded = VImage.newFromFile(arena, input.getAbsolutePath, VipsOption.Boolean("autorotate", true))
            loaded.rotate(rotate)
          } else {
            null
          }

          val resizable = width.isDefined && height.isDefined
          val finalImage = if (image == null) {
            if (resizable) {
              val w = width.get
              val h = height.get
              if (fill) {
                VImage.thumbnail(arena, input.getAbsolutePath, w, VipsOption.Int("height", h), VipsOption.Enum("crop", cropStrategy), VipsOption.Boolean("auto-rotate", true))
              } else {
                VImage.thumbnail(arena, input.getAbsolutePath, w, VipsOption.Int("height", h), VipsOption.Boolean("auto-rotate", true))
              }
            } else {
              VImage.newFromFile(arena, input.getAbsolutePath, VipsOption.Boolean("autorotate", true))
            }
          } else {
            if (resizable) {
              val w = width.get
              val h = height.get
              if (fill) {
                image.thumbnailImage(w, VipsOption.Int("height", h), VipsOption.Enum("crop", cropStrategy))
              } else {
                image.thumbnailImage(w, VipsOption.Int("height", h))
              }
            } else {
              image
            }
          }
          finalImage.writeToFile(outputFile.getAbsolutePath, VipsOption.Boolean("strip", true))
        }

        sample.stop(resizeMeter.withTags("size", s"${width.getOrElse(0)}x${height.getOrElse(0)}"))

        val duration = DateTime.now.getMillis - start.getMillis
        logger.info("Completed vips resize operation " + Seq(width, height, rotate, fill) + " output to: " + outputFile.getAbsolutePath() + " in " + duration + "ms")
        Some(outputFile)

      } catch {
        case e: Exception => {
          logger.error("Exception while executing vips operation; may be recoverable", e)
          if (outputFile.canRead && outputFile.length() > 0) {
            Some(outputFile)
          } else {
            outputFile.delete()
            None
          }
        }
      }
    }

  }

}
