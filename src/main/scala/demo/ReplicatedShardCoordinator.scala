package demo

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer, TimerScheduler}

import scala.concurrent.duration._
import akka.actor.typed.receptionist.Receptionist
import demo.hashing.{CropCircle, HashRing}

import scala.collection.immutable.SortedMultiDict

object ReplicatedShardCoordinator {

  val domainKey = ServiceKey[ShardRegionCmd]("domain")

  case class ClusterStateResponse(state: String)

  sealed trait Command

  case object SelfUpDb                                                    extends Command
  case class ClusterStateRequest(replyTo: ActorRef[ClusterStateResponse]) extends Command

  case class MembershipChanged(replicas: Set[ActorRef[ShardRegionCmd]]) extends Command

  case class ShardInfo(shard: String, ref: ActorRef[ShardRegionCmd], hostId: String) extends Command

  case class RolesInfo(m: Map[String, Set[ActorRef[ShardRegionCmd]]]) extends Command
  case object ReplyTimeout                                            extends Command

  case class GetCropCircle(replyTo: ActorRef[CropCircleView]) extends Command
  case class CropCircleView(json: String)                     extends Command

  case class Ping(id: Long) extends Command

  case object ToKey

  case class Replica(a: ActorRef[ShardRegionCmd], memberId: String)

  /*val onTerminate: PartialFunction[(ActorContext[ClusterDomainEvent], Signal), Behavior[ClusterDomainEvent]] = {
    case (ctx, Terminated(actor)) ⇒
      ctx.log.error("★ ★ ★ {} Terminated", actor)
      Behaviors.stopped
  }*/

  private val replyTimeout = 2.seconds

  def apply(replicaName: String): Behavior[Command] =
    Behaviors.setup { ctx ⇒
      ctx.system.receptionist ! Receptionist.Subscribe(
        ReplicatedShardCoordinator.domainKey,
        ctx.messageAdapter[Receptionist.Listing] {
          case ReplicatedShardCoordinator.domainKey.Listing(replicas) ⇒
            MembershipChanged(replicas)
        }
      )
      converge(replicaName)
    }

  def converge(shardName: String, buf: StashBuffer[Command] = StashBuffer[Command](1 << 6)): Behavior[Command] =
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
              None,                                   //hash ring for shards
              SortedMultiDict.empty[String, Replica], //grouped replicas by shard
              buf
            )
          } else Behaviors.same

        case ReplyTimeout ⇒
          Behaviors.same
        case cmd: PingDevice ⇒
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
    shardHash: Option[HashRing],
    m: SortedMultiDict[String, Replica],
    stash: StashBuffer[Command]
  ): Behavior[Command] =
    Behaviors.withTimers { ctx ⇒
      ctx.startSingleTimer(ToKey, ReplyTimeout, replyTimeout)
      current.tell(GetShardInfo(self))
      awaitInfo(shardName, self, current, rest, shardHash, m, stash, ctx)
    }

  def awaitInfo(
    shardName: String,
    self: ActorRef[Command],
    current: ActorRef[ShardRegionCmd],
    rest: Set[ActorRef[ShardRegionCmd]],
    shardHash: Option[HashRing],
    replicas: SortedMultiDict[String, Replica],
    buf: StashBuffer[Command],
    timer: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case ShardInfo(rName, ref, hostId) ⇒
          timer.cancel(ToKey)

          val uHash = shardHash match {
            case None ⇒
              HashRing(rName)
            case Some(r) ⇒
              (r :+ rName).map(_._1).getOrElse(r)
          }
          val um = replicas.add(rName, Replica(ref, hostId))

          if (ctx.self.path.address == ref.path.address)
            ref.tell(WakeUpDevice(hostId))

          if (rest.nonEmpty)
            reqClusterInfo(rName, self, rest.head, rest.tail, Some(uHash), um, buf)
          else {
            val info = um.keySet
              .map(k ⇒ s"[$k -> ${um.get(k).map(_.memberId).mkString(",")}]")
              .mkString(";")

            ctx.log.warning("★ ★ ★ {} - {}", rName, info)

            if (buf.isEmpty) stable(uHash, um, rName)
            else buf.unstashAll(ctx, converge(rName, buf))
          }
        case ReplyTimeout ⇒
          ctx.log.warning(s"No response within ${replyTimeout}. Retry {} ", current)
          //TODO: Limit number of retries because the target node might die in the middle of the process,
          // therefore we won't get the reply back. Moreover, we could step into infinite loop
          reqClusterInfo(shardName, self, current, rest, shardHash, replicas, buf)
        case m @ MembershipChanged(rs) if rs.nonEmpty ⇒
          buf.stash(m)
          Behaviors.same
        case cmd: PingDevice ⇒
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

  def stable(
    shardHash: HashRing,
    replicas: SortedMultiDict[String, Replica],
    shardName: String
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case Ping(id) ⇒
          //pick shard
          val shard = shardHash.lookup(id).head

          //pick replica
          val rs      = replicas.get(shard)
          val replica = rs.head.a //always pick the first
          val pid     = rs.head.memberId

          ctx.log.warning("{} goes to [{} - {}:{}]", id, shard, replica, replicas.size)

          if (rs.isEmpty) ctx.log.error(s"Critical error: Couldn't find actorRefs for ${shard}")
          else replica.tell(PingDevice(id, pid))
          Behaviors.same
        case m @ MembershipChanged(rs) ⇒
          if (rs.nonEmpty) {
            ctx.self.tell(m)
            converge(shardName)
          } else Behaviors.same
        case ClusterStateRequest(r) ⇒
          val info = replicas.keySet.map(k ⇒ s"[$k -> ${replicas.get(k).mkString(",")}]").mkString(";")
          r.tell(ClusterStateResponse(info))
          Behaviors.same
        case GetCropCircle(replyTo) ⇒
          val circle = replicas.keySet.foldLeft(CropCircle("fsa")) { (circle, c) ⇒
            replicas.get(c).map(_.a.path.toString).foldLeft(circle) { (circle, actorPath) ⇒
              circle :+ (c, actorPath)
            }
          }
          replyTo.tell(CropCircleView(circle.toString))
          Behaviors.same
        case other ⇒
          ctx.log.warning("Unexpected message in convergence: {}", other)
          Behaviors.same
      }
    }
}
