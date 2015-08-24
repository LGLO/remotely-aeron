package remotely.transport.aeron.tests

import remotely.GenServer
import remotely.GenClient
import remotely.Signature

object server {

  @GenServer(remotely.transport.aeron.tests.Test1Protocol.definition) abstract class Test1Server

  @GenClient(remotely.transport.aeron.tests.Test1Protocol.definition.signatures) object Test1Client
}
