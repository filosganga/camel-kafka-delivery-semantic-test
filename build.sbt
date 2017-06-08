import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.filippodeluca",
      scalaVersion := "2.12.2",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "camel-kafka-delivery-semantic-test",
    libraryDependencies ++= Seq(
    	"org.apache.camel" % "camel-kafka" % "2.19.0" % Test,
    	"org.apache.camel" % "camel-http" % "2.19.0"  % Test,
    	"com.github.tomakehurst" % "wiremock" % "2.6.0" % Test,
      "org.scalatest" %% "scalatest" % "3.0.3" % Test,
    	"net.manub" %% "scalatest-embedded-kafka" %  "0.13.1" % Test,
      "ch.qos.logback" % "logback-classic" % "1.2.3" % Test,
      "org.slf4j" % "log4j-over-slf4j" % "1.7.25" % Test
    ).map(_.exclude("log4j", "log4j"))
  )
