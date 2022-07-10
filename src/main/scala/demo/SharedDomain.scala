package demo

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.sharding.typed.ClusterShardingSettings.StateStoreModeDData
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingMessageExtractor}

import scala.concurrent.duration._

object SharedDomain {

  object DeviceMsgExtractor {
    def apply[T <: DeviceDigitalTwin.DeviceCommand]: ShardingMessageExtractor[T, T] =
      new ShardingMessageExtractor[T, T] {

        override def entityId(cmd: T): String = cmd.replica

        override def shardId(entityId: String): String = entityId

        override def unwrapMessage(cmd: T): T = cmd
      }
  }

  private val passivationTO = 120.seconds // TODO: make it configurable

  def apply(
    replicator: ActorRef[ShardReplica.Protocol],
    role: String, // alpha|betta|gamma
    system: akka.actor.typed.ActorSystem[_]
  ): akka.actor.typed.ActorRef[DeviceDigitalTwin.DeviceCommand] = {

    // Allocation strategy which decides on which nodes to allocate new shards.
    // https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html?_ga=2.193469741.1478281344.1585435561-801666185.1515340543#external-shard-allocation
    // Distributed processing with Akka Cluster & Kafka https://youtu.be/Ad2DyOn4dlY?t=197
    // https://github.com/akka/akka-samples/tree/2.6/akka-sample-kafka-to-sharding-scala

    // TODO: To make explicit allocations. Try it out in RingMaster
    // val shardAllocation = ExternalShardAllocation(system).clientFor(DeviceShadowEntity.entityKey.name)
    // shardAllocation.shardLocations()
    // val _: Future[Done] = shardAllocation.updateShardLocation(shardName, system.address)

    val sharding = ClusterSharding(system)

    val settings =
      ClusterShardingSettings(system)
        /*
          Remembering entities automatically restarts entities after shard rebalancing or entity crash. Without "remember-entities"
          restarts happen on the arrival of a message. That is exactly what we want in this case.
         */
        .withRememberEntities(false)
        .withStateStoreMode(StateStoreModeDData)
        .withPassivateIdleEntityAfter(passivationTO)
        .withRole(role)

    // val allocStrategy = sharding.defaultShardAllocationStrategy(settings)
    /*
    sharding.init(
      Entity(DeviceShadowEntity.entityKey)(createBehavior = _ ⇒ DeviceShadowEntity(shardName))
        .withAllocationStrategy(new ExternalShardAllocationStrategy(system, DeviceShadowEntity.entityKey.name))
        .withMessageExtractor(
          new ShardingMessageExtractor[DeviceCommand, DeviceCommand] {
            override def entityId(cmd: DeviceCommand): String             = cmd.replica
            override def shardId(entityId: String): String                = entityId
            override def unwrapMessage(cmd: DeviceCommand): DeviceCommand = cmd
          }
        )
    )
     */

    val entity = Entity(DeviceDigitalTwin.entityKey)(DeviceDigitalTwin(_, replicator, role))
      .withMessageExtractor(DeviceMsgExtractor[DeviceDigitalTwin.DeviceCommand])
      /*new ShardingMessageExtractor[DeviceCommand, DeviceCommand] {
          override def entityId(cmd: DeviceCommand): String             = cmd.replica
          override def shardId(entityId: String): String                = entityId
          override def unwrapMessage(cmd: DeviceCommand): DeviceCommand = cmd
        }*/
      // default AllocationStrategy
      /** The default ShardAllocationStrategy will allocate shards on the least-loaded nodes. See
        * https://github.com/akka/akka/blob/master/akka-cluster-sharding/src/main/scala/akka/cluster/sharding/ShardCoordinator.scala#L71
        * and https://doc.akka.io/docs/akka/current/cluster-sharding.html#shard-location.
        */
      .withAllocationStrategy(
        /*new akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy(
          rebalanceThreshold = 1,
          maxSimultaneousRebalance = 3
        )*/
        // https://doc.akka.io/docs/akka/2.6/typed/cluster-sharding.html?_ga=2.114148035.592677992.1602252039-408157630.1602252039#shard-allocation
        akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy.leastShardAllocationStrategy(20, 0.5)
      )
      // .withAllocationStrategy(new akka.cluster.sharding.external.ExternalShardAllocationStrategy(system, DeviceDigitalTwin.entityKey.name))
      .withSettings(settings)
      .withEntityProps(akka.actor.typed.Props.empty.withDispatcherFromConfig("akka.shard-dispatcher"))

    // val shardAllocationClient = e.allocationStrategy.get.asInstanceOf[ExternalShardAllocationStrategy]
    // .clientFor(DeviceShadowEntity.entityKey.name)
    // .updateShardLocation("chat0", system.path.address)

    sharding.init(entity)
  }
}
