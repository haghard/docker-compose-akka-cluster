package demo

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.Http
import akka.actor.typed.ActorRef
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.actor.CoordinatedShutdown.{PhaseServiceRequestsDone, PhaseServiceUnbind, Reason}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import scala.concurrent.duration._
import akka.cluster.ClusterEvent.ClusterDomainEvent

object Bootstrap {
  case object BindFailure extends Reason
}

class Bootstrap(
  shutdown: CoordinatedShutdown,
  metricsRef: ActorRef[ClusterDomainEvent],
  srcRef: ActorRef[ClusterJvmMetrics.Confirm],
  sr: ActorRef[DeviceCommand],
  hostName: String,
  port: Int
)(
  implicit sys: ActorSystem
) {

  val termDeadline = 2.seconds
  implicit val mat = ActorMaterializer(ActorMaterializerSettings.create(sys).withDispatcher("akka.cluster-dispatcher"))

  Http()
    .bindAndHandle(new HttpRoutes(metricsRef, srcRef, sr).route, hostName, port)
    .onComplete {
      case Failure(ex) ⇒
        sys.log.error(ex, s"Shutting down because can't bind to $hostName:$port")
        shutdown.run(Bootstrap.BindFailure)

      case Success(binding) ⇒
        sys.log.warning(s"* * * Seed node: Listening for HTTP connections on ${binding.localAddress} * * *")
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
