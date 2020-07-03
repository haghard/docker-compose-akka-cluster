package demo

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.Cluster
import akka.cluster.ddata.{PNCounterMap, PNCounterMapKey}
import akka.cluster.ddata.typed.scaladsl.Replicator.{Command, ModifyFailure, StoreFailure, UpdateDataDeleted, UpdateSuccess, UpdateTimeout}
import akka.cluster.ddata.typed.scaladsl.{Replicator, ReplicatorSettings}
import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.ddata.typed.scaladsl.Replicator._
import akka.cluster.ddata.typed.scaladsl.ReplicatorMessageAdapter

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

object DeviceReplicator {

  sealed trait Protocol

  final case class Ping(deviceId: Long) extends Protocol

  private final case class InternalUpdateResponse(rsp: UpdateResponse[PNCounterMap[Long]]) extends Protocol

  private case class InternalOnUpdateResponse(chg: Replicator.SubscribeResponse[PNCounterMap[Long]]) extends Protocol

  private val Key = PNCounterMapKey[Long]("device-counters")

  def replicatorConfig(role: String): Config =
    ConfigFactory.parseString(
      s"""
           | role = $role
           |
           | gossip-interval = 1 s
           |
           | use-dispatcher = "akka.actor.default-dispatcher"
           |
           | notify-subscribers-interval = 2000 ms
           |
           | max-delta-elements = 100
           |
           | # How often the Replicator checks for pruning of data associated with
           | # removed cluster nodes. If this is set to 'off' the pruning feature will
           | # be completely disabled.
           | pruning-interval = 120 s
           |
           | max-pruning-dissemination = 300 s
           |
           | pruning-marker-time-to-live = 6 h
           |
           | serializer-cache-time-to-live = 10s
           |
           | prefer-oldest = off
           |
           | delta-crdt {
           |   enabled = on
           |
           |   # Some complex deltas grow in size for each update and above this
           |   # threshold such deltas are discarded and sent as full state instead.
           |   # This is number of elements or similar size hint, not size in bytes.
           |   max-delta-size = 50
           | }
           |
           | durable {
           |  keys = []
           |  pruning-marker-time-to-live = 10 d
           |  store-actor-class = akka.cluster.ddata.LmdbDurableStore
           |  pinned-store {
           |    type = PinnedDispatcher
           |    executor = thread-pool-executor
           |  }
           |
           |  use-dispatcher = akka.cluster.distributed-data.durable.pinned-store
           | }
           |
          """.stripMargin
    )

  def apply(role: String, to: FiniteDuration = 1.seconds): Behavior[Protocol] =
    Behaviors.setup[Protocol] { ctx ⇒
      implicit val cl = Cluster(ctx.system.toClassic)

      //WriteMajority(to) respects in reachable nodes
      val wc = WriteLocal //WriteMajority(to) WriteTo(2, to)

      val ddConf = replicatorConfig(role)
      val akkaReplicator: ActorRef[Command] =
        ctx.spawn(akka.cluster.ddata.typed.scaladsl.Replicator.behavior(ReplicatorSettings(ddConf)), "akka-repl")

      val adapter = ReplicatorMessageAdapter[Protocol, PNCounterMap[Long]](ctx, akkaReplicator, to)
      adapter.subscribe(Key, InternalOnUpdateResponse.apply)

      def update: PartialFunction[Protocol, Behavior[Protocol]] = {
        case Ping(deviceId) ⇒
          ctx.log.warn(s"Write key:${Key._id} - device:$deviceId")
          adapter.askUpdate(
            askReplyTo ⇒ Replicator.Update(Key, PNCounterMap.empty[Long], wc, askReplyTo)(_.increment(deviceId, 1)),
            InternalUpdateResponse(_)
          )
          Behaviors.same

        case InternalOnUpdateResponse(change @ Replicator.Changed(Key)) ⇒
          ctx.log.warn(s"$role - keys:[${change.get(Key).entries.mkString(",")}]")
          Behaviors.same

        /*case InternalUpdateResponse(res: UpdateSuccess[PNCounterMap[Long]], replyTo) ⇒ {
          //replyTo ! WSuccess(sessionId)
          Behaviors.same
        }

        case InternalUpdateResponse(_: UpdateDataDeleted[PNCounterMap[Long]] @unchecked, replyTo) ⇒
          //replyTo ! WSuccess(sessionId)
          Behaviors.same

        case InternalUpdateResponse(_: UpdateTimeout[PNCounterMap[Long]] @unchecked, replyTo) ⇒
          //doesn't happen with WriteMajority
          //happens with WriteTo
          //replyTo ! WFailure(sessionId, "UpdateTimeout. Not all replicas responded on time")
          Behaviors.same

        case InternalUpdateResponse(_: ModifyFailure[PNCounterMap[Long]] @unchecked, replyTo) ⇒
          //replyTo ! WFailure(sessionId, "ModifyFailure")
          Behaviors.same

        case InternalUpdateResponse(_: StoreFailure[PNCounterMap[Long]] @unchecked, replyTo) ⇒
          //replyTo ! WFailure(sessionId, "StoreFailure")
          Behaviors.same*/
        case _ ⇒
          Behaviors.same
      }

      //Behaviors.receive(onMessage: (ActorContext[T], T) => Behavior[T]) - to get an AC and a message
      //Behaviors.receiveMessage(update[LWWMap[String, BackendSession]]) - same as Behaviors.receive but accepts only a pf

      //Behaviors.receivePartial(onMessage: PartialFunction[(ActorContext[T], T), Behavior[T]]) - to get an AC and a message and ignore unmatched messages
      //Behaviors.receiveMessagePartial(onMessage: PartialFunction[T, Behavior[T]]) //if onMessage doesn't match it returns Behaviors.inhandled

      Behaviors.receiveMessage(update)
    /*.receiveSignal {
          case (ctx, MessageAdaptionFailure(err)) =>
            ctx.log.error(s"Failure: ", err)
            Behaviors.same
          case (ctx, signal) ⇒
            ctx.log.error(s"Unexpected critical error. $signal")
            Behaviors.stopped
        }*/
    }
}
