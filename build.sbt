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

assemblyJarName in assembly := s"elastic-cluster-${version}.jar"


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
