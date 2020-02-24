package demo

import java.lang.management.ManagementFactory
import java.net.NetworkInterface

import akka.actor.{Address, CoordinatedShutdown}
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{Cluster, ClusterSingleton, ClusterSingletonSettings, Join, SelfUp, SingletonActor, Subscribe, Unsubscribe}
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

  val Name = "domain"

  val BufferSize = 1 << 5

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

    //akka.persistence.journal.plugin

    //val confDir  = System.getenv("EXTRA_CONF_DIR")
    //System.setProperty("NODE_TYPE", "master")
    //java.lang.System.getenv().put("NODE_TYPE", "master")

    //println("Env: " + System.getenv().keySet().asScala.mkString(","))

    val nodeType = System.getenv("NODE_TYPE").trim

    val isMasterNode = nodeType equals "master"

    val shardName = System.getenv("SHARD").trim

    val sysProps = sys.props

    val port = sysProps
      .get(sysPropSeedPort)
      .fold(throw new Exception(s"Couldn't find $sysPropsSeedHost system property"))(identity)

    val seedHostAddress = sysProps
      .get(sysPropsSeedHost)
      .fold(throw new Exception(s"Couldn't find $sysPropSeedPort system property"))(identity)

    val httpPort = sysProps
      .get(sysPropsHttpPort)
      .fold(throw new Exception(s"Couldn't find $sysPropsHttpPort system property"))(identity)

    val hostAddress = sysProps.get(sysPropsHost)

    //val extraCfg = new File(s"$confDir/$nodeType.conf")
    val cfg = hostAddress.fold(
      //docker
      if (isMasterNode) createConfig(seedHostAddress, port, shardName)
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

    val address = Address("akka", SystemName, seedHostAddress, port.toInt)

    if (isMasterNode)
      ActorSystem[Nothing](
        master(cfg, address, shardName, runtimeInfo, httpPort.toInt),
        SystemName,
        cfg
      )
    else
      ActorSystem[Nothing](worker(cfg, address, shardName, runtimeInfo, httpPort.toInt), SystemName, cfg)
  }

  def worker(
    cfg: Config,
    seedAddress: Address,
    shardName: String,
    runtimeInfo: String,
    httpPort: Int
  ): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx ⇒
        val cluster = Cluster(ctx.system)
        cluster.manager tell Join(seedAddress)
        cluster.subscriptions tell Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive[SelfUp] {
          case (ctx, _ @SelfUp(state)) ⇒
            val av = state.members.filter(_.status == MemberStatus.Up).map(_.address)
            ctx.log.warn(
              "★ ★ ★ {} Worker {}:{} joined cluster with existing members:[{}] ★ ★ ★",
              shardName,
              cfg.getString(AKKA_HOST),
              cfg.getInt(AKKA_PORT),
              av
            )
            ctx.log.info(runtimeInfo)

            cluster.subscriptions ! Unsubscribe(ctx.self)
            val shutdown = CoordinatedShutdown(ctx.system.toClassic)

            val shardRegion = SharedDomain(shardName, ctx.system)

            val ring = ClusterSingleton(ctx.system).init(SingletonActor(RingMaster(), "ring"))

            //ctx.spawn(RingMaster(shardName), "ring", DispatcherSelector.fromConfig("akka.metrics-dispatcher"))

            val jvmMetrics = ctx
              .spawn(
                ClusterJvmMetrics(BufferSize),
                "jvm-metrics",
                DispatcherSelector.fromConfig("akka.metrics-dispatcher")
              )
              .narrow[ClusterJvmMetrics.Confirm]

            new Bootstrap(
              shutdown,
              ring,
              shardRegion,
              jvmMetrics,
              cluster.selfMember.address.host.get,
              httpPort
            )(ctx.system)

            ctx.spawn(
              ShardRegionProxy(
                shardRegion,
                shardName,
                cluster.selfMember.address.host
                  .flatMap(h ⇒ cluster.selfMember.address.port.map(p ⇒ s"$h-$p"))
                  .getOrElse("none")
              ),
              Name
            )

            Behaviors.receiveSignal {
              case (_, Terminated(ring)) ⇒
                ctx.log.error("Membership failure detected !!!")
                Behaviors.stopped
            }
        }
      }
      .narrow

  def master(
    config: Config,
    seedAddress: Address,
    shardName: String,
    runtimeInfo: String,
    httpPort: Int
  ): Behavior[Nothing] =
    Behaviors
      .setup[SelfUp] { ctx ⇒
        val cluster = Cluster(ctx.system)
        cluster.manager tell Join(seedAddress)
        cluster.subscriptions tell Subscribe(ctx.self, classOf[SelfUp])

        Behaviors.receive[SelfUp] {
          case (ctx, _ @SelfUp(_)) ⇒
            ctx.log.warn(
              "★ ★ ★ {} {} bytes Seed {}:{} joined cluster ★ ★ ★",
              shardName,
              config.getMemorySize("akka.remote.artery.advanced.maximum-frame-size"),
              config.getString(AKKA_HOST),
              config.getInt(AKKA_PORT)
            )

            ctx.log.info(runtimeInfo)

            cluster.subscriptions ! Unsubscribe(ctx.self)

            val shutdown = CoordinatedShutdown(ctx.system.toClassic)

            val shardRegion = SharedDomain(shardName, ctx.system)

            val ringMaster = ClusterSingleton(ctx.system).init(SingletonActor(RingMaster(), "ring-master"))
            ctx.watch(ringMaster)

            val jvmMetrics = ctx
              .spawn(
                ClusterJvmMetrics(BufferSize),
                "jvm-metrics",
                DispatcherSelector.fromConfig("akka.metrics-dispatcher")
              )
              .narrow[ClusterJvmMetrics.Confirm]

            new Bootstrap(
              shutdown,
              ringMaster,
              shardRegion,
              jvmMetrics,
              cluster.selfMember.address.host.get,
              httpPort
            )(ctx.system)

            ctx.spawn(
              ShardRegionProxy(
                shardRegion,
                shardName,
                cluster.selfMember.address.host
                  .flatMap(h ⇒ cluster.selfMember.address.port.map(p ⇒ s"$h-$p"))
                  .getOrElse("none")
              ),
              Name
            )

            Behaviors.receiveSignal[SelfUp] {
              case (_, Terminated(ringMaster)) ⇒
                ctx.log.error("Membership failure detected !!!")
                Behaviors.stopped
            }
        }
      }
      .narrow
}
