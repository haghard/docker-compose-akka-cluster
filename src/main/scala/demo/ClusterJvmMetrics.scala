package demo

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZonedDateTime}

import akka.cluster.metrics.StandardMetrics.HeapMemory
import akka.cluster.metrics.{ClusterMetricsChanged, ClusterMetricsEvent, ClusterMetricsExtension}
import akka.util.ByteString
import spray.json._
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._

import scala.collection.mutable

object ClusterJvmMetrics {

  sealed trait Confirm                                                             extends ClusterMetricsEvent
  case object Confirm                                                              extends Confirm
  case class Connect(ref: akka.actor.typed.ActorRef[ClusterJvmMetrics.JvmMetrics]) extends Confirm

  sealed trait JvmMetrics
  case class ClusterMetrics(bs: ByteString) extends JvmMetrics
  case class StreamFailure(ex: Throwable)   extends JvmMetrics
  case object Completed                     extends JvmMetrics

  def apply(): Behavior[ClusterMetricsEvent] =
    Behaviors.receive[ClusterMetricsEvent] {
      case (ctx, _ @Connect(src)) ⇒
        val ex = ClusterMetricsExtension(ctx.system.toUntyped)
        ex.subscribe(ctx.self.toUntyped)
        active(src, mutable.Queue.empty[ClusterMetrics])
      case (ctx, other) ⇒
        ctx.log.warning("Unexpected message: {} in await", other.getClass.getName)
        Behaviors.ignore
    }

  def await(
    src: ActorRef[ClusterJvmMetrics.JvmMetrics],
    q: mutable.Queue[ClusterMetrics]
  ): Behavior[ClusterMetricsEvent] =
    Behaviors.receive[ClusterMetricsEvent] {
      case (_, ClusterJvmMetrics.Confirm) ⇒
        if (q.nonEmpty) {
          src.tell(q.dequeue)
          await(src, q)
        } else active(src, q)
      case (ctx, other) ⇒
        ctx.log.warning("Unexpected message: {} in await", other.getClass.getName)
        Behaviors.ignore
    }

  def active(
    src: ActorRef[ClusterJvmMetrics.JvmMetrics],
    q: mutable.Queue[ClusterMetrics],
    divider: Long = 1024 * 1024,
    defaultTZ: ZoneId = ZoneId.of(java.util.TimeZone.getDefault.getID),
    formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
  ): Behavior[ClusterMetricsEvent] =
    Behaviors
      .receive[ClusterMetricsEvent] {
        case (_, _ @ClusterMetricsChanged(clusterMetrics)) ⇒
          clusterMetrics.foreach {
            case HeapMemory(address, timestamp, used, _, max) ⇒
              val s = JsObject(
                Map(
                  "node"   → JsString(address.toString),
                  "metric" → JsString("heap"),
                  "when" → JsString(
                    formatter.format(
                      ZonedDateTime
                        .ofInstant(Instant.ofEpochMilli(timestamp), defaultTZ)
                    )
                  ),
                  "used" → JsString((used / divider).toString + " mb"),
                  "max"  → JsString((max.getOrElse(0L) / divider).toString + " mb")
                )
              ).prettyPrint
              q.enqueue(ClusterMetrics(ByteString(s)))
          }

          if (q.nonEmpty) {
            src.tell(q.dequeue)
            await(src, q)
          } else Behaviors.same
        case (ctx, other) ⇒
          ctx.log.warning("Unexpected message: {} active", other.getClass.getName)
          Behaviors.ignore
      }
      .receiveSignal {
        case (ctx, PostStop) ⇒
          ctx.log.warning("PostStop !!!")
          src ! StreamFailure(new Exception("Never gonna stop !!!"))
          Behaviors.stopped
      }
}
