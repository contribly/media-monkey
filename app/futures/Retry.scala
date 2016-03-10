package futures

import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

trait Retry {

  def retry[T](times: Int)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    Logger.debug("Trying with " + times + " attempts remaining")
    f recoverWith {
      case e: Exception => {
        val retriesRemaining = times - 1
        Logger.warn("Retrying after exception; " + retriesRemaining + " retries remaining.", e)
        Thread.sleep(5000)
        if (times > 0) {
          retry(retriesRemaining)(f)
        } else {
          Logger.error("Retry attempts exhausted; rethrowing exception", e)
          throw e
        }
      }
    }
  }

}
