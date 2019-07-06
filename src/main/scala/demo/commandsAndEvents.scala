package demo

import akka.actor.typed.ActorRef
import demo.Membership.Ops

sealed trait DataProtocol
case class GetReplicaName(replyTo: ActorRef[Ops]) extends DataProtocol

sealed trait DeviceCommand extends DataProtocol {
  def id: Int
}

case class PingDevice(id: Int) extends DeviceCommand
