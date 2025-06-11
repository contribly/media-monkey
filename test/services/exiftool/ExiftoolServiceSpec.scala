package services.exiftool

import java.io.File

import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ExiftoolServiceSpec extends PlaySpec with GuiceOneServerPerSuite {

  val tenSeconds = Duration(10, SECONDS)

  val exifToolService = fakeApplication().injector.instanceOf[ExiftoolService]

  "can detect content type of media files" in {
    val videoFile = new File("test/resources/IMG_0004.MOV")

    val contentType  = Await.result(exifToolService.contentType(videoFile), tenSeconds)

    contentType must equal (Some("video/quicktime"))
  }

}
