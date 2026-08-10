package services.tika

import akka.actor.ActorSystem
import io.micrometer.core.instrument.{MeterRegistry, Timer}
import org.apache.tika.metadata.Metadata
import org.apache.tika.mime.MimeTypes
import org.apache.tika.parser.AutoDetectParser
import org.xml.sax.helpers.DefaultHandler
import utils.GlobalLogger.logger

import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import scala.collection.JavaConverters._
import scala.concurrent.Future

class TikaService @Inject() (akkaSystem: ActorSystem, meterRegistry: MeterRegistry) {

  private val tikaMeter = Timer.builder("contribly.mediamonkey.tika")
    .description("MediaMonkey tika operations")
    .publishPercentileHistogram()
    .withRegistry(meterRegistry)

  lazy val parser = new AutoDetectParser()

  def meta(f: File): Future[Option[Map[String, String]]] = {
    implicit val executionContext = akkaSystem.dispatchers.lookup("meta-processing-context")

    Future {
      val input = Files.newInputStream(f.toPath)
      try {
        val meta = new Metadata()
        parser.parse(input, new DefaultHandler(), meta)
        val result = meta.names().map(key => key -> meta.get(key)).toMap
        Some(result)
      } catch {
        case e: Exception =>
          logger.warn("Could not extract media metadata", e)
          None
      } finally {
        input.close();
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
