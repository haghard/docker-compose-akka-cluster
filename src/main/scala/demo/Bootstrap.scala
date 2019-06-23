package demo

import akka.Done
import akka.http.scaladsl.Http
import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.actor.CoordinatedShutdown.{PhaseServiceRequestsDone, PhaseServiceUnbind, Reason}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import scala.concurrent.duration._

object Bootstrap {
  case object BindFailure extends Reason
}

class Bootstrap(shutdown: CoordinatedShutdown, hostName: String, port: Int)(
  implicit sys: ActorSystem
) {

  val termDeadline = 2.seconds

  implicit val mat = ActorMaterializer(
    ActorMaterializerSettings
      .create(sys)
      .withDispatcher("akka.cluster-dispatcher")
  )

  implicit val ec = mat.executionContext

  Http()
    .bindAndHandle(new HttpRoutes().route, hostName, port)
    .onComplete {
      case Failure(ex) ⇒
        sys.log.error(ex, s"Shutting down because can't bind to $hostName:$port")
        shutdown.run(Bootstrap.BindFailure)

      case Success(binding) ⇒
        sys.log.info(s"* * *  Seed  node: Listening for HTTP connections on ${binding.localAddress} * * *")
        shutdown.addTask(PhaseServiceUnbind, "api.unbind") { () ⇒
          sys.log.info("api.unbind")
          // No new connections are accepted
          // Existing connections are still allowed to perform request/response cycles
          binding.unbind()
        }

        shutdown.addTask(PhaseServiceRequestsDone, "api.terminate") { () ⇒
          sys.log.info("api.terminate")
          //graceful termination request being handled on this connection
          binding.terminate(termDeadline).map(_ ⇒ Done)(ExecutionContext.global)
        }
    }(ExecutionContext.global)
}
