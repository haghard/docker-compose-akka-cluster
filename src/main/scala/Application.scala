package main

import akka.actor._
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.util.{Failure, Success}
import scala.collection.JavaConverters._
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

  val cfg = ConfigFactory.empty()
    .withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port"))
    .withFallback(ConfigFactory.load())

  implicit val system = ActorSystem(SystemName, cfg)
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext


  val cluster = Cluster(system)

  if(isSeed) {
    val add = cluster.selfAddress
    println(s"seed join to $add")
    cluster.joinSeedNodes(immutable.Seq(add))
  } else {
    val seed = System.getenv().get("akka.cluster.seed")
    val add = Address("akka.tcp", SystemName, seed, port.toInt)
    println(s"node join to $add")
    cluster.joinSeedNodes(immutable.Seq(add))
  }

  cfg.getStringList("akka.cluster.seed-nodes").asScala.foreach(println(_))
  println(s"hostname: ${cfg.getString(AKKA_HOST)} port: ${cfg.getInt(AKKA_PORT)} ")

  val members = system.actorOf(Props[ClusterMembershipSupport], "cluster-support")
  if(isSeed) {
    Http().bindAndHandle(new SimpleRoute(members, hostName).route, interface = hostName, port = 9000).onComplete {
      case Success(r) =>
        println(s"http server available on ${r.localAddress}")
      case Failure(ex) =>
        println(ex.getMessage)
        System.exit(-1)
    }
  }

  sys.addShutdownHook(system.terminate())
}

/*
seed-nodes = [
      "akka.tcp://docker-cluster@seed-node:2551"
    ]
*/