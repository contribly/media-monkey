package controllers

import org.specs2.specification.core.SpecStructure
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.json.Json
import play.api.test.FakeRequest
import test.{BaseContext, UnitSpec}

import scala.concurrent.Future

class MetaControllerSpec extends UnitSpec {
  override def is: SpecStructure =
    s2"""
      meta should
        successfully return media metadata ${metaSuccessNoCallback()}
      """

  def metaSuccessNoCallback() = {
    val context = new Context

    (context.mockTikaService.meta _).expects(*).returning(Future.successful(Some(Map("Content-Type" -> "image/jpeg"))))
    (context.mockTikaService.suggestedFileExtension _).expects(*).returning(Some("image/jpeg"))

    val tempFile = SingletonTemporaryFileCreator.create("prefix", "txt")
    val result   = context.controller.meta(None)(FakeRequest().withBody(tempFile))

    val expectedJson = Json.obj(
      "summary" -> Json.obj(
        "type"          -> "image",
        "contentType"   -> "image/jpeg",
        "fileExtension" -> "image/jpeg",
        "md5"           -> "d41d8cd98f00b204e9800998ecf8427e"
      ),
      "formatSpecificAttributes" -> Json.obj(),
      "metadata"                 -> Json.obj("Content-Type" -> "image/jpeg")
    )

    (status(result) must_=== OK) and
      (contentAsJson(result) must_=== expectedJson)
  }

  class Context extends BaseContext {
    val controller = new MetaController(
      mockWSClient,
      mockTikaService,
      mockImageService,
      mockExiftoolService,
      mockMediaInfoService,
      mockFaceDetector
    )
  }
}
