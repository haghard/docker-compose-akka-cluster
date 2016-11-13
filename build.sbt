import _root_.sbtdocker.DockerPlugin.autoImport._
import sbt._
import sbtdocker.ImageName


scalaVersion := "2.11.8"

name := "docker-cluster"

version := "0.1"

val Akka = "2.4.11"


enablePlugins(sbtdocker.DockerPlugin)
enablePlugins(JavaAppPackaging)


mainClass in assembly := Some("demo.Application")

assemblyJarName in assembly := s"docker-cluster-${version}.jar"

// Resolve duplicates for Sbt Assembly
assemblyMergeStrategy in assembly := {
  case PathList(xs@_*) if xs.last == "io.netty.versions.properties" => MergeStrategy.rename
  case other => (assemblyMergeStrategy in assembly).value(other)
}

imageNames in docker := Seq(ImageName(namespace = Some("haghard"), repository = "docker-cluster", tag = Some(version)))

buildOptions in docker := BuildOptions(cache = false,
  removeIntermediateContainers = BuildOptions.Remove.Always,
  pullBaseImage = BuildOptions.Pull.Always)

dockerfile in docker := {
  val baseDir = baseDirectory.value
  val artifact: File = assembly.value

  val imageAppBaseDir = "/app"
  val configDir = "conf"
  val artifactTargetPath = s"$imageAppBaseDir/${artifact.name}"
  val artifactTargetPath_ln = s"$imageAppBaseDir/${name.value}.jar"

  new sbtdocker.mutable.Dockerfile {
    from("openjdk:8-jre")
    maintainer("haghard")
    runRaw("echo $JAVA_OPTS")
  }
}


libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % Akka,
  "com.typesafe.akka" %% "akka-cluster" % Akka,
  "com.typesafe.akka" %% "akka-http-experimental" %   Akka,
  "com.typesafe.akka" %% "akka-cluster-metrics"   %   Akka,
  "com.typesafe.akka" %% "akka-stream"            %   Akka,
  "com.typesafe.akka" %% "akka-slf4j"             %   Akka,
  "io.spray"          %% "spray-json"             %   "1.3.2",
  "ch.qos.logback"    %  "logback-classic"        %   "1.1.2",
  "com.typesafe.akka" %% "akka-stream-kafka"      %   "0.13"
)