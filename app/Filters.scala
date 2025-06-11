import modules.MetricsFilter
import play.api.http.HttpFilters

import javax.inject.Inject

class Filters @Inject() (metricsFilter: MetricsFilter) extends HttpFilters {
  val filters = Seq(metricsFilter)
}
