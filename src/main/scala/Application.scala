package main

import akka.actor._
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.util.{Failure, Success}
import scala.collection.JavaConverters._

object Application extends App {
  //val external = "192.168.0.3"

  val SystemName = "docker-cluster"

  val port = Option(System.getenv().get("akka.remote.netty.tcp.port"))
    .fold(throw new Exception("Couldn't lookup akka.remote.netty.tcp.port from env"))(identity)

  val hostName = Option(System.getenv().get("akka.remote.netty.tcp.hostname")).getOrElse("0.0.0.0")


  val cfg = ConfigFactory.empty()
    .withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port"))
    .withFallback(ConfigFactory.load())
/*

    if(hostName0 == "seed-node") {
    ConfigFactory.empty()
      //.withFallback(seeds)
      //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.bind-port=$port0"))
      //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.bind-hostname=$external"))
      .withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port0"))
      //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.hostname=$hostName0"))
      .withFallback(ConfigFactory.load())
  } else ConfigFactory.load()
*/

  implicit val system = ActorSystem(SystemName, cfg)
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext

  import scala.collection.JavaConverters._
  cfg.getStringList("akka.cluster.seed-nodes").asScala.foreach(println(_))

  //println("hostname: " + cfg.getString("akka.remote.netty.tcp.hostname") + " port: " + cfg.getInt("akka.remote.netty.tcp.port"))

  val cluster = Cluster(system)

  if(!hostName.startsWith("0")) {
    //seed
    val add = cluster.selfAddress
    println(s"Seed join to $add")
    cluster.joinSeedNodes(immutable.Seq(add))
  } else {
    //node
    val seed = System.getenv().get("akka.cluster.seed")
    val add = Address("akka.tcp", SystemName, seed, port.toInt)
    println(s"Node join to $add")
    cluster.joinSeedNodes(immutable.Seq(add))
  }

  /*val members = system.actorOf(Props[ClusterMembershipSupport], "cluster-support")
  if(hostName0 == "seed-node") {
    Http().bindAndHandle(new SimpleRoute(members, hostName0).route, interface = hostName0, port = 9000).onComplete {
      case Success(r) =>
        println(s"http server available on ${r.localAddress}")
      case Failure(ex) =>
        println(ex.getMessage)
        System.exit(-1)
    }
  }*/

  sys.addShutdownHook(system.terminate())
}

/*
seed-nodes = [
      "akka.tcp://docker-cluster@seed-node:2551"
    ]
*/