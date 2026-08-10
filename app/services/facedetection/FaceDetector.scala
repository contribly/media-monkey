package services.facedetection

import io.micrometer.core.instrument.{MeterRegistry, Timer}
import model.Point
import org.joda.time.DateTime
import org.openimaj.image.ImageUtilities
import org.openimaj.image.processing.face.detection.HaarCascadeDetector
import utils.GlobalLogger.logger

import java.io.File
import javax.inject.Inject
import scala.collection.convert.ImplicitConversions._
import scala.concurrent.{ExecutionContext, Future}

class FaceDetector @Inject()(meterRegistry: MeterRegistry) {

  private val meter = Timer.builder("contribly.mediamonkey.face_detect")
    .description("MediaMonkey face detection")
    .publishPercentileHistogram()
    .withRegistry(meterRegistry)

  def detectFaces(source: File)(implicit ec: ExecutionContext): Future[Seq[model.DetectedFace]] = {
    Future {

      def asPercentage(i: Float, of: Int) = {
        val percentage = (i / of) * 100
        BigDecimal.decimal(percentage).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble
      }

      logger.debug("Detecting faces in file: " + source.getAbsolutePath)
      val start = DateTime.now()

      val sample = Timer.start(meterRegistry)
      val fImage = ImageUtilities.readF(source)
      val detected = new HaarCascadeDetector().detectFaces(fImage).toSeq.map { r =>
        val b = r.getBounds()

        val topLeftBound = Point(asPercentage(b.getTopLeft.getX, fImage.width), asPercentage(b.getTopLeft.getY, fImage.height))
        val bottomRightBound = Point(asPercentage(b.getBottomRight.getX.toInt, fImage.width), asPercentage(b.getBottomRight.getY.toInt, fImage.height))

        model.DetectedFace(bounds = model.Bounds(topLeftBound, bottomRightBound), confidence = r.getConfidence)
      }
      sample.stop(meter.withTags())

//      logger.info("Detected " + detected.size + " in " + new Duration(start, DateTime.now))
      detected
    }
  }

}
