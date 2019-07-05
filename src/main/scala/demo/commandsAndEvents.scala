package demo

import akka.cluster.sharding.ShardRegion.ShardRegionQuery

sealed trait DeviceCommand {
  def id: Int
}

case class PingDevice(id: Int) extends DeviceCommand
