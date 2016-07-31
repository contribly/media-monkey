name := "media-monkey"

version := "1.0"

lazy val `media-monkey` = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(jdbc , cache , ws)

libraryDependencies += "org.apache.tika" % "tika-core" % "1.11"

libraryDependencies += "com.typesafe.play" %% "anorm" % "2.4.0"

libraryDependencies += "org.im4java" % "im4java" % "1.4.0"

libraryDependencies += specs2 % Test

unmanagedResourceDirectories in Test <+=  baseDirectory ( _ /"target/web/public/test" )  

maintainer in Linux := "Tony McCrae <tony@eelpieconsulting.co.uk>"

packageSummary in Linux := "Media Monkey"

packageDescription := "Media handling service"

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.2"

debianPackageDependencies in Debian ++= Seq("imagemagick", "libav-tools", "mediainfo", "openjdk-8-jdk", "libimage-exiftool-perl")

import com.typesafe.sbt.packager.archetypes.ServerLoader

serverLoading in Debian:= ServerLoader.Systemd

javaOptions in Universal ++= Seq(
  // -J params will be added as jvm parameters
  "-J-Xmx2048m"
)
