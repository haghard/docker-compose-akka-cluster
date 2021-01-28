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

object ClusterJvmMetrics {

  sealed trait Confirm                                            extends ClusterMetricsEvent
  case object Confirm                                             extends Confirm
  case class Connect(ref: ActorRef[ClusterJvmMetrics.JvmMetrics]) extends Confirm

  sealed trait JvmMetrics
  case class ClusterMetrics(bs: ByteString) extends JvmMetrics
  case class StreamFailure(ex: Throwable)   extends JvmMetrics
  case object Completed                     extends JvmMetrics

  def apply(bs: Int = 1 << 5): Behavior[ClusterMetricsEvent] =
    Behaviors.receive[ClusterMetricsEvent] {
      case (ctx, _ @Connect(src)) ⇒
        val ex = ClusterMetricsExtension(ctx.system.toClassic)
        ex.subscribe(ctx.self.toClassic)
        //if you have more than 32 node in the cluster you need to increase the buffer size
        active(src, new RingBuffer[ClusterMetrics](bs))
      case (ctx, other) ⇒
        ctx.log.warn("Unexpected message: {} in init", other.getClass.getName)
        Behaviors.stopped
    }

  def awaitConfirmation(
    source: ActorRef[ClusterJvmMetrics.JvmMetrics],
    rb: RingBuffer[ClusterMetrics]
  ): Behavior[ClusterMetricsEvent] =
    Behaviors.receive[ClusterMetricsEvent] {
      case (_, ClusterJvmMetrics.Confirm) ⇒
        if (rb.size > 0) {
          rb.poll.foreach(source.tell(_))
          awaitConfirmation(source, rb)
        } else active(source, rb)
      case _ ⇒
        Behaviors.ignore
    }

  def active(
    source: ActorRef[ClusterJvmMetrics.JvmMetrics],
    rb: RingBuffer[ClusterMetrics],
    divider: Long = 1024 * 1024,
    defaultTZ: ZoneId = ZoneId.of(java.util.TimeZone.getDefault.getID),
    formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
  ): Behavior[ClusterMetricsEvent] =
    Behaviors
      .receive[ClusterMetricsEvent] {
        case (_, _ @ClusterMetricsChanged(metrics)) ⇒
          metrics.foreach {
            case HeapMemory(address, timestamp, used, _, max) ⇒
              val js = JsObject(
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
              //the size of metrics emitted at once is equal to the number of nodes in the cluster,
              //therefore the max size of the queue is bound to that number
              rb.offer(ClusterMetrics(ByteString(js)))
            case _ ⇒
          }

          if (rb.size > 0) {
            rb.poll.foreach(source.tell(_))
            awaitConfirmation(source, rb)
          } else Behaviors.same
        case (ctx, other) ⇒
          ctx.log.warn("Unexpected message: {} active", other.getClass.getName)
          Behaviors.same
      }
      .receiveSignal { case (ctx, PostStop) ⇒
        ctx.log.debug("ClusterJvmMetrics.PostStop")
        source.tell(StreamFailure(new Exception("JvmMetrics should never stop")))
        Behaviors.stopped
      }
}
