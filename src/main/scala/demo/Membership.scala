package demo

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

import akka.actor.Address
import akka.actor.typed.{ActorRef, Behavior, Signal, Terminated}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardRegion
import akka.cluster.typed.{Cluster, Subscribe}
import demo.hashing.{CassandraMurmurHash, Rendezvous}

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._

object Membership {

  case object ShowClusterState                                                       extends ClusterDomainEvent
  case class ClusterStateRequest(replyTo: ActorRef[Membership.ClusterStateResponse]) extends ClusterDomainEvent
  case class ClusterStateResponse(line: String)

  private val onTerminate: PartialFunction[(ActorContext[ClusterDomainEvent], Signal), Behavior[ClusterDomainEvent]] = {
    case (ctx, Terminated(actor)) ⇒
      ctx.log.error("★ ★ ★ {} Terminated", actor)
      //shutdown.run(Bootstrap.DBFailure)
      Behaviors.stopped
  }

  def entityId(h: Rendezvous[Replica]): ShardRegion.ExtractEntityId = {
    case cmd: DeviceCommand ⇒
      val r        = h.shardFor(cmd.id.toString, 1).head
      val shardBts = r.toString.getBytes
      val hash =
        CassandraMurmurHash.hash3_x64_128(ByteBuffer.wrap(shardBts), 0, shardBts.length, h.seed)(1).toHexString
      println(s"entity: ${cmd.id}/${r}/${hash}")
      (hash, cmd)
  }

  def shardId(h: Rendezvous[Replica]): ShardRegion.ExtractShardId = {
    case cmd: DeviceCommand ⇒
      val r        = h.shardFor(cmd.id.toString, 1).head
      val shardBts = r.toString.getBytes
      val hash =
        CassandraMurmurHash.hash3_x64_128(ByteBuffer.wrap(shardBts), 0, shardBts.length, h.seed)(1).toHexString
      println(s"shard: ${cmd.id}/${r}/${hash}")
      hash
    case ShardRegion.StartEntity(entityId) ⇒
      println(s"start-entity: ${entityId}") //recreates shard that went down
      entityId
  }

  def apply(state: CurrentClusterState, h: Rendezvous[Replica]): Behavior[ClusterDomainEvent] =
    Behaviors.setup { ctx ⇒
      val c = Cluster(ctx.system)
      Behaviors.withTimers[ClusterDomainEvent] { t ⇒
        c.subscriptions ! Subscribe(ctx.self, classOf[ClusterDomainEvent])
        t.startPeriodicTimer(ShowClusterState, ShowClusterState, 15000.millis)

        val av = state.members.filter(_.status == MemberStatus.Up).map(_.address)
        av.foreach(a ⇒ h.add(Replica(a)))
        ctx.log.warning("★ ★ ★ Cluster State:{} ★ ★ ★", av.mkString(","))
        convergence(av, SortedSet[Address](), h)
      }
    }

  def convergence(
    available: SortedSet[Address],
    removed: SortedSet[Address],
    h: Rendezvous[Replica]
  ): Behavior[ClusterDomainEvent] =
    Behaviors
      .receivePartial[ClusterDomainEvent] {
        case (ctx, msg) ⇒
          msg match {
            case MemberUp(member) ⇒
              val av  = available + member.address
              val unv = removed - member.address
              ctx.log.warning("★ ★ ★  MemberUp = {}", av.mkString(","))
              av.foreach(a ⇒ h.add(Replica(a)))
              convergence(av, unv, h)
            case UnreachableMember(member) ⇒
              ctx.system.log.warning("★ ★ ★ Unreachable = {}", member.address)
              awaitForConvergence(available, removed, h)
            case MemberExited(member) ⇒ //graceful exit
              val rm = removed + member.address
              val av = available - member.address
              ctx.log.warning("★ ★ ★ {} exit gracefully", member.address)
              h.remove(Replica(member.address))
              convergence(av, rm, h)
            case ShowClusterState ⇒
              //ctx.log.info("★ ★ ★ [{}] - [{}]", available.mkString(","), removed.mkString(","))
              Behaviors.same
            case ClusterStateRequest(replyTo) ⇒
              replyTo.tell(ClusterStateResponse(available.mkString(",")))
              Behaviors.same
            case other ⇒
              //ReachabilityChanged(reachability)
              //ctx.log.warning("★ ★ ★ unexpected in convergence: {}", other)
              Behaviors.same
          }
      }
      .receiveSignal(onTerminate)
  /*
    A node was detected as unreachable and now a leader should take an action to deal with this situation.
    Two outcomes are possible, it's either becomes reachable or stays unreachable and will be removed from the cluster.
   */
  def awaitForConvergence(
    available: SortedSet[Address],
    removed: SortedSet[Address],
    h: Rendezvous[Replica]
  ): Behavior[ClusterDomainEvent] =
    Behaviors
      .receivePartial[ClusterDomainEvent] {
        case (ctx, msg) ⇒
          msg match {
            case ReachableMember(member) ⇒
              ctx.log.warning("★ ★ ★  Reachable again = {}", member.address)
              convergence(available, removed, h)
            case UnreachableMember(member) ⇒
              ctx.log.warning("★ ★ ★  Unreachable = {}", member.address)
              awaitForConvergence(available, removed, h)
            case MemberRemoved(member, _) ⇒ //sbr down the member
              val rm = removed + member.address
              val av = available - member.address
              h.remove(Replica(member.address))
              ctx.log.warning("★ ★ ★ MemberRemoved = {}. Taken downed after being unreachable.", member.address)
              convergence(av, rm, h)
            case ShowClusterState ⇒
              //ctx.log.info("[{}] - [{}]", available.mkString(","), removed.mkString(","))
              Behaviors.same
            case ClusterStateRequest(replyTo) ⇒
              replyTo.tell(ClusterStateResponse(available.mkString(",")))
              Behaviors.same
            case _ ⇒
              Behaviors.same
          }
      }
      .receiveSignal(onTerminate)
}
