package demo

import java.io.File
import java.net.NetworkInterface
import java.time.LocalDateTime
import java.util.TimeZone

import akka.actor.{Address, CoordinatedShutdown}
import akka.actor.CoordinatedShutdown.PhaseClusterExitingDone
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{Cluster, Join, SelfUp, Subscribe, Unsubscribe}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._
import akka.actor.typed.scaladsl.adapter._
import akka.stream.ActorMaterializerSettings

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

  def worker(config: Config): Behavior[SelfUp] =
    Behaviors.setup[SelfUp] { ctx ⇒
      implicit val sys = ctx.system.toUntyped
      val cluster      = Cluster(ctx.system)
      val seedAddress  = Address("akka", SystemName, seedHostAddress, port.toInt)
      cluster.manager tell Join(seedAddress)
      cluster.subscriptions tell Subscribe(ctx.self, classOf[SelfUp])

      Behaviors.receive { (ctx, _) ⇒
        ctx.log.info("★ ★ ★ Worker joined cluster {}:{} ★ ★ ★", cfg.getString(AKKA_HOST), cfg.getInt(AKKA_PORT))
        cluster.subscriptions ! Unsubscribe(ctx.self)
        val shutdown = CoordinatedShutdown(ctx.system.toUntyped)

        //ctx.spawn(ClusterMembership(), "cluster-members")

        shutdown.addTask(PhaseClusterExitingDone, "after.cluster-exiting-done") { () ⇒
          Future.successful(ctx.log.info("after.cluster-exiting-done")).map(_ ⇒ akka.Done)(ExecutionContext.global)
        }

        Behaviors.empty
      }
    }

  def seed(config: Config): Behavior[SelfUp] =
    Behaviors.setup[SelfUp] { ctx ⇒
      implicit val sys = ctx.system.toUntyped
      val cluster      = Cluster(ctx.system)
      val address = Address("akka", SystemName, seedHostAddress, port.toInt)

      cluster.manager tell Join(address)
      cluster.subscriptions tell Subscribe(ctx.self, classOf[SelfUp])

      Behaviors.receive[SelfUp] {
        case (ctx, _ @SelfUp(state)) ⇒
          ctx.log.info(
            "★ ★ ★ Seed joined cluster {}:{} ★ ★ ★",
            seedHostAddress,
            port.toInt)

          cluster.subscriptions ! Unsubscribe(ctx.self)
          val shutdown = CoordinatedShutdown(ctx.system.toUntyped)

          new Bootstrap(
            shutdown,
            ctx.spawn(
              ClusterMembership(state),
              "members",
              DispatcherSelector.fromConfig("akka.metrics-dispatcher")
            ),
            cluster.selfMember.address.host.get,
            httpPort.toInt
          )

          Behaviors.empty
      }
    }

  if (isSeedNode)
    ActorSystem(seed(cfg), SystemName, cfg)
  else
    ActorSystem(worker(cfg), SystemName, cfg)

}
