package uk.co.telegraph.qe

import java.net.URL
import java.util

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalToJson, post, urlMatching}
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.DefaultHttpClient


/**
  * Created by toorap on 12/09/2017.
  */
class StubTests extends FeatureSpec with GivenWhenThen with Matchers {

  val url = "http://localhost:8089/cars"

  feature("The user can pop an element off the top of the stack") {

    info("As a driver")
    info("I want to be able to use the car")
    info("So that I can get from a to b")

    scenario("validate state") {

      Given("the car is idle")

        MyStub.configureAndStart()

      When("when i move it")

        val post = new HttpPost(url)
        post.setHeader("Content-type", "application/json")
        post.setEntity(new StringEntity("""{"action":"drive"}"""))
        val response = (new DefaultHttpClient).execute(post)

      Then("it will move")

        response.getStatusLine.getStatusCode should equal (200)

    }
  }
}

  object MyStub extends SmartStub {


    override def setUpMocks(cannedResponsesPath: String): Unit  = {

      wireMockServer.stubFor(post(urlMatching(".*/cars"))
        .withRequestBody(equalToJson("{\"action\":\"drive\"}",true,true))
        .willReturn(
          aResponse()
            .withTransformerParameter("nextState", "moving")
            .withBody("""{"response":"{{request.path}}"}""")
            .withStatus(200)));

      wireMockServer.stubFor(post(urlMatching(".*/cars"))
        .withRequestBody(equalToJson("{\"action\":\"reverse\"}",true,true))
        .willReturn(
          aResponse()
            .withTransformerParameter("nextState", "reversing")
            .withBody("""{"response":"look out"}""")
            .withStatus(200)));

      wireMockServer.stubFor(post(urlMatching(".*/cars"))
        .withRequestBody(equalToJson("{\"action\":\"stop\"}",true,true))
        .willReturn(
          aResponse()
            .withTransformerParameter("nextState", "idle")
            .withBody("""{"response":"breaking"}""")
            .withStatus(200)));
    }

    def configureAndStart(): Unit = {
      MyStub.configureStub("8089", "resources/", "resources/openApi.json", "resources/stateModel.json", "idle")
      MyStub.start
    }
  }

