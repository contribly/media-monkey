package controllers

import play.api.libs.json.Json
import play.api.mvc._

import javax.inject.Inject

class PingController @Inject() ()(implicit val controllerComponents: ControllerComponents) extends BaseController with JsonResponses {

  def ping(): Action[AnyContent] = Action.apply { request =>
    Ok(Json.toJson("Ok"))
  }

}
