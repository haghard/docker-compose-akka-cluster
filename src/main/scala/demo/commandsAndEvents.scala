package demo

import akka.actor.typed.ActorRef
import demo.ReplicatedShardCoordinator.Command

sealed trait ShardRegionCmd
case class GetShardInfo(replyTo: ActorRef[Command]) extends ShardRegionCmd

sealed trait DeviceCommand extends ShardRegionCmd {
  def replica: String
}

case class WakeUpDevice(replica: String)         extends DeviceCommand
case class PingDevice(id: Long, replica: String) extends DeviceCommand
