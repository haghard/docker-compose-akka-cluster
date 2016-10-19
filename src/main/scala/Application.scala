package main

import akka.actor._
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.util.{Failure, Success}

object Application extends App {
  println("Args:" + args.mkString(","))

  /*
  import scala.collection.JavaConverters._
  val env = System.getenv().asScala
  */

  //val external = "192.168.0.3"

  val SystemName = "elastic-cluster"

  val port0 = System.getenv().get("akka.remote.netty.tcp.port")
  val hostName0 = Option(System.getenv().get("akka.remote.netty.tcp.hostname")).getOrElse("0.0.0.0")

  val cfg = if(hostName0 == "seed-node") {
    ConfigFactory.empty()
      //.withFallback(seeds)
      //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.bind-port=$port0"))
      //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.bind-hostname=$external"))
      .withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port0"))
      //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.hostname=$hostName0"))
      .withFallback(ConfigFactory.load())
  } else ConfigFactory.load()


  implicit val system = ActorSystem("docker-cluster", cfg)
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext

  val cluster = system.actorOf(Props[ClusterMembershipSupport], "cluster-support")

  Http().bindAndHandle(new SimpleRoute(cluster, hostName0).route, interface = hostName0, port = 9000).onComplete {
    case Success(r) =>
      println(s"http server available on ${r.localAddress}")
    case Failure(ex) =>
      println(ex.getMessage)
      System.exit(-1)
  }

  sys.addShutdownHook(system.terminate())
}