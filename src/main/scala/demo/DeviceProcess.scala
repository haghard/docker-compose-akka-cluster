package demo

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.{Attributes, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import demo.RingMaster.PingDeviceReply
import io.moia.streamee.{Process, ProcessSinkRef}
import io.moia.streamee.either.{EitherFlowWithContextOps, tapErrors}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import io.moia.streamee.{ProcessSink, SourceExt, Step}

object DeviceProcess {

  final case class Config(timeout: FiniteDuration, parallelism: Int)

  final case class CounterError(errMsg: String)

  def apply(
    config: Config,
    shardManager: ActorRef[ShardManager.Protocol]
  )(implicit sys: ActorSystem[_]): Process[ShardManager.PingDevice, Either[CounterError, PingDeviceReply]] = {
    implicit val timeout: Timeout = config.timeout
    implicit val ec               = sys.executionContext

    def getSinkRef(): Future[ProcessSinkRef[ShardManager.PingDevice, PingDeviceReply]] =
      shardManager
        .ask(ShardManager.GetSinkRef)(timeout, sys.scheduler)
        .recoverWith { case _ ⇒ getSinkRef() }

    val sink =
      Sink.futureSink(getSinkRef().map(_.sink))

    tapErrors { errorTap ⇒
      Process[ShardManager.PingDevice, Either[CounterError, PingDeviceReply]]
        .map { deviceId ⇒
          if (deviceId.deviceId <= 0) Left(CounterError("DeviceId should be more than 0")) else Right(deviceId)
        }
        .errorTo(errorTap)
        .via(flow(sink, 1.second))
        //.mapAsync(config.parallelism) { deviceId ⇒ ringMaster.ask[PingDeviceReply](RingMaster.Ping(deviceId, _)) }
        .map {
          case PingDeviceReply.Error(err) ⇒ Left(CounterError(err))
          case PingDeviceReply.Success    ⇒ Right(PingDeviceReply.Success)
        }
        .errorTo(errorTap)
    }
  }

  private def flow[Ctx](
    sink: ProcessSink[ShardManager.PingDevice, PingDeviceReply],
    processorTimeout: FiniteDuration,
    bs: Int = 4
  )(implicit mat: Materializer): Step[ShardManager.PingDevice, PingDeviceReply, Ctx] =
    Step[ShardManager.PingDevice, Ctx]
      .withAttributes(Attributes.inputBuffer(bs, bs))
      .asFlow
      // the performance gain come from keeping the downstream more saturated
      .batch(bs, {
        case (cmd, _) ⇒
          val rb = new RingBuffer[(ShardManager.PingDevice](bs)
          rb.offer(cmd)
          rb
      })({ (rb, cmd) ⇒
        rb.offer(cmd._1)
        rb
      })
      .mapAsync(1)(cmds ⇒
        Source
          .fromIterator(() => cmds.entries.iterator)
          //.single(pingDevice)
          .into(sink, processorTimeout, 10)
          .runWith(Sink.head)
      )


  private def flow0[Ctx](
    sink: ProcessSink[ShardManager.PingDevice, PingDeviceReply],
    processorTimeout: FiniteDuration,
    bs: Int = 4
  )(implicit mat: Materializer): Step[ShardManager.PingDevice, PingDeviceReply, Ctx] =
    Step[ShardManager.PingDevice, Ctx]
      .mapAsync(1)(pingDevice ⇒
        Source
          .single(pingDevice)
          .into(sink, processorTimeout, 10)
          .runWith(Sink.head)
      )

}
