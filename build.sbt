import com.typesafe.sbt.packager.docker._


scalaVersion := "2.11.8"

name := "docker-cluster"

enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)


val Akka = "2.4.11"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % Akka,
  "com.typesafe.akka" %% "akka-cluster" % Akka,
  "com.typesafe.akka" %% "akka-http-experimental" % Akka,
  "com.typesafe.akka" %% "akka-cluster-metrics"   % Akka,
  "com.typesafe.akka" %% "akka-stream"            % Akka,
  "io.spray"          %% "spray-json"             % "1.3.2"
)


//docker rm $(docker ps -a -q)
//docker-compose up -d
//sbt docker:publishLocal     # to create the Docker images
//docker-compose up -d        # to start the seed and the first node
//docker-compose scale node=5 # to scale up the number of nodes
//docker inspect -f '{{.Name}} - {{.NetworkSettings.IPAddress }}' $(docker ps -aq)
//docker inspect -f '{{.Name}} - {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(docker ps -aq)

//https://github.com/muller/docker-compose-akka-cluster


//docker-compose up -d
//docker-compose scale node=2