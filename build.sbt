import com.typesafe.sbt.packager.docker._


scalaVersion := "2.11.8"

name := "docker-cluster"

enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)


val Akka = "2.4.11"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % Akka,
  "com.typesafe.akka" %% "akka-cluster" % Akka,
  "com.typesafe.akka" %% "akka-http-experimental" %   Akka,
  "com.typesafe.akka" %% "akka-cluster-metrics"   %   Akka,
  "com.typesafe.akka" %% "akka-stream"            %   Akka,
  "com.typesafe.akka" %% "akka-slf4j"             %   Akka,
  "io.spray"          %% "spray-json"             %   "1.3.2",
  "ch.qos.logback"    %  "logback-classic"        %   "1.1.2"
)
