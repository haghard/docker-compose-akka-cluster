package demo

import akka.actor.{Actor, ActorLogging, Props}

object DeviceShadow {

  def props(replicaName: String) =
    Props(new DeviceShadow(replicaName)).withDispatcher("akka.shard-dispatcher")
}

class DeviceShadow(replicaName: String) extends Actor with ActorLogging {

  override def receive: Receive = {
    case PingDevice(id) ⇒
      log.info("ping for {}", id)
  }
}
