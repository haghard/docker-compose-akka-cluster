package demo

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset, ZonedDateTime}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.Cluster
import akka.cluster.metrics.StandardMetrics.HeapMemory
import akka.cluster.metrics.{ClusterMetricsChanged, ClusterMetricsExtension}
import akka.util.ByteString
import demo.ClusterJvmMetrics.ConnectSource
import spray.json._
import ClusterJvmMetrics._

object ClusterJvmMetrics {
  case class ConnectSource(src: ActorRef)
  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

  def props(cluster: Cluster) =
    Props(new ClusterJvmMetrics(cluster)).withDispatcher("")
}

class ClusterJvmMetrics(cluster: Cluster) extends Actor with ActorLogging {

  val divider = 1024 * 1024
  val extension = ClusterMetricsExtension(context.system)

  override def preStart() = extension.subscribe(self)

  override def postStop() = extension.unsubscribe(self)

  override def receive = {
    case s: ConnectSource =>
      context become connected(s.src)
    case _ => //ignore
  }

  def connected(src: ActorRef): Receive = {
    case ClusterMetricsChanged(clusterMetrics) =>
      clusterMetrics.foreach {
        case HeapMemory(address, timestamp, used, _, max) =>
          val json = JsObject(Map("node" -> JsString(address.toString),
            "metric" -> JsString("heap"),
            "when" -> JsString(formatter.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC))),
            "used" -> JsString((used / divider).toString + " mb"),
            "max" -> JsString((max.getOrElse(0l) / divider).toString + " mb"))).prettyPrint
          src ! ByteString(json)
        case other =>
          log.info("metric name: {}", other.getClass.getName)
      }
    case _ =>
  }
}