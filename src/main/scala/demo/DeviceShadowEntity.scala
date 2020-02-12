package demo

import akka.actor.typed.{Behavior, Signal}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
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

  def apply(entityId: String, replicaName: String): Behavior[DeviceCommand] =
    Behaviors.setup { ctx ⇒
      ctx.log.warn("* * *  Wake up sharded entity for replicator {}  * * *", replicaName)
      await(ctx.log, replicaName, entityId) //orElse idle(ctx.log)
    }

  //expect only akka.actor.typed.internal.PoisonPill
  /*def idle(log: org.slf4j.Logger): Behavior[DeviceCommand] =
    Behaviors.receiveSignal {
      case (_, signal) ⇒
        log.warn(s"* * *  Passivate sharded entity for replicator ${signal.getClass.getName}  * * *")
        Behaviors.stopped
    }*/

  def onSignal(log: org.slf4j.Logger): PartialFunction[(ActorContext[DeviceCommand], Signal), Behavior[DeviceCommand]] = {
    case (_, signal) ⇒
      log.warn(s"* * *  Passivate sharded entity for replicator ${signal.getClass.getName}  * * *")
      Behaviors.stopped[DeviceCommand]
  }

  private def await(log: org.slf4j.Logger, replicaName: String, entityId: String): Behavior[DeviceCommand] =
    Behaviors
      .receiveMessage[DeviceCommand] {
        case PingDevice(id, _) ⇒
          log.warn("* * *  ping replicator {}:{}  * * *", replicaName, id)
          //TODO: start replicator for the replicaName here !!!
          await(log, replicaName, entityId) //orElse idle(log)
      }
      .receiveSignal(onSignal(log))
}
