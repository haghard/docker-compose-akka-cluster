package demo

import akka.actor.Address
import akka.actor.typed.{ActorRef, Behavior, Signal, Terminated}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.cluster.typed.{Cluster, Subscribe}

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

object Membership {

  case object ShowClusterState                                           extends ClusterDomainEvent
  case class GetClusterState(replyTo: ActorRef[Membership.ClusterState]) extends ClusterDomainEvent
  case class ClusterState(line: String)

  private val onTerminate: PartialFunction[(ActorContext[ClusterDomainEvent], Signal), Behavior[ClusterDomainEvent]] = {
    case (ctx, Terminated(actor)) ⇒
      ctx.log.error("★ ★ ★ {} Terminated", actor)
      //shutdown.run(Bootstrap.DBFailure)
      Behaviors.stopped
  }

  def apply(state: CurrentClusterState): Behavior[ClusterDomainEvent] =
    Behaviors.setup { ctx ⇒
      val c = Cluster(ctx.system)
      Behaviors.withTimers[ClusterDomainEvent] { t ⇒
        c.subscriptions ! Subscribe(ctx.self, classOf[ClusterDomainEvent])
        t.startPeriodicTimer(ShowClusterState, ShowClusterState, 15000.millis)

        val av = state.members.filter(_.status == MemberStatus.Up).map(_.address)
        ctx.log.warning("★ ★ ★ Cluster State:{} ★ ★ ★", av.mkString(","))
        convergence(av, SortedSet[Address]())
      }
    }

  def convergence(
    available: SortedSet[Address],
    removed: SortedSet[Address]
  ): Behavior[ClusterDomainEvent] =
    Behaviors
      .receivePartial[ClusterDomainEvent] {
        case (ctx, msg) ⇒
          msg match {
            case MemberUp(member) ⇒
              val av  = available + member.address
              val unv = removed - member.address
              ctx.log.warning("★ ★ ★  MemberUp  :{}", av.mkString(","))
              convergence(av, unv)
            case UnreachableMember(member) ⇒
              ctx.system.log.warning("★ ★ ★ Unreachable = {}", member.address)
              awaitForConvergence(available, removed)
            case MemberExited(member) ⇒ //graceful exit
              val rm = removed + member.address
              val am = available - member.address
              ctx.log.warning("★ ★ ★ {} exit gracefully", member.address)
              convergence(am, rm)
            case ShowClusterState ⇒
              ctx.log.info("★ ★ ★ [{}] - [{}]", available.mkString(","), removed.mkString(","))
              Behaviors.same
            case GetClusterState(replyTo) ⇒
              replyTo.tell(ClusterState(available.mkString(",")))
              Behaviors.same
            case other ⇒
              //ReachabilityChanged(reachability)
              //ctx.log.warning("★ ★ ★ unexpected in convergence: {}", other)
              Behaviors.same
          }
      }
      .receiveSignal(onTerminate)

  //leader should take an action
  def awaitForConvergence(
    available: SortedSet[Address],
    removed: SortedSet[Address]
  ): Behavior[ClusterDomainEvent] =
    Behaviors
      .receivePartial[ClusterDomainEvent] {
        case (ctx, msg) ⇒
          msg match {
            case ReachableMember(member) ⇒
              ctx.log.warning("★ ★ ★  Reachable again = {}", member.address)
              convergence(available, removed)
            case UnreachableMember(member) ⇒
              ctx.log.warning("★ ★ ★  Unreachable = {}", member.address)
              awaitForConvergence(available, removed)
            case MemberRemoved(member, _) ⇒
              val rm = removed + member.address
              val am = available - member.address
              ctx.log.warning("★ ★ ★ {} was taken downed after being unreachable.", member.address)
              convergence(am, rm)
            case ShowClusterState ⇒
              ctx.log
                .info("[{}] - [{}]", available.mkString(","), removed.mkString(","))
              Behaviors.same
            case GetClusterState(replyTo) ⇒
              replyTo.tell(ClusterState(available.mkString(",")))
              Behaviors.same
            case _ ⇒
              Behaviors.same
          }
      }
      .receiveSignal(onTerminate)
}
