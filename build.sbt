import _root_.sbtdocker.DockerPlugin.autoImport._
import sbt._
import sbtdocker.ImageName

val scalaV = "2.11.8"
val Akka = "2.4.11"

val Version = "0.2"

//lazy val root = project.in(file(".")).settings(
name := "docker-cluster"
version := Version
scalacOptions in(Compile, console) := Seq("-feature", "-Xfatal-warnings", "-deprecation", "-unchecked")
scalaVersion := scalaV

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % Akka,
  "com.typesafe.akka" %% "akka-cluster" % Akka,
  "com.typesafe.akka" %% "akka-http-experimental" % Akka,
  "com.typesafe.akka" %% "akka-cluster-metrics" % Akka,
  "com.typesafe.akka" %% "akka-stream" % Akka,
  "com.typesafe.akka" %% "akka-slf4j" % Akka,
  "io.spray" %% "spray-json" % "1.3.2",
  "ch.qos.logback" % "logback-classic" % "1.1.2",
  "com.typesafe.akka" %% "akka-stream-kafka" % "0.13"
)

enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)

mainClass in assembly := Some("demo.Application")

assemblyJarName in assembly := s"${name.value}-${version.value}.jar"

// Resolve duplicates for Sbt Assembly
assemblyMergeStrategy in assembly := {
  case PathList(xs @ _*) if xs.last == "io.netty.versions.properties" => MergeStrategy.rename
  case other => (assemblyMergeStrategy in assembly).value(other)
}

imageNames in docker := Seq(ImageName(namespace = Some("haghard"), repository = "docker-cluster", tag = Some(version.value)))

buildOptions in docker := BuildOptions(cache = false,
  removeIntermediateContainers = BuildOptions.Remove.Always,
  pullBaseImage = BuildOptions.Pull.Always)

dockerfile in docker := {
  val baseDir = baseDirectory.value
  val artifact: File = assembly.value

  val imageAppBaseDir = "/app"
  val configDir = "conf"

  val artifactTargetPath = s"$imageAppBaseDir/${artifact.name}"
  //val artifactTargetPath_ln = s"$imageAppBaseDir/${name.value}.jar"

  val seedConfigSrc = baseDir / "src" / "resources" / "seed-node.conf"
  val workerConfigSrc = baseDir / "src" / "resources" / "worker-node.conf"


  val seedConfigTarget = s"${imageAppBaseDir}/${configDir}/seed-node.conf"
  val workerConfigTarget = s"${imageAppBaseDir}/${configDir}/worker-node.conf"

  new sbtdocker.mutable.Dockerfile {
    from("openjdk:8-jre")
    maintainer("haghard")

    env("VERSION", Version)
    workDir(imageAppBaseDir)
    runRaw("ls -la")

    runRaw("echo artifact.Path " + artifact.absolutePath)
    runRaw("echo " + artifact.exists)
    runRaw("echo artifactTargetPath " + artifactTargetPath)

    runRaw(s"echo cp ${artifact.absolutePath} $artifactTargetPath")

    add(artifact, artifactTargetPath)
    runRaw("echo artifact added")
    //copy(artifact, artifactTargetPath)

    add(seedConfigSrc, seedConfigTarget)
    runRaw("echo seed config added")
    add(workerConfigSrc, workerConfigTarget)
    runRaw("echo worker config added")

    runRaw("ls -la")

  }
}

//).enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)
