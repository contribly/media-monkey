package test

import org.scalamock.specs2.MockContext
import play.api.libs.ws.WSClient
import services.exiftool.ExiftoolService
import services.facedetection.FaceDetector
import services.images.ImageService
import services.mediainfo.MediainfoService
import services.tika.TikaService

class BaseContext extends MockContext {

  val mockWSClient: WSClient                 = mock[WSClient]
  val mockTikaService: TikaService           = mock[TikaService]
  val mockImageService: ImageService         = mock[ImageService]
  val mockExiftoolService: ExiftoolService   = mock[ExiftoolService]
  val mockMediaInfoService: MediainfoService = mock[MediainfoService]
  val mockFaceDetector: FaceDetector         = mock[FaceDetector]
}
