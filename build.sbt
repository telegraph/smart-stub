import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.telegraph.stub",
      scalaVersion := "2.11.8",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "SmartStub",
    libraryDependencies ++= Seq(
      "com.github.tomakehurst" % "wiremock" % "2.7.1",
      "com.atlassian.oai" % "swagger-request-validator-wiremock" % "1.2.1",
      "org.json4s" %% "json4s-jackson" % "3.5.3"
    )
  )