import com.typesafe.sbt.packager.docker._


enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)

scalaVersion := "2.11.8"

val Akka = "2.4.11"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % Akka,
  "com.typesafe.akka" %% "akka-cluster" % Akka,
  "com.typesafe.akka" %% "akka-http-experimental" % Akka
)
