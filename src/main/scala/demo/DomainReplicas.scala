package demo

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import demo.ReBalancer.ShardInfo

object DomainReplicas {

  def apply(
    shardRegion: ActorRef[DeviceCommand],
    shard: String,
    hostId: String
  ): Behavior[ShardRegionCmd] =
    Behaviors.setup { ctx ⇒
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist
        .Register(ReBalancer.domainKey, ctx.self)

      Behaviors.receiveMessage {
        case GetShardInfo(r) ⇒
          //if (java.util.concurrent.ThreadLocalRandom.current.nextBoolean)
          r.tell(ShardInfo(shard, ctx.self, hostId))
          Behaviors.same
        case cmd: DeviceCommand ⇒
          shardRegion.tell(cmd)
          Behaviors.same
      }
    }
}
