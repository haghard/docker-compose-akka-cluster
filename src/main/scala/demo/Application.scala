package demo

import java.lang.management.ManagementFactory
import java.net.NetworkInterface

import akka.actor.{Address, CoordinatedShutdown}
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{Cluster, ClusterSingleton, Join, SelfUp, SingletonActor, Subscribe, Unsubscribe}
import com.typesafe.config.{Config, ConfigFactory}

import scala.jdk.CollectionConverters._
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

object Application extends Ops {

  val SystemName            = "dc"
  val metricsDispatcherName = "akka.metrics-dispatcher"

  val AKKA_PORT = "akka.remote.artery.canonical.port"
  val AKKA_HOST = "akka.remote.artery.canonical.hostname"

  val sysPropSeedPort  = "seedPort"
  val sysPropsSeedHost = "seedHost"

  val sysPropsHost     = "host"
  val sysPropsHttpPort = "httpPort"

  val ipExpression = """\d{1,3}.\d{1,3}.\d{1,3}.\d{1,3}"""

  def createConfig(host: String, port: String, shardName: String): Config =
    ConfigFactory
      .empty()
      .withFallback(ConfigFactory.parseString(s"$AKKA_HOST=$host"))
      .withFallback(ConfigFactory.parseString(s"$AKKA_PORT=$port"))
      .withFallback(ConfigFactory.parseString(s"akka.cluster.roles = [ $shardName ]"))
      .withFallback(ConfigFactory.parseString(s"akka.management.cluster.http.host=$host"))
      .withFallback(ConfigFactory.parseString(s"akka.management.cluster.http.port=$port"))
      .withFallback(ConfigFactory.load())

  def main(args: Array[String]): Unit = {
    val opts: Map[String, String] = argsToOpts(args.toList)
    applySystemProperties(opts)

    //val confDir  = System.getenv("EXTRA_CONF_DIR")
    //System.setProperty("NODE_TYPE", "master")
    //java.lang.System.getenv().put("NODE_TYPE", "master")
    //println("Env: " + System.getenv().keySet().asScala.mkString(","))

    val nodeType  = sys.env.get("NODE_TYPE").getOrElse(throw new Exception("env var NODE_TYPE is expected"))
    val shardName = sys.env.get("SHARD").getOrElse(throw new Exception("env var SHARD is expected"))

    val port = sys.props
      .get(sysPropSeedPort)
      .fold(throw new Exception(s"Couldn't find $sysPropsSeedHost system property"))(identity)

    val seedHostAddress = sys.props
      .get(sysPropsSeedHost)
      .fold(throw new Exception(s"Couldn't find $sysPropSeedPort system property"))(identity)

    val httpPort = sys.props
      .get(sysPropsHttpPort)
      .fold(throw new Exception(s"Couldn't find $sysPropsHttpPort system property"))(identity)

    val hostAddress = sys.props.get(sysPropsHost)

    val cfg = hostAddress.fold(
      //docker
      if (nodeType == "seed") createConfig(seedHostAddress, port, shardName)
      else {
        val dockerInternalAddress = NetworkInterface
          .getByName("eth0")
          .getInetAddresses
          .asScala
          .find(_.getHostAddress.matches(ipExpression))
          .fold(throw new Exception("Couldn't find docker address"))(identity)
        createConfig(dockerInternalAddress.getHostAddress, port, shardName)
      }
    ) {
      createConfig(_, port, shardName)
    }

    val memorySize = ManagementFactory.getOperatingSystemMXBean
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
      .getTotalPhysicalMemorySize
    val runtimeInfo = new StringBuilder()
      .append("=================================================================================================")
      .append('\n')
      .append(s"★ ★ ★ Cores:${Runtime.getRuntime.availableProcessors}")
      .append('\n')
      .append("★ ★ ★  Total Memory:" + Runtime.getRuntime.totalMemory / 1000000 + "Mb")
      .append('\n')
      .append("★ ★ ★ Max Memory:" + Runtime.getRuntime.maxMemory / 1000000 + "Mb")
      .append('\n')
      .append("★ ★ ★ Free Memory:" + Runtime.getRuntime.freeMemory / 1000000 + "Mb")
      .append('\n')
      .append("★ ★ ★ RAM:" + memorySize / 1000000 + "Mb")
      .append('\n')
      .append("=================================================================================================")
      .toString()

    ActorSystem[Nothing](
      start(cfg, Address("akka", SystemName, seedHostAddress, port.toInt), shardName, runtimeInfo, httpPort.toInt),
      SystemName,
      cfg
    )
  }

  def start(
    cfg: Config,
    seedAddress: Address,
    shardName: String,
    runtimeInfo: String,
    httpPort: Int
  ): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx ⇒
        implicit val classicSystem = ctx.system.toClassic

        val cluster = Cluster(ctx.system)
        cluster.manager tell Join(seedAddress)
        cluster.subscriptions tell Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive[SelfUp] {
          case (ctx, _ @SelfUp(state)) ⇒
            val clusterMembers = state.members.filter(_.status == MemberStatus.Up).map(_.address)
            ctx.log.warn(
              "★ ★ ★ {} {}:{} joined cluster with existing members:[{}] ★ ★ ★",
              shardName,
              cfg.getString(AKKA_HOST),
              cfg.getInt(AKKA_PORT),
              clusterMembers
            )
            ctx.log.info(runtimeInfo)
            cluster.subscriptions ! Unsubscribe(ctx.self)

            val ringMaster = ClusterSingleton(ctx.system)
              .init(
                SingletonActor(
                  Behaviors
                    .supervise(RingMaster())
                    .onFailure[Exception](SupervisorStrategy.resume.withLoggingEnabled(true)),
                  "ring-master"
                ).withStopMessage(RingMaster.Shutdown)
              )

            val jvmMetrics = ctx
              .spawn(
                ClusterJvmMetrics(),
                "jvm-metrics",
                DispatcherSelector.fromConfig("akka.metrics-dispatcher")
              )
              .narrow[ClusterJvmMetrics.Confirm]

            val hostName     = cluster.selfMember.address.host.get
            val shardManager = ctx.spawn(ShardManager(shardName, hostName), "shard-manager")

            ctx.watch(shardManager)

            Bootstrap(shardName, ringMaster, jvmMetrics, hostName, httpPort)

            Behaviors.receiveSignal {
              case (_, Terminated(shardManager)) ⇒
                ctx.log.error(s"Proxy $shardManager has failed/stopped. Shutting down...")
                CoordinatedShutdown(ctx.system.toClassic).run(Bootstrap.CriticalError)
                Behaviors.same
            }
        }
      }
      .narrow
}
