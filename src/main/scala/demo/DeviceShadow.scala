package demo

import akka.cluster.sharding.ShardRegion.Passivate
import akka.actor.{Actor, ActorLogging, Props, ReceiveTimeout}

object DeviceShadow {
  //case object PassivationCnfm

  def props(replicaName: String) =
    Props(new DeviceShadow(replicaName)).withDispatcher("akka.shard-dispatcher")
}

class DeviceShadow(replicaName: String) extends Actor with ActorLogging {

  //import scala.concurrent.duration._
  //context.setReceiveTimeout(30.seconds)

  override def preStart(): Unit =
    log.warning("* * *   preStart  * * * ")

  override def postStop(): Unit =
    log.warning("* * *   postStop  * * * ")

  override val receive: Receive = {
    case PingDevice(id) ⇒
      log.info("ping for {}", id)
  }

  /*def withPassivation(r: Receive): Receive =
    r.orElse(
      {
        case ReceiveTimeout ⇒
          log.warning("* * *  passivate * * *")
          context.parent ! Passivate(DeviceShadow.PassivationCnfm)
        case DeviceShadow.PassivationCnfm ⇒
          log.warning("* * *  passivate cmf * * *")
      }
    )*/

}
