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
import java.util.concurrent._
import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}

import remotely._
import uk.co.real_logic.aeron.Aeron.Context
import uk.co.real_logic.aeron._
import uk.co.real_logic.aeron.logbuffer.{Header, FragmentHandler}
import uk.co.real_logic.agrona.DirectBuffer
import uk.co.real_logic.agrona.concurrent.{IdleStrategy, UnsafeBuffer}

import scalaz.stream.{Process, async}
import scalaz.stream.async.mutable.Queue
import scalaz.{\/-, -\/}
import scalaz.concurrent.{Strategy, Task}

object AeronServer {

  val nextStreamId = new AtomicInteger(2)
  val firstStreamId = 2

  /** Starts processes: acceptor, workers, connectStreamReader.
    * Pure 'side effect maker' */
  class Starter(aeron: Aeron,
                address: InetSocketAddress,
                handler: Handler,
                capabilities: Capabilities,
                logger: Monitoring,
                acceptorQ: Queue[AcceptorEvent],
                states: AcceptorState,
                running: AtomicBoolean,
                es:ExecutorService,
                strategy: IdleStrategy) {

    def go(): Unit = {
      startWorkers()
      startAcceptorProcess()
      startConnectStreamReader()
    }

    def startConnectStreamReader(): Unit = {
      val requestsHandler = new ConnectRequestsHandler(
        aeron, channel(address), capabilities, handler, es, logger, acceptorQ)
      val subs = aeron.addSubscription(channel(address), connectStream)
      logger.negotiating(Some(address), "Connect stream subscription added...", None)
      readConnectStream(requestsHandler, subs)
    }

    def readConnectStream(handler: ConnectRequestsHandler,
                          subs: Subscription): Unit = {
      Task[Unit] {
        val fragmentsAssembler = new FragmentAssembler(handler)
        try {
          while (running.get) {
            val fragmentsRead = subs.poll(fragmentsAssembler, 10)
            strategy.idle(fragmentsRead)
          }
        } finally {
          close(subs)
        }
      }(es).runAsync({
        case \/-(_) =>
          logger.negotiating(Some(address), "Normal acceptor loop exit", None)
        case -\/(e) =>
          logger.negotiating(Some(address), "Abnormal acceptor loop exit", Some(e))
      })
    }

    def startAcceptorProcess(): Unit = {
      val acceptor = new Acceptor(aeron, logger, es, channel(address), acceptorQ, states).process.run
      Task.fork(acceptor)(es).runAsync({
        case \/-(()) => logger.negotiating(Some(address), "Acceptor finished", None)
        case -\/(e) => logger.negotiating(Some(address), "Acceptor terminated", Some(e))
      })
    }

    def startWorkers(): Unit =
      states.workerQueues foreach startWorker.tupled

    val startWorker = (id: WorkerId, q: Queue[WorkerEvent]) => {
      val w = new Worker(q, acceptorQ, es, strategy, handler, logger, aeron, address)
      w.process.run.runAsync({
        case \/-(_) => logger.negotiating(Some(address), s"Worker $id stopped", None)
        case -\/(err) => logger.negotiating(Some(address), s"Worker $id stopped abnormally", Some(err))
      })
    }

  }

  def start(address: InetSocketAddress,
            env: Environment[_],
            workers: Int = 1,
            streamsPerWorker: Int = 1,
            capabilities: Capabilities = Capabilities.default,
            logger: Monitoring = Monitoring.empty,
            strategy: IdleStrategy): Task[Task[Unit]] = {

    val handler = EnvironmentUtil.serverHandler(env, logger)
    //3 threads: Acceptor process, connectStreamReader, connect responses sender
    //+ workers threads + one extra I cannot explain
    val es = Executors.newFixedThreadPool(4 + workers, Strategy.DefaultDaemonThreadFactory)
    Task {
      val running = new AtomicBoolean(true)
      val acceptorQ: Queue[AcceptorEvent] = async.unboundedQueue[AcceptorEvent]
      val aeron = connectToAeron(acceptorQ, address, logger)
      val states = initialState(workers, streamsPerWorker)
      new Starter(aeron, address, handler, capabilities, logger, acceptorQ, states, running, es, strategy).go()
      Task {
        logger.negotiating(Some(address), "Stopping...", None)
        running.set(false)
        aeron.close()
        logger.negotiating(Some(address), "Stopped", None)
      }
    }

  }

