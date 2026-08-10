package test

import akka.actor.ActorSystem
import org.scalamock.specs2.IsolatedMockFactory
import org.specs2.Specification
import play.api.http.{HeaderNames, HttpProtocol, MimeTypes, Status}
import play.api.mvc.ControllerComponents
import play.api.test.{
  DefaultAwaitTimeout,
  FutureAwaits,
  ResultExtractors,
  StubControllerComponentsFactory
}

trait UnitSpec extends Specification with IsolatedMockFactory with PlayHelpers {

  implicit val testActorSystem: ActorSystem                   = ActorSystem("test")
  implicit val testControllerComponents: ControllerComponents = stubControllerComponents()
}

trait PlayHelpers
    extends HeaderNames
    with Status
    with MimeTypes
    with HttpProtocol
    with DefaultAwaitTimeout
    with ResultExtractors
    with FutureAwaits
    with StubControllerComponentsFactory
