package model

import play.api.libs.json.{Json, OFormat}

case class FormatSpecificAttributes(
    width: Option[Int],
    height: Option[Int],
    rotation: Option[Int],
    orientation: Option[String],
    tracks: Option[Seq[Track]]
)

object FormatSpecificAttributes {
  implicit val formatSpecificAttributesFormat: OFormat[FormatSpecificAttributes] = Json.format[FormatSpecificAttributes]
}
