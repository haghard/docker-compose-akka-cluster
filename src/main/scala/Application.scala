package main

import akka.actor._
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.Await
import scala.util.{Failure, Success}
import scala.collection.JavaConverters._

object Application extends App {
  val SystemName = "docker-cluster"
  val default = "0.0.0.0"
  val AKKA_PORT = "akka.remote.netty.tcp.port"
  val AKKA_HOST = "akka.remote.netty.tcp.hostname"

  val port = Option(System.getenv().get(AKKA_PORT))
      .fold(throw new Exception(s"Couldn't lookup $AKKA_PORT from env"))(identity)

  val hostName = Option(System.getenv().get(AKKA_HOST)).getOrElse(default)
  val isSeed = !hostName.startsWith("0")

  val cfg = if(isSeed) {
    ConfigFactory.empty()
      .withFallback(ConfigFactory.parseString(s"$AKKA_HOST=$hostName"))
      .withFallback(ConfigFactory.parseString(s"$AKKA_PORT=$port"))
      .withFallback(ConfigFactory.load())
  } else {
    ConfigFactory.empty()
      .withFallback(ConfigFactory.parseString(s"$AKKA_PORT=$port"))
      .withFallback(ConfigFactory.load())
  }

  implicit val system = ActorSystem(SystemName, cfg)
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext

  val cluster = Cluster(system)

  if(isSeed) {
    //val add = cluster.selfAddress
    val add = Address("akka.tcp", SystemName, hostName, port.toInt)
    println(s"seed node is joining to itself $add")
    cluster.joinSeedNodes(immutable.Seq(add))

    Http().bindAndHandle(new HttpRoutes(cluster).route, interface = cluster.selfAddress.host.get, port = 9000)
      .onComplete {
        case Success(r) =>
          println(s"http server available on ${r.localAddress}")
        case Failure(ex) =>
          println(ex.getMessage)
          System.exit(-1)
      }
  } else {
    val seed = System.getenv().get("akka.cluster.seed")
    val add = Address("akka.tcp", SystemName, seed, port.toInt)
    println(s"regular node is joining to seed $add")
    cluster.joinSeedNodes(immutable.Seq(add))
  }

  cfg.getStringList("akka.cluster.seed-nodes").asScala.foreach(println(_))
  println(s"hostname: ${cfg.getString(AKKA_HOST)} port: ${cfg.getInt(AKKA_PORT)} ")


  sys.addShutdownHook {
    system.log.info("ShutdownHook")
    import scala.concurrent.duration._
    Await.ready(system.terminate, 5 seconds)
    cluster.leave(cluster.selfAddress)
  }
}