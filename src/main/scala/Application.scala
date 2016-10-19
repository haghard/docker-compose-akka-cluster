package main

import akka.actor._
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer

import scala.util.{Failure, Success}

object Application extends App {
  implicit val system = ActorSystem("elastic-cluster")
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext

  val host = system.settings.config.getString("akka.remote.netty.tcp.hostname")
  val port = system.settings.config.getInt("akka.remote.netty.tcp.port")

  println(s"HOST:PORT - $host:$port")

  val cluster = system.actorOf(Props[ClusterMembershipSupport])

  Http().bindAndHandle(new SimpleRoute(cluster, host).route, interface = host, port = 9000).onComplete {
    case Success(r) =>
      println(s"http server available on ${r.localAddress}")
    case Failure(ex) =>
      println(ex.getMessage)
      System.exit(-1)
  }

  sys.addShutdownHook(system.terminate())
}