package demo

import java.nio.ByteBuffer

import akka.actor.{ActorPath, Address}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.{ActorRef, Behavior, Signal, Terminated}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardRegion
import akka.cluster.typed.{Cluster, SelfUp, Subscribe, Unsubscribe}
import demo.hashing.{CassandraHash, Rendezvous}

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

object Membership {

  val domainKey = ServiceKey[DataProtocol]("domain")

  case class ClusterStateResponse(line: String)

  sealed trait Ops

  case object SelfUpDb extends Ops

  case class MembershipChanged(replicas: Set[ActorRef[DataProtocol]]) extends Ops

  case class ReplicaNameReply(role: String, ar: ActorRef[DataProtocol]) extends Ops

  case class RolesInfo(m: Map[String, Set[ActorRef[DataProtocol]]]) extends Ops
  case object ReplyTimeout                                          extends Ops

  /*val onTerminate: PartialFunction[(ActorContext[ClusterDomainEvent], Signal), Behavior[ClusterDomainEvent]] = {
    case (ctx, Terminated(actor)) ⇒
      ctx.log.error("★ ★ ★ {} Terminated", actor)
      Behaviors.stopped
  }*/

  def apply(): Behavior[Ops] =
    Behaviors.setup { ctx ⇒
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist.Subscribe(
        Membership.domainKey,
        ctx.messageAdapter[akka.actor.typed.receptionist.Receptionist.Listing] {
          case Membership.domainKey.Listing(replicas) ⇒
            MembershipChanged(replicas)
        }
      )

      /*Map[String, Set[ActorRef[ClusterOps]]]().withDefaultValue(Set.empty),*/
      run(StashBuffer[Ops](1 << 5))
    }

  def run(
    buf: StashBuffer[Ops]
  ): Behavior[Ops] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case MembershipChanged(rs) if rs.nonEmpty ⇒
          val hash = Rendezvous[String]
          val map = Map[String, Set[ActorRef[DataProtocol]]]().withDefaultValue(Set.empty)
          collectRoles(ctx.self, rs.head, rs.tail,
            hash,
            //map,
            buf
          )
        case _ ⇒
          Behaviors.same
      }
    }

  def collectRoles(
    self: ActorRef[Ops],
    current: ActorRef[DataProtocol],
    rest: Set[ActorRef[DataProtocol]],
    m: Map[String, Set[ActorRef[DataProtocol]]],
    stash: StashBuffer[Ops]
  ): Behavior[Ops] =
    Behaviors.withTimers { ctx ⇒
      ctx.startSingleTimer('TO, ReplyTimeout, 1.seconds)
      current.tell(GetReplicaName(self))
      awaitResp(self, current, rest, m, stash)
    }

  def awaitResp(
    self: ActorRef[Ops],
    current: ActorRef[DataProtocol],
    rest: Set[ActorRef[DataProtocol]],
    m: Map[String, Set[ActorRef[DataProtocol]]],
    buf: StashBuffer[Ops]
  ): Behavior[Ops] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case ReplicaNameReply(role, ar) ⇒
          val um = m.updated(role, m(role) + ar)
          if (rest.nonEmpty) collectRoles(self, rest.head, rest.tail, um, buf)
          else {
            ctx.log.warning("---- end: {}", um.mkString(","))
            buf.unstashAll(ctx, run( /*um,*/ buf))
          }
        case ReplyTimeout ⇒
          //retry
          ctx.log.warning("retry {} ", current)
          collectRoles(self, current, rest, m, buf)
        case m: MembershipChanged if m.replicas.nonEmpty ⇒
          buf.stash(m)
          Behaviors.same
        case _ ⇒
          Behaviors.stopped
      }
    }

}
