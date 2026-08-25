logLevel := Level.Warn

resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.9.10")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")

