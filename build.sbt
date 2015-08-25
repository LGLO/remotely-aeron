name := "remotely-aeron"

version := "1.0"

scalaVersion in Global := "2.10.5"

resolvers in Global += Resolver.mavenLocal
resolvers in Global += "Scalaz Bintray Repo" at "https://dl.bintray.com/scalaz/releases"

//offline in Global := true

lazy val core = project
lazy val `test-protocols` = project dependsOn core
lazy val test = project.dependsOn(`test-protocols`, core)


