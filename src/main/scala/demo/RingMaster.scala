package demo

import java.util.concurrent.ThreadLocalRandom

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}

import scala.concurrent.duration._
import akka.actor.typed.receptionist.Receptionist
import demo.hashing.{CropCircle, HashRing}

import scala.collection.immutable.SortedMultiDict
import akka.actor.typed.scaladsl.StashBuffer
import akka.cluster.sharding.external.ExternalShardAllocation

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

  //ActorRef[ShardRegionCmd], 127.0.0.1-2551
  case class Replica(ref: ActorRef[ShardRegionCmd], shardHost: String)

  /*val onTerminate: PartialFunction[(ActorContext[ClusterDomainEvent], Signal), Behavior[ClusterDomainEvent]] = {
    case (ctx, Terminated(actor)) ⇒
      ctx.log.error("★ ★ ★ {} Terminated", actor)
      Behaviors.stopped
  }*/

  case class HashRingState(
    hashRing: Option[HashRing] = None, //hash ring for shards
    replicas: SortedMultiDict[String, Replica] = SortedMultiDict.empty[String, Replica]
  ) //grouped replicas by shard name

  private val retryLimit   = 4
  private val replyTimeout = 1000.millis

  def apply(): Behavior[Command] =
    Behaviors.withStash(1 << 6) { buf ⇒
      Behaviors.setup { ctx ⇒
        ctx.system.receptionist.tell(
          Receptionist.Subscribe(
            RingMaster.domainKey,
            ctx.messageAdapter[Receptionist.Listing] {
              case RingMaster.domainKey.Listing(replicas) ⇒ MembershipChanged(replicas)
            }
          )
        )
        converge(HashRingState(), buf)
      }
    }

  def converge(state: HashRingState, buf: StashBuffer[Command]): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        case MembershipChanged(rs) ⇒
          ctx.log.warn("MembershipChanged: [{}]", rs.mkString(", "))
          if (rs.nonEmpty)
            //on each cluster state change we rebuild the whole state
            reqInfo(ctx.self, rs.head, rs.tail, state, buf)
          else Behaviors.same
        case ReplyTimeout ⇒
          Behaviors.same
        case cmd: PingDevice ⇒
          //TODO: respond fast, because we're not ready yet
          ctx.log.warn("{} respond fast, because we're not ready yet", cmd)
          Behaviors.same
        case other ⇒
          ctx.log.warn("other: {}", other)
          Behaviors.same
      }
    }

  def reqInfo(
    self: ActorRef[Command],
    head: ActorRef[ShardRegionCmd],
    tail: Set[ActorRef[ShardRegionCmd]],
    state: HashRingState,
    stash: StashBuffer[Command],
    numOfTry: Int = 0
  ): Behavior[Command] =
    Behaviors.withTimers { ctx ⇒
      ctx.startSingleTimer(ToKey, ReplyTimeout, replyTimeout)
      head.tell(GetShardInfo(self))
      awaitInfo(self, head, tail, state, stash, ctx, numOfTry)
    }

  def awaitInfo(
    self: ActorRef[Command],
    head: ActorRef[ShardRegionCmd],
    tail: Set[ActorRef[ShardRegionCmd]],
    state: HashRingState,
    buf: StashBuffer[Command],
    timer: TimerScheduler[Command],
    numOfTry: Int
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) ⇒
      msg match {
        //ShardInfo("alpha", ctx.self, "172.20.0.3-2551")
        case ShardInfo(shardName, shardProxy, shardAddress) ⇒
          timer.cancel(ToKey)

          //For any shardId that has not been allocated it will be allocated to the requesting node. To make explicit allocations:
          //val shardAllocationClient = ExternalShardAllocation(ctx.system).clientFor(DeviceShadowEntity.entityKey.name)
          //shardAllocationClient.updateShardLocation(shardAddress, shardProxy.path.address)

          val uHash = state.hashRing match {
            case None    ⇒ HashRing(shardName) //-128, 128, 4
            case Some(r) ⇒ (r :+ shardName).map(_._1).getOrElse(r)
          }
          val updatedReplicas = state.replicas.add(shardName, Replica(shardProxy, shardAddress))
          val updatedState    = state.copy(Some(uHash), updatedReplicas)

          //shardProxy.tell(InitDevice(shardAddress))

          if (tail.nonEmpty)
            reqInfo(self, tail.head, tail.tail, updatedState, buf)
          else if (buf.isEmpty) {
            val info = updatedReplicas.keySet
              .map(k ⇒ s"[$k -> ${updatedReplicas.get(k).map(_.shardHost).mkString(",")}]")
              .mkString(";")
            ctx.log.warn("★ ★ ★  Ring {}  ★ ★ ★", info)
            converged(updatedState)
          } else buf.unstashAll(converge(state, buf))
        case ReplyTimeout ⇒
          ctx.log.warn(s"No response within $replyTimeout. Retry {}", head)
          if (numOfTry < retryLimit) reqInfo(self, head, tail, state, buf, numOfTry + 1)
          else if (tail.nonEmpty) {
            ctx.log.warn(s"Declare {} death. Move on to the {}", head, tail.head)
            reqInfo(self, tail.head, tail.tail, state, buf)
          } else if (buf.isEmpty) converged(state)
          else buf.unstashAll(converge(state, buf))
        case m @ MembershipChanged(rs) if rs.nonEmpty ⇒
          buf.stash(m)
          Behaviors.same
        case cmd: PingDevice ⇒
          //TODO: respond false, because we're not ready yet
          ctx.log.warn("{} respond fast, because we're not ready yet", cmd)
          Behaviors.same
        case cmd: ClusterStateRequest ⇒
          cmd.replyTo.tell(ClusterStateResponse("Not ready. Try later"))
          Behaviors.same
        case other ⇒
          ctx.log.warn("Unexpected message in awaitInfo: {}", other)
          Behaviors.stopped
      }
    }

  def converged(state: HashRingState): Behavior[Command] =
    Behaviors.withStash(1 << 6) { buf ⇒
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case Ping(deviceId) ⇒
            state.hashRing.foreach { hashRing ⇒
              //pick shard
              val shardName = hashRing.lookup(deviceId).head
              //randomly pick a shard replica
              val replicas   = state.replicas.get(shardName).toVector
              val ind        = ThreadLocalRandom.current.nextInt(0, replicas.size)
              val replicaRef = replicas(ind).ref

              //127.0.0.1-2551 or 127.0.0.1-2552 or ...
              val replicaName = replicas(ind).shardHost

              //TODO: validate
              //val shardAllocationClient = ExternalShardAllocation(ctx.system).clientFor(DeviceShadowEntity.entityKey.name)
              //shardAllocationClient.updateShardLocation(replicaName, replica.path.address)
              //or
              //For any shardId that has not been allocated it will be allocated to the requesting node.
              // To make explicit allocations:
              /*implicit val ec = system.executionContext
              val shardAllocation = ExternalShardAllocation(system).clientFor(DeviceShadowEntity.entityKey.name)
              shardAllocation
                .shardLocations()
                .filter(_.locations.find(_._1 == entityId).isEmpty)
                .flatMap { _ =>
                  // the entityId becomes the akka-shard-id
                  shardAllocation.updateShardLocation(entityId, system.address)
                }
               */

              ctx.log.warn("{} -> [{} - {}:{}]", deviceId, shardName, replicaRef, state.replicas.size)
              if (replicas.isEmpty) ctx.log.error(s"Critical error: Couldn't find actorRefs for $shardName")
              else replicaRef.tell(PingDevice(deviceId, replicaName))
            }
            Behaviors.same
          case m @ MembershipChanged(rs) ⇒
            if (rs.nonEmpty) {
              ctx.self.tell(m)
              converge(HashRingState(), buf)
            } else Behaviors.same
          case ClusterStateRequest(r) ⇒
            val info = state.replicas.keySet.map(k ⇒ s"[$k -> ${state.replicas.get(k).mkString(",")}]").mkString(";")
            r.tell(ClusterStateResponse(info))
            ctx.log.warn(
              "Ring: {}",
              state.replicas.keySet
                .map(k ⇒ s"[$k -> ${state.replicas.get(k).map(_.shardHost).mkString(",")}]")
                .mkString(";")
            )
            ctx.log.warn("{}", state.hashRing.get.showSubRange(0, Long.MaxValue / 12))
            Behaviors.same
          case GetCropCircle(replyTo) ⇒
            //to show all token
            //state.shardHash.foreach(r ⇒ replyTo.tell(CropCircleView(r.toCropCircle)))

            val circle = state.replicas.keySet.foldLeft(CropCircle("circle")) { (circle, c) ⇒
              state.replicas.get(c).map(_.ref.path.toString).foldLeft(circle) { (circle, actorPath) ⇒
                circle :+ (c, actorPath)
              }
            }
            replyTo.tell(CropCircleView(circle.toString))

            Behaviors.same
          case other ⇒
            ctx.log.warn("Unexpected message in convergence: {}", other)
            Behaviors.same
        }
      }
    }
}
