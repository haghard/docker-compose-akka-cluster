import _root_.sbtdocker.DockerPlugin.autoImport._
import sbt._
import sbtdocker.ImageName

val scalaV = "2.12.6"
val Akka = "2.5.14"

val Version = "0.2"

name := "docker-cluster"
version := Version
scalacOptions in(Compile, console) := Seq("-feature", "-Xfatal-warnings", "-deprecation", "-unchecked")
scalaVersion := scalaV

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % Akka,
  "com.typesafe.akka" %% "akka-cluster" % Akka,
  "com.typesafe.akka" %% "akka-cluster-metrics" % Akka,
  "com.typesafe.akka" %% "akka-stream" % Akka,
  "com.typesafe.akka" %% "akka-slf4j" % Akka,
  "com.typesafe.akka" %% "akka-http" % "10.1.3",
  "io.spray" %% "spray-json" % "1.3.2",
  "ch.qos.logback" % "logback-classic" % "1.1.2"
)

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

  val seedConfigSrc = baseDir / "src" / "main" / "resources" / "seed.conf"
  val workerConfigSrc = baseDir / "src" / "main" / "resources" / "worker.conf"


  val seedConfigTarget = s"${imageAppBaseDir}/${configDir}/seed.conf"
  val workerConfigTarget = s"${imageAppBaseDir}/${configDir}/worker.conf"

  new sbtdocker.mutable.Dockerfile {
    from("openjdk:8-jre")
    maintainer("haghard")

    env("VERSION", Version)
    env("EXTRA_CONF_DIR", s"$imageAppBaseDir/$configDir")

    workDir(imageAppBaseDir)
    runRaw("ls -la")

    copy(artifact, artifactTargetPath)
    copy(seedConfigSrc, seedConfigTarget)
    copy(workerConfigSrc, workerConfigTarget)

    entryPoint("java", "-server", "-Xmx512m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=400", "-XX:+UseStringDeduplication", "-XX:ConcGCThreads=4", "-XX:ParallelGCThreads=4",
      s"-Djava.rmi.server.hostname=${System.getenv("HOST")}",
      s"-Dcom.sun.management.jmxremote.port=${System.getenv("SEED_JMX_PORT")}",
      s"-Dcom.sun.management.jmxremote.ssl=false",
      s"-Dcom.sun.management.jmxremote.authenticate=false",
      s"-Dcom.sun.management.jmxremote.local.only=false",
      s"-Dcom.sun.management.jmxremote.rmi.port=${System.getenv("SEED_JMX_PORT")}",
      s"-Dcom.sun.management.jmxremote=true",
      s"-DseedHost=${System.getenv("SEED_NODE")}",
      s"-DseedPort=${System.getenv("AKKA_PORT")}",
      s"-DhttpPort=${System.getenv("HTTP_PORT")}",
      s"-Duser.timezone=${System.getenv("TZ")}",
      "-jar", artifactTargetPath)
  }
}