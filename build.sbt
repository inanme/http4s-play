version := "0.0.1"

lazy val `examples-play` = project
  .in(file("example"))
  .enablePlugins(PlayScala)
  .settings(
    scalaVersion := "2.13.8",
    description  := "Example of http4s on Play",
    libraryDependencies ++= Seq(
      guice,
      "javax.xml.bind" % "jaxb-api"   % "2.3.1",
      "org.http4s"    %% "http4s-dsl" % "0.22.13"
    )
  )
  .dependsOn(`play-route`)

lazy val `play-route` = project
  .settings(
    scalaVersion := "2.13.8",
    description  := "Play wrapper of http4s services",
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play"                  % "2.8.16",
      "com.typesafe.play" %% "play-akka-http-server" % "2.8.16"  % "test",
      "co.fs2"            %% "fs2-reactive-streams"  % "2.5.11",
      "org.http4s"        %% "http4s-core"           % "0.22.13",
      "org.http4s"        %% "http4s-server"         % "0.22.13" % "test"
      // "org.http4s" %% "http4s-testing" % "0.22.13" % "test",
    )
  )
