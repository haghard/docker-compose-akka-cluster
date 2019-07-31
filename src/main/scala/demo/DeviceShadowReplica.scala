package demo

import akka.cluster.sharding.ShardRegion
import akka.actor.{Actor, ActorLogging, Props}

object DeviceShadowReplica {

  val entityId: ShardRegion.ExtractEntityId = {
    case cmd: DeviceCommand ⇒
      (cmd.replica, cmd)
  }

  val shardId: ShardRegion.ExtractShardId = {
    case cmd: DeviceCommand ⇒
      cmd.replica
  }

  def props(replicaName: String) =
    Props(new DeviceShadowReplica(replicaName)).withDispatcher("akka.shard-dispatcher")
}

class DeviceShadowReplica(replicaName: String) extends Actor with ActorLogging {

  /*override def preStart(): Unit =
    log.warning("* * *   preStart: {} * * * ", replicaName)

  override def postStop(): Unit =
    log.warning("* * *   postStop: {} * * * ", replicaName)
  */

  def active: Receive = {
    case PingDevice(id, _) ⇒
      log.info("ping device {}", id)
    case WakeUpDevice(_) ⇒
      //Ignore rerun wake up device because cluster membership has changed
  }

  def await: Receive = {
    case WakeUpDevice(_) ⇒
      log.warning("* * *  Wake up device: {}  * * *", replicaName)
      //TODO: start replicator for replicaName here !!!
      context.become(active)
    case other ⇒
    //IGNORE

  }

  override def receive: Receive = await
}
