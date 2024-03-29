package demo

import akka.stream._
import Application._
import akka.actor.typed.{ActorRef, ActorSystem, DispatcherSelector}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Route, _}
import akka.stream.scaladsl._
import akka.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.Future
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.model.StatusCodes.{BadRequest, OK}
import akka.stream.typed.scaladsl.ActorSource
import akka.cluster.sharding.ShardRegion.ClusterShardingStats
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import ExtraHttpDirectives._

import scala.util.{Failure, Success}

object HttpRoutes {

  case class CropCircleView(json: String)
}

case class HttpRoutes(
  ringMaster: ActorRef[RingMaster.Command],
  jvmMetricsSrc: ActorRef[ClusterJvmMetrics.Confirm],
  shardName: String
)(implicit sys: ActorSystem[Nothing])
    extends Directives {

  val folderName  = "d3"
  val circlePage  = "view.html"
  val circlePage1 = "view1.html"

  implicit val timeout = akka.util.Timeout(2.seconds)
  implicit val ec      = sys.dispatchers.lookup(DispatcherSelector.fromConfig(metricsDispatcherName))

  val (ref, metricsSource) =
    ActorSource
      .actorRefWithBackpressure[ClusterJvmMetrics.JvmMetrics, ClusterJvmMetrics.Confirm](
        jvmMetricsSrc,
        ClusterJvmMetrics.Confirm,
        { case ClusterJvmMetrics.Completed => CompletionStrategy.immediately },
        { case ClusterJvmMetrics.StreamFailure(ex) => ex }
      )
      .collect { case ClusterJvmMetrics.ClusterMetrics(bt) => bt }
      .toMat(BroadcastHub.sink[ByteString](1 << 3))(Keep.both) // one to many
      .run()

  jvmMetricsSrc.tell(ClusterJvmMetrics.Connect(ref))

  // Ensure that the Broadcast output is dropped if there are no listening parties.
  metricsSource.runWith(Sink.ignore)

  def flowWithHeartbeat(
    hbMsg: TextMessage = TextMessage("hb"),
    d: FiniteDuration = 30.second
  ): Flow[Message, Message, akka.NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() { implicit b =>
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
        .fromIterator(() =>
          Iterator.range(1, 64).map(created(_, 1)) ++ Iterator.range(1, 32).filter(_ % 2 == 0).map(delete(_))
            ++ Iterator.range(1, 32).filter(_ % 2 == 0).map(created(_, 1))
        )
        .zipWith(Source.tick(0.second, 100.millis, ()))((a, _) => a)
    )
  }

  val chord =
    extractLog { log =>
      path("chord") {
        get {
          log.info("GET chord")
          encodeResponse(getFromFile(folderName + "/" + "ring.html"))
        }
      }
    } ~
      path("ring-events")(handleWebSocketMessages(ringFlow()))
  // http://192.168.77.10:9000/rng

  def cropCircleRoute =
    path("view")(get(encodeResponse(getFromFile(folderName + "/" + circlePage)))) ~
      path("view1")(get(encodeResponse(getFromFile(folderName + "/" + circlePage1)))) ~
      pathPrefix("d3" / Remaining)(file => encodeResponse(getFromFile(folderName + "/" + file))) ~
      path("events") {
        handleWebSocketMessages(
          flowWithHeartbeat()
            .flatMapConcat(
              _.asTextMessage.getStreamedText.fold("")(_ + _)
            ) // the web socket spec says that a single msg over web socket can be streamed (multiple chunks)
            .mapAsync(1) { _ =>
              ringMaster
                .ask[HttpRoutes.CropCircleView](RingMaster.GetCropCircle(_))
                .map(r => TextMessage.Strict(r.json))
            }
        )
      }

  val pingRoute = extractLog { implicit log =>
    aroundRequest(logLatency(log)) {
      path("device" / LongNumber) { deviceId =>
        onComplete(ringMaster.ask[RingMaster.PingDeviceReply](RingMaster.PingReq(deviceId, _))) {
          case Failure(err) => complete(BadRequest -> err)
          case Success(_)   => complete(OK)
        }
      }
    }
  }

  def route: Route =
    path("ring")(get(complete(queryRing))) ~
      path("shards") {
        get {
          complete {
            ClusterSharding(sys).shardState
              .ask[ClusterShardingStats](
                akka.cluster.sharding.typed.GetClusterShardingStats(DeviceDigitalTwin.entityKey, timeout.duration, _)
              )
              .map(stats => shardName + "\n" + stats.regions.mkString("\n"))
          }
        }
      } ~
      pingRoute ~ chord ~ path("metrics")(
        get(
          complete(HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, metricsSource)))
        )
      ) ~ cropCircleRoute ~ akka.management.cluster.scaladsl.ClusterHttpManagementRoutes(akka.cluster.Cluster(sys))

  def queryRing: Future[HttpResponse] =
    ringMaster
      .ask[RingMaster.ClusterStateResponse](RingMaster.ClusterStateRequest(_))
      .map { reply =>
        HttpResponse(
          status = StatusCodes.OK,
          entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(reply.state))
        )
      }
}
