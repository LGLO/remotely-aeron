package remotely.transport.aeron.tests

import java.net.InetSocketAddress
import java.util.concurrent.{TimeUnit, CountDownLatch}

import remotely.transport.aeron.AeronClient
import remotely.transport.aeron.tests.ServerMain._
import remotely.transport.aeron.tests.server.Test1Client
import remotely.{Endpoint, Monitoring}
import uk.co.real_logic.aeron.Aeron
import uk.co.real_logic.aeron.Aeron.Context

import scalaz.{\/-, -\/}
import scalaz.concurrent.Task

object ClientMain extends App {

  import remotely.codecs._

  val serverAddress = if (args.length >= 1) args(0) else "127.0.0.1"
  val bindAddress = if (args.length >= 2) args(1) else "127.0.0.1"

  println(s"Server address: $serverAddress")
  println(s"Bind address $bindAddress")

  private val m: Monitoring = Monitoring.consoleLogger("Client")
  //private val m: Monitoring = Monitoring.empty

  val t0 = System.nanoTime()
  val aeron = Aeron.connect(new Context())

  val serverPort = 20123
  val c1Port = 20124
  val c2Port = 20125

  val c1 = client(c1Port).run
  //val c2 = client(c2Port).run
//  client(20126).attemptRun match{
//    case \/-(ac)=>println("Unexpected success...")
//    case -\/(e)=>println(s"Expected failure: $e")
//  }
  val clients = List(c1)
  val dtMs = (System.nanoTime()-t0)/1000000
  println(s"'connecting' took: $dtMs[ms]")
  //Thread.sleep(30000)

  val endpoint: Endpoint = Endpoint.single(c1)
  //val endpoint2: Endpoint = Endpoint.single(c2)

  pingTest(endpoint)

  //stringsTest(endpoint)

  val latch = new CountDownLatch(clients.size)
  clients.foreach {
    _.shutdown.attemptRun match {
      case \/-(_) => latch.countDown()
      case -\/(e) => throw e
    }
  }
  latch.await(10, TimeUnit.SECONDS)
  println("Done.")
  aeron.close()

  def client(clientPort:Int):Task[AeronClient] = AeronClient.single(
    new InetSocketAddress(serverAddress, serverPort),
    new InetSocketAddress(bindAddress, clientPort),
    Test1Client.expectedSignatures,
    Option.empty,
    m,
    aeron)

}
