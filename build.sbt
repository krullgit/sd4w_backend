import sbt.Keys._

lazy val GatlingTest = config("gatling") extend Test

scalaVersion in ThisBuild := "2.11.12"

crossScalaVersions := Seq("2.11.12", "2.12.4")

def gatlingVersion(scalaBinVer: String): String = scalaBinVer match {
  case "2.11" => "2.2.5"
  case "2.12" => "2.3.0"
}

libraryDependencies += guice
libraryDependencies += "org.joda" % "joda-convert" % "1.9.2"
libraryDependencies += "net.logstash.logback" % "logstash-logback-encoder" % "4.11"

libraryDependencies += "com.netaporter" %% "scala-uri" % "0.4.16"
libraryDependencies += "net.codingwell" %% "scala-guice" % "4.1.1"

libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test
libraryDependencies += "io.gatling.highcharts" % "gatling-charts-highcharts" % gatlingVersion(scalaBinaryVersion.value) % Test
libraryDependencies += "io.gatling" % "gatling-test-framework" % gatlingVersion(scalaBinaryVersion.value) % Test
scalaModuleInfo ~= (_.map(_.withOverrideScalaVersion(true)))

libraryDependencies += "com.typesafe.play" %% "play-json" % "2.6.7"
libraryDependencies += "com.google.guava" % "guava" % "21.0-rc2"
libraryDependencies += "com.sksamuel.elastic4s" %% "elastic4s-http-streams" % "6.1.2"
libraryDependencies += "edu.stanford.nlp" % "stanford-corenlp" % "3.7.0"
libraryDependencies += "edu.stanford.nlp" % "stanford-corenlp" % "3.7.0" classifier "models"
libraryDependencies += "com.sksamuel.avro4s" %% "avro4s-core" % "1.8.0"
/*libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.0-SNAP9" % Test*/ // do I need this? uncomment if I get bugs :)
libraryDependencies += "org.apache.spark" %% "spark-mllib" % "2.3.0"

resolvers += "Spark Packages Repo" at "http://dl.bintray.com/spark-packages/maven"
libraryDependencies += "brkyvz" % "lazy-linalg" % "0.1.0"




// The Play project itself
lazy val root = (project in file("."))
  .enablePlugins(Common, PlayScala, GatlingPlugin)
  .configs(GatlingTest)
  .settings(inConfig(GatlingTest)(Defaults.testSettings): _*)
  .settings(
    name := """play-scala-rest-api-example""",
    scalaSource in GatlingTest := baseDirectory.value / "/gatling/simulation"
  )

// Documentation for this project:
//    sbt "project docs" "~ paradox"
//    open docs/target/paradox/site/index.html
lazy val docs = (project in file("docs")).enablePlugins(ParadoxPlugin).
  settings(
    paradoxProperties += ("download_url" -> "https://example.lightbend.com/v1/download/play-rest-api")
  )

