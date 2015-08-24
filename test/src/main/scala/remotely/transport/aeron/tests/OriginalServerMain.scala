package remotely.transport.aeron.tests

import java.net.InetSocketAddress

import remotely.transport.netty.NettyServer

object OriginalServerMain extends App{

  val server = new Test1ServerImpl
  val addr = new InetSocketAddress("localhost", 8822)
  val shutdown = server.environment.serve(addr).run
  println("Press Enter to stop server")
  Console.readLine()
  shutdown.run
  
}
