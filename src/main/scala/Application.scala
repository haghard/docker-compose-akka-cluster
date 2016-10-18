package main

import akka.actor._
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer

import scala.util.{Failure, Success}

object Application extends App {
  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext
  val host = system.settings.config.getString("akka.remote.netty.tcp.hostname")

  println(s"HOST: $host")
  Http().bindAndHandle(new SimpleRoute(host).route, interface = host, port = 9000).onComplete {
    case Success(r) =>
    case Failure(ex) =>
      println(ex.getMessage)
      System.exit(-1)
  }
  system.actorOf(Props[ClusterMembershipSupport])
  sys.addShutdownHook(system.terminate())
}