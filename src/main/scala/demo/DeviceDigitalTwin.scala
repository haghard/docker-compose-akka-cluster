package demo

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import demo.RingMaster.PingDeviceReply

/** akka://dc/system/sharding/devices/127.0.0.1-2551/127.0.0.1-2551
  * akka://dc/system/sharding/devices/127.0.0.2-2551/127.0.0.2-2551
  * akka://dc/system/sharding/devices/127.0.0.2-2551/127.0.0.2-2551
  *
  * Should start a replicator instance for the given replica name
  */
object DeviceDigitalTwin {

  sealed trait DeviceCommand {
    def replica: String
  }
  case class PingDevice(id: Long, replica: String, reply: ActorRef[PingDeviceReply]) extends DeviceCommand

  val entityKey: EntityTypeKey[DeviceCommand] =
    EntityTypeKey[DeviceCommand]("devices")

  def apply(
    entityCtx: EntityContext[DeviceCommand],
    replicator: ActorRef[ShardReplica.Protocol],
    replicaName: String
  ): Behavior[DeviceCommand] =
    Behaviors.setup { ctx ⇒
      ctx.log.warn("* * *  Wake up sharded entity for replicator {}: {} * * *", replicaName, entityCtx.entityId)
      active(replicator, replicaName, entityCtx)(ctx.log) //orElse idle(ctx.log)
    }

  private def onSignal(
    log: org.slf4j.Logger
  ): PartialFunction[(ActorContext[DeviceCommand], Signal), Behavior[DeviceCommand]] = { case (_, signal) ⇒
    log.warn(s"Passivate sharded entity for replicator ${signal.getClass.getName}")
    Behaviors.stopped[DeviceCommand]
  }

  private def active(
    replicator: ActorRef[ShardReplica.Protocol],
    replicaName: String,
    entityCtx: EntityContext[DeviceCommand]
  )(implicit log: org.slf4j.Logger): Behavior[DeviceCommand] =
    Behaviors
      .receiveMessage[DeviceDigitalTwin.DeviceCommand] { case DeviceDigitalTwin.PingDevice(deviceId, _, replyTo) ⇒
        //log.warn("* * * Increment deviceId:{} thought shard [{}:{}] * * *", deviceId, replicaName, entityCtx.entityId)
        replicator.tell(ShardReplica.PingDeviceReplicator(deviceId, replyTo))
        active(replicator, replicaName, entityCtx) //orElse idle(log)
      }
      .receiveSignal(onSignal(log))

  /*
  def apply(replicaName: String): Behavior[DeviceCommand] =
    Behaviors.setup { ctx ⇒
      ctx.log.warn("* * *  Wake up sharded entity for replicator {}  * * *", replicaName)
      await(ctx.log, replicaName) //orElse idle(ctx.log)
    }

  def await(log: org.slf4j.Logger, replicaName: String): Behavior[DeviceCommand] =
    Behaviors
      .receiveMessage[DeviceCommand] {
        case PingDevice(id, _) ⇒
          log.warn("* * *  ping replicator {}:{}  * * *", replicaName, id)
          //TODO: start replicator for the replicaName here !!!
          await(log, replicaName) //orElse idle(log)
      }
      .receiveSignal(onSignal(log))
   */

  //expect only akka.actor.typed.internal.PoisonPill
  /*def idle(log: org.slf4j.Logger): Behavior[DeviceCommand] =
    Behaviors.receiveSignal {
      case (_, signal) ⇒
        log.warn(s"* * *  Passivate sharded entity for replicator ${signal.getClass.getName}  * * *")
        Behaviors.stopped
    }*/
}
