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

package remotely.transport

import java.net.{URI, InetSocketAddress}
import java.util.concurrent.{TimeUnit, ExecutorService}

import scodec.bits.BitVector
import uk.co.real_logic.aeron._
import uk.co.real_logic.agrona.DirectBuffer
import uk.co.real_logic.agrona.concurrent.{NoOpIdleStrategy, BusySpinIdleStrategy, BackoffIdleStrategy, UnsafeBuffer}

import scalaz.{-\/, \/-, \/}
import scalaz.concurrent.Task

package object aeron {

  type StreamId = Int
  type WorkerId = Int
  type Timestamp = Long
  type Channel = String
  type Callback[A] = (\/[Throwable, A] => Unit)

  val connectStream = 1
  val clientStream = 1

  def channel(a: InetSocketAddress): Channel =
    s"udp://${a.getHostString}:${a.getPort}"
  
  def addr(c:Channel): InetSocketAddress = {
    val uri = new URI(c)
    new InetSocketAddress(uri.getHost, uri.getPort)
  }

  def backoffStrategy() = new BackoffIdleStrategy(
    100, 10,
    TimeUnit.MICROSECONDS.toNanos(1),
    TimeUnit.MICROSECONDS.toNanos(100)
  )

  val noOpStrategy = new NoOpIdleStrategy()

  def retry[A](task: Task[A], retries: Int = 50, backOff: Long = 10)(es:ExecutorService): Task[A] = {

    /** Could be used but Scalaz Timer starts non daemon thread for timer and makes closing app cumbersome*/
    //def after[B](f:Future[B], t: Long): Future[B] =
    //  oneMsTimer.valueWait((), t).flatMap(_ => f)
    def go(n: Int): Task[A] = {
      task.attemptRun match {
        case \/-(x) => Task.now(x)
        case -\/(t) =>
          if (n < retries) {
            //TODO: don't block thread!
            Thread.sleep(backOff)
            /** See comment over 'after'*/
//            val t2: Task[A] = Task.fork {
//              go(n + 1)
//            }(es)
//            new Task(after(t2.get, backOff))
            Task.fork{
              go(n + 1)
            }(es)
          } else
            Task.fail(t)
      }
    }

    Task.suspend(go(0))
  }


  def offerResponse(pub: Publication, buf: DirectBuffer, respName: String)(es:ExecutorService): Task[Unit] = {

    def message(code:Long) = code match {
      case Publication.NOT_CONNECTED => "NOT_CONNECTED"
      case Publication.BACK_PRESSURED => "BACK_PRESSURED"
      case _ => s"code = $code"
    }

    def offerOnce: Task[Unit] = Task.suspend{
      val errCode: Long = pub.offer(buf, 0, buf.capacity())
      if (errCode >= 0) Task.now(())
      else Task.fail(new scala.RuntimeException(s"$respName not sent: ${message(errCode)}"))
    }

    retry(offerOnce, 100, 10)(es)
  }

  // Aeron's DirectBuffer utilities
  def toDirectBuffer(b: BitVector): DirectBuffer = new UnsafeBuffer(b.toByteArray)

  def fromDirectBuffer(buf : DirectBuffer, offset: Int, length:Int):BitVector = {
    val data = Array.ofDim[Byte](length)
    buf.getBytes(offset, data)
    BitVector(data)
  }

  def readString(buffer: DirectBuffer, offset: Int, length: Int): String = {
    val data = Array.ofDim[Byte](length)
    buffer.getBytes(offset, data)
    new String(data)
  }

  implicit def funToNewImageHandler(f: (Image, Subscription, Long, String) => Unit): NewImageHandler =
    new NewImageHandler {
      override def onNewImage(image: Image, s: Subscription, joiningPosition: Long, sourceIdentity: String): Unit =
        f(image, s, joiningPosition, sourceIdentity)
    }

  implicit def funToInactiveImageHandler(f: (Image, Subscription, Long) => Unit): InactiveImageHandler =
    new InactiveImageHandler {
      override def onInactiveImage(image: Image, s: Subscription, position: Long): Unit =
        f(image, s, position)
    }

}
