package utils

import play.api.Logger

object GlobalLogger {

  /** Using a singleton logger to keep compatibility. Suggested way:
    * https://www.playframework.com/documentation/2.7.x/Migration27#Static-Logger-singletons-deprecated
    */
  val logger = Logger("application")
}
