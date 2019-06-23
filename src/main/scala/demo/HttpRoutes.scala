package demo

import akka.actor.typed.ActorRef
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Route, _}
import akka.stream.scaladsl._
import akka.stream._
import akka.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.Future
import akka.cluster.ClusterEvent.ClusterDomainEvent
import akka.actor.typed.scaladsl.AskPattern._
import akka.stream.typed.scaladsl.ActorSource

class HttpRoutes(
  metricsRef: ActorRef[ClusterDomainEvent],
  srcRef: ActorRef[ClusterJvmMetrics.Confirm]
)(implicit sys: ActorSystem)
    extends Directives {
  val DispatcherName = "akka.metrics-dispatcher"

  implicit val t   = akka.util.Timeout(1.seconds)
  implicit val sch = sys.scheduler
  implicit val ec  = sys.dispatchers.lookup(DispatcherName)

  val bufferSize = 1 << 4

  implicit val mat = ActorMaterializer(
    ActorMaterializerSettings(sys).withDispatcher(DispatcherName).withInputBuffer(bufferSize, bufferSize)
  )

  val (ref, metricsSource) =
    ActorSource
      .actorRefWithAck[ClusterJvmMetrics.JvmMetrics, ClusterJvmMetrics.Confirm](srcRef, ClusterJvmMetrics.Confirm, {
        case ClusterJvmMetrics.Completed            ⇒ CompletionStrategy.immediately
      }, { case ClusterJvmMetrics.StreamFailure(ex) ⇒ ex })
      .collect { case ClusterJvmMetrics.ClusterMetrics(bt) ⇒ bt }
      .toMat(BroadcastHub.sink[ByteString](bufferSize))(Keep.both)
      .run()

  srcRef.tell(ClusterJvmMetrics.Connect(ref))

  //Ensure that the Broadcast output is dropped if there are no listening parties.
  metricsSource.runWith(Sink.ignore)

  val route: Route =
    path("members")(get(complete(queryForMembers))) ~
    path("metrics")(
      get(complete(HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, metricsSource))))
    )

  private def queryForMembers: Future[HttpResponse] =
    metricsRef.ask[Membership.ClusterState](Membership.GetClusterState(_)).map { reply ⇒
      HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(reply.line))
      )
    }

}
