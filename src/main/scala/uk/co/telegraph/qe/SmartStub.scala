package uk.co.telegraph.qe

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.atlassian.oai.validator.wiremock.SwaggerValidationListener
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, delete, equalToJson, get, post, put, urlMatching}
import com.github.tomakehurst.wiremock.common.FileSource
import com.github.tomakehurst.wiremock.extension.responsetemplating.ResponseTemplateTransformer
import com.github.tomakehurst.wiremock.extension.{Parameters, ResponseTransformer}
import com.github.tomakehurst.wiremock.http.{Fault, HttpHeaders, Request, Response}
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.json4s.{JValue, _}
import org.json4s.jackson.JsonMethods._

import scala.collection.mutable.ListBuffer
import scala.io.Source

/**
  * @author ${parsh.toora}
  *
  * BaseStub config and contract validation
  *
  * *** SHOULD NOT NEED TO CHANGE THIS OBJECT ***
  * 
  */

abstract class SmartStub {

  var wireMockServer: WireMockServer = null;
  private var wireMockListener: SwaggerValidationListener = null
  private var stubPrevState = ""
  private object StubModel {
    var stateModelJson: JValue = null
  }
  private object PrimedResponse {
    var primedResponses = new  ListBuffer[com.github.tomakehurst.wiremock.http.Request]()
    var primedResponseStatuses = new  ListBuffer[Int]()
  }
  val PRIMED_RESPONSE_URL = "/primeresponse"
  val NEXT_STATE_PARAM = "nextState"
  val RESPONSE_STATUS_QUERY_PARAM = "responseStatus"


  // configure port, canned responses, swagger, opening state
  def configureStub(inputPort: String, cannedResponsesPath: String, swaggerFile:String, stateModelFile:String, openingState:String): Unit = {
    // port
    var port: Int = 8080
    if (inputPort != null)
      port = inputPort.toInt

    // attach transformers
    wireMockServer = new WireMockServer(options().port(port).extensions(
      ContractValidationTransformer,
      PrimedResponseTransformer,
      new ResponseTemplateTransformer(true)))
    wireMockListener = new SwaggerValidationListener(Source.fromFile(swaggerFile).mkString)
    wireMockServer.addMockServiceRequestListener(wireMockListener)

    // add mocks
    setUpMocks(cannedResponsesPath)
    wireMockServer.stubFor(post(urlMatching(s".*$PRIMED_RESPONSE_URL.*"))
      .willReturn(
        aResponse()
          .withBody("primed")
          .withStatus(200)));

    implicit val formats = DefaultFormats
    StubModel.stateModelJson = parse(Source.fromFile(stateModelFile).mkString) \ "stateTransitions"
    if (StubModel.stateModelJson==null) {
      throw new Exception("State model not in correct format")
    }
    stubPrevState = openingState

    println(s"Stub configured for swagger api $swaggerFile for state model $stateModelFile running on port $port in opening state $openingState")
  }


  // start server
  def start = {
    if (wireMockServer!=null && !wireMockServer.isRunning)
      wireMockServer.start()
  }

  // stop server
  def stop = {
    if (wireMockServer==null)
      throw new Exception("Wiremock server may have found an invalid contract - please check logs")
    wireMockServer.stop
  }

  // method to override to set up mocks
  protected def setUpMocks(cannedResponsesPath: String): Unit


  // validate contract swagger and state
  private object ContractValidationTransformer extends ResponseTransformer {

    override def transform (request: com.github.tomakehurst.wiremock.http.Request, response: Response,
                            files: FileSource, parameters: Parameters): Response = {

      // if priming request ignore validation
      if (request.getAbsoluteUrl.contains(PRIMED_RESPONSE_URL)) {
        return response
      }
      try {
        wireMockListener.reset()

        // check for primed response, if exists then use it and remove it from the list
        var myResponse = response;
        if (PrimedResponse.primedResponses.length == 0) {
          myResponse = response
        } else {
          myResponse = new Response(
            PrimedResponse.primedResponseStatuses.head,
            response.getStatusMessage,
            PrimedResponse.primedResponses.head.getBodyAsString,
            PrimedResponse.primedResponses.head.getHeaders,
            response.wasConfigured(), response.getFault, response.isFromProxy)
          PrimedResponse.primedResponses.remove(0)
          PrimedResponse.primedResponseStatuses.remove(0)
        }

        // validate swagger
        wireMockListener.requestReceived(request, myResponse)
        wireMockListener.assertValidationPassed() // will throw error

        var stateTransitionIsValid=false
        // validate state transition if required

        if (parameters==null || parameters.containsKey(NEXT_STATE_PARAM)==false || parameters.getString(NEXT_STATE_PARAM)=="any") {
          stateTransitionIsValid=true
        } else {
          for {
            JObject(rec) <- StubModel.stateModelJson
            JField("prestate", JString(preState)) <- rec
            JField("poststate", JString(postState)) <- rec
          } {

            if (preState == null || postState == null) {
              throw new Exception("State model not in correct format")
            }
            // get current state for this resource if required
            if (!stateTransitionIsValid && stubPrevState == preState && parameters.getString(NEXT_STATE_PARAM) == postState) {
              stubPrevState = postState
              stateTransitionIsValid = true
            }
          }
        }
        if (!stateTransitionIsValid) {
          return Response.Builder.like(myResponse)
            .but()
            .body("Invalid state transition for " + request.getMethod.getName + "for resource " + request.getUrl + " for state transition " + stubPrevState +"->" + parameters.getString(NEXT_STATE_PARAM))
            .status(500)
            .build();
        }

        // otherwise just act as if nothing happened
        return myResponse

      } catch {
        case ex: Exception => {
          wireMockListener.reset()
          return Response.Builder.like(response)
            .but()
            .body("Invalid contract"+ ex.getLocalizedMessage)
            .status(500)
            .build();
        }
      }
    }

    override def getName: String = "state"
  }


  // prime next response
  private object PrimedResponseTransformer extends ResponseTransformer {

    override def transform (request: com.github.tomakehurst.wiremock.http.Request, response: Response,
                            files: FileSource, parameters: Parameters): Response = {

      // if priming save response for later and add mock default
      if (request.getAbsoluteUrl.contains(PRIMED_RESPONSE_URL)) {
        PrimedResponse.primedResponses += request
        PrimedResponse.primedResponseStatuses += request.queryParameter(RESPONSE_STATUS_QUERY_PARAM).values().get(0).toInt
        wireMockServer.stubFor(post(urlMatching(".*"))
            .atPriority(1000).willReturn(aResponse()))

        wireMockServer.stubFor(get(urlMatching(".*"))
          .atPriority(1001).willReturn(aResponse()))

        wireMockServer.stubFor(put(urlMatching(".*"))
          .atPriority(1002).willReturn(aResponse()))

        wireMockServer.stubFor(delete(urlMatching(".*"))
          .atPriority(1003).willReturn(aResponse()))
      }
      return response
    }

    override def getName: String = "response"
  }

}

