package demo

import java.io.File
import java.net.{NetworkInterface, InetSocketAddress}

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
  val SystemName = "dc-cluster"

  val AKKA_PORT = "akka.remote.artery.canonical.port"
  val AKKA_HOST = "akka.remote.artery.canonical.hostname"

  val sysPropSeedPort  = "seedPort"
  val sysPropsSeedHost = "seedHost"
  val sysPropsHttpPort = "httpPort"

  val confDir = System.getenv("EXTRA_CONF_DIR")
  val nodeType = System.getenv("node.type").trim

  val isSeedNode = nodeType equals "seed"

  val port = sys.props.get(sysPropSeedPort).fold(throw new Exception(s"Couldn't find $sysPropsSeedHost system property"))(identity)
  val seedHostName = sys.props.get(sysPropsSeedHost).fold(throw new Exception(s"Couldn't find $sysPropSeedPort system property"))(identity)
  val httpPort = sys.props.get(sysPropsHttpPort).fold(throw new Exception(s"Couldn't find $sysPropsHttpPort system property"))(identity)

  private def createConfig(address: String) = {
    ConfigFactory.empty()
      .withFallback(ConfigFactory.parseString(s"$AKKA_HOST=$address"))
      .withFallback(ConfigFactory.parseString(s"$AKKA_PORT=$port"))
      .withFallback(ConfigFactory.load())
  }

  import scala.collection.JavaConverters._
  val ipExpression = """\d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}"""

  val dockerInternalAddress = NetworkInterface.getByName("eth0").getInetAddresses.asScala
    .find(_.getHostAddress.matches(ipExpression))
    .fold(throw new Exception("Couldn't find docker address"))(identity)

  val cfg = if (isSeedNode) createConfig(seedHostName) else createConfig(dockerInternalAddress.getHostAddress)
  val extraCfg = new File(s"${confDir}/${nodeType}.conf")

  implicit val system = ActorSystem(SystemName, cfg)
  implicit val mat = ActorMaterializer()
  implicit val _ = mat.executionContext

  val cluster = Cluster(system)
  val log = system.log

  if (isSeedNode) {
    log.info("locate seed-node.conf: {}", extraCfg.exists)
    val address = Address("akka", SystemName, seedHostName, port.toInt)
    log.info("seed-node is being joined to itself {}", address)
    cluster.joinSeedNodes(immutable.Seq(address))

    Http().bindAndHandle(new HttpRoutes(cluster).route, interface = cluster.selfAddress.host.get, port = httpPort.toInt)
      .onComplete {
        case Success(r) =>
          val jmxPort = sys.props.get("com.sun.management.jmxremote.port")
          log.info(s"* * * http-server:${r.localAddress} host:${cfg.getString(AKKA_HOST)} akka-port:${cfg.getInt(AKKA_PORT)} JMX-port:$jmxPort * * *")
        case Failure(ex) =>
          system.log.error(ex, "Couldn't bind http server")
          System.exit(-1)
      }
  } else {
    log.info("worker-node.conf exists:{}", extraCfg.exists)
    val seedAddress = Address("akka", SystemName, seedHostName, port.toInt)
    cluster.joinSeedNodes(immutable.Seq(seedAddress))
    system.log.info(s"* * * host:${cfg.getString(AKKA_HOST)} akka-port:${cfg.getInt(AKKA_PORT)} * * *")
  }

  sys.addShutdownHook {
    Await.ready(system.terminate, 5 seconds)
    system.log.info("Node {} has been removed from the cluster", cluster.selfAddress)
    cluster.leave(cluster.selfAddress)
  }
}