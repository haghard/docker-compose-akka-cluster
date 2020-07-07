package demo

import demo.RingMaster.{PingDeviceReply, ShardInfo}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import io.moia.streamee.{IntoableProcessor, Process, ProcessSinkRef, Step}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import scala.concurrent.duration._

/**
  * Starts a shard region with specified shard name and
  * forwards all incoming messages to the shard region
  */
object ShardManager {

  sealed trait Protocol
  case class GetShardInfo(replyTo: akka.actor.typed.ActorRef[demo.RingMaster.Command]) extends Protocol
  case class PingDevice(deviceId: Long, replica: String)                               extends Protocol

  final case class GetSinkRef(replyTo: ActorRef[ProcessSinkRef[PingDevice, PingDeviceReply]]) extends Protocol

  def apply(
    shardName: String,   //"alpha"
    shardAddress: String //"172.20.0.3-2551"
  ): Behavior[ShardManager.Protocol] =
    Behaviors.setup[ShardManager.Protocol] { ctx ⇒
      ctx.system.receptionist tell akka.actor.typed.receptionist.Receptionist
        .Register(RingMaster.shardManagerKey, ctx.self)

      val replicator = ctx.spawn(
        Behaviors
          .supervise(ShardReplicator(shardName))
          .onFailure[Exception](SupervisorStrategy.resume.withLoggingEnabled(true)),
        "replicator"
      )

      val shardRegion = SharedDomain(replicator, shardName, ctx.system)
      active(shardRegion, shardName, shardAddress)(ctx)
    }

  def active(shardRegion: ActorRef[DeviceShadowEntity.DeviceCommand], shardName: String, shardAddress: String)(implicit
    ctx: ActorContext[ShardManager.Protocol]
  ): Behavior[ShardManager.Protocol] =
    Behaviors.receiveMessage {
      case ShardManager.GetShardInfo(ringMaster) ⇒
        implicit val sys = ctx.system
        ringMaster.tell(ShardInfo(shardName, ctx.self, shardAddress))
        /*Step[PingDevice, Ctx]
          .mapAsync(1) { words =>
            ???
          }*/

        val processor = IntoableProcessor(
          Process[PingDevice, PingDeviceReply]
            .mapAsync(4) { ping ⇒
              shardRegion
                .ask[PingDeviceReply](DeviceShadowEntity.PingDevice(ping.deviceId, ping.replica, _))(
                  Timeout(1.second),
                  sys.scheduler
                )
            },
          "consumer",
          4
        )

        /*
        processor.whenDone.onComplete { reason =>
          ctx.log.warn(s"Process completed: $reason")
          ctx.self ! StopShardRegionManager
        }(???)
         */

        Behaviors.receiveMessagePartial {
          case GetSinkRef(replyTo) ⇒
            replyTo ! processor.sinkRef()
            Behaviors.same
        }

      /*case cmd: DeviceCommand ⇒
        //forward the message to shardRegion
        shardRegion.tell(cmd)
        Behaviors.same
       */
    }
}
