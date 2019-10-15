package demo

import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.sharding.typed.ClusterShardingSettings.StateStoreModeDData
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingMessageExtractor}

import scala.concurrent.duration._

object SharedDomain {

  val passivationTO = 30.seconds //TODO: make it configurable

  /**
    *
    * The default ShardAllocationStrategy will allocate shards on the least-loaded nodes. See
    * https://github.com/akka/akka/blob/master/akka-cluster-sharding/src/main/scala/akka/cluster/sharding/ShardCoordinator.scala#L71 and
    * https://doc.akka.io/docs/akka/current/cluster-sharding.html#shard-location.
    *
    */
  def apply(replicaName: String, system: akka.actor.typed.ActorSystem[_]): akka.actor.typed.ActorRef[DeviceCommand] = {
    /*val allocStrategy = ClusterSharding(system)
      .defaultShardAllocationStrategy(ClusterShardingSettings(system).withRememberEntities(false).withRole(replicaName))*/

    /*
    ClusterSharding(system).start(
      typeName = "devices",
      entityProps = DeviceShadow.props(replicaName),
      /*
      rememberEntities == false ensures that a shard entity won't be recreates/restarted automatically on
      a different `ShardRegion` due to rebalance, crash or graceful exit. That is exactly what we want.
      But there is one downside - the associated shard entity will be allocated on first message arrives(lazy allocation).
      if u need to load massive amount of date in memory, it could be problematic.
     */
      settings = ClusterShardingSettings(system).withRememberEntities(false).withRole(replicaName),
      extractShardId = DeviceShadow.shardId,
      extractEntityId = DeviceShadow.entityId
    )*/

    val settings =
      ClusterShardingSettings(system)
        .withRememberEntities(false)
        .withStateStoreMode(StateStoreModeDData)
        .withPassivateIdleEntitiesAfter(passivationTO)
        .withRole(replicaName)

    ClusterSharding(system).init(
      Entity(DeviceShadowEntity.entityKey, entityCtx ⇒ DeviceShadowEntity(entityCtx.entityId, replicaName))
        .withMessageExtractor(
          new ShardingMessageExtractor[DeviceCommand, DeviceCommand] {
            override def entityId(cmd: DeviceCommand): String             = cmd.replica
            override def shardId(entityId: String): String                = entityId
            override def unwrapMessage(cmd: DeviceCommand): DeviceCommand = cmd
          }
        )
        .withSettings(settings)
        .withEntityProps(akka.actor.typed.Props.empty.withDispatcherFromConfig("akka.shard-dispatcher"))
    )
  }
}
