package main

import akka.actor._
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.util.{Failure, Success}

object Application extends App {

  println("Args:" + args.mkString(","))
  println(s"HOST:PORT - $host:$port")

  import scala.collection.JavaConverters._

  val env = System.getenv().asScala

  println("ENV \n" + env.mkString("\n"))

  /*
   -Dakka.remote.netty.tcp.hostname=seed-node
   -Dakka.remote.netty.tcp.port=2551"
   -Dakka.remote.netty.tcp.bind-hostname=seed-node
   */

  val cfg = ConfigFactory.load()

  println("CONFIG: \n" + cfg.root().render())

  implicit val system = ActorSystem("elastic-cluster", cfg)
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext

  val host = system.settings.config.getString("akka.remote.netty.tcp.hostname")
  val bindHostname = system.settings.config.getString("akka.remote.netty.tcp.bind-hostname")
  val port = system.settings.config.getInt("akka.remote.netty.tcp.port")

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