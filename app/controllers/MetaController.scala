package controllers

import java.io.File

import futures.Retry
import model._
import org.joda.time.DateTime
import play.api.Logger
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WS
import play.api.mvc.{Action, BodyParsers, Controller}
import services.exiftool.ExiftoolService
import services.facedetection.FaceDetector
import services.geo.ExifLocationExtractor
import services.images.ImageService
import services.mediainfo.{MediainfoInterpreter, MediainfoService}
import services.tika.TikaService
import services.video.VideoService

import scala.concurrent.Future

object MetaController extends Controller with MediainfoInterpreter with Retry with MetadataFunctions with ExifLocationExtractor with JsonResponses with ReasonableWaitTimes {

  val mediainfoService: MediainfoService = MediainfoService
  val tika = TikaService
  val exiftool = ExiftoolService
  val imageService = ImageService
  val videoService = VideoService
  val faceDetector = FaceDetector

  def tag = Action.async(BodyParsers.parse.multipartFormData) { request =>

    implicit val executionContext = Akka.system.dispatchers.lookup("meta-processing-context")

    val body = request.body

    body.files.headOption.fold {
      Future.successful(BadRequest(Json.toJson("No file seen on request")))

    } { bf =>
      val tagsJson = body.dataParts.get("tags").headOption.flatMap(ts => ts.headOption)

      tagsJson.fold {
        Future.successful(BadRequest(Json.toJson("No tags seen on request")))

      } { tj =>
      Logger.info("Tags JSON: " + tj)

        implicit val df = DateTimeFormat
        implicit val metadataTags = Json.reads[MetadataTags]
        val tags = Json.parse(tj).as[MetadataTags]

        Logger.info("Tags: " + tags)

        val tagsToAdd: Seq[(String, String)] = Seq(
            tags.title.map(t => Seq("dc:Title").map(i => (i, t))),
            tags.description.map(d => Seq("dc:Description").map(i => (i, d))),
            tags.attribution.map(c => Seq("dc:Contributor").map(i => (i, c))),
            tags.created.map(d => Seq("dc:Date").map(i => (i, DateTimeFormat.print(d)))),
            tags.email.map(e => Seq("iptcCore:CreatorWorkEmail").map(i => (i, e))),
            tags.place.map(p => Seq("iptcExt:LocationShown").map(i => (i, p)))
        ).flatten.flatten

        Logger.info("Tag to add: " + tags)

        ExiftoolService.addXmp(bf.ref.file, tagsToAdd).map { fo =>
          fo.fold {
            UnprocessableEntity(Json.toJson("Could not process file"))

          } { f =>
            Ok.sendFile(f, onClose = () => {
              Logger.debug("Deleting tmp file after sending file: " + f)
              f.delete()
            })
          }
        }
      }
    }
  }

  def defectFaces(callback: Option[String]) = Action.async(BodyParsers.parse.temporaryFile) { request =>

    def asJson(dfs: Seq[DetectedFace]): JsValue = {
      implicit val pw = Json.writes[Point]
      implicit val bw = Json.writes[Bounds]
      implicit val dfw = Json.writes[DetectedFace]
      Json.toJson(dfs)
    }

    implicit val executionContext = Akka.system.dispatchers.lookup("face-detection-processing-context")

    val sourceFile = request.body

    val eventualDetectedFaces = faceDetector.detectFaces(sourceFile.file)

    callback.fold {
      eventualDetectedFaces.map { dfs =>
        sourceFile.clean()
        Ok(asJson(dfs))
      }

    }{ c =>
      eventualDetectedFaces.map { dfs =>
        sourceFile.clean()
        Logger.info("Calling back to " + c)
        WS.url(c).withRequestTimeout(ThirtySeconds.toMillis).
          post(asJson(dfs)).map { rp =>
            Logger.info("Response from callback url " + callback + ": " + rp.status)
          }
      }

      Future.successful(Accepted(JsonAccepted))
    }

  }

  def meta = Action.async(BodyParsers.parse.temporaryFile) { request =>

    implicit val executionContext = Akka.system.dispatchers.lookup("meta-processing-context")

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

              val location = tmdo.flatMap(md => extractLocationFrom(combinedMetadata))

              Ok(Json.toJson(Metadata(summary = summary, formatSpecificAttributes = contentTypeSpecificAttributes, metadata = Some(combinedMetadata), location = location)))
            }

          }
        }
      }
    }
  }

  case class MetadataTags(title: Option[String], description: Option[String], created: Option[DateTime], attribution: Option[String], email: Option[String], place: Option[String])

}
