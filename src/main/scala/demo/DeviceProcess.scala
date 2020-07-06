package demo

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, Scheduler}
import akka.util.Timeout
import demo.RingMaster.{Ping, PingDeviceReply}
import io.moia.streamee.Process
import io.moia.streamee.either.{tapErrors, EitherFlowWithContextOps}

import scala.concurrent.duration.FiniteDuration

object DeviceProcess {

  final case class Config(timeout: FiniteDuration, parallelism: Int)

  final case class CounterError(errMsg: String)

  def apply(
    config: Config,
    ringMaster: ActorRef[Ping]
  )(implicit sch: Scheduler): Process[Long, Either[CounterError, Unit]] = {
    implicit val timeout: Timeout = config.timeout
    tapErrors { errorTap ⇒
      Process[Long, Either[CounterError, Unit]]
        .map { deviceId ⇒
          if (deviceId <= 0) Left(CounterError("DeviceId should more than 0")) else Right(deviceId)
        }
        .errorTo(errorTap)
        .mapAsync(config.parallelism) { deviceId ⇒
          ringMaster.ask[PingDeviceReply](RingMaster.Ping(deviceId, _))
        }
        .map {
          case PingDeviceReply.Error(err) ⇒ Left(CounterError(err))
          case PingDeviceReply.Success    ⇒ Right(())
        }
        .errorTo(errorTap)
    }
  }

}
