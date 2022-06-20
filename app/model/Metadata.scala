package model

import org.joda.time.DateTime
import play.api.libs.json.{Json, OWrites}

case class Metadata(
    summary: Summary,
    formatSpecificAttributes: Option[FormatSpecificAttributes],
    metadata: Option[Map[String, String]],
    location: Option[LatLong]
)

object Metadata {
  implicit val metadataWrites: OWrites[Metadata] = Json.writes[Metadata]
}

case class MetadataTags(
    title: Option[String],
    description: Option[String],
    created: Option[DateTime],
    attribution: Option[String],
    email: Option[String],
    place: Option[String]
)
