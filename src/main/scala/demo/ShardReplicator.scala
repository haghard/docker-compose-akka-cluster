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
import demo.RingMaster.PingDeviceReply

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

object ShardReplicator {

  sealed trait Protocol

  final case class PingReplicator(deviceId: Long, replyTo: ActorRef[PingDeviceReply]) extends Protocol

  private final case class InternalUpdateResponse(
    rsp: UpdateResponse[PNCounterMap[Long]],
    replyTo: ActorRef[PingDeviceReply]
  ) extends Protocol

  private case class InternalDataUpdated(chg: Replicator.SubscribeResponse[PNCounterMap[Long]]) extends Protocol

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

  def apply(shardName: String, to: FiniteDuration = 1.seconds): Behavior[Protocol] =
    Behaviors.setup[Protocol] { ctx ⇒
      //WriteMajority(to) WriteTo(2, to)
      implicit val clusterCluster = Cluster(ctx.system.toClassic)

      val ddConf = replicatorConfig(shardName)
      val akkaReplicator: ActorRef[Command] =
        ctx.spawn(akka.cluster.ddata.typed.scaladsl.Replicator.behavior(ReplicatorSettings(ddConf)), "ddata-replicator")

      val adapter = ReplicatorMessageAdapter[Protocol, PNCounterMap[Long]](ctx, akkaReplicator, to)
      adapter.subscribe(Key, InternalDataUpdated.apply)

      def behavior: PartialFunction[Protocol, Behavior[Protocol]] = {
        case PingReplicator(deviceId, replyTo) ⇒
          ctx.log.warn(s"Write key:${Key.id} - device:$deviceId")
          adapter.askUpdate(
            replyTo ⇒ Replicator.Update(Key, PNCounterMap.empty[Long], WriteLocal, replyTo)(_.increment(deviceId, 1)),
            InternalUpdateResponse(_, replyTo)
          )
          Behaviors.same

        case InternalUpdateResponse(res: UpdateSuccess[PNCounterMap[Long]], replyTo) ⇒
          ctx.log.warn(s"UpdateSuccess: [${res.key.id}: ${res.request}]")
          replyTo.tell(RingMaster.PingDeviceReply.Success(res.key.id))
          Behaviors.same

        case InternalUpdateResponse(res: UpdateTimeout[PNCounterMap[Long]], replyTo) ⇒
          ctx.log.warn(s"UpdateTimeout: [${res.key.id}: ${res.request}]")
          replyTo.tell(RingMaster.PingDeviceReply.Error(s"UpdateTimeout: [${res.key.id}]"))
          Behaviors.same

        case InternalDataUpdated(change @ Replicator.Changed(Key)) ⇒
          ctx.log.warn(s"[$shardName] - keys:[${change.get(Key).entries.mkString(",")}]")
          Behaviors.same

        /*
        case InternalUpdateResponse(_: ModifyFailure[PNCounterMap[Long]] @unchecked, replyTo) ⇒
          //replyTo ! WFailure(sessionId, "ModifyFailure")
          Behaviors.same

        case InternalUpdateResponse(_: StoreFailure[PNCounterMap[Long]] @unchecked, replyTo) ⇒
          replyTo ! WFailure(sessionId, "StoreFailure")
          Behaviors.same
         */
        case other ⇒
          ctx.log.warn(s"Unexpected message in ${getClass.getSimpleName}: $other")
          Behaviors.same
      }

      //Behaviors.receive(onMessage: (ActorContext[T], T) => Behavior[T]) - to get an AC and a message
      //Behaviors.receiveMessage(update[LWWMap[String, BackendSession]]) - same as Behaviors.receive but accepts only a pf

      //Behaviors.receivePartial(onMessage: PartialFunction[(ActorContext[T], T), Behavior[T]]) - to get an AC and a message and ignore unmatched messages
      //Behaviors.receiveMessagePartial(onMessage: PartialFunction[T, Behavior[T]]) //if onMessage doesn't match it returns Behaviors.inhandled

      Behaviors.receiveMessage(behavior)
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
