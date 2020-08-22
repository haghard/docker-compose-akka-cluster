package demo

import akka.Done
import akka.http.scaladsl.Http
import akka.actor.typed.ActorRef
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.{PhaseActorSystemTerminate, PhaseBeforeServiceUnbind, PhaseServiceRequestsDone, PhaseServiceStop, PhaseServiceUnbind, Reason}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.actor.typed.scaladsl.adapter._

object Bootstrap {
  case object BindFailure   extends Reason
  case object CriticalError extends Reason
}

case class Bootstrap(
  shardName: String,
  ringMaster: ActorRef[RingMaster.Command],
  jvmMetricsSrc: ActorRef[ClusterJvmMetrics.Confirm],
  hostName: String,
  port: Int
)(implicit classicSystem: akka.actor.ActorSystem) {

  implicit val ex = classicSystem.dispatcher

  val terminationDeadline = classicSystem.settings.config
    .getDuration("akka.coordinated-shutdown.default-phase-timeout")
    .getSeconds
    .second

  /*
    Creates a Source of IncomingConnection
    val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
      Http()
        .newServerAt(interface, port)
        .connectionSource()

    serverSource
      .to(
        Sink.foreach { con ⇒
          classicSystem.log.info("Accepted new connection from {}", con.remoteAddress)
          con.handleWith(routes)
        }
      )
   */

  Http()
    .newServerAt(hostName, port)
    .bindFlow(HttpRoutes(ringMaster, jvmMetricsSrc, shardName)(classicSystem.toTyped).route)
    .onComplete {
      case Failure(ex) ⇒
        classicSystem.log.error(s"Shutting down because can't bind on $hostName:$port", ex)
        CoordinatedShutdown(classicSystem).run(Bootstrap.BindFailure)
      case Success(binding) ⇒
        classicSystem.log.info(s"★ ★ ★ Listening for HTTP connections on ${binding.localAddress} * * *")
        CoordinatedShutdown(classicSystem).addTask(PhaseBeforeServiceUnbind, "before-unbind") { () ⇒
          Future {
            classicSystem.log.info("★ ★ ★ CoordinatedShutdown [before-unbind] ★ ★ ★")
            Done
          }
        }

        CoordinatedShutdown(classicSystem).addTask(PhaseServiceUnbind, "http-api.unbind") { () ⇒
          //No new connections are accepted. Existing connections are still allowed to perform request/response cycles
          binding.unbind().map { done ⇒
            classicSystem.log.info("★ ★ ★ CoordinatedShutdown [http-api.unbind] ★ ★ ★")
            done
          }
        }

        /*cShutdown.addTask(PhaseServiceUnbind, "akka-management.stop") { () =>
            AkkaManagement(classicSystem).stop().map { done =>
              classicSystem.log.info("CoordinatedShutdown [akka-management.stop]")
              done
            }
          }*/

        //graceful termination request being handled on this connection
        CoordinatedShutdown(classicSystem).addTask(PhaseServiceRequestsDone, "http-api.terminate") { () ⇒
          /**
            * It doesn't accept new connection but it drains the existing connections
            * Until the `terminationDeadline` all the req that had been accepted will be completed
            * and only than the shutdown will continue
            */
          binding.terminate(terminationDeadline).map { _ ⇒
            classicSystem.log.info("★ ★ ★ CoordinatedShutdown [http-api.terminate]  ★ ★ ★")
            Done
          }
        }

        //forcefully kills connections that are still open
        CoordinatedShutdown(classicSystem).addTask(PhaseServiceStop, "close.connections") { () ⇒
          Http().shutdownAllConnectionPools().map { _ ⇒
            classicSystem.log.info("CoordinatedShutdown [close.connections]")
            Done
          }
        }

        CoordinatedShutdown(classicSystem).addTask(PhaseActorSystemTerminate, "system.term") { () ⇒
          Future.successful {
            classicSystem.log.info("CoordinatedShutdown [system.term]")
            Done
          }
        }
    }
}
