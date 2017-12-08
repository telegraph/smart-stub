package uk.co.telegraph.qe

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalToJson, post, urlMatching, get}
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.{CloseableHttpClient, DefaultHttpClient}
import org.apache.http.util.EntityUtils

/**
  * Created by toorap on 12/09/2017.
  */
class StubTests extends FeatureSpec with GivenWhenThen with Matchers {

  val url = "http://localhost:8089/cars"
  val PRIMED_RESPONSE_URL_QUERY = url+MyStub.PRIMED_RESPONSE_URL+"?"+MyStub.RESPONSE_STATUS_QUERY_PARAM+"=200"

  feature("Basic test scenarios for SmartStub") {

    info("As a driver")
    info("I want to be able to use the car")
    info("So that I can get from a to b")

    scenario("happy GET with canned response") {

      Given("the car is idle")
        MyStub.configureAndStart()
      When("when i get it")
         val response = doGet(url)
      Then("it will return the canned data")
        response.getStatusLine.getStatusCode should equal (200)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("cars")
    }

    scenario("happy POST path with canned response") {

      Given("the car is idle")
        // already
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
        doPost(PRIMED_RESPONSE_URL_QUERY,  """{"response":"breeeeak"}""")
        doPost(PRIMED_RESPONSE_URL_QUERY,  """{"response":"zooom"}""")
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


    scenario("happy get path primed response") {

      Given("the i've primed a response")
        doPost(PRIMED_RESPONSE_URL_QUERY,  """{"response":"trucks"}""")
      When("when i get it")
        val response = doGet(url)
      Then("it i will get trucks")
        response.getStatusLine.getStatusCode should equal (200)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("trucks")
    }

    scenario("happy path with primed response with custom request where no mock exists") {

      Given("the car is moving and i prime response for halt")
        doPost(PRIMED_RESPONSE_URL_QUERY,  """{"response":"halted"}""")
      When("when i halt it")
        var response = doPost(url, """{"action":"halt"}""")
      Then("it will halted")
        response.getStatusLine.getStatusCode should equal (200)
        var responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("halted")
    }

    scenario("happy path with primed response with custom request where status is also injected") {

      Given("the car is moving and i prime response for halt")
      doPost(PRIMED_RESPONSE_URL_QUERY.replace("200","201"),  """{"response":"halted"}""")
      When("when i halt it")
      var response = doPost(url, """{"action":"halt"}""")
      Then("it will halted")
      response.getStatusLine.getStatusCode should equal (201)
      var responsePayloadString = EntityUtils.toString(response.getEntity)
      responsePayloadString should include ("halted")
    }

    scenario("sad path with primed response where response body not valid in swagger") {

      Given("the car is moving")
        doPost(url, """{"action":"stop"}""")
        doPost(url, """{"action":"drive"}""")
        doPost(PRIMED_RESPONSE_URL_QUERY,  """{"response":false}""")
      When("when i stop it")
        val response = doPost(url, """{"action":"stop"}""")
      Then("it will move")
        response.getStatusLine.getStatusCode should equal (400)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("Invalid contract")
    }

    scenario("sad POST path with primed response where status is not valid in swagger") {

      Given("the car is moving and i prime response for halt")
        doPost(PRIMED_RESPONSE_URL_QUERY.replace("200","202"),  """{"response":"halted"}""")
      When("when i halt it")
        var response = doPost(url, """{"action":"halt"}""")
        Then("it will halted")
        response.getStatusLine.getStatusCode should equal (400)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("Invalid contract")
    }

    scenario("sad GET path with primed response with invalid status") {

      Given("the i've primed a response")
        doPost(PRIMED_RESPONSE_URL_QUERY.replace("200","202"),  """{"response":"trucks"}""")
      When("when i get it")
        val response = doGet(url)
      Then("it i will get trucks")
        response.getStatusLine.getStatusCode should equal (400)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("Invalid contract")
    }

    scenario("bad request as per swagger") {

      Given("the car is idle")
        // already
      When("when i move it")
        val response = doPost(url, """{"action":"stop","hello":"bye"}""")
      Then("it will move")
        response.getStatusLine.getStatusCode should equal (400)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("Invalid contract")
    }

    scenario("bad response as per swagger from canned response") {

      Given("the car is idle")
        //
      When("when i move it")
        val response = doPost(url, """{"action":"badresponse"}""")
      Then("it will move")
        response.getStatusLine.getStatusCode should equal (400)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("Invalid contract")
    }

    scenario("bad state transition for canned responses") {

      Given("the car is moving")
        doPost(url, """{"action":"drive"}""")
      When("when i reverse it")
        val response = doPost(url, """{"action":"reverse"}""")
      Then("it will fail")
        response.getStatusLine.getStatusCode should equal (400)
        val responsePayloadString = EntityUtils.toString(response.getEntity)
        responsePayloadString should include ("Invalid state transition")
    }

    scenario("bad state transition for primed responses") {

      Given("the car is moving")
      // already
      When("when i reverse it")
        doPost(PRIMED_RESPONSE_URL_QUERY,  """{"response":"reversing"}""")
        val response = doPost(url, """{"action":"reverse"}""")
      Then("it will fail")
        response.getStatusLine.getStatusCode should equal (400)
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

  def doGet(url:String): CloseableHttpResponse = {
    val get = new HttpGet(url)
    get.setHeader("Content-type", "application/json")
    (new DefaultHttpClient).execute(get)
  }
}

  object MyStub extends SmartStub {

    override def setUpMocks(cannedResponsesPath: String): Unit  = {

      wireMockServer.stubFor(get(urlMatching(".*/cars"))
        .willReturn(
          aResponse()
            .withBody("""{"response":"cars"}""")
            .withStatus(200)));

      wireMockServer.stubFor(post(urlMatching(".*/cars"))
        .withRequestBody(equalToJson("{\"action\":\"drive\"}",true,true))
        .willReturn(
          aResponse()
            .withTransformerParameter("nextState", "moving")
            .withBody("""{"response":"moving"}""")
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

