package remotely.transport.aeron.tests

import server.Test1Server

class Test1ServerImpl extends Test1Server {
  import remotely.Response

  val zeroInt: Response[Int] = Response.now(0)

  val zeroString: Response[String] = Response.now("")

  val zeroLong: Response[Long] = Response.now(0l)

  val idInt = (a: Int) => Response.now(a)

  val idLong = (a: Long) => Response.now(a)

  val idString = (a: String) => Response.now(a)

  val addInt = (a: Int, b: Int) => Response.now(a + b)


//
//  val addString = (a: String, b: String) => Response.now(a + b)

}
