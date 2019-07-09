import _root_.sbtdocker.DockerPlugin.autoImport._
import sbt._
import sbtdocker.ImageName

val scalaV = "2.13.0"
val Akka = "2.5.23" //"2.6.0-M4"
val akkaHttpVersion = "10.1.8"

val Version = "0.3"

name := "docker-cluster"
version := Version
scalacOptions in(Compile, console) := Seq("-feature", "-Xfatal-warnings", "-deprecation", "-unchecked")
scalaVersion := scalaV

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-cluster-typed" % Akka,
  "com.typesafe.akka" %% "akka-cluster-metrics" % Akka,
  "com.typesafe.akka" %% "akka-stream-typed" % Akka,
  "com.typesafe.akka" %% "akka-stream-contrib" % "0.10",
  "com.typesafe.akka" %% "akka-cluster-sharding" % Akka,
  //"com.typesafe.akka" %% "akka-persistence-cassandra" % "0.98",
  //"com.typesafe.akka" %% "akka-cluster-sharding-typed" % Akka,
  "com.typesafe.akka" %% "akka-slf4j" % Akka,

  //local build for 2.13 /Users/haghard/.ivy2/local/com.github.TanUkkii007/akka-cluster-custom-downing_2.13/0.0.13-SNAPSHOT/jars/akka-cluster-custom-downing_2.13.jar
  "com.github.TanUkkii007" %% "akka-cluster-custom-downing" % "0.0.13-SNAPSHOT",
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  ("com.lihaoyi" % "ammonite" % "1.6.8" % "test").cross(CrossVersion.full)
)

//test:run
sourceGenerators in Test += Def.task {
  val file = (sourceManaged in Test).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

scalafmtOnCompile := true

enablePlugins(sbtdocker.DockerPlugin)

mainClass in assembly := Some("demo.Application")

assemblyJarName in assembly := s"${name.value}-${version.value}.jar"

// Resolve duplicates for Sbt Assembly
assemblyMergeStrategy in assembly := {
  case PathList(xs @ _*) if xs.last == "io.netty.versions.properties" => MergeStrategy.rename
  case other => (assemblyMergeStrategy in assembly).value(other)
}

imageNames in docker := Seq(ImageName(namespace = Some("haghard"), repository = name.value, tag = Some(version.value)))

buildOptions in docker := BuildOptions(cache = false,
  removeIntermediateContainers = BuildOptions.Remove.Always,
  pullBaseImage = BuildOptions.Pull.Always)

dockerfile in docker := {
  val baseDir = baseDirectory.value
  val artifact: File = assembly.value

  val imageAppBaseDir = "/app"
  val configDir = "conf"

  val artifactTargetPath = s"$imageAppBaseDir/${artifact.name}"

  val seedConfigSrc = baseDir / "src" / "main" / "resources" / "master.conf"
  val workerConfigSrc = baseDir / "src" / "main" / "resources" / "worker.conf"


  val seedConfigTarget = s"${imageAppBaseDir}/${configDir}/master.conf"
  val workerConfigTarget = s"${imageAppBaseDir}/${configDir}/worker.conf"

  new sbtdocker.mutable.Dockerfile {
    //from("openjdk:8-jre")
    //from("adoptopenjdk/openjdk11:jdk-11.0.1.13")
    //from("adoptopenjdk/openjdk8:x86_64-alpine-jdk8u192-b12")
    from("adoptopenjdk/openjdk12")

    maintainer("haghard")

    env("VERSION", Version)
    env("EXTRA_CONF_DIR", s"$imageAppBaseDir/$configDir")

    workDir(imageAppBaseDir)
    runRaw("ls -la")

    copy(artifact, artifactTargetPath)
    copy(seedConfigSrc, seedConfigTarget)
    copy(workerConfigSrc, workerConfigTarget)

    //https://docs.docker.com/compose/compose-file/#resources
    entryPoint("java", "-server", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=400", "-XX:ConcGCThreads=2", "-XX:ParallelGCThreads=2",
      //"-XX:+PrintFlagsFinal",
      "-XshowSettings",
      "-XX:MaxRAMFraction=1",
      "-XX:+PreferContainerQuotaForCPUCount", //Added in JDK11. Support for using the cpu_quota instead of cpu_shares for
      // picking the number of cores the JVM uses to makes decisions such as how many compiler theads, GC threads and sizing of the fork join pool
      s"-Djava.rmi.server.hostname=${System.getenv("HOST")}",
      s"-Dcom.sun.management.jmxremote.port=${System.getenv("SEED_JMX_PORT")}",
      s"-Dcom.sun.management.jmxremote.ssl=false",
      s"-Dcom.sun.management.jmxremote.authenticate=false",
      s"-Dcom.sun.management.jmxremote.local.only=false",
      s"-Dcom.sun.management.jmxremote.rmi.port=${System.getenv("SEED_JMX_PORT")}",
      s"-Dcom.sun.management.jmxremote=true",
      s"-DseedHost=${System.getenv("MASTER_DNS")}",
      s"-DseedPort=${System.getenv("AKKA_PORT")}",
      s"-DhttpPort=${System.getenv("HTTP_PORT")}",
      s"-Duser.timezone=${System.getenv("TZ")}",
      "-jar", artifactTargetPath)
  }
}