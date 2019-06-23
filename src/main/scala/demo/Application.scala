package demo

import java.io.File
import java.net.NetworkInterface
import java.time.LocalDateTime
import java.util.TimeZone

import akka.actor.CoordinatedShutdown.PhaseClusterExitingDone
import akka.actor._
import akka.cluster.Cluster
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._

/*
-Duser.timezone=UTC
TimeZone.setDefault(TimeZone.getTimeZone("UTC"))


Instant.now
java.util.TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
//val tz = java.util.TimeZone.getDefault.getID
LocalDateTime.now()
 */

object Application extends App {
  val SystemName = "dc-cluster"

  val AKKA_PORT = "akka.remote.artery.canonical.port"
  val AKKA_HOST = "akka.remote.artery.canonical.hostname"

  val sysPropSeedPort  = "seedPort"
  val sysPropsSeedHost = "seedHost"
  val sysPropsHttpPort = "httpPort"

  val confDir  = System.getenv("EXTRA_CONF_DIR")
  val nodeType = System.getenv("node.type").trim

  val isSeedNode = nodeType equals "seed"

  val ipExpression = """\d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}"""

  val port = sys.props
    .get(sysPropSeedPort)
    .fold(throw new Exception(s"Couldn't find $sysPropsSeedHost system property"))(identity)
  val seedHostAddress = sys.props
    .get(sysPropsSeedHost)
    .fold(throw new Exception(s"Couldn't find $sysPropSeedPort system property"))(identity)
  val httpPort = sys.props
    .get(sysPropsHttpPort)
    .fold(throw new Exception(s"Couldn't find $sysPropsHttpPort system property"))(identity)

  val dockerInternalAddress = NetworkInterface
    .getByName("eth0")
    .getInetAddresses
    .asScala
    .find(_.getHostAddress.matches(ipExpression))
    .fold(throw new Exception("Couldn't find docker address"))(identity)

  def createConfig(address: String) =
    ConfigFactory
      .empty()
      .withFallback(ConfigFactory.parseString(s"$AKKA_HOST=$address"))
      .withFallback(ConfigFactory.parseString(s"$AKKA_PORT=$port"))
      .withFallback(ConfigFactory.load())

  val extraCfg = new File(s"${confDir}/${nodeType}.conf")
  val cfg      = if (isSeedNode) createConfig(seedHostAddress) else createConfig(dockerInternalAddress.getHostAddress)

  implicit val system = ActorSystem(SystemName, cfg)

  val cluster  = Cluster(system)
  val log      = system.log
  val shutdown = CoordinatedShutdown(system)

  //TimeZone.getAvailableIDs

  if (isSeedNode) {
    val address = Address("akka", SystemName, seedHostAddress, port.toInt)
    cluster.joinSeedNodes(immutable.Seq(address))
    new Bootstrap(shutdown, cluster.selfAddress.host.get, httpPort.toInt)
  } else {
    val seedAddress = Address("akka", SystemName, seedHostAddress, port.toInt)
    cluster.joinSeedNodes(immutable.Seq(seedAddress))
    system.log.info(
      s"* * * host:${cfg.getString(AKKA_HOST)} akka-port:${cfg.getInt(AKKA_PORT)} at ${LocalDateTime.now} * * *"
    )

    shutdown.addTask(PhaseClusterExitingDone, "after.cluster-exiting-done") { () ⇒
      Future.successful(log.info("after.cluster-exiting-done")).map(_ ⇒ akka.Done)(ExecutionContext.global)
    }
  }
}
