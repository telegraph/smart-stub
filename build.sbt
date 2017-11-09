import sbt.Keys.{resolvers, _}
import sbt.addSbtPlugin

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "uk.co.telegraph.qe",
      scalaVersion := "2.11.8",
      version      := "0.5.0"
    )),
    name := "SmartStub",
    libraryDependencies ++= Seq(
      "com.github.tomakehurst" % "wiremock" % "2.7.1",
      "com.atlassian.oai" % "swagger-request-validator-wiremock" % "1.2.1",
      "org.json4s" %% "json4s-jackson" % "3.5.3",
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    ),
    publishMavenStyle := true
  )

resolvers += "mvn-tmg-resolver" at "s3://s3-eu-west-1.amazonaws.com/mvn-artifacts/release"
publishTo := {
  if( isSnapshot.value ){
    Some("mvn-tmg-publisher" at "s3://s3-eu-west-1.amazonaws.com/mvn-artifacts/snapshot")
  }else{
    Some("mvn-tmg-publisher" at "s3://s3-eu-west-1.amazonaws.com/mvn-artifacts/release")
  }
}
