package com.telegraph.stub

import com.github.tomakehurst.wiremock.{WireMockServer}
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.atlassian.oai.validator.wiremock.SwaggerValidationListener
import com.github.tomakehurst.wiremock.common.{FileSource}
import com.github.tomakehurst.wiremock.extension.{Parameters, ResponseTransformer}
import com.github.tomakehurst.wiremock.http.{Response}
import org.json4s.{JValue, _}
import org.json4s.jackson.JsonMethods._

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
  private object StubState{
    var currentState:String=""
  }
  private object StubModel{
    var stateModelJson:JValue=null
  }


  // configure port, canned responses, swagger, state
  def configureStub(inputPort: String, cannedResponsesPath: String, swaggerFile:String, stateModelFile:String, openingState:String): Unit = {
    // port
    var port: Int = 8080
    if (inputPort != null)
      port = inputPort.toInt

    wireMockServer = new WireMockServer(options().port(port).extensions(ContractValidationTransformer))
    wireMockListener = new SwaggerValidationListener(Source.fromFile(swaggerFile).mkString)
    wireMockServer.addMockServiceRequestListener(wireMockListener)
    setUpMocks(cannedResponsesPath)
    implicit val formats = DefaultFormats
    StubModel.stateModelJson = parse(Source.fromFile(stateModelFile).mkString) \ "stateTransitions"
    if (StubModel.stateModelJson==null) {
      throw new Exception("State model not in correct format")
    }
    StubState.currentState = openingState

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
      try {
        wireMockListener.reset()

        // validate swagger
        wireMockListener.requestReceived(request, response)
        wireMockListener.assertValidationPassed() // will throw error

        // validate state
        var nextState:String = null
        for {
          JObject(rec) <- StubModel.stateModelJson
          JField("action", JString(action)) <- rec
          JField("prestate", JString(preState)) <- rec
          JField("poststate", JString(postState)) <- rec
        } {
          if (action==null || preState==null || postState==null) {
            throw new Exception("State model not in correct format")
          }
          if (request.getMethod.getName.equalsIgnoreCase(action) &&
            StubState.currentState.equalsIgnoreCase(preState)) {
            nextState = postState
          }
        }
        if (nextState==null) {
          return Response.Builder.like(response)
            .but()
            .body("Invalid state transition for " + request.getMethod.getName + " from state " + StubState.currentState)
            .status(500)
            .build();
        }
        StubState.currentState = nextState

        // otherwise just act as if nothing happened
        return response

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

    override def getName: String = "validate-contract-request"
  }

}

