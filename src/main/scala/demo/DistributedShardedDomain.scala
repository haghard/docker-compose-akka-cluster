package demo

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import demo.hashing.Rendezvous

object DistributedShardedDomain {

  def apply(replicaName: String, system: ActorSystem, hash: Rendezvous[Replica]): ActorRef =
    ClusterSharding(system).start(
      typeName = "devices",
      entityProps = DeviceShadow.props(replicaName),
      settings = ClusterShardingSettings(system).withRememberEntities(true).withRole(replicaName),
      extractShardId = Membership.shardId(hash),
      extractEntityId = Membership.entityId(hash)
    )
}
