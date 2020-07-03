package demo

import demo.RingMaster.ShardInfo
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

object ShardingProxy {

  def apply(
    replicator: ActorRef[DeviceReplicator.Protocol],
    shardName: String,   //"alpha"
    shardAddress: String //"172.20.0.3-2551"
  ): Behavior[ShardRegionCmd] =
    Behaviors.setup[ShardRegionCmd] { ctx ⇒
      ctx.system.receptionist tell akka.actor.typed.receptionist.Receptionist
        .Register(RingMaster.domainKey, ctx.self)
      val shardRegion = SharedDomain(replicator, shardName, ctx.system)
      active(shardRegion, shardName, shardAddress)(ctx)
    }

  def active(shardRegion: ActorRef[DeviceCommand], shardName: String, shardAddress: String)(implicit
    ctx: ActorContext[ShardRegionCmd]
  ): Behavior[ShardRegionCmd] =
    Behaviors.receiveMessage {
      case GetShardInfo(ringMaster) ⇒
        ringMaster.tell(ShardInfo(shardName, ctx.self, shardAddress))
        Behaviors.same
      case cmd: DeviceCommand ⇒
        //forward the message to shardRegion
        shardRegion.tell(cmd)
        Behaviors.same
    }
}
