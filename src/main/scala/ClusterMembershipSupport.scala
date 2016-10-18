package main

import akka.cluster.Cluster
import akka.actor.{Actor, ActorLogging, Address}
import akka.cluster.ClusterEvent.{MemberEvent, MemberUp, UnreachableMember}

class ClusterMembershipSupport extends Actor with ActorLogging {

  override def preStart =
    Cluster(context.system).subscribe(self, classOf[MemberEvent], classOf[UnreachableMember])

  var cluster = Set[Address]()

  def receive = {
    case MemberUp(member) =>
      cluster = cluster + member.address
      log.info("memberUp = {}", member.address)
    case UnreachableMember(member) =>
      cluster = cluster - member.address
      log.debug("unreachableMember = {}", member.address)
    case 'Members =>
      log.info("Members {}", cluster.mkString(","))
      sender() ! "done"
    case event =>
      log.debug("event = {}", event.toString)
  }
}