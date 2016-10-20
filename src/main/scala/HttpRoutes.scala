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

class HttpRoutes(cluster: Cluster)
  (implicit ex: ExecutionContext, system: ActorSystem) extends Directives {

  val Dispatcher = "akka.metrics-dispatcher"

  implicit val _ = akka.util.Timeout(5 seconds)

  implicit val mat = ActorMaterializer(
      ActorMaterializerSettings(system)
        .withDispatcher(Dispatcher)
        .withInputBuffer(8, 8))

  //val members = system.actorOf(ClusterMembershipSupport.props(cluster), "cluster-support")

  //This allows us to have just one source actor and many subscribers
  val metricsSource =
    Source.actorPublisher[ByteString](ClusterMetrics.props(cluster).withDispatcher(Dispatcher))
      .toMat(BroadcastHub.sink(bufferSize = 32))(Keep.right).run()

  //Ensure that the Broadcast output is dropped if there are no listening parties.
  metricsSource.runWith(Sink.ignore)


  val route: Route =
    /*path("members") {
      get {
        complete(queryForMembers)
      }
    } ~*/
    path("metrics") {
      get {
        complete {
          HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, metricsSource))
        }
      }
    }

  /*private def queryForMembers: Future[HttpResponse] = {
    (members ask 'Members).mapTo[String].map { line: String =>
      HttpResponse(status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(line)))
    }
  }*/
}