package demo

import akka.cluster.sharding.ShardRegion
import akka.actor.{Actor, ActorLogging, Props}

object DeviceShadow {

  val entityId: ShardRegion.ExtractEntityId = {
    case cmd: DeviceCommand ⇒
      (cmd.replica, cmd)
  }

  val shardId: ShardRegion.ExtractShardId = {
    case cmd: DeviceCommand ⇒
      cmd.replica
  }

  def props(replicaName: String) =
    Props(new DeviceShadow(replicaName)).withDispatcher("akka.shard-dispatcher")
}

/**
  *
  * akka://dc@127.0.0.1:2551/system/sharding/devices/127.0.0.1-2551/127.0.0.1-2551] demo.DeviceShadow - * * *  Wake up device: alpha  * * *
  * akka://dc@127.0.0.2:2551/system/sharding/devices/127.0.0.2-2551/127.0.0.2-2551] demo.DeviceShadow - * * *  Wake up device: alpha  * * *
  * akka://dc@127.0.0.3:2551/system/sharding/devices/127.0.0.3-2551/127.0.0.3-2551] demo.DeviceShadow - * * *  Wake up device: alpha  * * *
  */
class DeviceShadow(replicaName: String) extends Actor with ActorLogging {

  /*override def preStart(): Unit =
    log.warning("* * *   preStart: {} * * * ", replicaName)

  override def postStop(): Unit =
    log.warning("* * *   postStop: {} * * * ", replicaName)
   */

  def active: Receive = {
    case PingDevice(id, _) ⇒
      log.info("ping device {}", id)
    case InitDevice(_) ⇒
    //Ignore rerun wake up device because cluster membership has changed
  }

  def await: Receive = {
    case InitDevice(_) ⇒
      log.warning("* * *  Wake up device: {}  * * *", replicaName)
      //TODO: start replicator for the replicaName here !!!
      context.become(active)
    case cmd: PingDevice ⇒
      log.warning("* * *  Wake up device by ping: {}  * * *", replicaName)
      //TODO: start replicator for the replicaName here !!!
      context.become(active)
    case other ⇒
      log.warning("* * *  Ignore: {} * * *", other)
  }

  override def receive: Receive = await
}
