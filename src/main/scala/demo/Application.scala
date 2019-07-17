package demo

import java.io.File
import java.lang.management.ManagementFactory
import java.net.NetworkInterface

import akka.actor.{Address, CoordinatedShutdown}
import akka.actor.CoordinatedShutdown.PhaseClusterExitingDone
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{Cluster, Join, SelfUp, Subscribe, Unsubscribe}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.MemberStatus

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

  val isMasterNode = nodeType equals "master"

  val ipExpression = """\d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}"""

  val shardName = System.getenv("shard").trim

  val bufferSize = 1 << 5

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

  def createConfig(address: String): Config =
    ConfigFactory
      .empty()
      .withFallback(ConfigFactory.parseString(s"$AKKA_HOST=$address"))
      .withFallback(ConfigFactory.parseString(s"$AKKA_PORT=$port"))
      .withFallback(ConfigFactory.parseString(s"akka.cluster.roles = [ $shardName ]"))
      .withFallback(ConfigFactory.load())

  val extraCfg = new File(s"${confDir}/${nodeType}.conf")
  val cfg      = if (isMasterNode) createConfig(seedHostAddress) else createConfig(dockerInternalAddress.getHostAddress)

  val Name = "domain"

  def worker(config: Config, shardName: String, runtimeInfo: String): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx ⇒
        implicit val sys = ctx.system.toUntyped
        val cluster      = Cluster(ctx.system)
        val seedAddress  = Address("akka", SystemName, seedHostAddress, port.toInt)
        cluster.manager tell Join(seedAddress)
        cluster.subscriptions tell Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive[SelfUp] {
          case (ctx, _ @SelfUp(state)) ⇒
            val av = state.members.filter(_.status == MemberStatus.Up).map(_.address)
            ctx.log.warning(
              "★ ★ ★ {} Worker {}:{} joined cluster with existing members:[{}] ★ ★ ★",
              shardName,
              cfg.getString(AKKA_HOST),
              cfg.getInt(AKKA_PORT),
              av
            )
            ctx.log.info(runtimeInfo)

            cluster.subscriptions ! Unsubscribe(ctx.self)
            val shutdown = CoordinatedShutdown(ctx.system.toUntyped)

            val shardRegion =
              SharedDomain(shardName, ctx.system.toUntyped).toTyped[DeviceCommand]

            val membership =
              ctx.spawn(Membership(shardName), "members", DispatcherSelector.fromConfig("akka.metrics-dispatcher"))

            val hostAddress = cluster.selfMember.address.host
              .flatMap(h ⇒ cluster.selfMember.address.port.map(p ⇒ s"${h}-${p}"))
              .getOrElse("none")
            ctx.spawn(DomainReplicas(shardRegion, shardName, hostAddress), Name)

            shutdown.addTask(PhaseClusterExitingDone, "after.cluster-exiting-done") { () ⇒
              Future.successful(ctx.log.info("after.cluster-exiting-done")).map(_ ⇒ akka.Done)(ExecutionContext.global)
            }

            Behaviors.receiveSignal {
              case (_, Terminated(`membership`)) ⇒
                ctx.log.error("Membership failure detected !!!")
                Behaviors.stopped
            }
        }
      }
      .narrow

  def master(config: Config, shardName: String, runtimeInfo: String): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx ⇒
        implicit val sys = ctx.system.toUntyped
        val cluster      = Cluster(ctx.system)
        val address      = Address("akka", SystemName, seedHostAddress, port.toInt)

        cluster.manager tell Join(address)
        cluster.subscriptions tell Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive[SelfUp] {
          case (ctx, _ @SelfUp(_)) ⇒
            ctx.log.warning(
              "★ ★ ★ {} {} bytes Seed {}:{} joined cluster ★ ★ ★",
              shardName,
              config.getMemorySize("akka.remote.artery.advanced.maximum-frame-size"),
              seedHostAddress,
              port.toInt
            )
            ctx.log.info(runtimeInfo)

            cluster.subscriptions ! Unsubscribe(ctx.self)
            val shutdown = CoordinatedShutdown(ctx.system.toUntyped)

            val shardRegion =
              SharedDomain(shardName, ctx.system.toUntyped).toTyped[DeviceCommand]

            val membership =
              ctx.spawn(Membership(shardName), "members", DispatcherSelector.fromConfig("akka.metrics-dispatcher"))

            ctx.watch(membership)

            new Bootstrap(
              shutdown,
              membership,
              shardRegion,
              ctx
                .spawn(
                  ClusterJvmMetrics(bufferSize),
                  "jvm-metrics",
                  DispatcherSelector.fromConfig("akka.metrics-dispatcher")
                )
                .narrow[ClusterJvmMetrics.Confirm],
              cluster.selfMember.address.host.get,
              httpPort.toInt
            )

            //dockerInternalAddress.getHostAddress
            val hostAddress = cluster.selfMember.address.host
              .flatMap(h ⇒ cluster.selfMember.address.port.map(p ⇒ s"${h}-${p}"))
              .getOrElse("none")

            ctx.spawn(DomainReplicas(shardRegion, shardName, hostAddress), Name)

            Behaviors.receiveSignal {
              case (_, Terminated(`membership`)) ⇒
                ctx.log.error("Membership failure detected !!!")
                Behaviors.stopped
            }
        }
      }
      .narrow

  val memorySize = ManagementFactory.getOperatingSystemMXBean
    .asInstanceOf[com.sun.management.OperatingSystemMXBean]
    .getTotalPhysicalMemorySize
  val runtimeInfo = new StringBuilder()
    .append("=================================================================================================")
    .append('\n')
    .append(s"Cores:${Runtime.getRuntime.availableProcessors}")
    .append('\n')
    .append(" Total Memory:" + Runtime.getRuntime.totalMemory / 1000000 + "Mb")
    .append('\n')
    .append(" Max Memory:" + Runtime.getRuntime.maxMemory / 1000000 + "Mb")
    .append('\n')
    .append(" Free Memory:" + Runtime.getRuntime.freeMemory / 1000000 + "Mb")
    .append('\n')
    .append(" RAM:" + memorySize / 1000000 + "Mb")
    .append('\n')
    .append("=================================================================================================")
    .toString()

  if (isMasterNode)
    ActorSystem[Nothing](master(cfg, shardName, runtimeInfo), SystemName, cfg)
  else
    ActorSystem[Nothing](worker(cfg, shardName, runtimeInfo), SystemName, cfg)

}
