package demo

import Application._
import akka.actor.typed.ActorRef
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Route, _}
import akka.stream.scaladsl._
import akka.stream._
import akka.util.ByteString
import demo.ReplicatedShardCoordinator.Ping

import scala.concurrent.duration._
import scala.concurrent.Future
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.model.StatusCodes.OK
import akka.stream.typed.scaladsl.ActorSource
import akka.cluster.sharding.ShardRegion.{ClusterShardingStats, GetClusterShardingStats}
import akka.http.scaladsl.model.ws.{Message, TextMessage}

class HttpRoutes(
  membership: ActorRef[ReplicatedShardCoordinator.Command],
  jvmMetricsSrc: ActorRef[ClusterJvmMetrics.Confirm],
  shardRegion: ActorRef[DeviceCommand]
)(implicit sys: ActorSystem)
    extends Directives {

  val folderName  = "d3"
  val circlePage  = "view.html"
  val circlePage1 = "view1.html"

  val DispatcherName = "akka.metrics-dispatcher"

  implicit val t   = akka.util.Timeout(1.seconds)
  implicit val sch = sys.scheduler
  implicit val ec  = sys.dispatchers.lookup(DispatcherName)

  implicit val mat = ActorMaterializer(
    ActorMaterializerSettings(sys)
      .withDispatcher(DispatcherName)
      .withInputBuffer(BufferSize, BufferSize)
  )

  val (ref, metricsSource) =
    ActorSource
      .actorRefWithAck[ClusterJvmMetrics.JvmMetrics, ClusterJvmMetrics.Confirm](
        jvmMetricsSrc,
        ClusterJvmMetrics.Confirm, {
          case ClusterJvmMetrics.Completed            ⇒ CompletionStrategy.immediately
        }, { case ClusterJvmMetrics.StreamFailure(ex) ⇒ ex }
      )
      .collect { case ClusterJvmMetrics.ClusterMetrics(bt) ⇒ bt }
      .toMat(BroadcastHub.sink[ByteString](BufferSize))(Keep.both) //one to many
      .run()

  jvmMetricsSrc.tell(ClusterJvmMetrics.Connect(ref))

  //Ensure that the Broadcast output is dropped if there are no listening parties.
  metricsSource.runWith(Sink.ignore)

  def flowWithHeartbeat(
    hbMsg: TextMessage = TextMessage("hb"),
    d: FiniteDuration = 30.second
  ): Flow[Message, Message, akka.NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val heartbeats = b.add(Source.tick(d, d, hbMsg))
        val merge      = b.add(MergePreferred[Message](1, eagerComplete = true))
        heartbeats ~> merge.in(0)
        FlowShape(merge.preferred, merge.out)
      }
    )

  val cropCircleRoute =
    path("circle")(get(encodeResponse(getFromFile(folderName + "/" + circlePage)))) ~
    path("circle1")(get(encodeResponse(getFromFile(folderName + "/" + circlePage1)))) ~
    pathPrefix("d3" / Remaining)(file ⇒ encodeResponse(getFromFile(folderName + "/" + file))) ~
    path("events") {
      handleWebSocketMessages(
        flowWithHeartbeat().mapAsync(1) {
          case TextMessage.Strict(_) ⇒
            membership
              .ask[ReplicatedShardCoordinator.CropCircleView](ReplicatedShardCoordinator.GetCropCircle(_))
              .map(r ⇒ TextMessage.Strict(r.json))
          case other ⇒
            throw new Exception(s"Unexpected message ${other} !!!")
        }
        /*flowWithHeartbeat().collect {
          case TextMessage.Strict(cmd) ⇒
            //log.info("ws cmd: {}", cmd)
            tree.setMemberType("akka://dc@172.20.0.3:2551", "http-server")
            TextMessage.Strict(tree.toJson)
        }*/
      )
    }

  val route: Route =
    path("members")(get(complete(queryForMembers))) ~
    path("shards") {
      get {
        import akka.actor.typed.scaladsl.adapter._
        import akka.pattern.ask
        complete {
          (shardRegion.toUntyped ? GetClusterShardingStats(2.seconds))
            .mapTo[ClusterShardingStats]
            .map(stats ⇒ "\n" + stats.regions.mkString("\n"))
        }
      }
    } ~
    path("device" / LongNumber) { deviceId ⇒
      get {
        membership.tell(Ping(deviceId))
        complete(OK)
      }
    } ~ path("metrics")(
      get(complete(HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, metricsSource))))
    ) ~ cropCircleRoute

  private def queryForMembers: Future[HttpResponse] =
    membership
      .ask[ReplicatedShardCoordinator.ClusterStateResponse](ReplicatedShardCoordinator.ClusterStateRequest(_))
      .map { reply ⇒
        HttpResponse(
          status = StatusCodes.OK,
          entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(reply.state))
        )
      }
}
