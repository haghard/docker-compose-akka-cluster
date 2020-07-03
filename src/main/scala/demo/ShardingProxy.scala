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
      implicit val actorCtx = ctx
      implicit val ec       = ctx.executionContext
      ctx.system.receptionist tell akka.actor.typed.receptionist.Receptionist
        .Register(RingMaster.domainKey, ctx.self)
      val shardRegion = SharedDomain(replicator, shardName, ctx.system)

      /*implicit val shardAllocationClient =
        ExternalShardAllocation(ctx.system).clientFor(DeviceShadowEntity.entityKey.name)
      ctx.pipeToSelf(shardAllocationClient.updateShardLocation(shardAddress, ctx.self.path.address))(_ ⇒
        demo.ShardAllocated
      )*/
      active(shardRegion, shardName, shardAddress)
    }

  def active(shardRegion: ActorRef[DeviceCommand], shardName: String, shardAddress: String)(implicit
    ctx: ActorContext[ShardRegionCmd]
  ): Behavior[ShardRegionCmd] =
    Behaviors.receiveMessagePartial { //because of demo.ShardAllocated
      case GetShardInfo(r) ⇒
        r.tell(ShardInfo(shardName, ctx.self, shardAddress))
        Behaviors.same
      case cmd: DeviceCommand ⇒
        /*ctx.pipeToSelf(client.shardLocations().map(_.locations.keySet.mkString(";"))(ctx.executionContext)) {
          case Success(str) ⇒ demo.ShardInfo(str)
          case Failure(ex)  ⇒ demo.ShardInfo(ex.getMessage)
        }*/

        //forward the message to shardRegion
        shardRegion.tell(cmd)
        Behaviors.same
      case demo.ShardInfo(str) ⇒
        ctx.log.warn(s"ShardAllocation [$str]")
        Behaviors.same
    }
}
