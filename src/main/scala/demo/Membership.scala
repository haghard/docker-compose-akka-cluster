package demo

import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer, TimerScheduler}
import demo.hashing.Rendezvous

import scala.concurrent.duration._

object Membership {

  val domainKey = ServiceKey[ShardRegionCmd]("domain")

  case class ClusterStateResponse(state: String)

  sealed trait Command

  case object SelfUpDb                                                    extends Command
  case class ClusterStateRequest(replyTo: ActorRef[ClusterStateResponse]) extends Command

  case class MembershipChanged(replicas: Set[ActorRef[ShardRegionCmd]]) extends Command

  case class ReplicaNameReply(shard: String, ref: ActorRef[ShardRegionCmd], hostId: String) extends Command

  case class RolesInfo(m: Map[String, Set[ActorRef[ShardRegionCmd]]]) extends Command
  case object ReplyTimeout                                            extends Command

  case class Ping(id: Int) extends Command


  case class Entity(h: Rendezvous[String], as: Set[ActorRef[ShardRegionCmd]])


  /*val onTerminate: PartialFunction[(ActorContext[ClusterDomainEvent], Signal), Behavior[ClusterDomainEvent]] = {
    case (ctx, Terminated(actor)) ⇒
      ctx.log.error("★ ★ ★ {} Terminated", actor)
      Behaviors.stopped
  }*/


  def apply(replicaName: String): Behavior[Command] =
    Behaviors.setup { ctx ⇒
      ctx.system.receptionist ! akka.actor.typed.receptionist.Receptionist.Subscribe(
        Membership.domainKey,
        ctx.messageAdapter[akka.actor.typed.receptionist.Receptionist.Listing] {
          case Membership.domainKey.Listing(replicas) ⇒
            MembershipChanged(replicas)
        }
      )
      converge(replicaName)
    }

  def converge(rn: String, buf: StashBuffer[Command] = StashBuffer[Command](1 << 5)): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case MembershipChanged(rs) ⇒
          if (rs.nonEmpty) {
            //on each cluster state change we rebuild the whole state
            val map          = Map[String, Set[ActorRef[ShardRegionCmd]]]().withDefaultValue(Set.empty)
            val shardHash    = Rendezvous[String]
            val replicasHash = Rendezvous[String]
            reqClusterInfo(
              rn,
              ctx.self,
              rs.head,
              rs.tail,
              shardHash,
              replicasHash,
              map,
              buf
            )
          } else Behaviors.same

        case ReplyTimeout ⇒
          Behaviors.same
        case cmd: Ping ⇒
          //TODO: respond fast, because we're not ready yet
          ctx.log.warning("{} respond fast, because we're not ready yet", cmd)
          Behaviors.same
        case other ⇒
          ctx.log.warning("other: {}", other)
          Behaviors.same
      }
    }

  def reqClusterInfo(
    rn: String,
    self: ActorRef[Command],
    current: ActorRef[ShardRegionCmd],
    rest: Set[ActorRef[ShardRegionCmd]],
    sh: Rendezvous[String],
    rh: Rendezvous[String],
    m: Map[String, Set[ActorRef[ShardRegionCmd]]],
    stash: StashBuffer[Command]
  ): Behavior[Command] =
    Behaviors.withTimers { ctx ⇒
      ctx.startSingleTimer('TO, ReplyTimeout, 2.seconds)
      current.tell(IdentifyShard(self))
      awaitInfo(rn, self, current, rest, sh, rh, m, stash, ctx)
    }

  def awaitInfo(
    rn: String,
    self: ActorRef[Command],
    current: ActorRef[ShardRegionCmd],
    rest: Set[ActorRef[ShardRegionCmd]],
    sh: Rendezvous[String],
    rh: Rendezvous[String],
    m: Map[String, Set[ActorRef[ShardRegionCmd]]],
    buf: StashBuffer[Command],
    timer: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case ReplicaNameReply(rName, ref, pid) ⇒
          timer.cancel('TO)

          val um = m.updated(rName, m(rName) + ref)

          if (rn == rName) rh.add(pid)

          sh.add(rName)
          if (rest.nonEmpty) reqClusterInfo(rn, self, rest.head, rest.tail, sh, rh, um, buf)
          else {
            ctx.log.warning("★ ★ ★ {} -> shards:{} replicas:{} {}", rn, sh.toString, rh.toString, buf.isEmpty)
            if (buf.isEmpty) convergence(sh, rh, um, rn)
            else buf.unstashAll(ctx, converge(rn, buf))
          }
        case ReplyTimeout ⇒
          ctx.log.warning("retry {} ", current)
          //TODO: limit retry with some number because requested node might die,
          // therefore we won't get info back
          reqClusterInfo(rn, self, current, rest, sh, rh, m, buf)
        case m @ MembershipChanged(rs) if rs.nonEmpty ⇒
          buf.stash(m)
          Behaviors.same
        case cmd: Ping ⇒
          //TODO: respond false, because we're not ready yet
          ctx.log.warning("{} respond fast, because we're not ready yet", cmd)
          Behaviors.same
        case cmd: ClusterStateRequest ⇒
          cmd.replyTo.tell(ClusterStateResponse("Not ready. Try later"))
          Behaviors.same
        case other ⇒
          ctx.log.warning("Unexpected message in awaitInfo: {}", other)
          Behaviors.stopped
      }
    }

  def convergence(
    sh: Rendezvous[String],
    rh: Rendezvous[String],
    m: Map[String, Set[ActorRef[ShardRegionCmd]]],
    rn: String
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case Ping(id) ⇒
          //pick shard and replica
          val shard   = sh.memberFor(id.toString, 1).head
          val replica = rh.memberFor(id.toString, 1).head
          ctx.log.warning("{}: {} -> {}", id, shard, replica)

          val replicas = m(shard)
          if (replicas.isEmpty) ctx.log.error(s"Critical error: Couldn't find actorRefs for ${shard}")
          else replicas.head.tell(PingDevice(id, replica))

          Behaviors.same
        case m @ MembershipChanged(rs)  ⇒
          if (rs.nonEmpty) {
            ctx.self.tell(m)
            converge(rn)
          } else Behaviors.same
        case ClusterStateRequest(r) ⇒
          r.tell(ClusterStateResponse("\n" + sh.toString + "\n" + rh.toString))
          Behaviors.same
        case other ⇒
          ctx.log.warning("Unexpected message in convergence: {}", other)
          Behaviors.same
      }
    }
}
