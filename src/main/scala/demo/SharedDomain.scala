package demo

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

object SharedDomain {

  /**
    *
    * The default ShardAllocationStrategy will allocate shards on the least-loaded nodes. See
    * https://github.com/akka/akka/blob/master/akka-cluster-sharding/src/main/scala/akka/cluster/sharding/ShardCoordinator.scala#L71 and
    * https://doc.akka.io/docs/akka/current/cluster-sharding.html#shard-location.
    *
    * @param replicaName
    * @param system
    * @return
    */
  def apply(replicaName: String, system: ActorSystem): ActorRef =
    /*val allocStrategy = ClusterSharding(system)
      .defaultShardAllocationStrategy(ClusterShardingSettings(system).withRememberEntities(false).withRole(replicaName))*/

    ClusterSharding(system).start(
      typeName = "devices",
      entityProps = DeviceReplica.props(replicaName),
      /*
      rememberEntities == false ensures that a shard entity won't be recreates/restarted automatically on
      a different `ShardRegion` due to rebalance, crash or graceful exit. That is exactly what we want.
      But there is one downside - the associated shard entity will be allocated on first message arrives.
      if u need to load massive amount of date, it could be problematic.
       */
      settings = ClusterShardingSettings(system).withRememberEntities(false).withRole(replicaName),
      extractShardId = DomainReplicas.shardId,
      extractEntityId = DomainReplicas.entityId
    )
}
