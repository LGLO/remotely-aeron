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
import java.util.concurrent.{Executors, ScheduledExecutorService, ExecutorService}
import java.util.concurrent.atomic.AtomicBoolean

import remotely.{Handler, Monitoring}
import uk.co.real_logic.aeron.logbuffer.FragmentHandler
import uk.co.real_logic.aeron.{Aeron, Subscription, FragmentAssembler, Publication}

import scala.concurrent.duration.Duration
import scalaz.{\/-, -\/}
import scalaz.concurrent.{Strategy, Task}

import scalaz.stream.async.mutable.Queue
import scalaz.stream.{time, wye, Process}

sealed trait WorkerEvent

case class Open(publication: Publication, stream: StreamId) extends WorkerEvent

case class Close(s: StreamId) extends WorkerEvent

case object CheckTimeouts extends WorkerEvent

case class UpdateTs(s:StreamId, ts:Timestamp) extends WorkerEvent


case class StreamState(running: AtomicBoolean, lastHbTs: Timestamp){

  def stop(): Unit = running.set(false)

  def isTimedOut(current: Timestamp) = current - lastHbTs > 5000 //TODO: don't hardcode

  def updateHbTs(ts: Timestamp) = copy(lastHbTs = ts)
}

case class WorkerState(m: Map[StreamId, StreamState]) {

  def stop(s: StreamId): WorkerState = {
    m.get(s).foreach(_.stop()) //I don't update current state because I never read 'running'. Maybe some other primitive should be used.
    WorkerState(m - s)
  }

  def add(sid: StreamId, ss: StreamState): WorkerState =
    WorkerState(m.updated(sid, ss))

  def checkTimeouts: WorkerState = {
    val t = System.currentTimeMillis()
    val timedOut = m.filter(_._2.isTimedOut(t))
    timedOut.foreach(_._2.stop())
    WorkerState(m -- timedOut.keys)
  }

  def updateHbTs(stream: StreamId, ts: Timestamp): WorkerState =
    WorkerState(m.get(stream).map(
      ss => m.updated(stream, ss.updateHbTs(ts))
    ).getOrElse(m))

}

object WorkerState {
  val empty = WorkerState(Map.empty)
}

object Worker{
  implicit val ses: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(Strategy.DefaultDaemonThreadFactory)
}

class Worker(val q: Queue[WorkerEvent],
             val acceptorQ: Queue[AcceptorEvent],
             val es: ExecutorService,
             val handler: Handler,
             val logger: Monitoring,
             val aeron: Aeron,
             val address: InetSocketAddress) {

  val srvChn: Channel = channel(address)

  val w = wye.dynamic( (_:Any) => wye.Request.R, (_:Any) => wye.Request.L)

  implicit val ses1: ScheduledExecutorService = Worker.ses

  val checkTimeouts: Process[Task, WorkerEvent] = time.awakeEvery(Duration("1 second")).map(_=>CheckTimeouts)

  val events: Process[Task, WorkerEvent] = q.dequeue.wye(checkTimeouts)(wye.merge)

  def process: Process[Task, WorkerState] = events.scan(WorkerState.empty)(onEvent)

  def onEvent(s: WorkerState, e: WorkerEvent): WorkerState = {
    e match {
      case Open(pub, stream) =>
        val ss = open(pub, stream)
        s.add(stream, ss)
      case Close(stream) =>
        s.stop(stream)
      case CheckTimeouts =>
        s.checkTimeouts
      case UpdateTs(stream, ts) =>
        s.updateHbTs(stream, ts)
    }
  }

  def open(p: Publication, stream: StreamId): StreamState = {
    val running = new AtomicBoolean(true)
    Task.fork {
      //aeron.addSubscription is effectively synchronized
      val subs = aeron.addSubscription(srvChn, stream)
      logger.negotiating(None, s"Subscribed for requests on stream $stream", None)
      val rh = new RequestsHandler(handler, p, running, logger, q, es)
      val h = new FragmentAssembler(rh)
      logger.negotiating(None, s"Awaiting requests ", None)
      pollWhileRunning(running, subs, h)(es)
    }(es).runAsync {
      case -\/(t) =>
        logger.negotiating(None, s"Run requests subscription:$stream: $t", Some(t))
        acceptorQ.enqueueOne(Closed(stream)).run
      case \/-(()) =>
        logger.negotiating(None, s"Run requests subscription:$stream completed!", None)
        acceptorQ.enqueueOne(Closed(stream)).run
    }
    StreamState(running, System.currentTimeMillis())
  }

  def pollWhileRunning(running: AtomicBoolean, s: Subscription, h: FragmentHandler)
                      (es: ExecutorService): Task[Unit] =
    if (running.get) {
      try {
        val fragmentsRead = s.poll(h, 1)
        busySpinStrategy.idle(fragmentsRead)
        Task.fork {
          pollWhileRunning(running, s, h)(es)
        }
      } catch {
        case e: IllegalStateException =>
          logger.negotiating(Some(address), s"Stream ${s.streamId()} reader stopped abnormally", Some(e))
          Task.fail(e)
      }
    } else {
      logger.negotiating(Some(address), s"Stream ${s.streamId()} reader stopped", None)
      Task.now(())}
}
