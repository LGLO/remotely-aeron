import common._

name := "remotely-aeron-core"

scalacOptions ++= Seq(
  "-Ywarn-value-discard",
  "-Xlint",
  "-feature",
  "-language:implicitConversions",
  "-language:postfixOps"
)

macrosSettings

libraryDependencies ++= {
  Seq(
    "org.scodec" %% "scodec-core" % "1.8.1",
    "oncue.remotely" %% "core" % remotelyVersion,
    "uk.co.real-logic" % "aeron-all" % aeronVersion,
    "uk.co.real-logic" % "aeron-samples" % aeronVersion,
    "org.scalaz"         %% "scalaz-core"   % "7.1.3",
    "org.scalaz.stream"  %% "scalaz-stream" % "0.7.2a"
  )
}

testSettings

assemblySettings