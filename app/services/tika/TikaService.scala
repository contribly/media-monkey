package services.tika

import java.io.File
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import javax.inject.Inject
import org.apache.tika.mime.MimeTypes
import play.api.libs.json._
import play.api.libs.functional._
import play.api.libs.json.Reads._
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logger}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

// TODO: apache tika is also available as a java library instead of a server
class TikaService @Inject()(configuration: Configuration, ws: WSClient, akkaSystem: ActorSystem ) {

  lazy val tikaUrl: String = configuration.get[String]("tika.url")

  def meta(f: File): Future[Option[Map[String, String]]] = {
    implicit val executionContext = akkaSystem.dispatchers.lookup("meta-processing-context")

    val tenSeconds = Duration(10, TimeUnit.SECONDS)

//    Logger.info("Posting submitted file to Tika for typing")
    val response = ws.url(tikaUrl + "/meta").withRequestTimeout(tenSeconds).addHttpHeaders(("Accept", "application/json; charset=UTF-8")).put(f)
    response.map { r =>
      r.status match {
        case 200 =>
          Json.parse(r.body) match {
            case JsObject(fields) =>
              val toMap = fields.map((f: (String, JsValue)) => {
                val key: String = f._1
                val value: Option[String] = f._2 match {
                  case JsString(j) => Some(j.toString())
                  case _ => None
                }
                value.map(v => (key, v))
              }).flatten.toMap
              Some(toMap)
            case _ => None
          }
        case _ =>
          Logger.warn("Unexpected response from Tika: " + r.status + " / " + r.body)
          Some(Map.empty)
      }
    }
  }

  def suggestedFileExtension(contentType: String): Option[String] = {
    val mimeType = MimeTypes.getDefaultMimeTypes.forName(contentType)

    val tikaSuggestion = mimeType.getExtensions.asScala.headOption.map { e =>
      e.replaceFirst("\\.", "")
    }

    tikaSuggestion.map { e =>
      if (e == "mp4s") "mp4" else e
    }
  }

}