  def connectToAeron(acceptorQ: Queue[AcceptorEvent], addr: InetSocketAddress, logger: Monitoring): Aeron = {
    logger.negotiating(Some(addr), "Connecting to Aeron media driver", None)
    val ctx = new Context()
      .unavailableImageHandler(onUnavailableImage(logger))
      .availableImageHandler(onAvailableImage(logger))
      .driverTimeoutMs(10000)
    val aeron = Aeron.connect(ctx)
    logger.negotiating(Some(addr), "Connected to Aeron media driver", None)
    aeron
  }

  def initialState(workers: WorkerId, streamsPerWorker: WorkerId): AcceptorState = {

    val rss: Seq[(WorkerId, AcceptorWorkerState)] =
      (0 until workers).map(w => {
        val sw1 = firstStreamId + w * streamsPerWorker
        val swN = sw1 + streamsPerWorker
        val streams = (sw1 until swN).toSet
        (w, AcceptorWorkerState(w, streams, async.unboundedQueue[WorkerEvent]))
      })

    new AcceptorState(rss.toMap)
  }

  def close(s: Subscription): Unit = {
    try {
      s.close()
    } catch {
      case t: Throwable =>
        t.printStackTrace()
    }
  }

  def onUnavailableImage(M: Monitoring): UnavailableImageHandler =
    (i: Image, s: Subscription, position: Long) =>
      M.negotiating(None, s"Inactive image = $i, subscription = $s at position = $position", None)

  def onAvailableImage(M: Monitoring): AvailableImageHandler =
    (i: Image, s: Subscription, joiningPos: Long, source: String) =>
      M.negotiating(None, s"New image $i, subscription = $s at position=$joiningPos from $source", None)

}

class ConnectRequestsHandler(val aeron: Aeron,
                             val srvChn: Channel,
                             val capabilities: Capabilities,
                             val handler: Handler,
                             val es: ExecutorService,
                             val logger: Monitoring,
                             val q: Queue[AcceptorEvent]) extends FragmentHandler {

  val encodedCapabilities = Capabilities.capabilitiesCodec.encode(capabilities).require.toByteArray
  val capabilitiesBuffer = new UnsafeBuffer(encodedCapabilities)

  override def onFragment(buffer: DirectBuffer, offset: Int, length: Int, h: logbuffer.Header): Unit =
    ConnectCodecs.decode(buffer, offset, length) match {
      case CapabilitiesRequest(c) => sendCapabilitiesResponse(c)
      case StreamIdRequest(c) => q.enqueueOne(StreamRequest(c)).run
      case DisconnectRequest(s) => q.enqueueOne(Disconnect(s)).run
    }

  def sendCapabilitiesResponse(client: Channel): Unit = {
    val pub = aeron.addPublication(client, connectStream)
    val a = Some(addr(client))
    logger.negotiating(a, "Sending Capabilities", None)
    offerResponse(pub, capabilitiesBuffer, "Capabilities")(es).attemptRun match {
      case \/-(_) => ()
      case -\/(t) => logger.negotiating(a, "Could not send capabilities request", Some(t))
    }
    pub.close()
  }

}

class RequestsHandler(val handler: Handler,
                      val pub: Publication,
                      val running: AtomicBoolean,
                      val logger: Monitoring,
                      val q: Queue[WorkerEvent],
                      val es: ExecutorService) extends FragmentHandler {

  logger.negotiating(None, s"RequestsHandler created $this", None)

  override def onFragment(b: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
    q.enqueueOne(UpdateTs(header.streamId(), System.currentTimeMillis())).run
    val bv = fromDirectBuffer(b, offset, length)
    val outP = handler(Process.emit(bv))
    val write: Task[Unit] = outP.evalMap { b =>
        offerResponse(pub, toDirectBuffer(b), "Response")(es)
    }.run

    write.runAsync {
      case -\/(e) =>
        logger.negotiating(None, s"uncaught exception in connection-processing logic: $e", Some(e))
        running.set(false)
      case _ =>
    }
  }

}






