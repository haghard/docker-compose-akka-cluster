package demo

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Route, _}
import akka.pattern.ask
import akka.stream.scaladsl._
import akka.stream._
import akka.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class HttpRoutes(cluster: Cluster)(implicit ex: ExecutionContext, system: ActorSystem) extends Directives {
  val Dispatcher = "akka.metrics-dispatcher"

  implicit val _ = akka.util.Timeout(5 seconds)

  implicit val mat = ActorMaterializer(
    ActorMaterializerSettings(system).withDispatcher(Dispatcher).withInputBuffer(1, 1)
  )

  val membership =
    system.actorOf(ClusterMembership.props(cluster).withDispatcher(Dispatcher), "cluster-members")

  val metrics =
    system.actorOf(ClusterJvmMetrics.props(cluster).withDispatcher(Dispatcher), "jvm-metrics")

  val metricsSource =
    Source.fromGraph(new ActorSource[ByteString](metrics))
      .toMat(BroadcastHub.sink(bufferSize = 1 << 4))(Keep.right).run()

  //Ensure that the Broadcast output is dropped if there are no listening parties.
  metricsSource.runWith(Sink.ignore)

  val route: Route =
    path("members") {
      get {
        complete(queryForMembers)
      }
    } ~
      path("metrics") {
        get {
          complete {
            HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, metricsSource))
          }
        }
      }

  private def queryForMembers: Future[HttpResponse] = {
    (membership ask 'Members).mapTo[String].map { line: String =>
      HttpResponse(status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(line)))
    }
  }
}