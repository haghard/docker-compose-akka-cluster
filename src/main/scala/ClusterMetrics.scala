package main

import java.time.format.DateTimeFormatter
import java.time.{ZoneOffset, Instant, Clock, ZonedDateTime}
import java.util.Date

import akka.actor.{ActorLogging, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.metrics.StandardMetrics.{Cpu, HeapMemory}
import akka.cluster.metrics.{ClusterMetricsChanged, ClusterMetricsExtension}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request, SubscriptionTimeoutExceeded}
import akka.util.ByteString

import scala.annotation.tailrec
import scala.collection.mutable

object ClusterMetrics {
  def props(cluster: Cluster) = Props(new ClusterMetrics(cluster))
}

class ClusterMetrics(cluster: Cluster) extends ActorPublisher[ByteString] with ActorLogging {
  val divider = 1024 * 1024
  val extension = ClusterMetricsExtension(context.system)

  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

  private val queue = mutable.Queue[ByteString]()

  override def preStart() = extension.subscribe(self)

  override def postStop() = extension.unsubscribe(self)

  import spray.json._
  import DefaultJsonProtocol._
  import scala.collection.JavaConverters._

  override def receive = {
    case state: CurrentClusterState =>
      log.info(s"Leader Node: {}", state.getLeader)
    case ClusterMetricsChanged(clusterMetrics) =>
      clusterMetrics.foreach {
        case HeapMemory(address, timestamp, used, committed, max) =>
          //JsNumber(timestamp),
          val json = JsObject(Map("node" -> JsString(address.toString),
              "metric" -> JsString("heap"),
              "when" -> JsString(formatter.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC))),
              "used" -> JsString((used.doubleValue / divider).toString + " mb"),
              "max" -> JsString((max.getOrElse(0l) / divider).toString + " mb"))).prettyPrint
          queue.enqueue(ByteString(json))
        case other =>
          log.info("metric name: {}", other.getClass.getName)
      }
      tryToReply

    case req @ Request(n) ⇒
      tryToReply
    case SubscriptionTimeoutExceeded ⇒
      log.info("canceled")
      (context stop self)
    case Cancel ⇒
      log.info("canceled")
      (context stop self)
  }

  @tailrec final def tryToReply: Unit = {
    if ((isActive && totalDemand > 0) && !queue.isEmpty) {
      onNext(queue.dequeue)
      tryToReply
    } else ()
  }
}