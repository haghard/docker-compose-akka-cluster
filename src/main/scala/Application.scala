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

  val hostPort = Option(System.getenv().get("akka.remote.netty.tcp.port"))
    .fold(throw new Exception("Couldn't find seedHostPort"))(identity)


  val hostName = Option(System.getenv().get("akka.remote.netty.tcp.hostname")).filter(_.length>0)

  val seedHost = Option(System.getenv().get("seed.tcp.hostname")).filter(_.length>0)

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

  /*val seed = if(hostName.isDefined) Address("akka.tcp", SystemName, hostName.get, hostPort.toInt)
  else Address("akka.tcp", SystemName, seedHost.get, hostPort.toInt)*/

  val cluster = Cluster(system)

  println(s"$hostPort - $hostName - ${seedHost}")

  if(seedHost.isEmpty) {
    println("****Join self seed node: " + cluster.selfAddress)
    cluster.join(cluster.selfAddress)
  } else {
    val seed = Address("akka.tcp", SystemName, seedHost.get, hostPort.toInt)
    println("****Join seed node: " + cluster.selfAddress)
    cluster.join(seed)
  }


  seedHost.fold(
    Http().bindAndHandle(new HttpRoutes(hostName.get, cluster).route, interface = hostName.get, port = 9000).onComplete {
      case Success(r) =>
        println(s"http server available on ${r.localAddress}")
      case Failure(ex) =>
        println(ex.getMessage)
        System.exit(-1)
    }) { _ => () }

  sys.addShutdownHook {
    system.log.info("ShutdownHook")
    import scala.concurrent.duration._
    Await.ready(system.terminate, 5 seconds)
    cluster.leave(cluster.selfAddress)
  }
}

/**
 * seed-nodes = [
 *  akka.tcp://docker-cluster@seed-node:2551
 *  akka.tcp://docker-cluster@seed-node:2551
 *  akka.tcp://docker-cluster@seed-node:2551
 * ]
 */