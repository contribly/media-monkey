package controllers

import java.io.File
import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem
import futures.Retry

import javax.inject.Inject
import model._
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import play.api.Logger
import play.api.http.FileMimeTypes
import play.api.libs.Files
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import play.api.mvc.{Action, BaseController, BodyParsers, Controller, ControllerComponents, MultipartFormData}
import services.exiftool.ExiftoolService
import services.facedetection.FaceDetector
import services.geo.ExifLocationExtractor
import services.images.ImageService
import services.mediainfo.{MediainfoInterpreter, MediainfoService}
import services.tika.TikaService
import services.video.VideoService

import scala.concurrent.Future
import scala.concurrent.duration.Duration

class MetaController @Inject()(
    val akkaSystem: ActorSystem,
    ws: WSClient,
    val tikaService: TikaService,
    imageService: ImageService,
    exiftoolService: ExiftoolService,
    val mediainfoService: MediainfoService,
    faceDetector: FaceDetector
)(implicit fileMimeTypes: FileMimeTypes, val controllerComponents: ControllerComponents)
    extends BaseController
    with MediainfoInterpreter
    with Retry
    with MetadataFunctions
    with ExifLocationExtractor
    with JsonResponses
    with ReasonableWaitTimes {

  val thirtySeconds = Duration(30, TimeUnit.SECONDS)

  def tag: Action[MultipartFormData[Files.TemporaryFile]] = Action.async(parse.multipartFormData) { request =>

    implicit val executionContext = akkaSystem.dispatchers.lookup("meta-processing-context")

    val body = request.body

    body.files.headOption.fold {
      Future.successful(BadRequest(Json.toJson("No file seen on request")))

    } { bf =>
      val tagsJson = body.dataParts.get("tags").headOption.flatMap(ts => ts.headOption)

      tagsJson.fold {
        Future.successful(BadRequest(Json.toJson("No tags seen on request")))

      } { tj =>
        implicit val df = DateTimeFormat
        implicit val metadataTags = Json.reads[MetadataTags]
        val tags = Json.parse(tj).as[MetadataTags]

        val tagsToAdd = Seq(
            tags.title.map(t => ("XMP-dc", "Title", t)),
            tags.title.map(t => ("IPTC", "Headline", t)),
            tags.description.map(d => ("XMP-dc", "Description", d)),
            tags.description.map(d => ("IPTC", "Caption-Abstract", d)),
            tags.attribution.map(c => ("XMP-dc", "Contributor", c)),
            tags.attribution.map(c => ("IPTC", "By-line", c)),
            tags.created.map(d => ("XMP-dc", "Date", DateTimeFormat.print(d))),
            tags.email.map(e => ("XMP-iptcCore", "CreatorWorkEmail", e)),
            tags.email.map(e => ("IPTC", "Contact", e)),
            tags.place.map(p => ("XMP-iptcExt", "LocationShown", p)),
            tags.place.map(p => ("ITPC", "ContentLocationName", p))
        ).flatten

        exiftoolService.addMeta(bf.ref.file, tagsToAdd).map { fo =>
          fo.fold {
            UnprocessableEntity(Json.toJson("Could not process file"))

          } { f =>
            val headers = Seq(("Content-Length", f.length().toString))
            Ok.sendFile(f, onClose = () => {
              Logger.debug("Deleting tmp file after sending file: " + f)
              f.delete()
            }).withHeaders(headers: _*)
          }
        }
      }
    }
  }

  def defectFaces(callback: String): Action[Files.TemporaryFile] = Action.async(parse.temporaryFile) { request =>

    def asJson(dfs: Seq[DetectedFace]): JsValue = {
      implicit val pw = Json.writes[Point]
      implicit val bw = Json.writes[Bounds]
      implicit val dfw = Json.writes[DetectedFace]
      Json.toJson(dfs)
    }

    val sourceFile = request.body

    val buffer = File.createTempFile("buffer", "." + "jpg")
    FileUtils.copyFile(sourceFile.file, buffer)

    implicit val executionContext = akkaSystem.dispatchers.lookup("face-detection-processing-context")

    imageService.workingSize(buffer).map { wo =>
      buffer.delete()

      wo.map { w =>
        faceDetector.detectFaces(w).map { dfs =>
          Logger.info("Calling back to " + callback)
          ws.url(callback).withRequestTimeout(thirtySeconds).
            post(asJson(dfs)).map { rp =>
            Logger.info("Response from callback url " + callback + ": " + rp.status)
          }
          w.delete()
        }
      }
    } // TODO recover and delete buffer on error

    Future.successful(Accepted(JsonAccepted))
  }

  def meta(callback: Option[String]): Action[Files.TemporaryFile] = Action.async(parse.temporaryFile) { request =>
    val sourceFile = request.body

    implicit val executionContext = akkaSystem.dispatchers.lookup("meta-processing-context")

    val metadata: Future[Option[Metadata]] = {
      Logger.info("Processing metadate for file: " + sourceFile.file)

      tikaService.meta(sourceFile.file).flatMap { tmdo =>
        val tikaContentType = tmdo.flatMap(md => md.get(CONTENT_TYPE))
        val eventualContentType = tikaContentType.fold {
          exiftoolService.contentType(sourceFile.file)
        }(ct => Future.successful(Some(ct)))

        eventualContentType.flatMap { contentType =>

          contentType.map { ct =>
            val summary = summarise(ct, sourceFile.file)

            summary.`type`.fold {
              sourceFile.delete
              val location = tmdo.flatMap(md => extractLocationFrom(md))
              Future.successful(Some(Metadata(summary = summary, formatSpecificAttributes = None, metadata = tmdo, location)))

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
                sourceFile.delete

                val trackMetadata: Option[Seq[(String, String)]] = contentTypeSpecificAttributes.flatMap { ctsa =>
                  ctsa.tracks.map { ts =>
                    val tracksToExportAsMetadata = Set("General", "Video")
                    ts.filter(t => tracksToExportAsMetadata contains t.`type`).flatMap { t =>
                      t.fields.toSeq
                    }
                  }
                }

                val combinedMetadata = tmdo.getOrElse(Map()) ++ (trackMetadata.getOrElse(Map()))
                // TODO Backwards compatibility. Client apps need to be picking this data from the tracks fields
                val location = tmdo.flatMap(md => extractLocationFrom(combinedMetadata))
                Some(Metadata(summary = summary, formatSpecificAttributes = contentTypeSpecificAttributes, metadata = Some(combinedMetadata), location = location))
              }

            }

          }.getOrElse {
            Logger.info("Unsupported media type")
            Future.successful(None)
          }
        }
      }
    }

    implicit val sw = Json.writes[Summary]
    implicit val fsaw = Json.writes[FormatSpecificAttributes]
    implicit val tw = Json.writes[Track]
    implicit val mdw = Json.writes[Metadata]

    callback.map { c =>
      metadata.map { mdo =>
        mdo.map { md =>
          Logger.info("Calling back to metadata callback url: " + c)
          ws.url(c).withHeaders((CONTENT_TYPE, "application/json")).
            withRequestTimeout(thirtySeconds).
            post(Json.stringify(Json.toJson(md))).map { rp =>
            Logger.info("Response from callback url " + callback + ": " + rp.status)
          }
        }
      }

      Logger.info("Replying Accepted to async metadata call")
      Future.successful(Accepted(Json.toJson("ok")))

    }.getOrElse {
      metadata.map { mdo =>
        Logger.info("Replying to sync metadata call")
        mdo.fold {
          UnsupportedMediaType(Json.toJson("Unsupported media type"))
        } { md =>
          Ok(Json.toJson(mdo))
        }
      }
    }

  }

  case class MetadataTags(title: Option[String], description: Option[String], created: Option[DateTime], attribution: Option[String], email: Option[String], place: Option[String])

}
