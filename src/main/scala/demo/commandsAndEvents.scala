package demo

import akka.actor.typed.ActorRef
import demo.Membership.Command

sealed trait ShardRegionCmd
case class IdentifyShard(replyTo: ActorRef[Command]) extends ShardRegionCmd

sealed trait DeviceCommand extends ShardRegionCmd {
  def id: Int
  def replica: String
}

case class PingDevice(id: Int, replica: String) extends DeviceCommand
