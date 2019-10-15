package demo

sealed trait ShardRegionCmd
case class GetShardInfo(replyTo: akka.actor.typed.ActorRef[demo.RingMaster.Command]) extends ShardRegionCmd

sealed trait DeviceCommand extends ShardRegionCmd {
  def replica: String
}

//case class InitDevice(replica: String)                 extends DeviceCommand
case class PingDevice(deviceId: Long, replica: String) extends DeviceCommand
