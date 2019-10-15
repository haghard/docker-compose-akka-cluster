package demo

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey

/**
  * akka://dc/system/sharding/device/127.0.0.1-2551/127.0.0.1-2551
  * akka://dc/system/sharding/device/127.0.0.2-2551/127.0.0.2-2551
  * akka://dc/system/sharding/device/127.0.0.2-2551/127.0.0.2-2551
  */
object DeviceShadowEntity {

  val entityKey: EntityTypeKey[DeviceCommand] =
    EntityTypeKey[DeviceCommand]("device")

  def apply(entityId: String, replicaName: String): Behavior[DeviceCommand] =
    Behaviors.setup { ctx ⇒
      await(ctx.log, replicaName, entityId)
    }

  def active(log: akka.actor.typed.Logger): Behavior[DeviceCommand] =
    Behaviors.receiveMessage {
      case PingDevice(id, _) ⇒
        log.info("ping entity {}", id)
        Behaviors.same
      case InitDevice(_) ⇒
        log.info("* * *  Wake up entity * * *")
        Behaviors.same
      //Ignore rerun wake up device because cluster membership has changed
    }

  def await(log: akka.actor.typed.Logger, replicaName: String, entityId: String): Behavior[DeviceCommand] =
    Behaviors.receiveMessage {
      case InitDevice(_) ⇒
        log.warning("* * *  Wake up entity: {}  * * *", replicaName)
        //TODO: start replicator for the replicaName here !!!
        active(log)
      case PingDevice(id, _) ⇒
        log.warning("* * *  Wake up entity by ping: {}:{}  * * *", replicaName, id)
        //TODO: start replicator for the replicaName here !!!
        active(log)
    }
}
