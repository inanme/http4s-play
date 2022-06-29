version := "0.0.1"

val kindProjector = addCompilerPlugin(
  "org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full
)
val betterMonadicFor = addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

lazy val example = project
  .in(file("example"))
  .enablePlugins(PlayScala)
  .settings(
    scalaVersion := "2.13.8",
    description  := "Example of http4s on Play",
    libraryDependencies ++= Seq(
      guice,
      "javax.xml.bind" % "jaxb-api"   % "2.3.1",
      "org.http4s"    %% "http4s-dsl" % "0.23.12"
    ),
    kindProjector,
    betterMonadicFor
  )
  .dependsOn(`play-route`)

lazy val `play-route` = project
  .settings(
    scalaVersion := "2.13.8",
    description  := "Play wrapper of http4s services",
    libraryDependencies ++= Seq(
      "com.typesafe.play"      %% "play"                  % "2.8.16",
      "com.typesafe.play"      %% "play-akka-http-server" % "2.8.16"  % Test,
      "co.fs2"                 %% "fs2-reactive-streams"  % "3.2.8",
      "org.http4s"             %% "http4s-core"           % "0.23.12",
      "org.http4s"             %% "http4s-dsl"            % "0.23.12",
      "org.http4s"             %% "http4s-server"         % "0.23.12" % Test,
      "org.scalatestplus.play" %% "scalatestplus-play"    % "5.1.0"   % Test
      // "org.http4s" %% "http4s-testing" % "0.22.13" % "test",
    ),
    kindProjector,
    betterMonadicFor
  )
