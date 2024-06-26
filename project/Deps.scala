
import sbt._
import sbt.Def.setting
import sbt.Keys.scalaVersion

object Deps {

  def caseApp = "com.github.alexarchambault" %% "case-app" % "2.0.6"
  def coursierInterface = "io.get-coursier" % "interface" % "1.0.6"
  def coursierLauncher = "io.get-coursier" %% "coursier-launcher" % "2.0.16"
  def dataClass = "io.github.alexarchambault" %% "data-class" % "0.2.5"
  def macroParadise = "org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full
  def svm = "org.graalvm.nativeimage" % "svm" % "19.3.6"
  def utest = setting {
    val sv = scalaVersion.value
    val ver =
      if (sv.startsWith("2.11.")) "0.7.10"
      else "0.7.7"
    "com.lihaoyi" %% "utest" % ver
  }
}
