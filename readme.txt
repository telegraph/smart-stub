Build instructions
------------------
sbt reload clean assembly


Usage
-----

Engineers should create an Object (e.g. MyStub.scala) extending SmartStub and add the following

1) Add a driver method:

   def main(args : Array[String]) {
      // port, canned file directory, swagger file, state model file, opening state
      MyStub.configureStub(args(0).toInt, args(1), args(2), args(3), "registered")
      MyStub.start
   }

2) Add wiremock stub methods:

    override def setUpMocks(cannedResponsesPath: String): Unit  = {

        wireMockServer.stubFor(post(urlMatching(".*/hello"))
         .willReturn(
           aResponse()
             .withHeader("Content-Type", "application/json")
             .withBody("""{"hello":"world"}""")
             .withStatus(200)))

        ...
    }


N.B. You should run your acceptance tests against the stub as well as the real service
to further improve the validity of the stub, as well as validating the test requests
against swagger.


Errors
------
An invalid request payload will return a 500 with an error
An invalid response (from the stub) will also result in a 500 with an error
An invalid state transition will (you guessed it) return a 500 with an error