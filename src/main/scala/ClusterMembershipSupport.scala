package main
import akka.actor.{ActorLogging, Actor}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{MemberUp, ClusterDomainEvent}

class ClusterMembershipSupport extends Actor with ActorLogging {

  override def preStart =
    Cluster(context.system).subscribe(self, classOf[ClusterDomainEvent])

  def receive = {
    case MemberUp(member) =>
      log.info("memberUp={}", member.address)
    case event =>
      log.debug("event={}", event.toString)
  }
}
