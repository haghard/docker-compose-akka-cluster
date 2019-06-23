package demo

import akka.actor.typed.ActorRef
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
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.ClusterEvent.ClusterDomainEvent
import akka.actor.typed.scaladsl.AskPattern._

class HttpRoutes(m: ActorRef[ClusterDomainEvent])(implicit sys: ActorSystem) extends Directives {
  //val cluster = Cluster(system)

  implicit val t   = akka.util.Timeout(1.seconds)
  implicit val sch = sys.scheduler
  implicit val ec  = sys.dispatchers.lookup("akka.metrics-dispatcher")

  /*implicit val mat = ActorMaterializer(
    ActorMaterializerSettings(system).withDispatcher(Dispatcher).withInputBuffer(1, 1)
  )*/

  /*val metricsSource =
    Source
      .fromGraph(
        new ActorSource[ByteString](
          system.actorOf(ClusterJvmMetrics.props(cluster).withDispatcher(Dispatcher), "jvm-metrics")
        )
      )
      .toMat(BroadcastHub.sink(bufferSize = 1 << 4))(Keep.right)
      .run()

  //Ensure that the Broadcast output is dropped if there are no listening parties.
  metricsSource.runWith(Sink.ignore)*/

  val route: Route =
    path("members")(get(complete(queryForMembers)))
  //path("metrics")(get(complete(HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, metricsSource)))))

  private def queryForMembers: Future[HttpResponse] =
    m.ask[ClusterMembership.ClusterState](ClusterMembership.GetClusterState(_)).map { reply ⇒
      HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(reply.line))
      )
    }

}
