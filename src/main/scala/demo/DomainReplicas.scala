package demo

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import demo.ReplicatedShardCoordinator.ShardInfo
import akka.cluster.sharding.ShardRegion

object DomainReplicas {

  /* def entityId(replicasHash: Rendezvous[String]): ShardRegion.ExtractEntityId = {
    case cmd: DeviceCommand ⇒
      val r        = replicasHash.inChargeOf(cmd.id.toString, 1).head
      val shardBts = r.toString.getBytes
      val hash =
        CassandraHash.hash3_x64_128(ByteBuffer.wrap(shardBts), 0, shardBts.length, replicasHash.seed)(1).toHexString
      println(s"entity: ${cmd.id}/${r}/${hash}")
      (hash, cmd)
  }

  def shardId(replicasHash: Rendezvous[String]): ShardRegion.ExtractShardId = {
    case cmd: DeviceCommand ⇒
      val r        = replicasHash.inChargeOf(cmd.id.toString, 1).head
      val shardBts = r.toString.getBytes
      val hash =
        CassandraHash.hash3_x64_128(ByteBuffer.wrap(shardBts), 0, shardBts.length, replicasHash.seed)(1).toHexString
      println(s"shard: ${cmd.id}/${r}/${hash}")
      hash
  }
   */

  val entityId: ShardRegion.ExtractEntityId = {
    case cmd: DeviceCommand ⇒
      //println(s"entity: ${cmd.id} -> ${cmd.replica}")
      (cmd.replica, cmd)
  }

  val shardId: ShardRegion.ExtractShardId = {
    case cmd: DeviceCommand ⇒
      //println(s"shard: ${cmd.id} -> ${cmd.replica}")
      cmd.replica
  }

  def apply(
    shardRegion: ActorRef[DeviceCommand],
    shard: String,
    hostId: String
  ): Behavior[ShardRegionCmd] =
    Behaviors.setup { ctx ⇒
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist
        .Register(ReplicatedShardCoordinator.domainKey, ctx.self)

      Behaviors.receiveMessage {
        case IdentifyShard(r) ⇒
          //if (java.util.concurrent.ThreadLocalRandom.current.nextBoolean)
          r.tell(ShardInfo(shard, ctx.self, hostId))
          Behaviors.same
        case cmd: PingDevice ⇒
          shardRegion.tell(cmd)
          Behaviors.same
      }
    }
}
