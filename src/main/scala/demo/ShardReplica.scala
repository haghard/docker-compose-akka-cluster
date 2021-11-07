package demo

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.Cluster
import akka.cluster.ddata.typed.scaladsl.Replicator.{Command, ModifyFailure, StoreFailure, UpdateDataDeleted, UpdateSuccess, UpdateTimeout, _}
import akka.cluster.ddata.typed.scaladsl.{Replicator, ReplicatorMessageAdapter, ReplicatorSettings}
import akka.cluster.ddata.{PNCounterMap, PNCounterMapKey}
import com.typesafe.config.{Config, ConfigFactory}
import demo.RingMaster.PingDeviceReply

import scala.concurrent.duration.{FiniteDuration, _}

object ShardReplica {

  sealed trait Protocol

  // TODO: serialization/deserialization
  final case class PingDeviceReplicator(deviceId: Long, replyTo: ActorRef[PingDeviceReply]) extends Protocol

  // TODO: serialization/deserialization
  private final case class InternalUpdateResponse(
    rsp: UpdateResponse[PNCounterMap[Long]],
    replyTo: ActorRef[PingDeviceReply]
  ) extends Protocol

  // TODO: serialization/deserialization
  private case class InternalDataUpdated(chg: Replicator.SubscribeResponse[PNCounterMap[Long]]) extends Protocol

  private val Key = PNCounterMapKey[Long]("device-counters")

  // log-data-size-exceeding
  def replicatorConfig(role: String): Config =
    ConfigFactory.parseString(
      s"""
         | role = $role
         |
         | gossip-interval = 1 s
         |
         | use-dispatcher = akka.actor.internal-dispatcher
         |
         | log-data-size-exceeding = 10 KiB
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

  def apply(shardName: String, to: FiniteDuration = 1.second): Behavior[Protocol] =
    Behaviors.setup[Protocol] { ctx ⇒
      // WriteMajority(to) WriteTo(2, to)
      implicit val clusterCluster = Cluster(ctx.system.toClassic)

      val ddConf = replicatorConfig(shardName)
      val akkaReplicator: ActorRef[Command] =
        ctx.spawn(akka.cluster.ddata.typed.scaladsl.Replicator.behavior(ReplicatorSettings(ddConf)), "ddata-replicator")

      val adapter = ReplicatorMessageAdapter[Protocol, PNCounterMap[Long]](ctx, akkaReplicator, to)
      adapter.subscribe(Key, InternalDataUpdated.apply)

      def behavior: PartialFunction[Protocol, Behavior[Protocol]] = {
        case PingDeviceReplicator(deviceId, replyTo) ⇒
          ctx.log.warn(s"Write key:${Key.id} - device:$deviceId")
          adapter.askUpdate(
            replyTo ⇒ Replicator.Update(Key, PNCounterMap.empty[Long], WriteLocal, replyTo)(_.increment(deviceId, 1)),
            InternalUpdateResponse(_, replyTo)
          )
          Behaviors.same

        case InternalUpdateResponse(res, replyTo) ⇒
          res match {
            case _: UpdateSuccess[PNCounterMap[Long]] ⇒
              ctx.log.warn(s"UpdateSuccess: [${res.key.id}: ${res.request}]")
              // To simulate io.moia.streamee.package$ResponseTimeoutException: No response within 1 second!
              // if (ThreadLocalRandom.current().nextDouble() > .5) Thread.sleep(1100)
              replyTo.tell(RingMaster.PingDeviceReply.Success)
            case _: UpdateTimeout[PNCounterMap[Long]] ⇒
              ctx.log.warn(s"UpdateTimeout: [${res.key.id}: ${res.request}]")
              replyTo.tell(RingMaster.PingDeviceReply.Error(s"UpdateTimeout: [${res.key.id}]"))
            case _: ModifyFailure[PNCounterMap[Long]]     ⇒
            case _: StoreFailure[PNCounterMap[Long]]      ⇒
            case _: UpdateDataDeleted[PNCounterMap[Long]] ⇒
          }
          Behaviors.same

        case InternalDataUpdated(change @ Replicator.Changed(Key)) ⇒
          ctx.log.warn(s"[$shardName] - keys:[${change.get(Key).entries.mkString(",")}]")
          Behaviors.same

        case other ⇒
          ctx.log.warn(s"Unexpected message in ${getClass.getSimpleName}: $other")
          Behaviors.same
      }

      // Behaviors.receive(onMessage: (ActorContext[T], T) => Behavior[T]) - to get an AC and a message
      // Behaviors.receiveMessage(update[LWWMap[String, BackendSession]]) - same as Behaviors.receive but accepts only a pf

      // Behaviors.receivePartial(onMessage: PartialFunction[(ActorContext[T], T), Behavior[T]]) - to get an AC and a message and ignore unmatched messages
      // Behaviors.receiveMessagePartial(onMessage: PartialFunction[T, Behavior[T]]) //if onMessage doesn't match it returns Behaviors.inhandled

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
