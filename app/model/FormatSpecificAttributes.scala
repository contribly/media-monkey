package model

import play.api.libs.json.Json

case class FormatSpecificAttributes(
    width: Option[Int],
    height: Option[Int],
    rotation: Option[Int],
    orientation: Option[String],
    tracks: Option[Seq[Track]]
)

object FormatSpecificAttributes {
  implicit val formatSpecificAttributesFormat = Json.format[FormatSpecificAttributes]
}
