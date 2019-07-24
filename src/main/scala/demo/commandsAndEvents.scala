package demo

import akka.actor.typed.ActorRef
import demo.ReplicatedShardCoordinator.Command

sealed trait ShardRegionCmd
case class IdentifyShard(replyTo: ActorRef[Command]) extends ShardRegionCmd

sealed trait DeviceCommand extends ShardRegionCmd {
  def id: Long
  def replica: String
}

case class PingDevice(id: Long, replica: String) extends DeviceCommand
