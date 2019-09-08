package demo

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer, TimerScheduler}

import scala.concurrent.duration._
import akka.actor.typed.receptionist.Receptionist
import demo.hashing.{CropCircle, HashRing}

import scala.collection.immutable.SortedMultiDict

object RingMaster {

  val domainKey = ServiceKey[ShardRegionCmd]("domain")

  case class ClusterStateResponse(state: String)

  sealed trait Command

  case object SelfUpDb                                                    extends Command
  case class ClusterStateRequest(replyTo: ActorRef[ClusterStateResponse]) extends Command

  case class MembershipChanged(replicas: Set[ActorRef[ShardRegionCmd]]) extends Command

  case class ShardInfo(shardName: String, shardProxy: ActorRef[ShardRegionCmd], shardAddress: String) extends Command

  case class RolesInfo(m: Map[String, Set[ActorRef[ShardRegionCmd]]]) extends Command
  case object ReplyTimeout                                            extends Command

  case class GetCropCircle(replyTo: ActorRef[CropCircleView]) extends Command
  case class CropCircleView(json: String)                     extends Command

  case class Ping(id: Long) extends Command

  case object ToKey

  case class Replica(shardProxy: ActorRef[ShardRegionCmd], shardName: String)

  /*val onTerminate: PartialFunction[(ActorContext[ClusterDomainEvent], Signal), Behavior[ClusterDomainEvent]] = {
    case (ctx, Terminated(actor)) ⇒
      ctx.log.error("★ ★ ★ {} Terminated", actor)
      Behaviors.stopped
  }*/

  private val replyTimeout = 2.seconds

  def apply(replicaName: String): Behavior[Command] =
    Behaviors.setup { ctx ⇒
      ctx.system.receptionist ! Receptionist.Subscribe(
        RingMaster.domainKey,
        ctx.messageAdapter[Receptionist.Listing] {
          case RingMaster.domainKey.Listing(replicas) ⇒
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
    localShardName: String,
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
        case ShardInfo(rName, shardProxy, shardAddress) ⇒
          timer.cancel(ToKey)

          val uHash = shardHash match {
            case None    ⇒ HashRing(rName)
            case Some(r) ⇒ (r :+ rName).map(_._1).getOrElse(r)
          }
          val updatedReplicas = replicas.add(rName, Replica(shardProxy, shardAddress))

          //if (ctx.self.path.address == shardProxy.path.address)
          shardProxy.tell(InitDevice(shardAddress))

          if (rest.nonEmpty)
            reqClusterInfo(rName, self, rest.head, rest.tail, Some(uHash), updatedReplicas, buf)
          else {
            if (buf.isEmpty) {
              val info = updatedReplicas.keySet
                .map(k ⇒ s"[$k -> ${updatedReplicas.get(k).map(_.shardName).mkString(",")}]")
                .mkString(";")
              ctx.log.info("★ ★ ★   Forming ring {}   ★ ★ ★", info)
              converged(uHash, updatedReplicas, rName)
            } else buf.unstashAll(ctx, converge(rName, buf))
          }
        case ReplyTimeout ⇒
          ctx.log.warning(s"No response within ${replyTimeout}. Retry {}", current)
          //TODO: Limit number of retries because the target node might die in the middle of the process
          // therefore, we won't get the reply back. Moreover, we could step into infinite loop
          reqClusterInfo(localShardName, self, current, rest, shardHash, replicas, buf)
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
          Behaviors.ignore //.stopped
      }
    }

  def converged(
    shardHash: HashRing,
    replicas: SortedMultiDict[String, Replica],
    shardName: String
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case Ping(deviceId) ⇒
          //pick shard
          val shard = shardHash.lookup(deviceId).head
          //pick replica
          val rs          = replicas.get(shard)
          val replica     = rs.head.shardProxy //always pick the first
          val replicaName = rs.head.shardName
          ctx.log.warning("{} goes to [{} - {}:{}]", deviceId, shard, replica, replicas.size)
          if (rs.isEmpty) ctx.log.error(s"Critical error: Couldn't find actorRefs for $shard")
          else replica.tell(PingDevice(deviceId, replicaName))
          Behaviors.same
        case m @ MembershipChanged(rs) ⇒
          if (rs.nonEmpty) {
            ctx.log.error("!!!!!!! converged -> converge")
            ctx.self.tell(m)
            converge(shardName)
          } else Behaviors.same
        case ClusterStateRequest(r) ⇒
          /*
            [alpha ->
              Replica([akka://dc/user/domain#1683965312], master-2551),
              Replica([akka://dc@172.20.0.3:2551/user/domain#-1231738166], 172.20.0.3-2551)];
            [betta -> Replica([akka://dc@172.20.0.4:2551/user/domain#1030306788], 172.20.0.4-2551)];
            [gamma -> Replica([akka://dc@172.20.0.5:2551/user/domain#48910236], 172.20.0.5-2551)]
           */
          val info = replicas.keySet.map(k ⇒ s"[$k -> ${replicas.get(k).mkString(",")}]").mkString(";")
          r.tell(ClusterStateResponse(info))

          ctx.log.warning(
            "Ring: {}",
            replicas.keySet.map(k ⇒ s"[$k -> ${replicas.get(k).map(_.shardName).mkString(",")}]").mkString(";")
          )
          ctx.log.warning("{}", shardHash.showSubRange(0, Long.MaxValue / 12))
          Behaviors.same
        case GetCropCircle(replyTo) ⇒
          val circle = replicas.keySet.foldLeft(CropCircle("circle")) { (circle, c) ⇒
            replicas.get(c).map(_.shardProxy.path.toString).foldLeft(circle) { (circle, actorPath) ⇒
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
