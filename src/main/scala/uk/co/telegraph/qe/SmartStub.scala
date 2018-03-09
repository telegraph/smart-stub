package uk.co.telegraph.qe

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.atlassian.oai.validator.wiremock.SwaggerValidationListener
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalToJson, post, urlMatching, any, anyUrl}
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

  // state
  private var stubPrevState = ""
  private object StubModel {
    var stateModelJson: JValue = null
  }
  val NEXT_STATE_PARAM = "nextState"

  // sla
  private object Sla {
    var slaJson: JValue = null
  }

  // Save primed responses
  private object PrimedResponse {
    var primedResponses = new  ListBuffer[com.github.tomakehurst.wiremock.http.Request]()
    var primedResponseStatuses = new  ListBuffer[Int]()
  }
  val PRIMED_RESPONSE_URL = "/primeresponse"
  val RESPONSE_STATUS_QUERY_PARAM = "responseStatus"

  // Saved string substitution
  private object ReplaceResponse {
    var replaceTarget = ""
    var replaceWith = ""
  }
  val RESPONSE_SUBSTITUTE_STRING_URL = "/substitutestring"
  val RESPONSE_SUBSTITUTE_TARGET_QUERY_PARAM = "responseTarget"
  val RESPONSE_SUBSTITUTE_WITH_QUERY_PARAM = "responseWith"


  /*********************************************************************************
    configure port, canned responses, swagger, stateModel, opening state, mappings file location
    connect transformers
   ********************************************************************************/

  def configureStub(inputPort: String, cannedResponsesPath: String, swaggerFile:String, stateModelFile:String, openingState:String, slaFile:String, mappingsFile:String): Unit = {
    // port
    var port: Int = 8080
    if (inputPort != null)
      port = inputPort.toInt

    // mappings file location
    var mappingsFileLocation=mappingsFile
    if (mappingsFileLocation==null) {
      mappingsFileLocation="src/test/resources"
    }

    // attach transformers
    wireMockServer = new WireMockServer(options()
      .port(port)
        .usingFilesUnderDirectory(mappingsFileLocation)
      .extensions(
      ContractValidationTransformer,
      MyResponseTransformer,
      new ResponseTemplateTransformer(true)))

    // check valid swagger is present
    var swagger = Source.fromFile(swaggerFile).mkString
    if (swagger==null || swagger.replaceAll("\\s", "").length < 3) {
      throw new Exception("Swagger file should be present and populated")
    }
    if (swaggerFile.endsWith(".yaml") || swaggerFile.endsWith(".yml") )
      swagger = convertYamlToJson(swagger)

    wireMockListener = new SwaggerValidationListener(swagger)
    wireMockServer.addMockServiceRequestListener(wireMockListener)

    // add mocks including one for the internal resources to prime and substitute
    setUpMocks(cannedResponsesPath)
    wireMockServer.stubFor(post(urlMatching(s".*$PRIMED_RESPONSE_URL.*"))
      .willReturn(
        aResponse()
          .withBody("primed")
          .withStatus(200)));
    wireMockServer.stubFor(post(urlMatching(s".*$RESPONSE_SUBSTITUTE_STRING_URL.*"))
      .willReturn(
        aResponse()
          .withBody("substituted")
          .withStatus(200)));

    // store state model
    if (stateModelFile!=null) {
      implicit val formats = DefaultFormats
      StubModel.stateModelJson = parse(Source.fromFile(stateModelFile).mkString) \ "stateTransitions"
      if (StubModel.stateModelJson == null) {
        throw new Exception("State model not in correct format")
      }
      stubPrevState = openingState
    }

    // sla
    if (slaFile!=null) {
      Sla.slaJson = parse(Source.fromFile(slaFile).mkString) \ "slaPoints"
      if (Sla.slaJson == null) {
        throw new Exception("SLA not in correct format")
      }
    }

    println(s"Stub configured for swagger api $swaggerFile for state model $stateModelFile for sla $slaFile running on port $port in opening state $openingState with mappings at $mappingsFileLocation")
  }

  /**************************************************************
    configure port,  swagger, and mappings file location
    connect transformers
    *************************************************************/

  def configureStubWithOnlySwaggerAndMappings(inputPort: String, swaggerFile:String, mappingsFile:String): Unit = {
    configureStub(inputPort, null, swaggerFile,null,null,null,mappingsFile)
  }


  /**************
    start server
   **************/
  def start = {
    if (wireMockServer!=null && !wireMockServer.isRunning)
      wireMockServer.start()
  }

  /**************
    stop server
    **************/
  def stop = {
    if (wireMockServer==null)
      throw new Exception("Wiremock server may have found an invalid contract - please check logs")
    wireMockServer.stop
  }

  /*****************************************
      method to override to set up mocks
   ****************************************/
  protected def setUpMocks(cannedResponsesPath: String): Unit


  /*******************************************************
     validate contract swagger and state and apply latency
    ******************************************************/
  private object ContractValidationTransformer extends ResponseTransformer {

    override def transform (request: com.github.tomakehurst.wiremock.http.Request, response: Response,
                            files: FileSource, parameters: Parameters): Response = {

      // if priming request ignore validation
      if (request.getAbsoluteUrl.contains(PRIMED_RESPONSE_URL) || request.getAbsoluteUrl.contains(RESPONSE_SUBSTITUTE_STRING_URL)) {
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

        // check for string replacement
        if (ReplaceResponse.replaceTarget.length > 0) {
          myResponse = new Response(
            myResponse.getStatus,
            myResponse.getStatusMessage,
            myResponse.getBodyAsString.replaceAll(ReplaceResponse.replaceTarget, ReplaceResponse.replaceWith),
            myResponse.getHeaders,
            myResponse.wasConfigured(), myResponse.getFault, myResponse.isFromProxy)
        }

        // validate swagger
        wireMockListener.requestReceived(request, myResponse)
        wireMockListener.assertValidationPassed() // will throw error

        // validate state transition if required
        var stateTransitionIsValid=false

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
            .status(400)
            .build();
        }

        // add latency
        for {
          JObject(rec) <- Sla.slaJson
          JField("endpoint", JString(endpoint)) <- rec
          JField("action", JString(action)) <- rec
          JField("latency", JInt(latency)) <- rec
        } {

          if (endpoint == null || action == null || latency==null) {
            throw new Exception("SLA not in correct format")
          }
          // apply latency if needed
          if (request.getAbsoluteUrl.toUpperCase.contains(endpoint.toUpperCase) && request.getMethod.getName.toUpperCase.contains(action.toUpperCase) )
           Thread.sleep(latency.toInt)
          }

        // otherwise just act as if nothing happened
        return myResponse

      } catch {
        case ex: Exception => {
          wireMockListener.reset()
          return Response.Builder.like(response)
            .but()
            .body("Invalid contract"+ ex.getLocalizedMessage)
            .status(400)
            .build();
        }
      }
    }

    override def getName: String = "state"
  }


  // prime next response
  private object MyResponseTransformer extends ResponseTransformer {

    override def transform (request: com.github.tomakehurst.wiremock.http.Request, response: Response,
                            files: FileSource, parameters: Parameters): Response = {

      // if replacing string in response
      if (request.getAbsoluteUrl.contains(RESPONSE_SUBSTITUTE_STRING_URL)) {
        ReplaceResponse.replaceTarget = request.queryParameter(RESPONSE_SUBSTITUTE_TARGET_QUERY_PARAM).values().get(0).toString
        ReplaceResponse.replaceWith = request.queryParameter(RESPONSE_SUBSTITUTE_WITH_QUERY_PARAM).values().get(0).toString
      }
      // if priming save response for later and add mock default
      else if (request.getAbsoluteUrl.contains(PRIMED_RESPONSE_URL)) {
        PrimedResponse.primedResponses += request
        PrimedResponse.primedResponseStatuses += request.queryParameter(RESPONSE_STATUS_QUERY_PARAM).values().get(0).toInt

        // Need placeholder so response can be replaced
        wireMockServer.stubFor(any(anyUrl())
            .atPriority(1000).willReturn(aResponse()))
      }
      return response
    }

    override def getName: String = "response"
  }


  import com.fasterxml.jackson.databind.ObjectMapper
  import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

  def convertYamlToJson(yaml: String): String = {
    val yamlReader = new ObjectMapper(new YAMLFactory)
    val obj = yamlReader.readValue(yaml, classOf[Any])
    val jsonWriter = new ObjectMapper
    jsonWriter.writeValueAsString(obj)
  }

}

