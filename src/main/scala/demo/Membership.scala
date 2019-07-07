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

  case class ShardInfo(shard: String, ref: ActorRef[ShardRegionCmd], hostId: String) extends Command

  case class RolesInfo(m: Map[String, Set[ActorRef[ShardRegionCmd]]]) extends Command
  case object ReplyTimeout                                            extends Command

  case class Ping(id: Int) extends Command

  case class Entity(h: Rendezvous[String], actors: Set[ActorRef[ShardRegionCmd]])

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

  def converge(rn: String, buf: StashBuffer[Command] = StashBuffer[Command](1 << 4)): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case MembershipChanged(rs) ⇒
          if (rs.nonEmpty) {
            //on each cluster state change we rebuild the whole state
            reqClusterInfo(
              rn,
              ctx.self,
              rs.head,
              rs.tail,
              Rendezvous[String],
              Map[String, Entity](),
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
    m: Map[String, Entity],
    stash: StashBuffer[Command]
  ): Behavior[Command] =
    Behaviors.withTimers { ctx ⇒
      ctx.startSingleTimer('TO, ReplyTimeout, 2.seconds)
      current.tell(IdentifyShard(self))
      awaitInfo(rn, self, current, rest, sh, m, stash, ctx)
    }

  def awaitInfo(
    rn: String,
    self: ActorRef[Command],
    current: ActorRef[ShardRegionCmd],
    rest: Set[ActorRef[ShardRegionCmd]],
    sh: Rendezvous[String],
    m: Map[String, Entity],
    buf: StashBuffer[Command],
    timer: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case ShardInfo(rName, ref, pid) ⇒
          timer.cancel('TO)

          val maybeEntity = m.get(rName)
          val um = if (maybeEntity.isEmpty) {
            val h = Rendezvous[String]
            h.add(pid)
            m.updated(rName, Entity(h, Set(ref)))
          } else {
            val entity  = maybeEntity.get
            val updated = entity.actors + ref
            entity.h.add(pid)
            m.updated(rName, entity.copy(actors = updated))
          }

          sh.add(rName)
          if (rest.nonEmpty) reqClusterInfo(rn, self, rest.head, rest.tail, sh, um, buf)
          else {
            val info = um.keySet
              .map(k ⇒ s"[$k -> ${um(k).h.toString}]")
              .mkString(";")

            ctx.log.warning(
              "★ ★ ★ {} -> shards:{} replicas:{} {}",
              rn,
              sh.toString,
              info,
              buf.isEmpty
            )

            if (buf.isEmpty) convergence(sh, um, rn)
            else buf.unstashAll(ctx, converge(rn, buf))
          }
        case ReplyTimeout ⇒
          ctx.log.warning("retry {} ", current)
          //TODO: limit retry with some number because requested node might die,
          // therefore we won't get info back
          reqClusterInfo(rn, self, current, rest, sh, m, buf)
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
    m: Map[String, Entity],
    rn: String
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case Ping(id) ⇒
          //pick shard and replica
          val shard    = sh.memberFor(id.toString, 1).head
          val entity   = m(shard)
          val replica  = entity.h.memberFor(id.toString, 1).head
          val replicas = entity.actors

          ctx.log.warning("{}: shard:{} - replica:{}", id, shard, replica)

          //val replicas = m(shard)
          if (replicas.isEmpty) ctx.log.error(s"Critical error: Couldn't find actorRefs for ${shard}")
          else replicas.head.tell(PingDevice(id, replica))

          Behaviors.same
        case m @ MembershipChanged(rs) ⇒
          if (rs.nonEmpty) {
            ctx.self.tell(m)
            converge(rn)
          } else Behaviors.same
        case ClusterStateRequest(r) ⇒
          val info = m.keySet.map(k ⇒ s"[$k -> ${m(k).h.toString}]").mkString(";")
          r.tell(
            ClusterStateResponse("\n" + sh.toString + "\n" + info)
          )
          Behaviors.same
        case other ⇒
          ctx.log.warning("Unexpected message in convergence: {}", other)
          Behaviors.same
      }
    }
}
