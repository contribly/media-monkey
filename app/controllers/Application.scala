package controllers

import java.io.File

import futures.Retry
import model._
import play.api.Logger
import play.api.Play.current
import play.api.libs.json._
import play.api.libs.ws.WS
import play.api.mvc.{Action, BodyParsers, Controller, Result}
import services.exiftool.ExiftoolService
import services.facedetection.FaceDetector
import services.geo.ExifLocationExtractor
import services.images.ImageService
import services.mediainfo.{MediainfoInterpreter, MediainfoService}
import services.tika.TikaService
import services.video.VideoService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object Application extends Controller with MediainfoInterpreter with Retry with MetadataFunctions with ExifLocationExtractor {

  val XWidth = "X-Width"
  val XHeight = "X-Height"

  case class OutputFormat(mineType: String, fileExtension: String)

  val SupportedImageOutputFormats = Seq(OutputFormat("image/jpeg", "jpg"), OutputFormat("image/png", "png"), OutputFormat("image/gif", "gif"), OutputFormat("image/x-icon", "ico"))
  val SupportedVideoOutputFormats = Seq(OutputFormat("video/theora", "ogg"), OutputFormat("video/mp4", "mp4"), OutputFormat("image/jpeg", "jpg"))
  val AudioOutputFormat = OutputFormat("audio/wav", "wav")

  val UnsupportedOutputFormatRequested = "Unsupported output format requested"

  val mediainfoService: MediainfoService = MediainfoService
  val tika = TikaService
  val exiftool = ExiftoolService
  val imageService = ImageService
  val videoService = VideoService
  val faceDetector = FaceDetector

  def defectFaces = Action.async(BodyParsers.parse.temporaryFile) { request =>
    val sourceFile = request.body
    faceDetector.detectFaces(sourceFile.file).map { dfs =>
      implicit val pw = Json.writes[Point]
      implicit val bw = Json.writes[Bounds]
      implicit val dfw = Json.writes[DetectedFace]
      Ok(Json.toJson(dfs))
    }
  }

  def meta = Action.async(BodyParsers.parse.temporaryFile) { request =>

    val sourceFile = request.body

    retry(3)(tika.meta(sourceFile.file)).flatMap { tmdo =>

      val tikaContentType = tmdo.flatMap(md => md.get(CONTENT_TYPE))
      val eventualContentType = tikaContentType.fold {
        exiftool.contentType(sourceFile.file)
      }(ct => Future.successful(Some(ct)))

      eventualContentType.flatMap { contentType =>
        contentType.fold {
          Future.successful(UnsupportedMediaType(Json.toJson("Unsupported media type")))

        } { ct =>
          val summary = summarise(ct, sourceFile.file)

          implicit val sw = Json.writes[Summary]
          implicit val fsaw = Json.writes[FormatSpecificAttributes]
          implicit val tw = Json.writes[Track]
          implicit val mdw = Json.writes[Metadata]

          summary.`type`.fold {
            sourceFile.clean()

            val location = tmdo.flatMap(md => extractLocationFrom(md))

            Future.successful(UnsupportedMediaType(Json.toJson(Metadata(summary = summary, formatSpecificAttributes = None, metadata = tmdo, location))))

          } { t =>

            def inferContentTypeSpecificAttributes(`type`: String, file: File, metadata: Option[Map[String, String]]): Future[Option[FormatSpecificAttributes]] = {
              `type` match {
                case "image" =>
                  Future.successful(metadata.map(md => (inferImageSpecificAttributes(md))))
                case "video" =>
                  inferVideoSpecificAttributes(file).map(i => Some(i))
                case _ =>
                  Future.successful(None)
              }
            }

            inferContentTypeSpecificAttributes(t, sourceFile.file, tmdo).map { contentTypeSpecificAttributes =>
              sourceFile.clean()

              val trackMetadata: Option[Seq[(String, String)]] = contentTypeSpecificAttributes.flatMap { ctsa =>
                ctsa.tracks.map { ts =>
                  val tracksToExportAsMetadata = Set("General", "Video")
                  ts.filter(t => tracksToExportAsMetadata contains t.`type`).map { t =>
                    t.fields.toSeq
                  }.flatten
                }
              }

              val combinedMetadata = tmdo.getOrElse(Map()) ++ (trackMetadata.getOrElse(Map()))   // TODO Backwards compatibility. Client apps need to be picking this data from the tracks fields

              val location = tmdo.flatMap( md => extractLocationFrom(md))

              Ok(Json.toJson(Metadata(summary = summary, formatSpecificAttributes = contentTypeSpecificAttributes, metadata = Some(combinedMetadata), location = location)))
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

  def scale(w: Option[Int], h: Option[Int], rotate: Option[Int], callback: Option[String], fill: Option[Boolean], gravity: Option[String]) = Action.async(BodyParsers.parse.temporaryFile) { request =>
    val width = w
    val height = h
    val rotationToApply = rotate.getOrElse(0)

    val sourceFile = request.body

    inferOutputTypeFromAcceptHeader(request.headers.get("Accept"), SupportedImageOutputFormats).fold(Future.successful(BadRequest(UnsupportedOutputFormatRequested))) { of =>
      // TODO no error handling

      val eventualResult = imageService.resizeImage(sourceFile.file, width, height, rotationToApply, of.fileExtension, fill.getOrElse(false), gravity).flatMap { ro =>
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
