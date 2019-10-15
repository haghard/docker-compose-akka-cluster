package demo

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey

/**
  * akka://dc/system/sharding/devices/127.0.0.1-2551/127.0.0.1-2551
  * akka://dc/system/sharding/devices/127.0.0.2-2551/127.0.0.2-2551
  * akka://dc/system/sharding/devices/127.0.0.2-2551/127.0.0.2-2551
  *
  *
  * Should start a replicator instance for the given replica name
  */
object DeviceShadowEntity {

  val entityKey: EntityTypeKey[DeviceCommand] =
    EntityTypeKey[DeviceCommand]("devices")

  //expect only akka.actor.typed.internal.PoisonPill
  private def idle(log: akka.actor.typed.Logger): Behavior[DeviceCommand] =
    Behaviors.receiveSignal {
      case (_, signal) ⇒
        log.warning(s"* * *  Passivate sharded entity for replicator ${signal.getClass.getName}  * * *")
        Behaviors.stopped
    }

  def apply(entityId: String, replicaName: String): Behavior[DeviceCommand] =
    Behaviors.setup { ctx ⇒
      ctx.log.warning("* * *  Wake up sharded entity for replicator {}  * * *", replicaName)
      await(ctx.log, replicaName, entityId) orElse idle(ctx.log)
    }

  private def await(log: akka.actor.typed.Logger, replicaName: String, entityId: String): Behavior[DeviceCommand] =
    Behaviors.receiveMessage {
      case PingDevice(id, _) ⇒
        log.warning("* * *  ping replicator {}:{}  * * *", replicaName, id)
        //TODO: start replicator for the replicaName here !!!
        await(log, replicaName, entityId) orElse idle(log)
    }
}
