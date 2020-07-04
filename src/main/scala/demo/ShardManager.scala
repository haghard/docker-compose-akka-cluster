package demo

import demo.RingMaster.ShardInfo
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

/**
  * Starts a shard region with specified shard name and
  * forwards all incoming messages to the shard region
  */
object ShardManager {

  def apply(
    shardName: String,   //"alpha"
    shardAddress: String //"172.20.0.3-2551"
  ): Behavior[ShardRegionCmd] =
    Behaviors.setup[ShardRegionCmd] { ctx ⇒
      ctx.system.receptionist tell akka.actor.typed.receptionist.Receptionist
        .Register(RingMaster.domainKey, ctx.self)

      val replicator = ctx.spawn(
        Behaviors
          .supervise(ShardReplicator(shardName))
          .onFailure[Exception](SupervisorStrategy.resume.withLoggingEnabled(true)),
        "replicator"
      )

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
