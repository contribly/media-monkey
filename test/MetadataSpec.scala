import java.io.File

import org.specs2.mutable._
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, _}

class MetadataSpec extends Specification with ResponseToFileWriter with TestWSClient {

  val port: Port = 3334
  val localUrl = "http://localhost:" + port.toString
  val thirtySeconds = Duration(30, SECONDS)

  "can detect images" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_9758.JPG"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "summary" \ "type").toOption.get.as[String] must equalTo("image")
    }
  }

  "should provide a suggested file extension" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_9758.JPG"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "summary" \ "fileExtension").toOption.get.as[String] must equalTo("jpg")
    }
  }

  /*
  "should indicate unsupported formats" in {
    running(TestServer(port)) {
      val eventualResponse = WS.url(localUrl + "/meta").post(new File("test/resources/test.txt"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(UNSUPPORTED_MEDIA_TYPE)

      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "summary"  \ "contentType").toOption.get.as[String] must equalTo("text/plain; charset=ISO-8859-1")
    }
  }
  */

  "image size and orientation should be summarised" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_20150422_122718.jpg"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "formatSpecificAttributes" \ "width").toOption.get.as[Int] must equalTo(2448)
      (jsonResponse \ "formatSpecificAttributes"\ "height").toOption.get.as[Int] must equalTo(3264)
      (jsonResponse \ "formatSpecificAttributes" \ "orientation").toOption.get.as[String] must equalTo("portrait")
    }
  }

  "image orientation should account for EXIF rotation corrections" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_9803.JPG"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)

      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "formatSpecificAttributes" \ "width").toOption.get.as[Int] must equalTo(2304)
      (jsonResponse \ "formatSpecificAttributes" \ "height").toOption.get.as[Int] must equalTo(3456)
      (jsonResponse \ "formatSpecificAttributes" \ "orientation").toOption.get.as[String] must equalTo("portrait")
    }
  }

  "EXIF data can be extracted from images" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_20150422_122718.jpg"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "metadata" \ "GPS Latitude").toOption.get.as[String] must equalTo("37° 45' 18.26\"")
    }
  }

  "can detect videos" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_0004.MOV"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "summary" \ "type").toOption.get.as[String] must equalTo("video")
    }
  }

  "video size can be detected" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_0004.MOV"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "formatSpecificAttributes" \ "width").toOption.get.as[Int] must equalTo(568)
      (jsonResponse \ "formatSpecificAttributes" \ "height").toOption.get.as[Int] must equalTo(320)
    }
  }

  "video metadata should include mediainfo data" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_0004.MOV"))

      val response = Await.result(eventualResponse, thirtySeconds)

      (Json.parse(response.body) \ "formatSpecificAttributes" \\ "DisplayAspectRatio").head.as[String] must equalTo("1.775")
      //(Json.parse(response.body) \ "formatSpecificAttributes" \\ "Frame_rate").head.as[String] must equalTo("30.000 FPS")
    }
  }

  "video metadata should include inferred rotation" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/VID_20150822_144123.mp4"))

      val response = Await.result(eventualResponse, thirtySeconds)

      val rotationField = (Json.parse(response.body) \ "formatSpecificAttributes" \ "rotation").toOption
      rotationField.get.as[Int] must equalTo(90)
    }
  }

  "video metadata should include inferred rotation (iPhone)" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_0063.MOV"))

      val response = Await.result(eventualResponse, thirtySeconds)
      val rotationField = (Json.parse(response.body) \ "formatSpecificAttributes" \ "rotation").toOption

      rotationField.get.as[Int] must equalTo(90)
    }
  }

  "md5 hash should be included in the summary to assist with duplicate detection" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_20150422_122718.jpg"))

      val response = Await.result(eventualResponse, thirtySeconds)

      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "summary" \ "md5").toOption.get.as[String] must equalTo("8eecbf514c06b9a98744b9ef7bc33ec0")
    }
  }

  "Location should be extracted from image EXIF GPS tags if available" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/IMG_20150422_122718.jpg"))

      val response = Await.result(eventualResponse, thirtySeconds)

      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "location" \ "latitude").toOption.get.as[Double] must equalTo(37.7551)
      (jsonResponse \ "location" \ "longitude").toOption.get.as[Double] must equalTo(-119.6)
    }
  }

  /*
  "Location should be extracted from video ISO6709 fields if available" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/meta").post(new File("test/resources/VID_20150822_144123.mp4"))

      val response = Await.result(eventualResponse, thirtySeconds)

      val jsonResponse = Json.parse(response.body)
      (jsonResponse \ "location" \ "latitude").toOption.get.as[Double] must equalTo(50.7215)
      (jsonResponse \ "location" \ "longitude").toOption.get.as[Double] must equalTo(-1.8374)
    }
  }
  */

}