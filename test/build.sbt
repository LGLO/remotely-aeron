import common._

macrosSettings


scalacOptions ++= Seq(
  "-Ywarn-value-discard",
  "-Xlint",
  "-feature",
  "-language:implicitConversions",
  "-language:postfixOps"
)


libraryDependencies ++= {
  Seq(
    "oncue.remotely" %% "core" % remotelyVersion,
    "uk.co.real-logic" % "aeron-all" % aeronVersion,
    "uk.co.real-logic" % "aeron-samples" % aeronVersion,
    "org.hdrhistogram" % "HdrHistogram" % "2.1.6"
  )
}