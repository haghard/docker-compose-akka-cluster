package demo

import java.util.concurrent.ThreadLocalRandom

import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.Reason
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop, SupervisorStrategy}
import akka.stream.StreamRefAttributes
import akka.util.Timeout
import demo.RingMaster.{PingDeviceReply, ShardInfo}
import io.moia.streamee.{IntoableProcessor, Process, ProcessSinkRef}

import scala.concurrent.duration._

/**
  * Starts a shard region with specified shard name and
  * forwards all incoming messages to the shard region
  */
object ShardManager {

  sealed trait Protocol
  case class GetShardInfo(replyTo: akka.actor.typed.ActorRef[demo.RingMaster.Command])  extends Protocol
  case class GetSinkRef(replyTo: ActorRef[ProcessSinkRef[PingDevice, PingDeviceReply]]) extends Protocol

  case class Config(timeout: FiniteDuration, parallelism: Int, bufferSize: Int)

  case object ProcessorCompleted extends Protocol

  case object ShardManagerOutage extends Reason

  def apply(
    shardName: String,    //"alpha"
    shardAddress: String, //"172.20.0.3-2551"
    config: Config
  ): Behavior[ShardManager.Protocol] =
    Behaviors.setup[ShardManager.Protocol] { ctx ⇒
      implicit val sys         = ctx.system
      implicit val to: Timeout = Timeout(config.timeout)

      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist
        .Register(RingMaster.shardManagerKey, ctx.self)

      val replicator = ctx.spawn(
        Behaviors
          .supervise(ShardReplicator(shardName))
          .onFailure[Exception](SupervisorStrategy.resume.withLoggingEnabled(true)),
        "replicator"
      )

      val shardRegion = SharedDomain(replicator, shardName, ctx.system)
      val mergeHub = IntoableProcessor(
        Process[PingDevice, PingDeviceReply]
          .mapAsync(config.parallelism)(req ⇒
            shardRegion.ask[PingDeviceReply](DeviceShadowEntity.PingDevice(req.deviceId, req.replica, _))
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
  )(implicit ctx: ActorContext[ShardManager.Protocol]): Behavior[ShardManager.Protocol] =
    Behaviors
      .receiveMessage[ShardManager.Protocol] {
        case ShardManager.GetShardInfo(ringMaster) ⇒
          ringMaster.tell(ShardInfo(shardName, ctx.self, shardAddress))
          Behaviors.same

        case ShardManager.GetSinkRef(replyTo) ⇒
          ctx.log.info(s"GetSinkRef for $shardName")
          implicit val s = ctx.system
          replyTo.tell(processor.sinkRef(StreamRefAttributes.subscriptionTimeout(config.timeout)))
          Behaviors.same

        case ShardManager.ProcessorCompleted ⇒
          Behaviors.stopped
      }
      .receiveSignal {
        case (ctx, PostStop) ⇒
          processor.shutdown()
          CoordinatedShutdown(ctx.system).run(ShardManagerOutage)
          Behaviors.same
      }
}
