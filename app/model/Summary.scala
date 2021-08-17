package model

import play.api.libs.json.{Json, OWrites}

case class Summary(`type`: Option[MediaType], contentType: String, fileExtension: Option[String], md5: String)

object Summary {
  implicit val summaryWrites: OWrites[Summary] = Json.writes[Summary]
}
