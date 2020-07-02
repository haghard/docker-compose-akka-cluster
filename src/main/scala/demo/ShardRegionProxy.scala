package demo

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.external.ExternalShardAllocation
import demo.RingMaster.ShardInfo

import scala.util.{Failure, Success}

object ShardRegionProxy {

  def apply(
    shardName: String,   //"alpha"
    shardAddress: String //"172.20.0.3-2551"
  ): Behavior[ShardRegionCmd] =
    Behaviors.setup { ctx ⇒
      implicit val ec = ctx.executionContext
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist
        .Register(RingMaster.domainKey, ctx.self)

      val shardRegion           = SharedDomain(shardName, ctx.system)
      val shardAllocationClient = ExternalShardAllocation(ctx.system).clientFor(DeviceShadowEntity.entityKey.name)
      ctx.pipeToSelf(shardAllocationClient.updateShardLocation(shardAddress, ctx.self.path.address))(_ ⇒ demo.Ready)

      Behaviors.receiveMessagePartial {
        case demo.Ready ⇒
          Behaviors.receiveMessagePartial {
            case GetShardInfo(r) ⇒
              //ctx.log.warning("* * *  Got GetShardInfo {}:{}", shardName, shardAddress)

              //ShardInfo("alpha", ctx.self, "172.20.0.3-2551")
              r.tell(ShardInfo(shardName, ctx.self, shardAddress))
              Behaviors.same
            case cmd: DeviceCommand ⇒
              ctx.pipeToSelf(shardAllocationClient.shardLocations().map(_.locations.keySet.mkString(";"))) {
                case Success(str) ⇒ demo.ShardInfo(str)
                case Failure(ex)  ⇒ demo.ShardInfo(ex.getMessage)
              }
              shardRegion.tell(cmd)
              Behaviors.same
            case demo.ShardInfo(str) ⇒
              ctx.log.warn(s"ShardAllocation [$str]")
              Behaviors.same

          }
      }
    }
}
