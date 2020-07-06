package demo

import akka.actor.typed.ActorRef
import demo.RingMaster.PingDeviceReply

sealed trait ShardRegionCmd
final case class GetShardInfo(replyTo: akka.actor.typed.ActorRef[demo.RingMaster.Command]) extends ShardRegionCmd

sealed trait DeviceCommand extends ShardRegionCmd {
  def replica: String
}

case class PingDevice(deviceId: Long, replica: String, replyTo: ActorRef[PingDeviceReply]) extends DeviceCommand
