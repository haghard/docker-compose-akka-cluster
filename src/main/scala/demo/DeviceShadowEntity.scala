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

  //expect only akka.actor.typed.internal.PoisonPill
  private def idle(log: akka.actor.typed.Logger): Behavior[DeviceCommand] = Behaviors.receiveSignal {
    case (_, signal) ⇒
      log.info(s"Signal ${signal.getClass.getName}")
      Behaviors.stopped
  }

  def apply(entityId: String, replicaName: String): Behavior[DeviceCommand] =
    Behaviors.setup { ctx ⇒
      ctx.log.info("* * *  Start up entity * * *")
      await(ctx.log, replicaName, entityId) orElse idle(ctx.log)
    }

  private def active(log: akka.actor.typed.Logger): Behavior[DeviceCommand] =
    Behaviors.receiveMessage {
      case PingDevice(id, _) ⇒
        log.info("ping entity {}", id)
        Behaviors.same
      case InitDevice(_) ⇒
        //Ignore rerun wake up device because cluster membership has changed
        log.info("* * *  Wake up entity * * *")
        Behaviors.same
    }

  private def await(log: akka.actor.typed.Logger, replicaName: String, entityId: String): Behavior[DeviceCommand] =
    Behaviors.receiveMessage {
      case InitDevice(_) ⇒
        log.warning("* * *  Wake up entity: {}  * * *", replicaName)
        //TODO: start replicator for the replicaName here !!!
        active(log) orElse idle(log)
      case PingDevice(id, _) ⇒
        log.warning("* * *  Wake up entity by ping: {}:{}  * * *", replicaName, id)
        //TODO: start replicator for the replicaName here !!!
        active(log) orElse idle(log)
    }
}
