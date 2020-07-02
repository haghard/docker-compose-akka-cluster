package demo

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.Http
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}
import akka.actor.CoordinatedShutdown.{PhaseClusterExitingDone, PhaseServiceRequestsDone, PhaseServiceUnbind, Reason}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.actor.typed.scaladsl.adapter._

object Bootstrap {
  case object BindFailure extends Reason
}

class Bootstrap(
  shutdown: CoordinatedShutdown,
  ringMaster: ActorRef[RingMaster.Command],
  jvmMetricsSrc: ActorRef[ClusterJvmMetrics.Confirm],
  hostName: String,
  port: Int
)(implicit sys: ActorSystem[Nothing]) {
  val terminationDeadline = 4.seconds

  implicit val classicSystem = sys.toClassic
  implicit val mat           = Materializer(sys)

  /*Http().bind(hostName, port)
    .to(akka.stream.scaladsl.Sink.foreach { con =>
      //increment counter
      con.handleWith(new HttpRoutes(membersRef, srcRef, sr).route)
      //decrement counter
    }).run()*/

  Http()
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
    }(ExecutionContext.global)
}
