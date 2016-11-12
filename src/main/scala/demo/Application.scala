package demo

import akka.actor._
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Application extends App {
  val SystemName = "docker-cluster"
  val defaultNetwork = "0.0.0.0"

  val AKKA_PORT = "akka.remote.netty.tcp.port"
  val AKKA_HOST = "akka.remote.netty.tcp.hostname"

  val port = sys.env.get(AKKA_PORT).fold(throw new Exception(s"Couldn't find $AKKA_PORT system property"))(identity)
  val hostName = sys.env.get(AKKA_HOST).getOrElse(defaultNetwork)
  val seedNode = hostName ne (defaultNetwork)

  val cfg = {
    val overrideConfig = if (seedNode) {
      ConfigFactory.empty()
        .withFallback(ConfigFactory.parseString(s"$AKKA_HOST=$hostName"))
        .withFallback(ConfigFactory.parseString(s"$AKKA_PORT=$port"))
    } else {
      ConfigFactory.empty()
        .withFallback(ConfigFactory.parseString(s"$AKKA_PORT=$port"))
    }
    overrideConfig.withFallback(ConfigFactory.load())
  }

  implicit val system = ActorSystem(SystemName, cfg)
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext

  val cluster = Cluster(system)

  if (seedNode) {
    val add = Address("akka.tcp", SystemName, hostName, port.toInt)
    system.log.info("seed node is joining to itself {}", add)
    cluster.joinSeedNodes(immutable.Seq(add))
    //println(System.getProperty("java.rmi.server.hostname"))
    system.log.info("JMX port {}", System.getProperty("com.sun.management.jmxremote.port"))

    Http().bindAndHandle(new HttpRoutes(cluster).route, interface = cluster.selfAddress.host.get, port = 9000)
      .onComplete {
        case Success(r) =>
          system.log.info("http server available on {}", r.localAddress)
        case Failure(ex) =>
          system.log.error(ex, "")
          System.exit(-1)
      }
  } else {
    val seed = sys.env.get("akka.cluster.seed").fold(throw new Exception("Couldn't find akka.cluster.seed system property"))(identity)
    val add = Address("akka.tcp", SystemName, seed, port.toInt)
    system.log.info(s"regular node is joining to seed {}", add)
    cluster.joinSeedNodes(immutable.Seq(add))
  }

  system.log.info(s"* * * * hostname: ${cfg.getString(AKKA_HOST)} port: ${cfg.getInt(AKKA_PORT)} * * * *")

  sys.addShutdownHook {
    Await.ready(system.terminate, 5 seconds)
    system.log.info("Node {} has been removed from the cluster", cluster.selfAddress)
    cluster.leave(cluster.selfAddress)
  }
}