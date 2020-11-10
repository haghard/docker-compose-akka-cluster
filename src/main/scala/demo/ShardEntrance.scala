package demo

import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.Reason
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop, SupervisorStrategy}
import akka.stream.StreamRefAttributes
import akka.util.Timeout
import demo.RingMaster.{PingDeviceReply, ShardInfo}
import io.moia.streamee.{IntoableProcessor, Process, ProcessSinkRef, Respondee}

import scala.concurrent.duration._

/** Starts a shard region with specified shard name and forwards all incoming messages to the shard region
  */
object ShardEntrance {

  sealed trait Protocol
  final case class GetShardInfo(replyTo: akka.actor.typed.ActorRef[demo.RingMaster.Command])  extends Protocol
  final case class GetSinkRef(replyTo: ActorRef[ProcessSinkRef[PingDevice, PingDeviceReply]]) extends Protocol

  final case class Config(timeout: FiniteDuration, parallelism: Int, bufferSize: Int)

  case object ProcessorCompleted extends Protocol

  case object ShardEntranceOutage extends Reason

  def apply(
    shardName: String,    //"alpha"
    shardAddress: String, //"172.20.0.3-2551"
    config: Config
  ): Behavior[ShardEntrance.Protocol] =
    Behaviors.setup[ShardEntrance.Protocol] { ctx ⇒
      implicit val sys         = ctx.system
      implicit val to: Timeout = Timeout(config.timeout)

      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist
        .Register(RingMaster.shardManagerKey, ctx.self)

      val replicator = ctx.spawn(
        Behaviors
          .supervise(ShardReplica(shardName))
          .onFailure[Exception](SupervisorStrategy.resume.withLoggingEnabled(true)),
        "replicator"
      )

      val shardRegion = SharedDomain(replicator, shardName, ctx.system)

      /*
      import akka.stream.scaladsl.{Flow, FlowWithContext}
      val f: Flow[(PingDevice, Respondee[PingDeviceReply]), (PingDeviceReply, Respondee[PingDeviceReply]), Any] =
        Process[PingDevice, PingDeviceReply]
          .mapAsync(config.parallelism)(req ⇒
            shardRegion.ask[PingDeviceReply](DeviceShadowEntity.PingDevice(req.deviceId, req.replica, _))
          )
          .asFlow
          .batch(
            config.parallelism,
            {
              case (reply, resp) ⇒
                val rb = new RingBuffer[(PingDeviceReply,Respondee[PingDeviceReply])](config.parallelism)
                rb.offer(reply -> resp)
                rb
            }
          ) { (rb, tuple) ⇒
            rb.offer(tuple)
            rb
          }
          .mapConcat { rb ⇒
            new scala.collection.immutable.Iterable[PingDeviceReply]() {
              override def iterator: Iterator[PingDeviceReply] =
                rb.entries.iterator
            }
          }
      FlowWithContext.fromTuples(f)
       */

      val mergeHub = IntoableProcessor(
        Process[PingDevice, PingDeviceReply]
          .mapAsync(config.parallelism)(req ⇒
            shardRegion.ask[PingDeviceReply](DeviceDigitalTwin.PingDevice(req.deviceId, req.replica, _))
          ),
        "shard-input",
        config.bufferSize
      )

      mergeHub.whenDone.onComplete { _ ⇒
        ctx.self.tell(ProcessorCompleted)
      }(ctx.system.executionContext)

      active(mergeHub, shardName, shardAddress, config)(ctx)
    }

  def active(
    processor: IntoableProcessor[PingDevice, PingDeviceReply],
    shardName: String,
    shardAddress: String,
    config: Config
  )(implicit ctx: ActorContext[ShardEntrance.Protocol]): Behavior[ShardEntrance.Protocol] =
    Behaviors
      .receiveMessage[ShardEntrance.Protocol] {
        case ShardEntrance.GetShardInfo(ringMaster) ⇒
          //Example:  ShardInfo("alpha", ctx.self, "172.20.0.3-2551")
          ringMaster.tell(ShardInfo(shardName, ctx.self, shardAddress))
          Behaviors.same

        case ShardEntrance.GetSinkRef(replyTo) ⇒
          ctx.log.info(s"${classOf[GetSinkRef].getName} for $shardName")
          implicit val s = ctx.system
          replyTo.tell(processor.sinkRef(StreamRefAttributes.subscriptionTimeout(config.timeout)))
          Behaviors.same

        case ShardEntrance.ProcessorCompleted ⇒
          Behaviors.stopped
      }
      .receiveSignal { case (ctx, PostStop) ⇒
        processor.shutdown()
        CoordinatedShutdown(ctx.system).run(ShardEntranceOutage)
        Behaviors.same
      }
}
