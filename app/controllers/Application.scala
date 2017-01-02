package controllers

import java.io.{File, FileInputStream}

import futures.Retry
import org.apache.commons.codec.digest.DigestUtils
import play.api.Logger
import play.api.Play.current
import play.api.libs.json._
import play.api.libs.ws.WS
import play.api.mvc.{Action, BodyParsers, Controller, Result}
import services.exiftool.ExiftoolService
import services.images.ImageService
import services.mediainfo.{MediainfoInterpreter, MediainfoService}
import services.tika.TikaService
import services.video.VideoService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object Application extends Controller with MediainfoInterpreter with Retry with MetadataFunctions {

  val XWidth = "X-Width"
  val XHeight = "X-Height"

  case class OutputFormat(mineType: String, fileExtension: String)

  val RecognisedImageTypes = Seq(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/x-ms-bmp",
    "image/tiff"
  )

  val RecognisedVideoTypes = Seq(
    "application/mp4",
    "video/3gpp",
    "video/m2ts",
    "video/mp4",
    "video/mpeg",
    "video/quicktime",
    "video/x-flv",
    "video/x-m4v",
    "video/x-matroska",
    "video/x-ms-asf",
    "video/x-msvideo",
    "video/theora",
    "video/webm"
  )

  val SupportedImageOutputFormats = Seq(OutputFormat("image/jpeg", "jpg"), OutputFormat("image/png", "png"), OutputFormat("image/gif", "gif"), OutputFormat("image/x-icon", "ico"))
  val SupportedVideoOutputFormats = Seq(OutputFormat("video/theora", "ogg"), OutputFormat("video/mp4", "mp4"), OutputFormat("image/jpeg", "jpg"))
  val AudioOutputFormat = OutputFormat("audio/wav", "wav")

  val UnsupportedOutputFormatRequested = "Unsupported output format requested"

  val mediainfoService: MediainfoService = MediainfoService
  val tika = TikaService
  val exiftool = ExiftoolService
  val imageService = ImageService
  val videoService = VideoService

  def meta = Action.async(BodyParsers.parse.temporaryFile) { request =>

    def inferContentTypeSpecificAttributes(`type`: String, file: File, metadata: Map[String, String]): Future[Map[String, Any]] = {

      def inferVideoSpecificAttributes(file: File): Future[Seq[(String, Any)]] = {

        def parseRotation(r: String): Int = {
          r.replaceAll("[^\\d]", "").toInt
        }

        mediainfoService.mediainfo(file).map { mit =>
          val videoTrackDimensions = videoDimensions(mit)
          val rotation = inferRotation(mit)

          val trackFields: Option[Seq[(String, String)]] = mit.map { ts =>
            ts.filter(t => t.trackType == "General" || t.trackType == "Video").flatMap { t => // TODO work out a good format to preserver all of this information
              t.fields.toSeq
            }
          }

          val combinedTrackFields: Seq[(String, String)] = Seq(trackFields).flatten.flatten
          val dimensionFields: Seq[(String, Int)] = Seq(videoTrackDimensions.map(d => Seq("width" -> d._1, "height" -> d._2))).flatten.flatten

          combinedTrackFields ++ dimensionFields :+ ("rotation" -> rotation)
        }
      }

      val eventualContentTypeSpecificAttributes = `type` match {
        case "image" =>
          Future.successful(inferImageSpecificAttributes(metadata))
        case "video" =>
          inferVideoSpecificAttributes(file)
        case _ =>
          Future.successful(Seq())
      }

      eventualContentTypeSpecificAttributes.map { i =>
        i.toMap
      }
    }

    val sourceFile = request.body

    retry(3)(tika.meta(sourceFile.file)).flatMap { tmdo =>

      val metadata = tmdo.getOrElse(Map[String, String]())

      val eventualContentType = metadata.get(CONTENT_TYPE).fold {
        exiftool.contentType(sourceFile.file)
      }(ct => Future.successful(Some(ct)))

      eventualContentType.flatMap { contentType =>
        contentType.fold(Future.successful(UnsupportedMediaType(Json.toJson("Unsupported media type")))) { ct =>

          def summarise(`type`: Option[String], contentType: String, file: File): Map[String, String] = {
            val stream: FileInputStream = new FileInputStream(sourceFile.file)
            val md5Hash = DigestUtils.md5Hex(stream)
            stream.close()

            Seq(
              `type`.map(t => "type" -> t),
              Some("contentType" -> ct),
              tika.suggestedFileExtension(ct).map(e => "fileExtension" -> e),
              Some("md5" -> md5Hash)
            ).flatten.toMap
          }

          val `type`: Option[String] = inferTypeFromContentType(ct)
          Logger.info("Infered type from content type: " + `type` + " / " + ct)

          val summary = summarise(`type`, ct, sourceFile.file)

          `type`.fold {
            sourceFile.clean()
            Future.successful(UnsupportedMediaType(Json.toJson(metadata ++ summary)))

          } { t =>
            inferContentTypeSpecificAttributes(t, sourceFile.file, metadata).map { contentTypeSpecificAttributes =>

              implicit val writes = new Writes[Map[String, Any]] {
                override def writes(o: Map[String, Any]): JsValue = {
                  val map = o.map(i => {
                    val value: Any = i._2
                    val json: JsValue = value match {
                      case i: Int => JsNumber(i)
                      case _ => Json.toJson(value.toString)
                    }
                    (i._1, json)
                  })
                  JsObject(map.toList)
                }
              }

              sourceFile.clean()
              Ok(Json.toJson(metadata - "width" - "height" - "orientation" - "rotation" ++ contentTypeSpecificAttributes ++ summary))
            }
          }
        }
      }
    }
  }

  def crop(width: Int, height: Int, x: Int, y: Int) = Action.async(BodyParsers.parse.temporaryFile) { request =>

    val sourceFile = request.body

    inferOutputTypeFromAcceptHeader(request.headers.get("Accept"), SupportedImageOutputFormats).fold(Future.successful(BadRequest(UnsupportedOutputFormatRequested))) { of =>
      // TODO no error handling

      val eventualResult = imageService.cropImage(sourceFile.file, width, height, x, y, of.fileExtension).flatMap { ro =>
        sourceFile.clean()

        ro.map{ r =>
          imageService.info(r).map { dimensions =>
            Some(r, Some(dimensions), of)
          }
        }.getOrElse(Future.successful(None))
      }

      handleResult(eventualResult, None)
    }
  }

  def scale(w: Option[Int], h: Option[Int], rotate: Option[Int], callback: Option[String], f: Option[Boolean]) = Action.async(BodyParsers.parse.temporaryFile) { request =>
    val width = w
    val height = h
    val rotationToApply = rotate.getOrElse(0)

    val fill = f.getOrElse(false)

    val sourceFile = request.body

    inferOutputTypeFromAcceptHeader(request.headers.get("Accept"), SupportedImageOutputFormats).fold(Future.successful(BadRequest(UnsupportedOutputFormatRequested))) { of =>
      // TODO no error handling

      val eventualResult = imageService.resizeImage(sourceFile.file, width, height, rotationToApply, of.fileExtension, fill).flatMap { ro =>
        sourceFile.clean()

        ro.map { r =>
          imageService.info(r).map { dimensions =>
            Some(r, Some(dimensions), of)
          }
        }.getOrElse(Future.successful(None))
      }

      handleResult(eventualResult, callback)
    }
  }

  def videoStrip(w: Option[Int], h: Option[Int], callback: Option[String], rotate: Option[Int], aspectRatio: Option[Double]) = Action.async(BodyParsers.parse.temporaryFile) { request =>
    val width = w.getOrElse(320)
    val height = h.getOrElse(180)

    inferOutputTypeFromAcceptHeader(request.headers.get("Accept"), SupportedImageOutputFormats).fold(Future.successful(BadRequest(UnsupportedOutputFormatRequested))) { of =>
      val sourceFile = request.body
      val eventualResult = videoService.strip(sourceFile.file, of.fileExtension, width, height, aspectRatio, rotate).flatMap { ro =>
        sourceFile.clean()

        ro.map { r =>
          imageService.info(r).map { dimensions =>
            Some(r, Some(dimensions), of)
          }
        }.getOrElse(Future.successful(None))
      }

      handleResult(eventualResult, callback)
    }
  }

  def videoAudio(callback: Option[String]) = Action.async(BodyParsers.parse.temporaryFile) { request =>
    val sourceFile = request.body
    val eventualResult = videoService.audio(sourceFile.file).map { ro =>
      sourceFile.clean()

      ro.map { r =>
        val noDimensions: Option[(Int, Int)] = None
        Some(r, noDimensions, AudioOutputFormat)
      }.getOrElse(None)
    }
    handleResult(eventualResult, callback)
  }


  def videoTranscode(width: Option[Int], height: Option[Int], callback: Option[String], rotate: Option[Int], aspectRatio: Option[Double]) = Action.async(BodyParsers.parse.temporaryFile) { request =>

    val sourceFile = request.body

    inferOutputTypeFromAcceptHeader(request.headers.get("Accept"), SupportedVideoOutputFormats).fold(Future.successful(BadRequest(UnsupportedOutputFormatRequested))) { of =>

      val eventualResult = if (of.mineType.startsWith("image/")) {

        videoService.thumbnail(sourceFile.file, of.fileExtension, width, height, aspectRatio, rotate).flatMap { ro =>
          sourceFile.clean()

          ro.map { r =>
            imageService.info(r).map { dimensions =>
              Some(r, Some(dimensions), of)
            }
          }.getOrElse(Future.successful(None))
        }

      } else {

        val outputSize: Option[(Int, Int)] = width.flatMap { w =>
          height.map { h =>
            (w, h)
          }
        }

        videoService.transcode(sourceFile.file, of.fileExtension, outputSize, aspectRatio, rotate).flatMap { ro =>
          sourceFile.clean()

          ro.map{ r =>
            mediainfoService.mediainfo(r).map { mi =>
              Some(r, videoDimensions(mi), of)
            }
          }.getOrElse(Future.successful(None))
        }
      }

      handleResult(eventualResult, callback)
    }
  }

  private def inferTypeFromContentType(contentType: String): Option[String] = {

    if (RecognisedImageTypes.contains(contentType)) {
      Some("image")
    } else if (RecognisedVideoTypes.contains(contentType)) {
      Some("video")
    } else {
      None
    }
  }

  private def inferOutputTypeFromAcceptHeader(acceptHeader: Option[String], availableFormats: Seq[OutputFormat]): Option[OutputFormat] = {
    val defaultOutputFormat = availableFormats.headOption
    acceptHeader.fold(defaultOutputFormat)(ah => {
      if (ah.equals("*/*")) {
        defaultOutputFormat
      } else {
        availableFormats.find(sf => sf.mineType == ah)
      }
    })
  }

  private def handleResult(eventualResult: Future[Option[(File, Option[(Int, Int)], OutputFormat)]], callback: Option[String]): Future[Result] = {

    def headersFor(of: OutputFormat, dimensions: Option[(Int, Int)]): Seq[(String, String)] = {
      val dimensionHeaders = Seq(dimensions.map(d => XWidth -> d._1.toString), dimensions.map(d => XHeight -> d._2.toString)).flatten
      Seq(CONTENT_TYPE -> of.mineType) ++ dimensionHeaders
    }

    callback.fold {
      eventualResult.map { ro =>
        ro.fold {
          UnprocessableEntity(Json.toJson("Could not process file"))

        } { r =>
          val of: OutputFormat = r._3
          Ok.sendFile(r._1, onClose = () => {
            Logger.debug("Deleting tmp file after sending file: " + r._1)
            r._1.delete()
          }).withHeaders(headersFor(of, r._2): _*)
        }
      }

    } { c =>
      eventualResult.map { ro =>
        ro.fold {
          Logger.warn("Failed to process file; not calling back")

        } { r =>

          val ThirtySeconds = Duration(30, SECONDS)

          Logger.info("Calling back to " + c)
          val of: OutputFormat = r._3
          WS.url(c).withHeaders(headersFor(of, r._2): _*).
            withRequestTimeout(ThirtySeconds.toMillis).
            post(r._1).map { rp =>
            Logger.info("Response from callback url " + callback + ": " + rp.status)
            Logger.debug("Deleting tmp file after calling back: " + r._1)
            r._1.delete()
          }
        }
      }
      Future.successful(Accepted(Json.toJson("Accepted")))
    }
  }

}
