package demo

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.Http
import akka.actor.typed.ActorRef
import akka.actor.CoordinatedShutdown.{PhaseActorSystemTerminate, PhaseBeforeServiceUnbind, PhaseClusterExitingDone, PhaseServiceRequestsDone, PhaseServiceStop, PhaseServiceUnbind, Reason}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.actor.typed.scaladsl.adapter._

object Bootstrap {
  case object BindFailure   extends Reason
  case object CriticalError extends Reason

  val terminationDeadline = 4.seconds
}

class Bootstrap(
  ringMaster: ActorRef[RingMaster.Command],
  jvmMetricsSrc: ActorRef[ClusterJvmMetrics.Confirm],
  hostName: String,
  port: Int
)(implicit classicSystem: akka.actor.ActorSystem) {

  implicit val ex = classicSystem.dispatcher

  /*Http().bind(hostName, port)
    .to(akka.stream.scaladsl.Sink.foreach { con =>
      //increment counter
      con.handleWith(new HttpRoutes(membersRef, srcRef, sr).route)
      //decrement counter
    }).run()*/

  /*Http()
    .bindAndHandle(new HttpRoutes(ringMaster, jvmMetricsSrc).route, hostName, port)
    .onComplete {
      case Failure(ex) ⇒
        classicSystem.log.error(ex, s"Shutting down because can't bind to $hostName:$port")
        shutdown.run(Bootstrap.BindFailure)

      case Success(binding) ⇒
        classicSystem.log.warning(s"★ ★ ★ Listening for HTTP connections on ${binding.localAddress} * * *")

        shutdown.addTask(PhaseServiceUnbind, "api.unbind") { () ⇒
          classicSystem.log.info("api.unbind")
          // No new connections are accepted
          // Existing connections are still allowed to perform request/response cycles
          binding
            .unbind()
            .map { r ⇒
              classicSystem.log.info("★ ★ ★ api.unbind ★ ★ ★")
              r
            }(ExecutionContext.global)
        }

        shutdown.addTask(PhaseServiceRequestsDone, "api.terminate") { () ⇒
          classicSystem.log.info("★ ★ ★ api.terminate ★ ★ ★")
          //graceful termination requests being handled on this connection
          binding.terminate(terminationDeadline).map(_ ⇒ Done)(ExecutionContext.global)
        }
    }(ExecutionContext.global)*/

  val cShutdown = CoordinatedShutdown(classicSystem)

  Http()
    .bindAndHandle(new HttpRoutes(ringMaster, jvmMetricsSrc)(classicSystem.toTyped).route, hostName, port)
    .onComplete {
      case Failure(ex) ⇒
        classicSystem.log.error(s"Shutting down because can't bind on $hostName:$port", ex)
        cShutdown.run(Bootstrap.BindFailure)
      case Success(binding) ⇒
        classicSystem.log.info(s"★ ★ ★ Listening for HTTP connections on ${binding.localAddress} * * *")
        cShutdown.addTask(PhaseBeforeServiceUnbind, "before-unbind") { () ⇒
          Future {
            classicSystem.log.info("CoordinatedShutdown [before-unbind]")
            Done
          }
        }

        cShutdown.addTask(PhaseServiceUnbind, "http-api.unbind") { () ⇒
          //No new connections are accepted. Existing connections are still allowed to perform request/response cycles
          binding.unbind().map { done ⇒
            classicSystem.log.info("CoordinatedShutdown [http-api.unbind]")
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
        cShutdown.addTask(PhaseServiceRequestsDone, "http-api.terminate") { () ⇒
          /**
            * It doesn't accept new connection but it drains the existing connections
            * Until the `terminationDeadline` all the req that have been accepted will be completed
            * and only than the shutdown will continue
            */
          binding.terminate(Bootstrap.terminationDeadline).map { _ ⇒
            classicSystem.log.info("CoordinatedShutdown [http-api.terminate]")
            Done
          }
        }

        //forcefully kills connections that are still open
        cShutdown.addTask(PhaseServiceStop, "close.connections") { () ⇒
          Http().shutdownAllConnectionPools().map { _ ⇒
            classicSystem.log.info("CoordinatedShutdown [close.connections]")
            Done
          }
        }

        cShutdown.addTask(PhaseActorSystemTerminate, "sys.term") { () ⇒
          Future.successful {
            classicSystem.log.info("CoordinatedShutdown [sys.term]")
            Done
          }
        }
    }
}
