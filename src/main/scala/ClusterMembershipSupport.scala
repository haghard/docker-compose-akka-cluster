package main

import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, MemberStatus}
import akka.actor.{Props, Actor, ActorLogging, Address}

object ClusterMembershipSupport {
  def props(cluster: Cluster) = Props(new ClusterMembershipSupport(cluster))
}

class ClusterMembershipSupport(cluster: Cluster) extends Actor with ActorLogging {

  override def preStart = {
    cluster.subscribe(self, classOf[ClusterDomainEvent])
  }

  private def evolve(clusterMembers: Set[Address]): Receive = {
    case MemberUp(member) =>
      log.info("MemberUp = {}", member.address)
      context become (evolve(clusterMembers + member.address))

    case MemberExited(member) =>
      log.info("MemberExited = {}", member.address)

    case ReachableMember(member) =>
      log.debug("ReachableMember = {}", member.address)

    case UnreachableMember(member) =>
      log.debug("UnreachableMember = {}", member.address)

    case MemberRemoved(member, prev) =>
      if (prev == MemberStatus.Exiting) log.debug("{} gracefully exited", member.address)
      else log.debug("{} downed after Unreachable", member.address)
      context become evolve(clusterMembers - member.address)

    case state: CurrentClusterState =>
      log.debug("Cluster state = {}", state)

    case 'Members =>
      sender() ! clusterMembers.mkString(",")
  }

  override def receive = evolve(Set[Address]() + cluster.selfAddress)
}