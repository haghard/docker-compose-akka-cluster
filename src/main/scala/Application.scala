package main

import akka.actor._
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.util.{Failure, Success}

object Application extends App {
  println("Args:" + args.mkString(","))

  import scala.collection.JavaConverters._
  //val env = System.getenv().asScala

  val external = "192.168.0.3"

  val hostName0 = System.getenv().get("akka.remote.netty.tcp.hostname")
  val port0 = System.getenv().get("akka.remote.netty.tcp.port")

  println(s"ENV $hostName0:$port0")

  val Sys = "elastic-cluster"

  val seedNodesString = List(hostName0 + ":" + port0).map { node =>
    val ap = node.split(":")
    s"""akka.cluster.seed-nodes += "akka.tcp://$Sys@${ap(0)}:${ap(1)}"""
  }.mkString("\n")


  val seeds = (ConfigFactory parseString seedNodesString).resolve()

  val cfg = ConfigFactory.empty().withFallback(seeds)
    //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.bind-port=$port"))
    //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.bind-hostname=$external"))
    .withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port0"))
    .withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.hostname=$hostName0"))
    .withFallback(ConfigFactory.load())


  println("Remote: \n" + cfg.getConfig("akka.remote.netty.tcp").root().render)

  println("Cluster: \n" + cfg.getConfig("akka.cluster").root().render)

  implicit val system = ActorSystem("elastic-cluster", cfg)
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext

  //val host = system.settings.config.getString("akka.remote.netty.tcp.hostname")
  //val bindHostname = system.settings.config.getString("akka.remote.netty.tcp.bind-hostname")
  //val port = system.settings.config.getInt("akka.remote.netty.tcp.port")

  val cluster = system.actorOf(Props[ClusterMembershipSupport])

  Http().bindAndHandle(new SimpleRoute(cluster, hostName0).route, interface = hostName0, port = 9000).onComplete {
    case Success(r) =>
      println(s"http server available on ${r.localAddress}")
    case Failure(ex) =>
      println(ex.getMessage)
      System.exit(-1)
  }

  sys.addShutdownHook(system.terminate())
}