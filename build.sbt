name := "media-monkey"

lazy val `media-monkey` = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.12.19"

resolvers += "openimaj" at "https://maven.openimaj.org"
resolvers += "billylieurance-net" at "https://www.billylieurance.net/maven2"
resolvers += "gael" at "https://repository.gael-systems.com/repository/public"

libraryDependencies += guice
libraryDependencies += ws

libraryDependencies += "org.apache.tika" % "tika-core" % "2.9.4" exclude("org.slf4j", "slf4j-api")
libraryDependencies += "org.apache.tika" % "tika-parsers-standard-package" % "2.9.4" exclude("org.slf4j", "jcl-over-slf4j") exclude("org.slf4j", "slf4j-api")
libraryDependencies += "org.im4java" % "im4java" % "1.4.0"
libraryDependencies += "org.openimaj" % "core" % "1.3.6"
libraryDependencies += "org.openimaj" % "core-image" % "1.3.6"
libraryDependencies += "org.openimaj" % "faces" % "1.3.6"
libraryDependencies += "commons-io" % "commons-io" % "2.5"
libraryDependencies += "io.micrometer" % "micrometer-registry-prometheus" % "1.15.0"
libraryDependencies += "io.prometheus" % "prometheus-metrics-exporter-httpserver" % "1.3.8"

// test deps
libraryDependencies ++= Seq(
  specs2 % Test,
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test,
  "org.scalamock"          %% "scalamock"          % "5.1.0" % Test
)

javaOptions in Universal ++= Seq(
  // -J params will be added as jvm parameters
  // This value dictates the maximum memory value.
  // The corresponding value needs to be changed in the hosting environment, i.e. GCP.
  "-J-XX:+PrintFlagsFinal",
  "-J-XX:MaxRAMPercentage=75"
)

enablePlugins(GitVersioning)

PlayKeys.devSettings := Seq("play.server.http.port" -> "9010")

enablePlugins(DockerPlugin)
import com.typesafe.sbt.packager.docker._
dockerBaseImage := "archlinux/archlinux:latest"
dockerRepository := Option("eu.gcr.io/contribly-dev")
dockerCommands ++= Seq(
    Cmd("USER", "root"),
    Cmd("RUN", "pacman", "-Syu", "--noconfirm"),
    Cmd("RUN", "pacman", "-S", "--noconfirm", "jre11-openjdk-headless", "imagemagick", "ffmpeg", "mediainfo", "perl-image-exiftool", "extra/libwebp"),
    Cmd("RUN", "ln", "-s", "/usr/bin/vendor_perl/exiftool", "/usr/bin/exiftool")
)
