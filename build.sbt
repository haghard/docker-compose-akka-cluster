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


//docker rm $(docker ps -a -q)
//docker-compose up -d
//sbt docker:publishLocal     # to create the Docker images
//docker-compose up -d        # to start the seed and the first node
//docker-compose scale node=5 # to scale up the number of nodes
//docker inspect -f '{{.Name}} - {{.NetworkSettings.IPAddress }}' $(docker ps -aq)
//docker inspect -f '{{.Name}} - {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(docker ps -aq)

//https://github.com/muller/docker-compose-akka-cluster