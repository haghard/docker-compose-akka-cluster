package demo

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{RestartSink, Sink}
import demo.RingMaster.PingDeviceReply
import io.moia.streamee.either.EitherFlowWithContextOps
import io.moia.streamee.{Process, ProcessSinkRef}

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}

object ShardInputProcess {

  final case class CounterError(errMsg: String)

  case class Config(processorTimeout: FiniteDuration, parallelism: Int)

  /**
    * A long-running process that links
    */
  def apply(
    shardManager: ActorRef[ShardManager.Protocol],
    config: Config
  )(implicit sys: ActorSystem[_]): Process[PingDevice, Either[CounterError, PingDeviceReply]] = {
    import config._
    sys.log.warn("★ ★ ★ Start ShardInputProcess ★ ★ ★")

    implicit val ec = sys.executionContext

    def getSinkRef(): Future[ProcessSinkRef[PingDevice, PingDeviceReply]] =
      shardManager
        .ask(ShardManager.GetSinkRef)(processorTimeout, sys.scheduler)
    /*.recoverWith {
          case err ⇒
            sys.log.warn(s"Failed to get ${classOf[ShardManager.GetSinkRef]}: ${err.getMessage}")
            getSinkRef()
        }
     */

    val shardingSink =
      RestartSink.withBackoff(100.millis, 500.millis, 0.1)(() ⇒ Sink.futureSink(getSinkRef().map(_.sink)))

    either.tapErrors { errorTap ⇒
      Process[PingDevice, Either[CounterError, PingDeviceReply]]
        .map { req ⇒
          if (req.deviceId <= 10) Left(CounterError("DeviceId should be more than 10")) else Right(req)
        }
        .errorTo(errorTap)
        .into(shardingSink, processorTimeout, parallelism)
        //.via(shardingFlow(shardingSink, processorTimeout))
        .map {
          case PingDeviceReply.Error(err) ⇒ Left(CounterError(err))
          case PingDeviceReply.Success    ⇒ Right(PingDeviceReply.Success)
        }
        /*.mapConcat { replies: Seq[PingDeviceReply] =>
          replies.map {
            case PingDeviceReply.Error(err) ⇒ Left(CounterError(err))
            case PingDeviceReply.Success ⇒ Right(PingDeviceReply.Success)
          }
        }*/
        .errorTo(errorTap)
    }
  }

  /*
  import akka.stream.{Attributes, Materializer}
  import io.moia.streamee.{ProcessSink, SourceExt, Step}

  def shardingFlow[Ctx](
    sink: ProcessSink[ShardManager.PingDevice, PingDeviceReply],
    processorTimeout: FiniteDuration
  )(implicit mat: Materializer): Step[ShardManager.PingDevice, PingDeviceReply, Ctx] =
    Step[ShardManager.PingDevice, Ctx]
      .mapAsync(1)(pingDevice ⇒
        Source
          .single(pingDevice)
          .into(sink, processorTimeout, 10)
          .runWith(Sink.head)
      )


   private def flow[Ctx](
    sink: ProcessSink[ShardManager.PingDevice, Seq[PingDeviceReply]],
    processorTimeout: FiniteDuration,
    bs: Int = 16
  )(implicit mat: Materializer): Step[ShardManager.PingDevice, Seq[PingDeviceReply], Ctx] =
    Step[ShardManager.PingDevice, Ctx]
      .withAttributes(Attributes.inputBuffer(bs, bs))
      .asFlow
      // the performance gain come from keeping the downstream more saturated
      .batch(
        bs,
        {
          case (cmd, _) ⇒
            val rb = new RingBuffer[ShardManager.PingDevice](bs)
            rb.offer(cmd)
            rb
        }
      ) { (rb, cmd) ⇒
        rb.offer(cmd._1)
        rb
      }
      .mapAsync(1)(cmds ⇒
        Source
          .fromIterator(() ⇒ cmds.entries.iterator)
          .into(sink, processorTimeout, 10)
          .runWith(Sink.seq).map(_.flatten)(mat.executionContext)
      )*/
}
