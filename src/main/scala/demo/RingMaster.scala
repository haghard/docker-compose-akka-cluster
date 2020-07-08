package demo

import java.util.concurrent.ThreadLocalRandom

import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import demo.hashing.{CropCircle, HashRing}
import io.moia.streamee.FrontProcessor

import scala.collection.immutable.SortedMultiDict
import scala.concurrent.duration._

object RingMaster {

  private val retryLimit = 1 << 3

  //get info timeout
  private val replyTimeout = 1000.millis

  private val timeoutKey = "to"
  val shardManagerKey    = ServiceKey[ShardManager.Protocol]("shard-manager")

  private val bSize = 1 << 6

  case class ClusterStateResponse(state: String)

  //case class Replica(ref: ActorRef[ShardManager.Protocol], shardHost: String) // 127.0.0.1-2551
  case class Replica(
    shardManager: ActorRef[ShardManager.Protocol],
    shardHost: String
  ) // 127.0.0.1-2551

  case class HashRingState(
    hashRing: Option[HashRing] = None, //hash ring for shards
    replicas: SortedMultiDict[String, Replica] = SortedMultiDict.empty[String, Replica],
    processors: Map[
      ActorRef[ShardManager.Protocol],
      FrontProcessor[PingDevice, Either[ShardInputProcess.CounterError, PingDeviceReply]]
    ] = Map.empty
  ) //grouped replicas by shard name

  //TODO: serialization/des
  sealed trait PingDeviceReply
  object PingDeviceReply {
    case object Success           extends PingDeviceReply
    case class Error(err: String) extends PingDeviceReply
  }

  sealed trait Command

  case class ClusterStateRequest(replyTo: ActorRef[ClusterStateResponse]) extends Command

  case class MembershipChanged(replicas: Set[ActorRef[ShardManager.Protocol]]) extends Command

  case class ShardInfo(shardName: String, shardManager: ActorRef[ShardManager.Protocol], shardAddress: String)
      extends Command

  //case class RolesInfo(m: Map[String, Set[ActorRef[ShardManager.Protocol]]]) extends Command
  case object ReplyTimeout extends Command

  case class GetCropCircle(replyTo: ActorRef[HttpRoutes.CropCircleView]) extends Command

  //TODO: serialization/des
  case class PingReq(deviceId: Long) extends Command

  case object Shutdown extends Command

  def apply(): Behavior[Command] =
    Behaviors.withStash(bSize) { buf ⇒
      Behaviors.setup { ctx ⇒
        ctx.system.receptionist.tell(
          Receptionist.Subscribe(
            RingMaster.shardManagerKey,
            ctx.messageAdapter[Receptionist.Listing] {
              case RingMaster.shardManagerKey.Listing(replicas) ⇒ MembershipChanged(replicas)
            }
          )
        )
        converge(HashRingState(), buf)(ctx)
      }
    }

