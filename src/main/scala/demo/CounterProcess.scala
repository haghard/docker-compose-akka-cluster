package demo

import akka.actor.typed.{ActorRef, Scheduler}
import akka.util.Timeout
import demo.RingMaster.{Ping, PingDeviceReply}
import io.moia.streamee.Process
import io.moia.streamee.either.{tapErrors, EitherFlowWithContextOps}
import akka.actor.typed.scaladsl.AskPattern._

import scala.concurrent.duration.FiniteDuration
//import scala.util.Try

object CounterProcess {

  final case class Config(timeout: FiniteDuration, parallelism: Int)

  sealed trait CounterError {
    def msg: String
  }
  object CounterError {
    final case class NegativeValue(msg: String) extends CounterError
  }

  def apply(
    config: Config,
    ringMaster: ActorRef[Ping]
  )(implicit sch: Scheduler): Process[Long, Either[CounterError, String]] = {
    implicit val timeout: Timeout = config.timeout
    tapErrors { errorTap ⇒
      Process[Long, Either[CounterError, String]]
        .map { deviceId ⇒
          if (deviceId <= 0) Left(CounterError.NegativeValue("DeviceId should be positive")) else Right(deviceId)
        }
        .errorTo(errorTap)
        .mapAsync(config.parallelism) { deviceId ⇒
          ringMaster.ask[PingDeviceReply](RingMaster.Ping(deviceId, _))
        }
        .map(reply ⇒ Right(reply.key))
        .errorTo(errorTap)
    }
  }

}
