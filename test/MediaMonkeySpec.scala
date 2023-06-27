import java.io.File
import org.specs2.mutable._
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSResponse
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, _}

class MediaMonkeySpec extends Specification with ResponseToFileWriter with TestWSClient {

  val port: Port = 3334
  val localUrl = "http://localhost:" + port.toString
  val thirtySeconds = Duration(30, SECONDS)

  "can scale image to fit box along longest axis" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/scale?width=800&height=600&rotate=0").post(new File("test/resources/4.webp"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "summary" \ "contentType").toOption.get.as[String] must equalTo("image/jpeg")
      (jsonMeta \ "formatSpecificAttributes" \ "width").toOption.get.as[Int] must equalTo(796)
      (jsonMeta \ "formatSpecificAttributes" \ "height").toOption.get.as[Int] must equalTo(600)
    }
  }

  "can scale and crop image to fill box" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/scale?width=800&height=600&rotate=0").post(new File("test/resources/IMG_9758.JPG"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "summary" \ "contentType").toOption.get.as[String] must equalTo("image/jpeg")
      (jsonMeta \ "formatSpecificAttributes" \ "width").toOption.get.as[Int] must equalTo(800)
      (jsonMeta \ "formatSpecificAttributes" \ "height").toOption.get.as[Int] must equalTo(533)
    }
  }

  "scaled images should be returned with there dimensions available in the headers" in {
    running(TestServer(port)) {

      val eventualResponse = ws.url(localUrl + "/scale?width=800&height=600&rotate=0&fill=true").post(new File("test/resources/IMG_9758.JPG"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      response.header("X-width").get.toInt must equalTo(800)
      response.header("X-height").get.toInt must equalTo(600)
    }
  }

  "can scale portrait image to fit box along longest axis" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/scale?width=600&height=800&rotate=0").post(new File("test/resources/IMG_9803.JPG"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta\ "summary" \ "contentType").toOption.get.as[String] must equalTo("image/jpeg")
      (jsonMeta \ "formatSpecificAttributes" \ "width").toOption.get.as[Int] must equalTo(533)
      (jsonMeta \ "formatSpecificAttributes" \ "height").toOption.get.as[Int] must equalTo(800)
    }
  }

  "can scale and crop portrait image to fill box" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/scale?width=600&height=800&rotate=0&fill=true").post(new File("test/resources/IMG_9803.JPG"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "summary" \ "contentType").toOption.get.as[String] must equalTo("image/jpeg")
      (jsonMeta \ "formatSpecificAttributes"\ "width").toOption.get.as[Int] must equalTo(600)
      (jsonMeta \ "formatSpecificAttributes"\ "height").toOption.get.as[Int] must equalTo(800)
    }
  }

  "image output format can be specified via the Accept header" in {
    running(TestServer(port)) {
      val eventualScalingResponse = ws.url(localUrl + "/scale?width=800&height=600&rotate=0").
        withHeaders(("Accept" -> "image/png")).
        post(new File("test/resources/IMG_9758.JPG"))

      val response = Await.result(eventualScalingResponse, thirtySeconds)

      response.status must equalTo(OK)
      (metadataForResponse(response) \ "summary" \ "contentType").toOption.get.as[String] must equalTo("image/png")
    }
  }

  "scaled image dimensions should be returned as headers" in {
    running(TestServer(port)) {
      val eventualScalingResponse = ws.url(localUrl + "/scale?width=800&height=600&rotate=0").
        withHeaders(("Accept" -> "image/png")).
        post(new File("test/resources/IMG_9758.JPG"))

      val response = Await.result(eventualScalingResponse, thirtySeconds)

      response.header("X-Width").get.toInt must equalTo(800)
      response.header("X-Height").get.toInt must equalTo(533)
    }
  }

  "unknown image output formats should result in a 400 response" in {
    running(TestServer(port)) {
      val eventualScalingResponse = ws.url(localUrl + "/scale?width=800&height=600&rotate=0").
        withHeaders(("Accept" -> "image/sausages")).
        post(new File("test/resources/IMG_9758.JPG"))

      val scalingResponse = Await.result(eventualScalingResponse, thirtySeconds)

      scalingResponse.status must equalTo(BAD_REQUEST)
    }
  }

  "sensitive exif data must be stripped from scaled images" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/scale?width=800&height=600&rotate=0").post(new File("test/resources/IMG_20150422_122718.jpg"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "metadata" \ "GPS Latitude").toOption.isEmpty must equalTo(true)
    }
  }

  "can thumbnail videos" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/video/transcode?width=320&height=200").
        withHeaders(("Accept" -> "image/jpeg")).
        post(new File("test/resources/IMG_0004.MOV"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "summary" \ "contentType").toOption.get.as[String] must equalTo("image/jpeg")
    }
  }

  "video thumbnail size can be specified" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/video/transcode?width=120&height=100").
        withHeaders(("Accept" -> "image/jpeg")).
        post(new File("test/resources/IMG_0004.MOV"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      response.header("X-Width").get.toInt must equalTo(120)
      response.header("X-Height").get.toInt must equalTo(100)

      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "formatSpecificAttributes" \ "width").toOption.get.as[Int] must equalTo(120)
      (jsonMeta \ "formatSpecificAttributes" \ "height").toOption.get.as[Int] must equalTo(100)
    }
  }

  "can transcode videos" in {
    running(TestServer(port)) {
      val eventualResponse = ws.url(localUrl + "/video/transcode?width=284&height=160").post(new File("test/resources/IMG_0004.MOV"))

      val response = Await.result(eventualResponse, thirtySeconds)

      response.status must equalTo(OK)
      response.header("X-Width").get.toInt must equalTo(284)
      response.header("X-Height").get.toInt must equalTo(160)
      (metadataForResponse(response) \ "summary" \ "contentType").toOption.get.as[String] must equalTo("video/ogg")
    }
  }

  "video output format can be specified via the Accept header" in {
    running(TestServer(port)) {
      val eventualTranscodingResponse = ws.url(localUrl + "/video/transcode").
        withHeaders(("Accept" -> "video/mp4")).
        post(new File("test/resources/IMG_0004.MOV"))

      val response = Await.result(eventualTranscodingResponse, thirtySeconds)

      response.status must equalTo(OK)
      response.header("Content-Type").get must equalTo("video/mp4")

      val jsonMeta = metadataForResponse(response)
      (jsonMeta \ "summary" \ "contentType").toOption.get.as[String] must equalTo("video/mp4")
    }
  }

  "video thumbnails can be rotated and letter boxed to fit requested dimensions" in {
    running(TestServer(port)) {
      val response = Await.result(ws.url(localUrl + "/video/transcode?width=568&height=320&rotate=90").
        withHeaders(("Accept" -> "image/jpeg")).
        post(new File("test/resources/IMG_0004.MOV")), thirtySeconds)

      response.header("X-Width").get.toInt must equalTo(568)
      response.header("X-Height").get.toInt must equalTo(320)
    }
  }

  /*  TODO how to test async only end points?
  "faces in images can be detected so that client apps can make more informed cropping decisions" in {
    running(TestServer(port)) {
      val response = Await.result(ws.url(localUrl + "/detect-faces").
        post(new File("test/resources/5282722938_e0e2515624_o.jpg")), thirtySeconds)

      Json.parse(response.body).as[JsArray].value.size must equalTo(1)
    }
  }
  */

  private def metadataForResponse(response: WSResponse): JsValue = {
    val tf = java.io.File.createTempFile("response", "tmp")
    writeResponseBodyToFile(response, tf)

    val eventualMetaResponse = ws.url(localUrl + "/meta").post(tf)
    val metaResponse = Await.result(eventualMetaResponse, thirtySeconds)
    Json.parse(metaResponse.body)
  }

}