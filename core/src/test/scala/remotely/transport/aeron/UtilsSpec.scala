package remotely.transport.aeron

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import scalaz.concurrent.Task

class UtilsSpec extends FlatSpec
  with Matchers
  with BeforeAndAfterAll {

  behavior of "offerResponse"

  it should "not run task before it is run" in {
    val a = new AtomicBoolean(false)
    val t = Task{
      a.set(true)
    }
    val r = retry(t, 1, 1)(Executors.newSingleThreadExecutor())
    a.get() should be(false)
    r.run
    a.get() should be(true)
  }

}
