package demo

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import demo.RingMaster.ShardInfo

object ShardRegionProxy {

  def apply(
    shardRegion: ActorRef[DeviceCommand],
    shardName: String,
    shardAddress: String
  ): Behavior[ShardRegionCmd] =
    Behaviors.setup { ctx ⇒
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist
        .Register(RingMaster.domainKey, ctx.self)

      //ctx.log.warning("* * *  Start proxy [{}:{}] * * *", shardName, shardAddress)
      Behaviors.receiveMessage {
        case GetShardInfo(r) ⇒
          //ctx.log.warning("* * *  Got GetShardInfo {}:{}", shardName, shardAddress)
          //if (java.util.concurrent.ThreadLocalRandom.current.nextBoolean)

          //ShardInfo("alpha", ctx.self, "172.20.0.3-2551")
          r.tell(ShardInfo(shardName, ctx.self, shardAddress))
          Behaviors.same
        case cmd: DeviceCommand ⇒
          shardRegion.tell(cmd)
          Behaviors.same
      }
    }
}
