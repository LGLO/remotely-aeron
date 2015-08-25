package remotely.transport.aeron.tests

import java.net.InetSocketAddress

import remotely.transport.aeron.tests.OriginalClient._
import remotely.transport.netty.NettyServer

object OriginalServerMain extends App{

  val bindAddress = if (args.length >= 1) args(0) else "127.0.0.1"

  val server = new Test1ServerImpl
  val addr = new InetSocketAddress(bindAddress, 8822)
  val shutdown = server.environment.serve(addr).run
  println(s"Listening for connections on $bindAddress")
  println("Press Enter to stop server")
  Console.readLine()
  shutdown.run
  
}
