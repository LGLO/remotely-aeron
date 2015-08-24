package remotely.transport.aeron.tests

import java.net.InetSocketAddress
import remotely.transport.aeron.AeronServer
import remotely.transport.aeron.tests.server.Test1Server
import remotely.{Capabilities, Monitoring}

object ServerMain extends App {

  val s1:Test1Server = new Test1ServerImpl()

  val start = AeronServer.start(
    new InetSocketAddress("localhost", 20123),
    s1.environment,
    2,
    1,
    Capabilities(Capabilities.required),
    //Monitoring.consoleLogger("Server")
    Monitoring.empty
  )

  val stop = start.run
  println("Press Enter to stop the server.")
  Console.readLine()
  stop.run

}