  def converge(state: HashRingState, buf: StashBuffer[Command])(implicit
    ctx: ActorContext[Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case MembershipChanged(rs) ⇒
        ctx.log.warn("MembershipChanged: [{}]", rs.mkString(", "))
        if (rs.nonEmpty) reqInfo(ctx.self, rs.head, rs.tail, state, buf) else Behaviors.same
      case cmd: PingDevice ⇒
        //TODO: respond fast, because we're not ready yet
        ctx.log.warn("{} respond fast, because we're not ready yet", cmd)
        Behaviors.same
      case other ⇒
        ctx.log.warn(s"Unexpected $other in ${getClass.getSimpleName}: converge")
        Behaviors.same
    }

  def reqInfo(
    self: ActorRef[Command],
    head: ActorRef[ShardManager.Protocol],
    tail: Set[ActorRef[ShardManager.Protocol]],
    state: HashRingState,
    stash: StashBuffer[Command],
    numOfTry: Int = 0
  )(implicit ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.withTimers { timer ⇒
      timer.startSingleTimer(timeoutKey, ReplyTimeout, replyTimeout)
      head.tell(ShardManager.GetShardInfo(self))
      awaitInfo(self, head, tail, state, stash, timer, numOfTry)(ctx)
    }

  def awaitInfo(
    self: ActorRef[Command],
    head: ActorRef[ShardManager.Protocol],
    tail: Set[ActorRef[ShardManager.Protocol]],
    state: HashRingState,
    buf: StashBuffer[Command],
    timer: TimerScheduler[Command],
    numOfTry: Int
  )(implicit ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      //ShardInfo("alpha", ctx.self, "172.20.0.3-2551")
      case ShardInfo(shardName, shardManager, shardAddress) ⇒
        timer.cancel(timeoutKey)
        implicit val s  = ctx.system
        implicit val ec = ctx.executionContext

        val uHash = state.hashRing match {
          case None    ⇒ HashRing(shardName)
          case Some(r) ⇒ (r :+ shardName).map(_._1).getOrElse(r)
        }

        val updatedReplicas = state.replicas.add(shardName, Replica(shardManager, shardAddress))
        val updatedState    = state.copy(Some(uHash), updatedReplicas)

        if (tail.nonEmpty)
          reqInfo(self, tail.head, tail.tail, updatedState, buf)
        else if (buf.isEmpty) {
          val info = updatedReplicas.keySet
            .map(k ⇒ s"[$k -> ${updatedReplicas.get(k).map(_.shardHost).mkString(",")}]")
            .mkString(";")
          ctx.log.warn("★ ★ ★  Ring {}  [{}] ★ ★ ★", info, updatedState.processors.keySet.mkString(","))
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
      case RingMaster.Shutdown ⇒
        ctx.log.warn("Shutdown RingMaster")
        Behaviors.stopped
      case other ⇒
        ctx.log.warn("Unexpected message in awaitInfo: {}", other)
        Behaviors.stopped
    }

  def converged(state: HashRingState)(implicit ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.withStash(bSize) { buf ⇒
      Behaviors.receiveMessage {
        case PingReq(deviceId) ⇒
          state.hashRing.fold(Behaviors.same[Command]) { hashRing ⇒
            implicit val system = ctx.system
            implicit val ec     = system.executionContext

            //pick up shard
            val shardName = hashRing.lookup(deviceId).head
            //randomly pick a shard replica
            val replicas     = state.replicas.get(shardName).toVector
            val ind          = ThreadLocalRandom.current.nextInt(0, replicas.size)
            val shardManager = replicas(ind).shardManager

            val updated =
              if (state.processors.get(shardManager).isEmpty) {

                /**
                  * https://en.wikipedia.org/wiki/Little%27s_law
                  *
                  * L = λ * W
                  * L – the average number of items in a queuing system (queue size)
                  * λ – the average number of items arriving at the system per unit of time
                  * W – the average waiting time an item spends in a queuing system
                  *
                  * Question: How many processes running in parallel we need given
                  * throughput = 100 rps and average latency = 100 millis  ?
                  *
                  * 100 * 0.1 = 10
                  *
                  * Give the numbers above, the Little’s Law shows that on average, having
                  * queue size == 100,
                  * parallelism factor == 10
                  * average latency of single request == 100 millis
                  * we can keep up with throughput = 100 rps
                  */
                val cfg = ShardInputProcess.Config(1.second, 10)
                val shardingInput =
                  FrontProcessor(ShardInputProcess(shardManager, cfg)(ctx.system), cfg.processorTimeout, name = "input", bufferSize =100)

                state.copy(processors = state.processors + (shardManager → shardingInput))
              } else state

            val inputProcessor = updated.processors(shardManager)

            //127.0.0.1-2551 or 127.0.0.1-2552 or ...
            val replicaName = replicas(ind).shardHost
            ctx.log.warn("{} -> {}:{}", deviceId, shardName, state.replicas.size)
            if (replicas.isEmpty) ctx.log.error(s"Critical error: Couldn't find actorRefs for $shardName")
            else
              //TODO
              inputProcessor
                .offer(PingDevice(deviceId, replicaName))
                .onComplete(r ⇒ println(s"Ping result: $r"))(ctx.executionContext)

            converged(updated)
          }

        case m @ MembershipChanged(rs) ⇒
          if (rs.nonEmpty) {
            ctx.self.tell(m)
            state.processors.foreach { kv ⇒
              kv._2.shutdown()
              kv._2.whenDone
                .foreach(_ ⇒ println(s"Stopped ShardInputProcess for: ${kv._1.path.toString}"))(ctx.executionContext)
            }
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
          ctx.log.warn("{}", state.hashRing.get.showSubRange(0, Long.MaxValue / 32))
          Behaviors.same

        case GetCropCircle(replyTo) ⇒
          //to show all token
          //state.shardHash.foreach(r ⇒ replyTo.tell(CropCircleView(r.toCropCircle)))

          val circle = state.replicas.keySet.foldLeft(CropCircle("circle")) { (circle, c) ⇒
            state.replicas.get(c).map(_.shardManager.path.toString).foldLeft(circle) { (circle, actorPath) ⇒
              circle :+ (c, actorPath)
            }
          }
          replyTo.tell(HttpRoutes.CropCircleView(circle.toString))
          Behaviors.same

        case RingMaster.Shutdown ⇒
          ctx.log.warn("Shutdown RingMaster")
          Behaviors.stopped

        case other ⇒
          ctx.log.warn(s"Unexpected message:$other in converged")
          Behaviors.stopped
      }
    }
}

/*val onTerminate: PartialFunction[(ActorContext[ClusterDomainEvent], Signal), Behavior[ClusterDomainEvent]] = {
  case (ctx, Terminated(actor)) ⇒
    ctx.log.error("★ ★ ★ {} Terminated", actor)
    Behaviors.stopped
}*/
