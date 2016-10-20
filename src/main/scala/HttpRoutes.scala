package main

import akka.actor.{ActorSystem, ActorRef}
import akka.cluster.Cluster
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Route, _}
import akka.stream.{ActorMaterializerSettings, ActorMaterializer}
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.util.ByteString

import scala.concurrent.{ExecutionContext, Future}

import akka.pattern.ask
import scala.concurrent.duration._

class HttpRoutes(listener: ActorRef, host: String, cluster: Cluster)
  (implicit ex: ExecutionContext, system: ActorSystem) extends Directives {

  implicit val _ = akka.util.Timeout(5 seconds)

  implicit val _ = ActorMaterializer(
      ActorMaterializerSettings(system)
        .withDispatcher("akka.metrics-dispatcher")
        .withInputBuffer(1, 1))

  val route = route1 ~ route2

  //This allows us to have just one source actor and many subscribers
  val metricsSource =
    Source.actorPublisher[ByteString](ClusterMetrics.props(cluster).withDispatcher("akka.metrics-dispatcher"))
      .toMat(BroadcastHub.sink(bufferSize = 32))(Keep.right).run()

  //Ensure that the Broadcast output is dropped if there are no listening parties.
  metricsSource.runWith(Sink.ignore)

  val route1: Route =
    path("members") {
      get {
        complete(queryForMembers)
      }
    }

  val route2: Route =
    path("metrics") {
      get {
        complete {
          HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, metricsSource))
        }
      }
    }

  private def queryForMembers: Future[HttpResponse] = {
    (listener ask 'Members).mapTo[String].map { line: String =>
      HttpResponse(status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(line)))
    }
  }
}