package main

import akka.cluster.Cluster
import akka.actor.{Actor, ActorLogging, Address}
import akka.cluster.ClusterEvent.{MemberEvent, MemberUp, UnreachableMember}

class ClusterMembershipSupport extends Actor with ActorLogging {

  val cluster = Cluster(context.system)

  var clusterMembers = Set[Address]()

  override def preStart = {
    cluster.subscribe(self, classOf[MemberEvent], classOf[UnreachableMember])
    clusterMembers = clusterMembers + cluster.selfAddress
  }

  override def receive = {
    case MemberUp(member) =>
      clusterMembers = clusterMembers + member.address
      log.info("memberUp = {}", member.address)
    case UnreachableMember(member) =>
      clusterMembers = clusterMembers - member.address
      log.debug("unreachableMember = {}", member.address)
    case 'Members =>
      log.info("Members {}", clusterMembers.mkString(","))
      sender() ! "done"
    case event =>
      log.debug("event = {}", event.toString)
  }
}