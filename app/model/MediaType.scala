package model

import play.api.libs.json._

sealed trait MediaType

object MediaType {

  object Image extends MediaType
  object Video extends MediaType

  implicit def mediaTypeWrites: Writes[MediaType] = {
    case Image => JsString("image")
    case Video => JsString("video")
  }
}
