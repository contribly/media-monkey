import java.io.{FileOutputStream, BufferedOutputStream, File}

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.{WS, WSResponse}
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, Future}

class MediaMonkeySpec extends Specification with ResponseToFileWriter {

  val port: Port = 3334
  val localUrl = "http://localhost:" + port.toString
  val tenSeconds = Duration(10, SECONDS)
  val thirtySeconds = Duration(30, SECONDS)

  "can scale image" in {
    running(TestServer(port)) {
      val eventualResponse = WS.url(localUrl + "/scale?width=800&height=600&rotate=0").post(new File("test/resources/IMG_9758.JPG"))

      val response = Await.result(eventualResponse, tenSeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "width").toOption.get.as[Int] must equalTo(800)
      (jsonMeta \ "height").toOption.get.as[Int] must equalTo(600)
    }
  }

  "image output format can be specified via the Accept header" in {
    running(TestServer(port)) {
      val eventualScalingResponse = WS.url(localUrl + "/scale?width=800&height=600&rotate=0").
        withHeaders(("Accept" -> "image/png")).
        post(new File("test/resources/IMG_9758.JPG"))

      val response = Await.result(eventualScalingResponse, tenSeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "Content-Type").toOption.get.as[String] must equalTo("image/png")
    }
  }

  "unknown image output formats should result in a 400 response" in {
    running(TestServer(port)) {
      val eventualScalingResponse = WS.url(localUrl + "/scale?width=800&height=600&rotate=0").
        withHeaders(("Accept" -> "image/sausages")).
        post(new File("test/resources/IMG_9758.JPG"))

      val scalingResponse = Await.result(eventualScalingResponse, tenSeconds)

      scalingResponse.status must equalTo(BAD_REQUEST)
    }
  }

  "sensitive exif data must be stripped from scaled images" in {
    running(TestServer(port)) {
      val eventualResponse = WS.url(localUrl + "/scale?width=800&height=600&rotate=0").post(new File("test/resources/IMG_20150422_122718.jpg"))

      val response = Await.result(eventualResponse, tenSeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "GPS Latitude").toOption.isEmpty must equalTo(true)
    }
  }

  "can thumbnail videos" in {
    running(TestServer(port)) {
      val eventualResponse = WS.url(localUrl + "/video/thumbnail").post(new File("test/resources/IMG_0004.MOV"))

      val response = Await.result(eventualResponse, tenSeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "Content-Type").toOption.get.as[String] must equalTo("image/jpeg")
    }
  }

  "can transcode videos" in {
    running(TestServer(port)) {
      val eventualResponse = WS.url(localUrl + "/video/transcode").post(new File("test/resources/IMG_0004.MOV"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "Content-Type").toOption.get.as[String] must equalTo("video/theora")
    }
  }

  "video output format can be specified via the Accept header" in {
    running(TestServer(port)) {
      val eventualTranscodingResponse = WS.url(localUrl + "/video/transcode").
        withHeaders(("Accept" -> "video/mp4")).
        post(new File("test/resources/IMG_0004.MOV"))

      val response = Await.result(eventualTranscodingResponse, thirtySeconds)

      response.status must equalTo(OK)
      response.header("Content-Type").get must equalTo("video/mp4")
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "Content-Type").toOption.get.as[String] must equalTo("application/mp4")
    }
  }

  private def metadataForResponse(response: WSResponse): JsValue = {
    val tf = java.io.File.createTempFile("response", "tmp")
    writeResponseBodyToFile(response, tf)

    val eventualMetaResponse = WS.url(localUrl + "/meta").post(tf)
    val metaResponse = Await.result(eventualMetaResponse, tenSeconds)
    Json.parse(metaResponse.body)
  }

}