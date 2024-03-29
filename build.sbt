import sbt._
import sbtdocker.ImageName

val scalaV = "2.13.10"
val Akka   = "2.6.20"
val AkkaManagement = "1.1.4"

val akkaHttpVersion = "10.2.9"

val Version = "0.4"

name := "docker-cluster"
version := Version

//scalacOptions in (Compile, console) := Seq("-feature", "-Xfatal-warnings", "-deprecation", "-unchecked")

val scalacOps = Seq(
  "-Xsource:3",
  "-target:14",
  //"-deprecation", // Emit warning and location for usages of deprecated APIs.
  "-unchecked",   // Enable additional warnings where generated code depends on assumptions.
  "-encoding", "UTF-8", // Specify character encoding used by source files.
  "-Ywarn-dead-code",                  // Warn when dead code is identified.
  "-Ywarn-extra-implicit",             // Warn when more than one implicit parameter section is defined.
  "-Ywarn-numeric-widen",              // Warn when numerics are widened.
  "-Ywarn-unused:implicits",           // Warn if an implicit parameter is unused.
  "-Ywarn-unused:imports",             // Warn if an import selector is not referenced.
  "-Ywarn-unused:locals",              // Warn if a local definition is unused.
  "-Ywarn-unused:params",              // Warn if a value parameter is unused.
  "-Ywarn-unused:patvars",             // Warn if a variable bound in a pattern is unused.
  "-Ywarn-unused:privates",            // Warn if a private member is unused.
  "-Ywarn-value-discard"              // Warn when non-Unit expression results are unused.
)

Compile / scalacOptions := scalacOps
console / scalacOptions := scalacOps

scalaVersion := scalaV

libraryDependencies ++= Seq(
  "com.typesafe.akka"       %% "akka-cluster-metrics"        % Akka,
  "com.typesafe.akka"       %% "akka-cluster-typed"          % Akka,
  "com.typesafe.akka"       %% "akka-stream-typed"           % Akka,
  "com.typesafe.akka"       %% "akka-cluster-sharding-typed" % Akka,
  "com.typesafe.akka"       %% "akka-distributed-data"       % Akka,
  "io.moia"                 %% "streamee"                    % "5.0.0",
  "com.github.pureconfig"   %% "pureconfig"                  % "0.17.1",

  "com.typesafe.akka"             %% "akka-discovery"                    % Akka,
  //"com.lightbend.akka.discovery"  %% "akka-discovery-kubernetes-api"     % AkkaManagement,
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagement,
  "com.lightbend.akka.management" %% "akka-management-cluster-http"      % AkkaManagement,


  //"com.typesafe.akka" %% "akka-persistence-cassandra" % "1.0.3",
  //"com.typesafe.akka" %% "akka-stream-contrib" % "0.10",

  "com.typesafe.akka"      %% "akka-slf4j"               % Akka,
  "org.scala-lang.modules" %% "scala-collection-contrib" % "0.2.2",

  "com.typesafe.akka"      %% "akka-http"                % akkaHttpVersion,
  "com.typesafe.akka"      %% "akka-http-spray-json"     % akkaHttpVersion,

  "ch.qos.logback"         % "logback-classic"           % "1.4.4",

  "com.lihaoyi" % "ammonite" % "2.5.5" % "test" cross CrossVersion.full
)

//test:run
Test / sourceGenerators  += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

scalafmtOnCompile := true


assembly / mainClass := Some("demo.Application")

assembly / assemblyJarName := s"${name.value}-${version.value}.jar"

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)

buildInfoPackage := "demo"

enablePlugins(sbtdocker.DockerPlugin, BuildInfoPlugin)

// Resolve duplicates for Sbt Assembly
assembly / assemblyMergeStrategy := {
  case PathList(xs @ _*) if xs.last == "io.netty.versions.properties" ⇒ MergeStrategy.rename
  case other                                                          ⇒ (assemblyMergeStrategy in assembly).value(other)
}

docker / imageNames := Seq(ImageName(namespace = Some("haghard"), repository = name.value, tag = Some(version.value)))

docker / buildOptions := BuildOptions(
  cache = false,
  removeIntermediateContainers = BuildOptions.Remove.Always,
  pullBaseImage = BuildOptions.Pull.Always
)

