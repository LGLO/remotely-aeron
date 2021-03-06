package remotely.transport.aeron.tests

import java.net.InetSocketAddress

import server.Test1Client
import remotely.transport.netty.NettyTransport
import remotely.{Endpoint, Monitoring}

import scalaz.concurrent.Task

object OriginalClient extends App{

  import remotely.codecs._

  val serverAddress = if (args.length >= 1) args(0) else "127.0.0.1"
  println(s"Will connect to $serverAddress")

  private val m: Monitoring = Monitoring.consoleLogger("Netty")
  val t0 = System.nanoTime()
  val transport: Task[NettyTransport] = NettyTransport.single(
    new InetSocketAddress(serverAddress, 8822),
    Test1Client.expectedSignatures, monitoring = m)
  private val nettyTransport = transport.run
  val endpoint:Endpoint = Endpoint.single(nettyTransport)
  val dtMs = (System.nanoTime()-t0)/1000000
  println(s"'connecting' took: $dtMs[ms]")

  //stringsTest(endpoint)
  pingTest(endpoint)

  nettyTransport.shutdown.run
  System.exit(0)

}
