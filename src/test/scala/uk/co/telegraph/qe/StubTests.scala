package uk.co.telegraph.qe

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalToJson, post, urlMatching}
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.apache.http.client.methods.{CloseableHttpResponse, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.{CloseableHttpClient, DefaultHttpClient}
import org.apache.http.util.EntityUtils

/**
  * Created by toorap on 12/09/2017.
  */
class StubTests extends FeatureSpec with GivenWhenThen with Matchers {

  val url = "http://localhost:8089/cars"

  feature("Basic test scenarios for SmartStub") {

    info("As a driver")
    info("I want to be able to use the car")
    info("So that I can get from a to b")

    scenario("happy happy path canned response") {

      Given("the car is idle")
        MyStub.configureAndStart()
      When("when i move it")
         val response = doPost(url, """{"action":"drive"}""")
      Then("it will move")
        response.getStatusLine.getStatusCode should equal (200)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("moving")
    }

    scenario("good state transition") {

      Given("the car is idle")
        // already
      When("when i move it")
        val response = doPost(url, """{"action":"stop"}""")
      Then("it will move")
        response.getStatusLine.getStatusCode should equal (200)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("breaking")
    }


    scenario("happy path with multiple primed responses") {

      Given("the car is moving and i prime responses")
        doPost(url, """{"action":"drive"}""")
        doPost(url+MyStub.PRIMED_RESPONSE_URL,  """{"response":"breeeeak"}""")
        doPost(url+MyStub.PRIMED_RESPONSE_URL,  """{"response":"zooom"}""")
      When("when i stop it")
        var response = doPost(url, """{"action":"stop"}""")
      Then("it will breeeeak")
        response.getStatusLine.getStatusCode should equal (200)
        var responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("breeeeak")
      And("when I drive it it will zoom")
        response = doPost(url, """{"action":"drive"}""")
        responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("zooom")
    }

    scenario("happy path with primed response with custom request where no mock exists") {

      Given("the car is moving and i prime response for halt")
        doPost(url+MyStub.PRIMED_RESPONSE_URL,  """{"response":"halted"}""")
      When("when i halt it")
        var response = doPost(url, """{"action":"halt"}""")
      Then("it will halted")
        response.getStatusLine.getStatusCode should equal (200)
        var responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("halted")
    }

    scenario("bad swagger with primed response") {

      Given("the car is moving")
        doPost(url, """{"action":"stop"}""")
        doPost(url, """{"action":"drive"}""")
        doPost(url+MyStub.PRIMED_RESPONSE_URL,  """{"response":false}""")
      When("when i stop it")
        val response = doPost(url, """{"action":"stop"}""")
      Then("it will move")
        response.getStatusLine.getStatusCode should equal (500)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("Invalid contract")
    }

    scenario("bad request as per swagger") {

      Given("the car is idle")
      // already
      When("when i move it")
      val response = doPost(url, """{"action":"stop","hello":"bye"}""")
      Then("it will move")
      response.getStatusLine.getStatusCode should equal (500)
      val responsePayloadString = EntityUtils.toString(response.getEntity)
      responsePayloadString should include ("Invalid contract")
    }

    scenario("bad response as per swagger from canned response") {

      Given("the car is idle")
        //
      When("when i move it")
        val response = doPost(url, """{"action":"badresponse"}""")
      Then("it will move")
        response.getStatusLine.getStatusCode should equal (500)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("Invalid contract")
    }

    scenario("bad state transition for canned responses") {

      Given("the car is moving")
        doPost(url, """{"action":"drive"}""")
      When("when i reverse it")
        val response = doPost(url, """{"action":"reverse"}""")
      Then("it will fail")
        response.getStatusLine.getStatusCode should equal (500)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("Invalid state transition")
    }

    scenario("bad state transition for primed responses") {

      Given("the car is moving")
      // already
      When("when i reverse it")
        doPost(url+MyStub.PRIMED_RESPONSE_URL,  """{"response":"reversing"}""")
        val response = doPost(url, """{"action":"reverse"}""")
      Then("it will fail")
        response.getStatusLine.getStatusCode should equal (500)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("Invalid state transition")
    }
  }

  def doPost(url:String, body:String): CloseableHttpResponse = {
    val post = new HttpPost(url)
    post.setHeader("Content-type", "application/json")
    post.setEntity(new StringEntity(body))
    (new DefaultHttpClient).execute(post)
  }
}

  object MyStub extends SmartStub {

    override def setUpMocks(cannedResponsesPath: String): Unit  = {

      wireMockServer.stubFor(post(urlMatching(".*/cars"))
        .withRequestBody(equalToJson("{\"action\":\"drive\"}",true,true))
        .willReturn(
          aResponse()
            .withTransformerParameter("nextState", "moving")
            .withBody("""{"response":"moving"}""")    // templated example
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

      wireMockServer.stubFor(post(urlMatching(".*/cars"))
        .withRequestBody(equalToJson("{\"action\":\"badresponse\"}",true,true))
        .willReturn(
          aResponse()
            .withTransformerParameter("nextState", "any")
            .withBody("""{"response":false}""")
            .withStatus(200)));
    }

    def configureAndStart(): Unit = {
      MyStub.configureStub("8089", "src/test/resources/", "src/test/resources/openApi.json", "src/test/resources/stateModel.json", "idle")
      MyStub.start
    }
  }