docker / dockerfile := {
  val baseDir        = baseDirectory.value
  val artifact: File = assembly.value

  val imageAppBaseDir = "/app"
  val configDir       = "conf"
  val d3Dir           = baseDir / "src" / "main" / "resources" / "d3"

  val artifactTargetPath = s"$imageAppBaseDir/${artifact.name}"

  val seedConfigSrc   = baseDir / "src" / "main" / "resources" / "master.conf"
  val workerConfigSrc = baseDir / "src" / "main" / "resources" / "worker.conf"

  val seedConfigTarget   = s"${imageAppBaseDir}/${configDir}/master.conf"
  val workerConfigTarget = s"${imageAppBaseDir}/${configDir}/worker.conf"
  val d3TargetDirPath    = s"${imageAppBaseDir}/d3"

  new sbtdocker.mutable.Dockerfile {
    //from("adoptopenjdk/openjdk11")
    from("adoptopenjdk/openjdk14")

    //from("adoptopenjdk:14-jre")

    //from("adoptopenjdk/openjdk12:x86_64-ubuntu-jre-12.0.2_10")

    //from("openjdk:8-jre")
    //from("adoptopenjdk/openjdk11:jdk-11.0.1.13")
    //from("adoptopenjdk/openjdk8:x86_64-alpine-jdk8u192-b12")
    //from("adoptopenjdk/openjdk12")

    maintainer("haghard")

    env("VERSION", Version)
    env("EXTRA_CONF_DIR", s"$imageAppBaseDir/$configDir")

    workDir(imageAppBaseDir)
    runRaw("ls -la")

    copy(artifact, artifactTargetPath)
    copy(seedConfigSrc, seedConfigTarget)
    copy(workerConfigSrc, workerConfigTarget)
    copy(d3Dir, d3TargetDirPath)

    runRaw(s"cd $d3TargetDirPath; ls -la")

    //https://docs.docker.com/compose/compose-file/#resources
    entryPoint(
      "java",
      "-server",
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=400",
      "-XX:ConcGCThreads=2",
      "-XX:ParallelGCThreads=2",
      //"-XX:+PrintFlagsFinal",
      "-XshowSettings",
      //"-XX:MaxRAMFraction=1",
      //"-XX:+UnlockExperimentalVMOptions",
      "-XX:InitialRAMPercentage=75",
      "-XX:MaxRAMPercentage=75",
      "-XX:+PreferContainerQuotaForCPUCount", //Added in JDK11. Support for using the cpu_quota instead of cpu_shares for
      // picking the number of cores the JVM uses to makes decisions such as how many compiler threads, GC threads and sizing of the fork join pool
      //These env vars are set in docker-compose2.yml
      s"-DseedHost=${sys.env.get("SEED_DNS").getOrElse(throw new Exception("env var SEED_DNS is expected"))}",
      s"-DseedPort=${sys.env.get("AKKA_PORT").getOrElse(throw new Exception("env var AKKA_PORT is expected"))}",
      s"-DhttpPort=${sys.env.get("HTTP_PORT").getOrElse(throw new Exception("env var HTTP_PORT is expected"))}",
      s"-Duser.timezone=${sys.env.get("TZ").getOrElse(throw new Exception("env var TZ is expected"))}",
      "-jar",
      artifactTargetPath
    )
  }
}

addCommandAlias("c", "compile")
addCommandAlias("r", "reload")

ThisBuild / turbo := true


//I need this to be able to set envVars below
//Comment out for ammonite
run / fork := true
javaOptions := Seq("-XshowSettings:vm", "-XX:+PrintCommandLineFlags", "-Xms256M", "-Xmx350M")


//sbt -DSHARD=a runA0
//sbt -DSHARD=a runA1
//sbt -DSHARD=a runA2

//sbt -DSHARD=b runB0
//sbt -DSHARD=b runB1
//sbt -DSHARD=b runB2

//sbt -DSHARD=g runG0
//sbt -DSHARD=g runG1
//sbt -DSHARD=g runG2


//sbt -DSHARD=docker docker

val shard = sys.props.getOrElse("SHARD", "docker")

// https://stackoverflow.com/questions/26244115/how-to-execute-runmain-from-custom-task

val runA0 = taskKey[Unit]("Run alpha0")
runA0 := {
  (Compile / runMain).toTask(" demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.1").value
}

