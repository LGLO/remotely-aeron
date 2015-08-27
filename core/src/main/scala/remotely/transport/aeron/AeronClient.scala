//: ----------------------------------------------------------------------------
//: Copyright (C) 2015 Lech Głowiak.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------

package remotely.transport.aeron

import java.net.InetSocketAddress
import java.util.concurrent.{Executors, ExecutorService}
import java.util.concurrent.atomic.AtomicBoolean

import remotely.Response.{Context => RemotelyContext}
import remotely._
import scodec.Attempt
import scodec.Attempt.{Failure, Successful}
import scodec.bits.BitVector
import uk.co.real_logic.aeron.{Publication, Subscription, Aeron, FragmentAssembler}
import uk.co.real_logic.aeron.logbuffer.{Header, FragmentHandler}
import uk.co.real_logic.agrona.DirectBuffer
import uk.co.real_logic.agrona.concurrent.{NoOpIdleStrategy, UnsafeBuffer}


import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.{Cause, Process}
import scalaz.{-\/, \/-}

class AeronClient(val aeron: Aeron,
                  val publication: Publication,
                  val subs: Subscription,
                  val log: Monitoring) extends Handler {

  val running: AtomicBoolean = new AtomicBoolean(true)
  val serverChannel = publication.channel()
  val offerEs = Executors.newSingleThreadExecutor(Strategy.DefaultDaemonThreadFactory)

  def apply(toServer: Process[Task, BitVector]): Process[Task, BitVector] = {
    // read next byte vector from subscription to this queue

    val pubToUse = Task.now(publication)
    val server = toServer.map(b => new UnsafeBuffer(b.toByteArray))
    Process.await(pubToUse) { pub =>
      val writeBytes: Task[Unit] = server.evalMap(write(pub)).run
      val result: Process[Task, BitVector] = Process.await(writeBytes)(_ => readNextFrame).onHalt {
        case Cause.End =>
          //Stop reading from subscription
          Process.Halt(Cause.End)
        case cause =>
          //Stop reading from subscription
          Process.Halt(cause)
      }
      result
    }
  }

  def readNextFrame: Process[Task, BitVector] = {
    val t0 = System.currentTimeMillis()
    running.set(true)
    var ret: Option[BitVector] = None
    val h = new FragmentAssembler(
      new FragmentHandler {
        override def onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
          ret = Some(fromDirectBuffer(buffer, offset, length))
          running.set(false)
        }
      })

    while (running.get() && (System.currentTimeMillis() - t0) < 30000) {
      //TODO - don't hardcode
      val fragmentsRead = subs.poll(h, 10)
      backoffStrategy.idle(fragmentsRead)
    }
    if (ret.isDefined) {
      Process.emit(ret.get)
    } else {
      Process.fail(new RuntimeException("Could not read response"))
    }
  }

  def write(pub: Publication)(buf: DirectBuffer): Task[Unit] =
    offerResponse(pub, buf, "Request")(offerEs)

  def shutdown: Task[Unit] = Task {
    offerEs.shutdown()
    val stream: StreamId = publication.streamId()
    val pub = aeron.addPublication(serverChannel, connectStream)
    val data = ConnectCodecs.encode(DisconnectRequest(stream))
    val es: ExecutorService = Executors.newSingleThreadExecutor()
    offerResponse(pub, data, s"Disconnect '$stream' request")(es).attemptRun
    es.shutdown()
    pub.close()
    publication.close()
    subs.close()
    running.set(false)
  }

}


object AeronClient {
  type Log = (String, Option[Throwable]) => Unit

