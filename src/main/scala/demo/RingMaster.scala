package demo

import java.util.concurrent.ThreadLocalRandom

import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import demo.hashing.{CropCircle, HashRing}
import io.moia.streamee.FrontProcessor

import scala.collection.immutable.SortedMultiDict
import scala.concurrent.duration._
import scala.util.{Failure, Success}

//TODO: Can you ExternalShardAllocationStrategy here ???
object RingMaster {

  private val retryLimit = 1 << 3

  // get info timeout
  private val replyTimeout = 1000.millis

  private val timeoutKey = "to"
  val shardManagerKey    = ServiceKey[EntryPoint.Protocol]("shard-entrance")

  private val bSize = 1 << 6

  final case class ClusterStateResponse(state: String)

  // case class Replica(ref: ActorRef[ShardManager.Protocol], shardHost: String) // 127.0.0.1-2551
  final case class Replica(entry: ActorRef[EntryPoint.Protocol], shardHost: String) // 127.0.0.1-2551

  final case class HashRingState(
    hashRing: Option[HashRing] = None, // hash ring for shards
    replicas: SortedMultiDict[String, Replica] = SortedMultiDict.empty[String, Replica],
    processors: Map[
      ActorRef[EntryPoint.Protocol],
      FrontProcessor[PingDevice, Either[ShardInputProcess.ProcessError, PingDeviceReply]]
    ] = Map.empty
  ) { self =>

    def addReplica(hashRing: HashRing, shardName: String, replica: Replica): HashRingState =
      self.copy(hashRing = Some(hashRing), replicas = self.replicas.add(shardName, replica))

    def addProcessor(
      entryPoint: ActorRef[EntryPoint.Protocol],
      processor: FrontProcessor[PingDevice, Either[ShardInputProcess.ProcessError, PingDeviceReply]]
    ): HashRingState =
      self.copy(processors = self.processors + (entryPoint -> processor))
  }

  // TODO: serialization/des
  sealed trait PingDeviceReply
  object PingDeviceReply {
    case object Success           extends PingDeviceReply
    case class Error(err: String) extends PingDeviceReply
  }

  sealed trait Command

  final case class ClusterStateRequest(replyTo: ActorRef[ClusterStateResponse]) extends Command

  final case class MembershipChanged(replicas: Set[ActorRef[EntryPoint.Protocol]]) extends Command

  final case class ShardInfo(shardName: String, shardEntrance: ActorRef[EntryPoint.Protocol], shardAddress: String)
      extends Command

  case object ReplyTimeout extends Command

  final case class GetCropCircle(replyTo: ActorRef[HttpRoutes.CropCircleView]) extends Command

  final case class PingReq(deviceId: Long, replyTo: ActorRef[PingDeviceReply]) extends Command

  case object Shutdown extends Command

  def apply(): Behavior[Command] =
    Behaviors.withStash(bSize) { buf =>
      Behaviors.setup { ctx =>
        ctx.system.receptionist.tell(
          Receptionist.Subscribe(
            RingMaster.shardManagerKey,
            ctx.messageAdapter[Receptionist.Listing] { case RingMaster.shardManagerKey.Listing(replicas) =>
              MembershipChanged(replicas)
            }
          )
        )
        active(HashRingState(), buf)(ctx)
      }
    }