val runA1 = taskKey[Unit]("Run alpha1")
runA1 := {
  (Compile / runMain).toTask(" demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.2").value
}

val runA2 = taskKey[Unit]("Run alpha2")
runA2 := {
  (Compile / runMain).toTask(" demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.3").value
}


val runB0 = taskKey[Unit]("Run betta0")
runB0 := {
  (Compile / runMain).toTask(" demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.10").value
}

val runB1 = taskKey[Unit]("Run betta1")
runB1 := {
  (Compile / runMain).toTask(" demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.11").value
}

val runB2 = taskKey[Unit]("Run betta2")
runB2 := {
  (Compile / runMain).toTask(" demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.12").value
}

val runG0 = taskKey[Unit]("Run gamma0")
runG0 := {
  (Compile / runMain).toTask(" demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.20").value
}

val runG1 = taskKey[Unit]("Run gamma1")
runG1 := {
  (Compile / runMain).toTask(" demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.21").value
}

val runG2 = taskKey[Unit]("Run gamma2")
runG2 := {
  (Compile / runMain).toTask(" demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.22").value
}


//envVars works only when run / fork := true
shard match {
  case "a" => {
    println("---- SET SHARD alpha ----")
    //sys.env ++ Map("NODE_TYPE" -> "seed", "SHARD" -> "alpha", "DM" -> "config")
    envVars := Map("NODE_TYPE" -> "seed", "SHARD" -> "alpha", "DM" -> "config")
  }
  case "b" => {
    println("---- SET SHARD betta ----")
    envVars := Map("NODE_TYPE" -> "shard", "SHARD" -> "betta", "DM" -> "config")
  }
  case "g" => {
    println("---- SET SHARD gamma ----")
    envVars := Map("NODE_TYPE" -> "shard", "SHARD" -> "gamma", "DM" -> "config")
  }
  case "docker" => {
    println("docker")
    envVars := Map()
  }
}






/*
shard match {
  case "a" => {
    println("a")
    addCommandAlias("a", "runMain demo.Application -DseedPort=2551 -DseedHost=127.0.0.4 -DhttpPort=9000 -Dhost=127.0.0.1")
  }
  case "b" => {
    println("b")
    addCommandAlias("b", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.3")
  }
  case "g" => {
    println("g")
    addCommandAlias("g", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.4")
  }
}
*/


/*
addCommandAlias("a", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.1")
addCommandAlias("b", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.3")
addCommandAlias("g", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.4")
*/

/*
shard match {
  case "a" => {
    println("---- SET Alias alpha ----")
    addCommandAlias("a", "runMain demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.1")
  }
  case "b" => {
    println("---- SET Alias betta ----")
    addCommandAlias("b", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.3")
  }
  case "g" => {
    println("---- SET Alias gamma ----")
    addCommandAlias("g", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.4")
  }
}*/


//envVars in run := Map("NODE_TYPE" -> "master", "SHARD" -> "alpha")
//addCommandAlias("a0", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.1")

//sudo ifconfig lo0 127.0.0.2 add
//envVars in run := Map("NODE_TYPE" -> "worker", "SHARD" -> "alpha")
//addCommandAlias("a1", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.2")

//sudo ifconfig lo0 127.0.0.3 add
//envVars in run := Map("NODE_TYPE" -> "worker", "SHARD" -> "betta")
//addCommandAlias("b0", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.3")

//sudo ifconfig lo0 127.0.0.4 add
//envVars in run := Map("NODE_TYPE" -> "worker", "SHARD" -> "gamma")
//addCommandAlias("g0", "run demo.Application -DseedPort=2551 -DseedHost=127.0.0.1 -DhttpPort=9000 -Dhost=127.0.0.4")

/*
s"-Djava.rmi.server.hostname=${System.getenv("HOST")}",
  s"-Dcom.sun.management.jmxremote.port=${System.getenv("SEED_JMX_PORT")}",
  s"-Dcom.sun.management.jmxremote.ssl=false",
  s"-Dcom.sun.management.jmxremote.authenticate=false",
  s"-Dcom.sun.management.jmxremote.local.only=false",
  s"-Dcom.sun.management.jmxremote.rmi.port=${System.getenv("SEED_JMX_PORT")}",
  s"-Dcom.sun.management.jmxremote=true",
 */
