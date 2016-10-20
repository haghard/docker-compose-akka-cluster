package main

import akka.actor._
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.Await
import scala.util.{Failure, Success}

object Application extends App {
  /*
  import scala.collection.JavaConverters._
  val env = System.getenv().asScala
  */

  val SystemName = "docker-cluster"

  val seedHostPort = Option(System.getenv().get("akka.remote.netty.tcp.port"))
    .fold(throw new Exception("Couldn't find seedHostPort"))(identity)

  val seedHostName = Option(System.getenv().get("akka.remote.netty.tcp.hostname"))
    .fold(throw new Exception("Couldn't find seedHostName"))(identity)

  val isSeed = Option(System.getenv().get("isSeed")).map(_ => true).getOrElse(false)

  println(s"$seedHostPort - $seedHostName - $isSeed")

  val cfg = ConfigFactory.load()

  /*if (isSeed) {
    ConfigFactory.empty()
      //.withFallback(seeds)
      //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.bind-port=$port0"))
      //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.bind-hostname=$external"))
      .withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$seedHostPort"))
      //.withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.hostname=$hostName0"))
      .withFallback(ConfigFactory.load())
  } else ConfigFactory.load()*/


  implicit val system = ActorSystem(SystemName, cfg)
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext

  val cluster = Cluster(system)

  val seed = Address("akka.tcp", SystemName, seedHostName, seedHostPort.toInt)
  println("Join seed node: " + seed)
  cluster.joinSeedNodes(immutable.Seq(seed))

  if (isSeed) {
    Http().bindAndHandle(new HttpRoutes(seedHostName, cluster).route, interface = seedHostName, port = 9000).onComplete {
      case Success(r) =>
        println(s"http server available on ${r.localAddress}")
      case Failure(ex) =>
        println(ex.getMessage)
        System.exit(-1)
    }
  }

  sys.addShutdownHook {
    system.log.info("ShutdownHook")
    import scala.concurrent.duration._
    Await.ready(system.terminate, 5 seconds)
    cluster.leave(cluster.selfAddress)
  }
}

/**
 * seed-nodes = [
 * "akka.tcp://docker-cluster@seed-node:2551"
 * ]
 */