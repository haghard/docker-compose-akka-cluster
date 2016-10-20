package main

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
  //val selfAddress = cluster.selfAddress
  val extension = ClusterMetricsExtension(context.system)

  private val queue = mutable.Queue[ByteString]()

  override def preStart() = {
    log.info("**************")
    log.debug("*********************")
    extension.subscribe(self)
  }

  override def postStop() = {
    extension.unsubscribe(self)
  }

  import spray.json._
  import DefaultJsonProtocol._

  override def receive = {
    case state: CurrentClusterState =>
      log.info(s"Leader Node: {}", state.getLeader)
    case ClusterMetricsChanged(clusterMetrics) =>
      clusterMetrics.foreach {
        case HeapMemory(address, timestamp, used, committed, max) =>
          //log.info("Used heap: {} mb", used.doubleValue / divider)
          val metrics = Map("node" -> address.toString, "metric" -> "heap", "when" -> timestamp.toString,
            "used" -> (used.doubleValue / divider).toString, "committed" -> committed.toString, "max" -> max.toString)
          queue.enqueue(ByteString(metrics.toJson.prettyPrint))
        case Cpu(address, timestamp, Some(systemLoadAverage), cpuCombined, cpuStolen, processors) =>
          log.info("Load: {} ({} processors)", systemLoadAverage, processors)
          val metrics = Map("node" -> address.toString, "metric" -> "cpu",
            "when" -> timestamp.toString, "avr" -> systemLoadAverage.toString,
            "cpuCombined" -> cpuCombined.toString, "cpu-stolen" -> cpuStolen.toString, "processors" -> processors.toString)
          queue.enqueue(ByteString(metrics.toJson.prettyPrint))
        case _ =>
      }

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