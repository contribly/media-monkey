package services.facedetection

import java.io.File

import javax.inject.Inject
import model.Point
import org.joda.time.{DateTime, Duration}
import org.openimaj.image.ImageUtilities
import org.openimaj.image.processing.face.detection.HaarCascadeDetector
import play.api.Logger

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}

class FaceDetector @Inject()() {

  def detectFaces(source: File)(implicit ec: ExecutionContext): Future[Seq[model.DetectedFace]] = {
    Future {

      def asPercentage(i: Float, of: Int) = {
        val percentage = (i / of) * 100
        BigDecimal.decimal(percentage).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble
      }

      Logger.info("Detecting faces in file: " + source.getAbsolutePath)
      val start = DateTime.now()

      val fImage = ImageUtilities.readF(source)
      val detected = new HaarCascadeDetector().detectFaces(fImage).map { r =>
        val b = r.getBounds()

        val topLeftBound = Point(asPercentage(b.getTopLeft.getX, fImage.width), asPercentage(b.getTopLeft.getY, fImage.height))
        val bottomRightBound = Point(asPercentage(b.getBottomRight.getX.toInt, fImage.width), asPercentage(b.getBottomRight.getY.toInt, fImage.height))

        model.DetectedFace(bounds = model.Bounds(topLeftBound, bottomRightBound), confidence = r.getConfidence)
      }

//      Logger.info("Detected " + detected.size + " in " + new Duration(start, DateTime.now))
      detected
    }
  }

}
