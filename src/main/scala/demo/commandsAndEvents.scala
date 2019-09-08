package demo

import akka.actor.typed.ActorRef
import demo.RingMaster.Command

sealed trait ShardRegionCmd
case class GetShardInfo(replyTo: ActorRef[Command]) extends ShardRegionCmd

sealed trait DeviceCommand extends ShardRegionCmd {
  def replica: String
}

case class InitDevice(replica: String)                 extends DeviceCommand
case class PingDevice(deviceId: Long, replica: String) extends DeviceCommand
