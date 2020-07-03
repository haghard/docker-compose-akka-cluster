package demo

sealed trait ShardRegionCmd
final case class GetShardInfo(replyTo: akka.actor.typed.ActorRef[demo.RingMaster.Command]) extends ShardRegionCmd

sealed trait DeviceCommand extends ShardRegionCmd {
  def replica: String
}

final case class PingDevice(deviceId: Long, replica: String) extends DeviceCommand
