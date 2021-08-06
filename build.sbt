name := "media-monkey"

lazy val `media-monkey` = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.12.13"

resolvers += "openimaj" at "http://maven.openimaj.org"
resolvers += "billylieurance-net" at "http://www.billylieurance.net/maven2"

libraryDependencies += guice
libraryDependencies += ws

libraryDependencies += "org.apache.tika" % "tika-core" % "1.11"
// libraryDependencies += "com.typesafe.play" %% "anorm" % "2.4.0"
libraryDependencies += "org.im4java" % "im4java" % "1.4.0"
libraryDependencies += "org.openimaj" % "core" % "1.3.6"
libraryDependencies += "org.openimaj" % "core-image" % "1.3.6"
libraryDependencies += "org.openimaj" % "faces" % "1.3.6"
libraryDependencies += "us.fatehi" % "pointlocation6709" % "4.1"
libraryDependencies += "commons-io" % "commons-io" % "2.5"

libraryDependencies += specs2 % Test
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % "test"

javaOptions in Universal ++= Seq(
  // -J params will be added as jvm parameters
  "-J-Xmx2048m"
)

enablePlugins(GitVersioning)

enablePlugins(DockerPlugin)
import com.typesafe.sbt.packager.docker._
dockerBaseImage := "debian:buster"
dockerCommands ++= Seq(
  Cmd("USER", "root"),
  ExecCmd("RUN", "apt-get", "update"),
  ExecCmd("RUN", "apt-get", "install", "-y", "openjdk-11-jre-headless"),
  ExecCmd("RUN", "apt-get", "install", "-y", "imagemagick"),
  ExecCmd("RUN", "apt-get", "install", "-y", "ffmpeg"),
  ExecCmd("RUN", "apt-get", "install", "-y", "mediainfo"),
  ExecCmd("RUN", "apt-get", "install", "-y", "libimage-exiftool-perl"),
  ExecCmd("RUN", "apt-get", "install", "-y", "webp")
)
