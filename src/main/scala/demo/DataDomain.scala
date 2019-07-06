package demo

import java.nio.ByteBuffer

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.receptionist.Receptionist
import demo.Membership.ReplicaNameReply
import demo.hashing.{CassandraHash, Rendezvous}
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.ShardRegion

object DataDomain {

  def entityId(h: Rendezvous[String]): ShardRegion.ExtractEntityId = {
    case cmd: DeviceCommand ⇒
      val r        = h.replicaFor(cmd.id.toString, 1).head
      val shardBts = r.toString.getBytes
      val hash     = CassandraHash.hash3_x64_128(ByteBuffer.wrap(shardBts), 0, shardBts.length, h.seed)(1).toHexString
      println(s"entity: ${cmd.id}/${r}/${hash}")
      (hash, cmd)
  }

  def shardId(h: Rendezvous[String]): ShardRegion.ExtractShardId = {
    case cmd: DeviceCommand ⇒
      val r        = h.replicaFor(cmd.id.toString, 1).head
      val shardBts = r.toString.getBytes
      val hash     = CassandraHash.hash3_x64_128(ByteBuffer.wrap(shardBts), 0, shardBts.length, h.seed)(1).toHexString
      println(s"shard: ${cmd.id}/${r}/${hash}")
      hash
  }

  def apply(replicaName: String): Behavior[DataProtocol] =
    Behaviors.setup { ctx ⇒
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist.Register(Membership.domainKey, ctx.self)

      //val shardRegion = DistributedShardedDomain(replicaName, ctx.system.toUntyped, hash).toTyped[DeviceCommand]

      Behaviors.receiveMessage {
        case GetReplicaName(r) ⇒
          //if (java.util.concurrent.ThreadLocalRandom.current().nextBoolean)
          r.tell(ReplicaNameReply(replicaName, ctx.self))
          Behaviors.same
        case PingDevice(id) ⇒
          //val role = hash.replicaFor(id.toString, 1).head
          Behaviors.same
      }
    }
}
