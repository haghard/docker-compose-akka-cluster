package demo

import akka.stream._
import Application._
import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Route, _}
import akka.stream.scaladsl._
import akka.util.ByteString
import demo.RingMaster.Ping

import scala.concurrent.duration._
import scala.concurrent.Future
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.model.StatusCodes.OK
import akka.stream.typed.scaladsl.ActorSource
import akka.cluster.sharding.ShardRegion.{ClusterShardingStats, GetClusterShardingStats}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes

class HttpRoutes(
  membership: ActorRef[RingMaster.Command],
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
        ClusterJvmMetrics.Confirm, { case ClusterJvmMetrics.Completed ⇒ CompletionStrategy.immediately },
        { case ClusterJvmMetrics.StreamFailure(ex)                    ⇒ ex }
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

  def ringFlow(): Flow[Message, Message, akka.NotUsed] = {

    def created(nodeId: Int, successorId: Int) =
      TextMessage.Strict(s"""{"type":"NodeCreated","nodeId":$nodeId,"successorId":$successorId}""")

    def delete(nodeId: Int) =
      TextMessage.Strict(s"""{"type":"NodeDeleted","nodeId":$nodeId}""")

    Flow.fromSinkAndSource[Message, Message](
      Sink.ignore,
      Source
        .fromIterator(
          () ⇒
            Iterator.range(1, 64).map(created(_, 1)) ++ Iterator.range(1, 32).filter(_ % 2 == 0).map(delete(_))
            ++ Iterator.range(1, 32).filter(_                                          % 2 == 0).map(created(_, 1))
        )
        .zipWith(Source.tick(0.second, 500.millis, ()))((a, _) ⇒ a)
    )
  }

  val ringRoute = path("rng")(get(encodeResponse(getFromFile(folderName + "/" + "ring.html")))) ~
    path("ring-events")(handleWebSocketMessages(ringFlow())) //http://192.168.77.10:9000/rng

  val cropCircleRoute =
    path("view")(get(encodeResponse(getFromFile(folderName + "/" + circlePage)))) ~
    path("view1")(get(encodeResponse(getFromFile(folderName + "/" + circlePage1)))) ~
    pathPrefix("d3" / Remaining)(file ⇒ encodeResponse(getFromFile(folderName + "/" + file))) ~
    path("events") {
      handleWebSocketMessages(
        flowWithHeartbeat().mapAsync(1) {
          case TextMessage.Strict(_) ⇒
            membership
              .ask[RingMaster.CropCircleView](RingMaster.GetCropCircle(_))
              .map(r ⇒ TextMessage.Strict(r.json))
          case other ⇒
            throw new Exception(s"Unexpected message $other !!!")
        }
        /*flowWithHeartbeat().collect {
          case TextMessage.Strict(cmd) ⇒
            //log.info("ws cmd: {}", cmd)
            tree.setMemberType("akka://dc@172.20.0.3:2551", "http-server")
            TextMessage.Strict(tree.toJson)
        }*/
      )
    }

  import ExtraHttpDirectives._

  val pingRoute = extractLog { implicit log ⇒
    aroundRequest(logLatency(log)) {
      path("device" / LongNumber) { deviceId ⇒
        get {
          membership.tell(Ping(deviceId))
          complete(OK)
        }
      }
    }
  }

  val route: Route =
    path("ring")(get(complete(queryForMembers))) ~
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
    } ~ pingRoute ~ path("metrics")(
      get(complete(HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, metricsSource))))
    ) ~ ClusterHttpManagementRoutes(akka.cluster.Cluster(sys)) ~ cropCircleRoute

  def queryForMembers: Future[HttpResponse] =
    membership
      .ask[RingMaster.ClusterStateResponse](RingMaster.ClusterStateRequest(_))
      .map { reply ⇒
        HttpResponse(
          status = StatusCodes.OK,
          entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(reply.state))
        )
      }
}
