package demo

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Route, _}
import akka.pattern.ask
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class HttpRoutes(cluster: Cluster)
  (implicit ex: ExecutionContext, system: ActorSystem) extends Directives {

  val Dispatcher = "akka.metrics-dispatcher"

  implicit val _ = akka.util.Timeout(5 seconds)

  implicit val mat = ActorMaterializer(
      ActorMaterializerSettings(system)
        .withDispatcher(Dispatcher)
        .withInputBuffer(1, 1))

  val members = system.actorOf(ClusterMembershipSupport.props(cluster), "cluster-members")

  //This allows us to have just one source actor and many subscribers
  val metricsSource =
    Source.actorPublisher[ByteString](ClusterMetrics.props(cluster).withDispatcher(Dispatcher))
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
    (members ask 'Members).mapTo[String].map { line: String =>
      HttpResponse(status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(line)))
    }
  }
}