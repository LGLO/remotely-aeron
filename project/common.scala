import sbt.Keys._
import sbt._

object common {

  val aeronVersion = "0.1.3-SNAPSHOT"

  val remotelyVersion = "1.4.2-SNAPSHOT"

  def macrosSettings = Seq(
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0-M5" cross CrossVersion.full),
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided"
    ) ++ (
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, scalaMajor)) if scalaMajor == 10 => Seq("org.scalamacros" %% "quasiquotes" % "2.1.0-M5")
        case _ => Nil
      }
      )
  )
}