  def active(state: HashRingState, buf: StashBuffer[Command])(implicit
    ctx: ActorContext[Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case MembershipChanged(rs) =>
        ctx.log.warn("MembershipChanged: [{}]", rs.mkString(", "))
        if (rs.nonEmpty)
          converging(ctx.self, rs.head, rs.tail, state, buf)
        else
          Behaviors.same
      case cmd: PingDevice =>
        // TODO: respond fast, because we're not ready yet
        ctx.log.warn("{} respond fast, because we're not ready yet", cmd)
        Behaviors.same
      case other =>
        ctx.log.warn(s"Unexpected $other in ${getClass.getSimpleName}: converge")
        Behaviors.same
    }

  def converging(
    self: ActorRef[Command],
    head: ActorRef[EntryPoint.Protocol],
    tail: Set[ActorRef[EntryPoint.Protocol]],
    state: HashRingState,
    stash: StashBuffer[Command],
    numOfTry: Int = 0
  )(implicit ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.withTimers { timer =>
      timer.startSingleTimer(timeoutKey, ReplyTimeout, replyTimeout)
      head.tell(EntryPoint.GetShardInfo(self))
      awaitEntryPoint(self, head, tail, state, stash, timer, numOfTry)(ctx)
    }

  def awaitEntryPoint(
    self: ActorRef[Command],
    head: ActorRef[EntryPoint.Protocol],
    tail: Set[ActorRef[EntryPoint.Protocol]],
    hashRingState: HashRingState,
    buf: StashBuffer[Command],
    timer: TimerScheduler[Command],
    numOfTry: Int
  )(implicit ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      // ShardInfo("alpha", ctx.self, "172.20.0.3-2551")
      case ShardInfo(shardName, shardEntrance, shardAddress) =>
        timer.cancel(timeoutKey)

        val uHash = hashRingState.hashRing match {
          case None    => HashRing(shardName)
          case Some(r) => (r :+ shardName).map(_._1).getOrElse(r)
        }

        val updatedHashRingState = hashRingState.addReplica(uHash, shardName, Replica(shardEntrance, shardAddress))

        if (tail.nonEmpty)
          converging(self, tail.head, tail.tail, updatedHashRingState, buf)
        else if (buf.isEmpty) {
          val info = updatedHashRingState.replicas.keySet
            .map(k => s"[$k -> ${updatedHashRingState.replicas.get(k).map(_.shardHost).mkString(",")}]")
            .mkString(";")
          ctx.log.warn("★ ★ ★  Ring {}  [{}] ★ ★ ★", info, updatedHashRingState.processors.keySet.mkString(","))
          stable(updatedHashRingState)
        } else buf.unstashAll(active(hashRingState, buf))
      case ReplyTimeout =>
        ctx.log.warn(s"No response within $replyTimeout. Retry {}", head)
        if (numOfTry < retryLimit) converging(self, head, tail, hashRingState, buf, numOfTry + 1)
        else if (tail.nonEmpty) {
          ctx.log.warn(s"Declare {} death. Move on to the {}", head, tail.head)
          converging(self, tail.head, tail.tail, hashRingState, buf)
        } else if (buf.isEmpty) stable(hashRingState)
        else buf.unstashAll(active(hashRingState, buf))
      case m @ MembershipChanged(rs) if rs.nonEmpty =>
        buf.stash(m)
        Behaviors.same
      case cmd: PingDevice =>
        // TODO: respond false, because we're not ready yet
        ctx.log.warn("{} respond fast, because we're not ready yet", cmd)
        Behaviors.same
      case cmd: ClusterStateRequest =>
        // TODO: buffer messages
        cmd.replyTo.tell(ClusterStateResponse("Converging ... Try later"))
        Behaviors.same
      case RingMaster.Shutdown =>
        ctx.log.warn("Shutdown RingMaster")
        Behaviors.stopped
      case other =>
        ctx.log.warn("Unexpected message in awaitInfo: {}", other)
        Behaviors.stopped
    }

  // format: off
  /** 1) Convergence: Any two correct replicas that have applied the same set of updates are in the same state (even if the
    * updates were applied in a different order).
    * 2) Convergence: Correct replicas that have delivered the same set of
    * updates have equivalent state.
    *
    * RingMaster reaches convergence when all members from [[MembershipChanged(members)]] replied with [[ShardInfo]].
    * Upon arrival of a [[MembershipChanged(members)]] message in this actor, [[HashRingState]] state will be reassembled.
    *
    */
  // format: on
  def stable(hashRingState: HashRingState)(implicit ctx: ActorContext[Command]): Behavior[Command] =
    Behaviors.withStash(bSize) { buf =>
      Behaviors.receiveMessage {
        case PingReq(deviceId, replyTo) =>
          hashRingState.hashRing.fold(Behaviors.same[Command]) { hashRing =>
            implicit val system = ctx.system
            implicit val ec     = system.executionContext

            // pick up the target shard
            val shardName = hashRing.lookup(deviceId).head
            // randomly pick up the shard replica
            val replicas        = hashRingState.replicas.get(shardName).toVector
            val ind             = ThreadLocalRandom.current.nextInt(0, replicas.size)
            val shardEntryPoint = replicas(ind).entry

            val updated =
              if (hashRingState.processors.get(shardEntryPoint).isEmpty) {

                // format: off
                /** https://en.wikipedia.org/wiki/Little%27s_law
                  *
                  * L = λ * W
                  * L – the average number of items in a queuing system (queue size).
                  * λ – the average number of items arriving at the system per unit of time.
                  * W – the average waiting time an item spends in a queuing system.
                  *
                  * Question: How many processes running in parallel we need given throughput = 100 rps and average
                  * latency = 100 millis ?
                  *
                  * 100 * 0.1 = 10
                  *
                  * Give the numbers above, the Little’s Law shows that on average, having queue size == 100,
                  * parallelism factor == 10 average latency of single request == 100 millis we can keep up with
                  * throughput = 100 rps
                  */
                // format: on
                val cfg = ShardInputProcess.Config(2.second, 5, 50)
                val processor =
                  FrontProcessor(
                    ShardInputProcess(shardEntryPoint, cfg)(ctx.system),
                    cfg.processorTimeout,
                    name = s"$shardName-front-entrance",
                    bufferSize = cfg.bufferSize
                  )

                hashRingState.addProcessor(shardEntryPoint, processor)
              } else hashRingState

            val inputProcessor = updated.processors(shardEntryPoint)

            // 127.0.0.1-2551 or 127.0.0.1-2552 or ...
            val replicaName = replicas(ind).shardHost
            ctx.log.warn("{} -> {}:{}", deviceId, shardName, hashRingState.replicas.size)
            if (replicas.isEmpty)
              ctx.log.error(s"Critical error: Couldn't find shard entry point for $shardName")
            else
              inputProcessor
                .offer(PingDevice(deviceId, replicaName)) // .pipeToSelf
                .onComplete {
                  case Success(r) =>
                    replyTo.tell(r.fold(err => PingDeviceReply.Error(err.errMsg), identity))
                  case Failure(err) =>
                    replyTo.tell(PingDeviceReply.Error(err.getMessage))
                }(ctx.executionContext)

            stable(updated)
          }

        case m @ MembershipChanged(rs) =>
          if (rs.nonEmpty) {
            ctx.self.tell(m)
            hashRingState.processors.foreach { kv =>
              kv._2.shutdown()
              kv._2.whenDone
                .foreach(_ => println(s"Stopped ShardInputProcess for: ${kv._1.path.toString}"))(ctx.executionContext)
            }
            active(HashRingState(), buf)
          } else Behaviors.same

        case ClusterStateRequest(r) =>
          val info = hashRingState.replicas.keySet
            .map(k => s"[$k -> ${hashRingState.replicas.get(k).mkString(",")}]")
            .mkString(";")
          r.tell(ClusterStateResponse(info))
          ctx.log.warn(
            "Ring: {}",
            hashRingState.replicas.keySet
              .map(k => s"[$k -> ${hashRingState.replicas.get(k).map(_.shardHost).mkString(",")}]")
              .mkString(";")
          )
          ctx.log.warn("{}", hashRingState.hashRing.get.showSubRange(0, Long.MaxValue / 32))
          Behaviors.same

        case GetCropCircle(replyTo) =>
          // to show all token
          // state.shardHash.foreach(r ⇒ replyTo.tell(CropCircleView(r.toCropCircle)))

          val circle = hashRingState.replicas.keySet.foldLeft(CropCircle("circle")) { (circle, c) =>
            hashRingState.replicas.get(c).map(_.entry.path.toString).foldLeft(circle) { (circle, actorPath) =>
              circle :+ (c, actorPath)
            }
          }
          replyTo.tell(HttpRoutes.CropCircleView(circle.toString))
          Behaviors.same

        case RingMaster.Shutdown =>
          ctx.log.warn("Shutdown RingMaster")
          Behaviors.stopped

        case other =>
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
