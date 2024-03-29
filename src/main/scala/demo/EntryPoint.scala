package demo

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

// format: off
/** Starts:
  * 1) replicator actor with specified shard name
  * 2) A shard region with specified shard name and forwards all incoming messages to the shard region
  */
// format: on
object EntryPoint {

  sealed trait Protocol
  final case class GetShardInfo(replyTo: akka.actor.typed.ActorRef[demo.RingMaster.Command])  extends Protocol
  final case class GetSinkRef(replyTo: ActorRef[ProcessSinkRef[PingDevice, PingDeviceReply]]) extends Protocol

  final case class Config(timeout: FiniteDuration, parallelism: Int, bufferSize: Int)

  case object ProcessorCompleted extends Protocol

  case object ShardEntranceOutage extends Reason

  def apply(
    shardName: String,    // "alpha"
    shardAddress: String, // "172.20.0.3-2551"
    config: Config
  ): Behavior[EntryPoint.Protocol] =
    Behaviors.setup[EntryPoint.Protocol] { ctx =>
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

      val intoableProcessor = IntoableProcessor(
        Process[PingDevice, PingDeviceReply]
          .mapAsync(config.parallelism)(req =>
            shardRegion.ask[PingDeviceReply](DeviceDigitalTwin.PingDevice(req.deviceId, req.replica, _))
          ),
        s"$shardName-into-entrance",
        config.bufferSize
      )

      intoableProcessor.whenDone.onComplete { _ =>
        ctx.self.tell(ProcessorCompleted)
      }(ctx.system.executionContext)

      active(intoableProcessor, shardName, shardAddress, config)(ctx)
    }

  def active(
    intoableProcessor: IntoableProcessor[PingDevice, PingDeviceReply],
    shardName: String,
    shardAddress: String,
    config: Config
  )(implicit ctx: ActorContext[EntryPoint.Protocol]): Behavior[EntryPoint.Protocol] =
    Behaviors
      .receiveMessage[EntryPoint.Protocol] {
        case EntryPoint.GetShardInfo(ringMaster) =>
          // Example:  ShardInfo("alpha", ctx.self, "172.20.0.3-2551")
          ringMaster.tell(ShardInfo(shardName, ctx.self, shardAddress))
          Behaviors.same

        case EntryPoint.GetSinkRef(replyTo) =>
          // ctx.log.info(s"${classOf[GetSinkRef].getName} for $shardName")
          // implicit val m = akka.stream.Materializer.matFromSystem(akka.actor.typed.scaladsl.adapter.TypedActorSystemOps(ctx.system).toClassic)
          implicit val s = ctx.system
          replyTo.tell(intoableProcessor.sinkRef(StreamRefAttributes.subscriptionTimeout(config.timeout)))
          Behaviors.same

        case EntryPoint.ProcessorCompleted =>
          Behaviors.stopped

      }
      .receiveSignal { case (ctx, PostStop) =>
        intoableProcessor.shutdown()
        CoordinatedShutdown(ctx.system).run(ShardEntranceOutage)
        Behaviors.same
      }
}