  class Starter(val serverAddr: InetSocketAddress,
                val clientAddr: InetSocketAddress,
                val expectedSigs: Set[Signature] = Set.empty,
                val aeron: Aeron,
                val es: ExecutorService,
                val logger: Monitoring) {

    val server: Channel = channel(serverAddr)
    val client: Channel = channel(clientAddr)

    def go(): Task[AeronClient] = {
      val connectPublication = aeron.addPublication(server, connectStream)
      val subscription = aeron.addSubscription(client, clientStream)
      val connect: Task[AeronClient] = for {
        _ <- sendCapabilitiesRequest(connectPublication)
        c <- receiveCapabilities(subscription)
        _ <- sendStreamIdRequest(connectPublication)
        _ <- Task(connectPublication.close())
        streamId <- receiveStreamId(subscription)
        pub <- Task(aeron.addPublication(server, streamId))
        _ <- sendDescribeRequest(pub, streamId)
        _ <- receiveDescribeResponse(subscription, expectedSigs)
      } yield new AeronClient(aeron, pub, subscription, logger)
      connect.onFinish(_ => Task.now(es.shutdown()))
    }

    val log: Log = (msg, err) => logger.negotiating(Some(serverAddr), msg, err)

    def receiveStreamId(s: Subscription): Task[StreamId] = {
      Task.async(cb => {
        val running = new AtomicBoolean(true)
        val strategy = new NoOpIdleStrategy()
        val h = new FragmentAssembler(
          new StreamIdResponseHandler(running, cb, log), 1024)
        while (running.get) {
          val fragmentsRead = s.poll(h, 1)
          strategy.idle(fragmentsRead)
        }
      })
    }

    def receiveCapabilities(s: Subscription): Task[Capabilities] = {
      val running = new AtomicBoolean(true)
      val strategy = new NoOpIdleStrategy()
      Task.async[Capabilities] { cb =>
        log("Receiving capabilities...", None)
        val h = new CapabilitiesResponseHandler(running, cb, log)
        val clientCapabilitiesHandler = new FragmentAssembler(h, 1024)
        while (running.get) {
          val fragmentsRead = s.poll(clientCapabilitiesHandler, 1)
          strategy.idle(fragmentsRead)
        }
        log("Receive capabilities END.", None)
      }
    }

    def receiveDescribeResponse(respSubs: Subscription, expected: Set[Signature]): Task[Unit] = {
      log("Awaiting 'describe' response", None)
      val running = new AtomicBoolean(true)
      val strategy = new NoOpIdleStrategy()
      Task.async[Set[Signature]] { cb =>
        val h = new DescribeResponseHandler(running, cb, log)
        val clientCapabilitiesHandler = new FragmentAssembler(h, 1024)
        while (running.get) {
          val fragmentsRead = respSubs.poll(clientCapabilitiesHandler, 1)
          strategy.idle(fragmentsRead)
        }
      }.map(s => {
        log(s"Received 'describe' response: $s", None)
        val missing = expected -- s
        if (missing.nonEmpty)
          throw new RuntimeException("Describe mismatch. Missing signatures: " + missing)
      })
    }

    def sendCapabilitiesRequest(pub: Publication): Task[Unit] = {
      val data = ConnectCodecs.encode(CapabilitiesRequest(client))
      offerResponse(pub, data, "Capabilities request")(es).onFinish(x => Task {
        log("Capabilities request sent", x)
      })
    }

    def sendStreamIdRequest(pub: Publication): Task[Unit] = {
      val data = ConnectCodecs.encode(StreamIdRequest(client))
      offerResponse(pub, data, "StreamId request")(es).onFinish(x => Task {
        log("StreamId request sent", x)
      })
    }

    def startResponsesSubscription(): Task[Subscription] =
      Task {
        val s = aeron.addSubscription(client, clientStream)
        log(s"Started responses subscription: $client/$clientStream", None)
        s
      }

    def sendDescribeRequest(pub: Publication, stream: StreamId): Task[Unit] = Task {
      val bv = codecs.encodeRequest(Remote.ref[List[Signature]]("describe"), RemotelyContext.empty) match {
        case Successful(bitVector) =>
          log("Describe request encoded", None)
          bitVector
        case Failure(err) =>
          throw new RuntimeException("Could not encode 'describe' request") //merely possible
      }

      val bytes = bv.toByteArray
      val buffer = new UnsafeBuffer(bytes)
      val logMsg = s"'describe' to ${pub.channel()}, $stream request"
      offerResponse(pub, buffer, logMsg)(es).attemptRun match {
        case \/-(_) => log("'describe' request sent", None)
        case -\/(t) => log("'describe' request sending failed", Some(t))
          throw new RuntimeException("Could not send 'describe' request", t)
      }
    }
  }

  def single(serverAddr: InetSocketAddress,
             clientAddr: InetSocketAddress,
             expectedSigs: Set[Signature] = Set.empty,
             workerThreads: Option[Int] = None,
             M: Monitoring = Monitoring.empty,
             aeron: Aeron): Task[AeronClient] = {

    //Dunno why single thread was deadlocking sometimes
    val es = Executors.newFixedThreadPool(2, Strategy.DefaultDaemonThreadFactory)
    new Starter(serverAddr, clientAddr, expectedSigs, aeron, es, M).go()
  }

}

/** *
  * Use only as delegate of FragmentAssembler.
  */
class CapabilitiesResponseHandler(running: AtomicBoolean,
                                  cb: Callback[Capabilities],
                                  logger: (String, Option[Throwable]) => Unit)
  extends FragmentHandler {

  import remotely.Capabilities

  override def onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
    val data = Array.ofDim[Byte](length)
    buffer.getBytes(offset, data)
    val str = new String(data, "UTF-8")
    logger(s"Received capabilities string: $str", None)
    val r: Attempt[Capabilities] = Capabilities.parseHelloString(str)
    running.set(false)
    r match {
      case Successful(c) =>
        logger(s"Decoded capabilities: $c", None)
        cb(\/-(c))
      case Failure(err) =>
        val t = new scala.RuntimeException(err.toString())
        logger(s"Decoding capabilities: $str", Some(t))
        cb(-\/(t))
    }
  }
}

class StreamIdResponseHandler(running: AtomicBoolean,
                              cb: Callback[StreamId],
                              logger: (String, Option[Throwable]) => Unit) extends FragmentHandler {
  override def onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
    running.set(false)
    try {
      //TODO: use scodec and \/-[String, StreamId] for Response
      val stream = buffer.getInt(offset)
      if (stream > 1) {
        logger(s"Requests streamId: $stream", None)
        cb(\/-(stream))
      } else {
        val t = new scala.RuntimeException(s"Illegal streamId: $stream")
        logger(s"Get requestStreamId", Some(t))
        cb(-\/(t))
      }
    } catch {
      case e: Exception =>
        logger(s"Couldn't decode stream response", Some(e))
        cb(-\/(e))
    }
  }
}

/** *
  * Use only as delegate of FragmentAssembler.
  */
class DescribeResponseHandler(running: AtomicBoolean, cb: Callback[Set[Signature]], log: (String, Option[Throwable]) => Unit) extends FragmentHandler {

  override def onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
    val bv = fromDirectBuffer(buffer, offset, length)
    val str = new String(bv.toByteArray, "UTF-8")
    val sigs = codecs.responseDecoder[List[Signature]](codecs.list(Signature.signatureCodec)).complete.decode(bv).map(_.value)
    running.set(false)
    sigs match {
      case Successful(errorOrSignatures) =>
        errorOrSignatures.fold(
          l => cb(-\/(new RuntimeException(l))),
          r => cb(\/-(r.toSet))
        )
      case Failure(err) =>
        val t = new RuntimeException(s"Could not decode signatures from $str")
        log(s"Decode signatures failed: $err", Some(t))
        cb(-\/(t))
    }
  }
}
