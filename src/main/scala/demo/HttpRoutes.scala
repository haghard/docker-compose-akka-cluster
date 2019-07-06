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
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.sharding.ShardRegion.{ClusterShardingStats, GetClusterShardingStats, ShardRegionQuery, ShardRegionStats}
import akka.http.scaladsl.model.StatusCodes.OK
import akka.stream.typed.scaladsl.ActorSource
import demo.hashing.Rendezvous

class HttpRoutes(
  membersRef: ActorRef[Membership.Ops],
  srcRef: ActorRef[ClusterJvmMetrics.Confirm]
  //sr: ActorRef[DeviceCommand]
  //h: Rendezvous[String]
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
    //path("members")(get(complete(queryForMembers))) ~
    //curl  http://192.168.77.10:9000/shards
    /*path("shards") {
      get {
        import akka.actor.typed.scaladsl.adapter._
        import akka.pattern.ask
        complete {
          (sr.toUntyped ? GetClusterShardingStats(2.seconds))
            .mapTo[ClusterShardingStats]
            .map(stats ⇒ "\n" + stats.regions.mkString("\n"))
        }
      }
    } ~*/
    path("device" / IntNumber) { deviceId ⇒
      get {
        //h.replicaFor(deviceId.toString, 1).head
        //sr.tell(PingDevice(deviceId))
        complete(OK)
      }
    } ~ path("metrics")(
      get(complete(HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, metricsSource))))
    )

  /*private def queryForMembers: Future[HttpResponse] =
    membersRef.ask[Membership.ClusterStateResponse](Membership.ClusterStateRequest(_)).map { reply ⇒
      HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(reply.line))
      )
    }*/

}
