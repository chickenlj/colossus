package colossus.metrics

import akka.actor._
import akka.agent.Agent
import colossus.metrics.MetricAddress.Root
import com.typesafe.config.{ConfigFactory, Config}

import scala.concurrent.duration._

class MetricInterval private[metrics](val namespace : MetricAddress,
                                      val interval : FiniteDuration,
                                      val intervalAggregator : ActorRef, snapshot : Agent[MetricMap]){

  /**
   * The latest metrics snapshot
    *
    * @return
   */
  def last : MetricMap = snapshot.get()

  /**
   * Attach a reporter to this MetricPeriod.
    *
    * @param config  The [[MetricReporterConfig]] used to configure the [[MetricReporter]]
   * @return
   */
  def report(config : MetricReporterConfig)(implicit fact: ActorRefFactory) : ActorRef = MetricReporter(config, intervalAggregator)
}

/**
 * The MetricSystem is a set of actors which handle the background operations of dealing with metrics. In most cases,
 * you only want to have one MetricSystem per application.
 *
 * Metrics are generated periodically by a Tick message published on the global event bus. By default this happens once
 * per second, but it can be configured to any time interval. So while events are being collected as they occur,
 * compiled metrics (such as rates and histogram percentiles) are generated once per tick.
 *
 * @param namespace the base of the url describing the location of metrics within the system
 * @param metricIntervals Map of all MetricIntervals for which this system collects Metrics.
 * @param config Config object from which Metric configurations will be sourced.
 */
case class MetricSystem private[metrics] (namespace: MetricAddress, metricIntervals : Map[FiniteDuration, MetricInterval],
                                          collectionSystemMetrics : Boolean, config : Config) {

  implicit val base = new Collection(CollectorConfig(metricIntervals.keys.toSeq, config))
  registerCollection(base)

  def registerCollection(collection: Collection): Unit = {
    metricIntervals.values.foreach(_.intervalAggregator ! IntervalAggregator.RegisterCollection(collection))
  }

}

object MetricSystem {
  /**
   * Constructs a metric system
    *
    * @param namespace the base of the url describing the location of metrics within the system
   * @param metricIntervals How often to report metrics
   * @param collectSystemMetrics whether to collect metrics from the system as well
   * @param system the actor system the metric system should use
   * @return
   */
  def apply(namespace: MetricAddress, metricIntervals: Seq[FiniteDuration] = Seq(1.second, 1.minute),
            collectSystemMetrics: Boolean = true, config : Config = ConfigFactory.defaultReference())
  (implicit system: ActorSystem): MetricSystem = {
    import system.dispatcher


    val m : Map[FiniteDuration, MetricInterval] = metricIntervals.map{ interval =>
      val snap = Agent[MetricMap](Map())
      val aggregator : ActorRef = createIntervalAggregator(system, namespace, interval, snap, collectSystemMetrics)
      val i = new MetricInterval(namespace, interval, aggregator, snap)
      interval -> i
    }.toMap

    MetricSystem(namespace, m, collectSystemMetrics, config)
  }

  private def createIntervalAggregator(system : ActorSystem, namespace : MetricAddress, interval : FiniteDuration,
                                       snap : Agent[MetricMap], collectSystemMetrics : Boolean) = {
    system.actorOf(Props(classOf[IntervalAggregator], namespace, interval, snap, collectSystemMetrics))
  }

  def deadSystem(implicit system: ActorSystem) = {
    MetricSystem(Root / "DEAD", Map[FiniteDuration, MetricInterval](), false, ConfigFactory.defaultReference())
  }

  def apply()(implicit system : ActorSystem) : MetricSystem = {
    apply("colossus.metrics")
  }

  def apply(name : String)(implicit system : ActorSystem) : MetricSystem = {
    apply(name, ConfigFactory.load()) //reference?
  }

  def apply(name : String, config : Config)(implicit system : ActorSystem) : MetricSystem = {

    import MetricSystemConfigHelpers._

    val userPathObject = config.getObject(name)
    val metricsObject = userPathObject.withFallback(config.getObject("colossus.metrics"))
    val metricsConfig = metricsObject.toConfig
    val mergedConfig = config.withValue("colossus.metrics", metricsObject) //<-- see, I told you

    val collectSystemMetrics = metricsConfig.getBoolean("collect-system-metrics")
    val metricIntervals = metricsConfig.getFiniteDurations("metric-intervals")
    val metricAddress = metricsConfig.getString("metric-address")
    MetricSystem(MetricAddress(metricAddress), metricIntervals, collectSystemMetrics, mergedConfig)
  }
}

//has to be a better way
object MetricSystemConfigHelpers {

  implicit class FiniteDurationLoader(config : Config) {

    import scala.collection.JavaConversions._

    def getFiniteDurations(path : String) : Seq[FiniteDuration] = config.getStringList(path).map(finiteDurationOnly(_, path))

    private def finiteDurationOnly(str : String, key : String) = {
      Duration(str) match {
        case duration : FiniteDuration => duration
        case other => throw new FiniteDurationExpectedException(s"$str is not a valid FiniteDuration.  Expecting only finite for path $key.  Evaluted to $other")
      }
    }
  }
}

private[metrics] class FiniteDurationExpectedException(str : String) extends Exception(str)






