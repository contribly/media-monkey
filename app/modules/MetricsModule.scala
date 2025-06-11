package modules

import akka.stream.Materializer
import io.micrometer.core.instrument.binder.jvm.{JvmGcMetrics, JvmInfoMetrics, JvmMemoryMetrics, JvmThreadMetrics}
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.{MeterRegistry, Timer}
import io.micrometer.prometheusmetrics.{PrometheusConfig, PrometheusMeterRegistry}
import io.prometheus.metrics.exporter.httpserver.HTTPServer
import play.api.http.Status
import play.api.inject.{ApplicationLifecycle, bind}
import play.api.mvc.{Filter, RequestHeader, Result}
import play.api.routing.Router

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class MetricsModule extends play.api.inject.SimpleModule((_,config)=>{
  val registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  new ProcessorMetrics().bindTo(registry);
  new JvmInfoMetrics().bindTo(registry);
  new JvmMemoryMetrics().bindTo(registry);
  new JvmGcMetrics().bindTo(registry);
  new JvmThreadMetrics().bindTo(registry);
  Seq(
    bind(classOf[MeterRegistry]).to(registry),
    bind(classOf[PrometheusMeterRegistry]).to(registry),
    bind(classOf[PrometheusEndpointStarter]).toSelf.eagerly(),
    bind(classOf[MetricsFilter]).toSelf
  )
})

class PrometheusEndpointStarter @Inject()(lifecycle: ApplicationLifecycle, registry: PrometheusMeterRegistry) {
  val server: HTTPServer = HTTPServer.builder()
    .port(3000)
    .registry(registry.getPrometheusRegistry)
    .buildAndStart()

  lifecycle.addStopHook { () =>
    server.stop()
    Future.successful(())
  }
}

class MetricsFilter @Inject()(registry: MeterRegistry)(implicit val mat: Materializer, val ec: ExecutionContext) extends Filter {

  private val timerProvider = Timer.builder("http.server.requests")
    .description("HTTP server requests")
    .publishPercentiles(0.5, 0.95, 0.99)
    .withRegistry(registry)

  override def apply(next: RequestHeader => Future[Result])(rh: RequestHeader): Future[Result] = {
    rh.attrs.get(Router.Attrs.HandlerDef).fold {
      next(rh)
    } { attrs =>
      val sample = Timer.start(registry)
      val path = attrs.path.replaceAll("\\$(\\w+)(<[^>]+>)?", ":$1")
      next(rh).transform(
        result => {
          val outcome = (result.header.status / 100) match {
            case 2 => "SUCCESS"
            case 3 => "REDIRECT"
            case 4 => "CLIENT_ERROR"
            case 5 => "SERVER_ERROR"
            case _ => "UNKNOWN"
          }
          sample.stop(timerProvider.withTags("uri", path, "method", rh.method, "outcome", outcome, "status", result.header.status.toString))
          result
        },
        error => {
          sample.stop(timerProvider.withTags("uri", path, "method", rh.method, "outcome", "SERVER_ERROR", "status", Status.INTERNAL_SERVER_ERROR.toString))
          error
        }
      )
    }
  }
}
