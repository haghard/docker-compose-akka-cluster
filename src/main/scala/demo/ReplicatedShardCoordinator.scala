package demo

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer, TimerScheduler}

import scala.concurrent.duration._
import akka.actor.typed.receptionist.Receptionist
import akka.routing.{ConsistentHash ⇒ AkkaConsistentHash}
import demo.hashing.CropCircle

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

  case class GetCropCircle(replyTo: ActorRef[ReplicatedShardCoordinator.CropCircleView]) extends Command
  case class CropCircleView(json: String)                                                extends Command

  case class Ping(id: Long) extends Command

  case class ReplicaEntity(
    hash: AkkaConsistentHash[String],
    actors: Set[ActorRef[ShardRegionCmd]]
  )

  case object ToKey

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

            /*
            var map = scala.collection.immutable.SortedMultiDict.empty[String, Int]
            map = map + ("a" → 1) + ("a" → 2)
            map.get("a")
             */

            //on each cluster state change we rebuild the whole state
            reqClusterInfo(
              shardName,
              ctx.self,
              rs.head,
              rs.tail,
              AkkaConsistentHash[String](Iterable.empty, 1 << 6), //hash ring for shards
              Map[String, ReplicaEntity]()                        //grouped replicas by shard
                .withDefaultValue(ReplicaEntity(AkkaConsistentHash[String](Iterable.empty, 1 << 6), Set.empty)),
              buf,
              CropCircle("fsa")
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
    shardHash: AkkaConsistentHash[String],
    m: Map[String, ReplicaEntity],
    stash: StashBuffer[Command],
    circle: CropCircle
  ): Behavior[Command] =
    Behaviors.withTimers { ctx ⇒
      ctx.startSingleTimer(ToKey, ReplyTimeout, replyTimeout)
      current.tell(IdentifyShard(self))
      awaitInfo(shardName, self, current, rest, shardHash, m, stash, ctx, circle)
    }

  def awaitInfo(
    shardName: String,
    self: ActorRef[Command],
    current: ActorRef[ShardRegionCmd],
    rest: Set[ActorRef[ShardRegionCmd]],
    shardHash: AkkaConsistentHash[String],
    m: Map[String, ReplicaEntity],
    buf: StashBuffer[Command],
    timer: TimerScheduler[Command],
    circle: CropCircle
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case ShardInfo(rName, ref, pid) ⇒
          timer.cancel(ToKey)

          val entity = m(rName)
          val um     = m.updated(rName, entity.copy(entity.hash.add(pid), entity.actors + ref))
          val ut     = circle :+ (rName, ref.path.toString)

          if (rest.nonEmpty)
            reqClusterInfo(rName, self, rest.head, rest.tail, shardHash.add(rName), um, buf, ut)
          else {
            val info = um.keySet
              .map(k ⇒ s"[$k -> ${um(k).actors.mkString(",")}]")
              .mkString(";")

            ctx.log.warning("★ ★ ★ {} - {}", rName, info)

            if (buf.isEmpty) stable(shardHash, um, rName, ut)
            else buf.unstashAll(ctx, converge(rName, buf))
          }
        case ReplyTimeout ⇒
          ctx.log.warning(s"No response within ${replyTimeout}. Retry {} ", current)
          //TODO: Limit number of retries because the target node might die in the middle of the process,
          // therefore we won't get the reply back. Moreover, we could step into infinite loop
          reqClusterInfo(shardName, self, current, rest, shardHash, m, buf, circle)
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
    shardHash: AkkaConsistentHash[String],
    m: Map[String, ReplicaEntity],
    shardName: String,
    circle: CropCircle
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case Ping(id) ⇒
          //pick shard
          val shard = shardHash.nodeFor(id.toString)

          //pick replica
          val replicaEntity = m(shard)
          val replica       = replicaEntity.hash.nodeFor(id.toString)
          val replicas      = replicaEntity.actors

          ctx.log.warning("{} goes to [{} - {}:{}]", id, shard, replica, replicas.size)
          if (replicas.isEmpty) ctx.log.error(s"Critical error: Couldn't find actorRefs for ${shard}")
          else replicas.headOption.foreach(_.tell(PingDevice(id, replica)))
          Behaviors.same
        case m @ MembershipChanged(rs) ⇒
          if (rs.nonEmpty) {
            ctx.self.tell(m)
            converge(shardName)
          } else Behaviors.same
        case ClusterStateRequest(r) ⇒
          val info = m.keySet.map(k ⇒ s"[$k -> ${m(k).actors.mkString(",")}]").mkString(";")
          r.tell(ClusterStateResponse(info))
          Behaviors.same
        case GetCropCircle(replyTo) ⇒
          /*val keys = m.keySet
          val ring = keys.tail.foldLeft(Ring(keys.head))(_.:+(_).get._1)
          val js   = ring.toCropCircle*/
          val js = circle.toString
          ctx.log.info("{}", js)
          replyTo.tell(CropCircleView(js))
          Behaviors.same
        case other ⇒
          ctx.log.warning("Unexpected message in convergence: {}", other)
          Behaviors.same
      }
    }
}
