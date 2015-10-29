package controllers

import java.io.File

import play.api.Logger
import play.api.libs.json.{JsLookupResult, JsObject, JsValue, Json}
import play.api.mvc.{Action, BodyParsers, Controller}
import services.images.ImageService
import services.mediainfo.MediainfoService
import services.tika.TikaService
import services.video.VideoService

object Application extends Controller {

  val ApplicationJsonHeader: (String, String) = CONTENT_TYPE -> ("application/json")
  val ApplicationXmlHeader: (String, String) = CONTENT_TYPE -> ("application/xml")
  val ImageJpegHeader: (String, String) = CONTENT_TYPE -> ("image/jpeg")
  val VideoOggHeader: (String, String) = CONTENT_TYPE -> ("video/ogg")

  val supportedImageOutputTypes = Map("image/jpeg" -> "jpg", "image/png" -> "png")
  val defaultImageOutputType: Option[String] = Some(supportedImageOutputTypes.head._2)

  val mediainfoService: MediainfoService= MediainfoService
  val tikaService: TikaService = TikaService
  val imageService: ImageService = ImageService
  val videoService: VideoService = VideoService

  def meta = Action(BodyParsers.parse.temporaryFile) { request =>

    val supportedImageTypes = Seq[String]("image/jpeg", "image/tiff", "image/png")
    val supportedVideoTypes = Seq[String]("application/mp4")

    def appendInferedType(tikaMetaData: JsValue): JsValue = {
      val tikaContentType: Option[String] = (tikaMetaData \ "Content-Type").toOption.map(jv => jv.as[String])

      tikaContentType.fold(tikaMetaData)(tct =>
        if (supportedImageTypes.contains(tct)) {
          val withType: JsObject = tikaMetaData.as[JsObject] + ("type" -> Json.toJson("image"))

          val tikaImageWidth = tikaMetaData \ "Image Width"
          val tikaImageHeight = tikaMetaData \ "Image Height"

          var width: Option[Int] = tikaImageWidth.toOption.map(tw => {
            tw.as[String].replace(" pixels", "").toInt
          })
          var height: Option[Int] = tikaImageHeight.toOption.map(th => {
            th.as[String].replace(" pixels", "").toInt
          })

          (tikaMetaData \ "Orientation").toOption.fold()(o => {
            val orientationsRequiringWidthHeightFlip = Seq("Right side, top (Rotate 90 CW)", "Left side, bottom (Rotate 270 CW)")
            if (orientationsRequiringWidthHeightFlip.contains(o.as[String])) {
              val w = width
              width = height
              height = w
            }
          })

          val withWidth = width.fold(withType)(w =>
            withType.as[JsObject] + ("width" -> Json.toJson(w))
          )
          val withHeight = height.fold(withWidth)(h =>
            withWidth.as[JsObject] + ("height" -> Json.toJson(h))
          )

          if (!width.isEmpty && !height.isEmpty) {
            val orientation: String = if (width.get > height.get) {
              "landscape"
            } else {
              "portrait"
            }
            withHeight.as[JsObject] + ("orientation" -> Json.toJson(orientation))

          } else {
            withHeight
          }

        } else if (supportedVideoTypes.contains(tct)) {
          tikaMetaData.as[JsObject] + ("type" -> Json.toJson("video"))
        } else {
          tikaMetaData
        }
      )
    }

    val f: File = request.body.file
    Logger.info("Received meta request to " + f.getAbsolutePath)

    val tikaMetaData: JsValue = tikaService.meta(f)

    Ok(Json.toJson(appendInferedType(tikaMetaData)))
  }

  def scale(width: Int = 800, height: Int = 600, rotate: Double = 0) = Action(BodyParsers.parse.temporaryFile) { request =>

    def inferOutputTypeFromAcceptHeader(acceptsHeader: Option[String]): Option[String] = {
      acceptsHeader.fold(defaultImageOutputType)(ah =>
        supportedImageOutputTypes.get(ah)
      )
    }

    val acceptHeader: Option[String] = request.headers.get("Accept")
    inferOutputTypeFromAcceptHeader(acceptHeader).fold(BadRequest("Unsupported image output format requested"))(op => {
      val f: File = request.body.file
      Logger.info("Received scale request to " + f.getAbsolutePath)

      val output = imageService.resizeImage(f, width, height, rotate, op)
      val source = scala.io.Source.fromFile(new File(output.getAbsolutePath))
      Ok.sendFile(output).withHeaders(CONTENT_TYPE -> acceptHeader.get) // TODO Naked get
    })
  }

  def videoThumbnail() = Action(BodyParsers.parse.temporaryFile) { request =>
    val f: File = request.body.file
    Logger.info("Received thumbnail request to " + f.getAbsolutePath)

    val output = videoService.thumbnail(f)

    output.fold(InternalServerError(Json.toJson("Video could not be thumbnailed")))(o => {
      val source = scala.io.Source.fromFile(new File(o.getAbsolutePath))
      Ok.sendFile(o).withHeaders(ImageJpegHeader)
    })
  }

  def videoTranscode() = Action(BodyParsers.parse.temporaryFile) { request =>
    val f: File = request.body.file
    Logger.info("Received transcode request to " + f.getAbsolutePath)

    val output = videoService.transcode(f)

    output.fold(InternalServerError(Json.toJson("Video could not be transcoded")))(o => {
      val source = scala.io.Source.fromFile(new File(o.getAbsolutePath))
      Ok.sendFile(o).withHeaders(VideoOggHeader)
    })
  }

  def mediainfo() = Action(BodyParsers.parse.temporaryFile) { request =>
    val f: File = request.body.file
    Logger.info("Received transcode request to mediainfo" + f.getAbsolutePath)

    val output = mediainfoService.mediainfo(f)

    output.fold(InternalServerError(Json.toJson("Video could not be transcoded")))(o => {
      Ok(o).withHeaders(ApplicationXmlHeader)
    })
  }

}