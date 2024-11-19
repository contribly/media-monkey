package services.images

import java.io.File

import akka.actor.ActorSystem
import javax.inject.Inject
import org.im4java.core.{ConvertCmd, IMOperation, Info}
import org.joda.time.DateTime
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

class ImageService @Inject()(akkaSystem: ActorSystem) {

  def info(input: File): Future[(Int, Int)] = {
    implicit val imageProcessingExecutionContext = akkaSystem.dispatchers.lookup("image-processing-context")

    Future {
      val imageInfo = new Info(input.getAbsolutePath, true)
      (imageInfo.getImageWidth, imageInfo.getImageHeight)
    }
  }

  def cropImage(input: File, width: Int, height: Int, x: Int, y: Int, outputFormat: String): Future[Option[File]] = {

    def imCropOperation(width: Int, height: Int, x: Int, y: Int): IMOperation = {
        val op = new IMOperation()
        addInputImageUsingFirstLayer(op)
        op.crop(width, height, x, y)
        op.strip()
        op.addImage()
        op
    }

    implicit val imageProcessingExecutionContext = akkaSystem.dispatchers.lookup("image-processing-context")
    Future {
      val outputFile = File.createTempFile("image", "." + outputFormat)
      Logger.debug("Applying ImageMagik operation to output file: " + outputFile.getAbsoluteFile)
      try {
        val start = DateTime.now
        val cmd: ConvertCmd = new ConvertCmd()
        cmd.run(imCropOperation(width, height, x, y), input.getAbsolutePath, outputFile.getAbsolutePath())

        val duration = DateTime.now.getMillis - start.getMillis
        Logger.info("Completed ImageMagik crop operation " + Seq(width, height, x, y) + " output to: " + outputFile.getAbsolutePath() + " in " + duration + "ms")
        Some(outputFile)

      } catch {
        case e: Exception => {
          Logger.error("Exception while executing IM operation", e)
          outputFile.delete()
          None
        }
      }
    }

  }

  def workingSize(input: File)(implicit ec: ExecutionContext): Future[Option[File]] = {
    val op = new IMOperation()
    addInputImageUsingFirstLayer(op)
    op.autoOrient()
    op.resize(null, null, "800>")
    op.addImage()

    Future {
      Logger.debug("Applying ImageMagik operation to input file: " + input.getAbsoluteFile + ": " + input.canRead)

      val outputFile = File.createTempFile("workingimage", "." + "jpg")
      try {
        val start = DateTime.now
        val cmd: ConvertCmd = new ConvertCmd()
        cmd.run(op, input.getAbsolutePath, outputFile.getAbsolutePath())

        val duration = DateTime.now.getMillis - start.getMillis
        Logger.info("Completed ImageMagik working image operation output to: " + outputFile.getAbsolutePath() + " in " + duration + "ms")
        Some(outputFile)

      } catch {
        case e: Exception => {
          Logger.error("Exception while executing IM operation", e)
          outputFile.delete()
          None
        }

      }
    }
  }

  def resizeImage(input: File, width: Option[Int], height: Option[Int], rotate: Double, outputFormat: String, fill: Boolean, gravity: Option[String]): Future[Option[File]] = {

    def imResizeOperation(width: Option[Int], height: Option[Int], rotate: Double, fill: Boolean): IMOperation = {

      val PermittedGravities = Set("North", "Center")
      val g = gravity.flatMap(g => PermittedGravities.find(i => i == g)).getOrElse("Center")

      if (fill) {
        val op = new IMOperation()
        addInputImageUsingFirstLayer(op)
        op.autoOrient()
        op.rotate(rotate)

        width.flatMap { w =>
          height.map { h =>
            op.resize(w, h, "^")
            op.gravity(g)
            op.extent(w, h)
          }
        }
        op.strip()
        op.addImage()
        op

      } else {
        val op = new IMOperation()
        addInputImageUsingFirstLayer(op)
        op.autoOrient()
        op.rotate(rotate)

        width.flatMap { w =>
          height.map { h =>
            op.resize(w, h)
          }
        }

        op.strip()
        op.addImage()
        op
      }
    }

    implicit val imageProcessingExecutionContext = akkaSystem.dispatchers.lookup("image-processing-context")

    Future {
      val outputFile = File.createTempFile("image", "." + outputFormat)
      Logger.debug("Applying ImageMagik operation to output file: " + outputFile.getAbsoluteFile)
      try {
        val start = DateTime.now
        val cmd = new ConvertCmd()
        cmd.run(imResizeOperation(width, height, rotate, fill), input.getAbsolutePath, outputFile.getAbsolutePath())

        val duration = DateTime.now.getMillis - start.getMillis
        Logger.info("Completed ImageMagik resize operation " + Seq(width, height, rotate, fill) + " output to: " + outputFile.getAbsolutePath() + " in " + duration + "ms")
        Some(outputFile)

      } catch {
        case e: Exception => {
          Logger.error("Exception while executing IM operation; may be recoverable", e)
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

  private def addInputImageUsingFirstLayer(op: IMOperation): Unit = {
    op.addImage("[0]")
  }

}