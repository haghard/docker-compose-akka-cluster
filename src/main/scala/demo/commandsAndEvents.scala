package demo

import akka.actor.typed.ActorRef

sealed trait ShardRegionCmd
case class GetShardInfo(replyTo: ActorRef[demo.RingMaster.Command]) extends ShardRegionCmd

sealed trait DeviceCommand extends ShardRegionCmd {
  def replica: String
}

case class InitDevice(replica: String)                 extends DeviceCommand
case class PingDevice(deviceId: Long, replica: String) extends DeviceCommand
