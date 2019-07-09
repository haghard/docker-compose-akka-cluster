package demo

import demo.hashing.Rendezvous
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer, TimerScheduler}

import scala.concurrent.duration._
import akka.actor.typed.receptionist.Receptionist

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

  case class ReplicaEntity(h: Rendezvous[String], actors: Set[ActorRef[ShardRegionCmd]])

  case object ToKey

  /*val onTerminate: PartialFunction[(ActorContext[ClusterDomainEvent], Signal), Behavior[ClusterDomainEvent]] = {
    case (ctx, Terminated(actor)) ⇒
      ctx.log.error("★ ★ ★ {} Terminated", actor)
      Behaviors.stopped
  }*/

  private val replyTimeout = 2.seconds

  def apply(replicaName: String): Behavior[Command] =
    Behaviors.setup { ctx ⇒
      ctx.system.receptionist ! Receptionist.Subscribe(Membership.domainKey, ctx.messageAdapter[Receptionist.Listing] {
        case Membership.domainKey.Listing(replicas) ⇒
          MembershipChanged(replicas)
      })
      converge(replicaName)
    }

  def converge(shardName: String, buf: StashBuffer[Command] = StashBuffer[Command](1 << 5)): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case MembershipChanged(rs) ⇒
          if (rs.nonEmpty) {
            //on each cluster state change we rebuild the whole state
            reqClusterInfo(
              shardName,
              ctx.self,
              rs.head,
              rs.tail,
              Rendezvous[String],
              Map[String, ReplicaEntity](),
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
    shardName: String,
    self: ActorRef[Command],
    current: ActorRef[ShardRegionCmd],
    rest: Set[ActorRef[ShardRegionCmd]],
    sh: Rendezvous[String],
    m: Map[String, ReplicaEntity],
    stash: StashBuffer[Command]
  ): Behavior[Command] =
    Behaviors.withTimers { ctx ⇒
      ctx.startSingleTimer(ToKey, ReplyTimeout, replyTimeout)
      current.tell(IdentifyShard(self))
      awaitInfo(shardName, self, current, rest, sh, m, stash, ctx)
    }

  def awaitInfo(
    shardName: String,
    self: ActorRef[Command],
    current: ActorRef[ShardRegionCmd],
    rest: Set[ActorRef[ShardRegionCmd]],
    sh: Rendezvous[String],
    m: Map[String, ReplicaEntity],
    buf: StashBuffer[Command],
    timer: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case ShardInfo(rName, ref, pid) ⇒
          timer.cancel(ToKey)

          val um = if (m.contains(rName)) {
            val entity = m(rName)
            entity.h.add(pid)
            m.updated(rName, entity.copy(actors = entity.actors + ref))
          } else {
            val h = Rendezvous[String]
            h.add(pid)
            m.updated(rName, ReplicaEntity(h, Set(ref)))
          }

          sh.add(rName)
          if (rest.nonEmpty) reqClusterInfo(rName, self, rest.head, rest.tail, sh, um, buf)
          else {
            val info = um.keySet
              .map(k ⇒ s"[$k -> ${um(k).h.toString}]")
              .mkString(";")

            ctx.log.warning(
              "★ ★ ★ {} -> shards:{} replicas:{} {}",
              rName,
              sh.toString,
              info,
              buf.isEmpty
            )

            if (buf.isEmpty) active(sh, um, rName)
            else buf.unstashAll(ctx, converge(rName, buf))
          }
        case ReplyTimeout ⇒
          ctx.log.warning(s"No response within ${replyTimeout}. Retry {} ", current)
          //TODO: Limit number of retries because the target node might die in the middle of the process,
          // therefore we won't get the reply back. Moreover, we could step into infinite loop
          reqClusterInfo(shardName, self, current, rest, sh, m, buf)
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

  def active(
    sh: Rendezvous[String],
    m: Map[String, ReplicaEntity],
    shardName: String
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

          if (replicas.isEmpty) ctx.log.error(s"Critical error: Couldn't find actorRefs for ${shard}")
          else replicas.head.tell(PingDevice(id, replica))

          Behaviors.same
        case m @ MembershipChanged(rs) ⇒
          if (rs.nonEmpty) {
            ctx.self.tell(m)
            converge(shardName)
          } else Behaviors.same
        case ClusterStateRequest(r) ⇒
          val info = m.keySet.map(k ⇒ s"[$k -> ${m(k).h.toString}]").mkString(";")
          r.tell(ClusterStateResponse(s"\n ${sh.toString}\n $info"))
          Behaviors.same
        case other ⇒
          ctx.log.warning("Unexpected message in convergence: {}", other)
          Behaviors.same
      }
    }
}
