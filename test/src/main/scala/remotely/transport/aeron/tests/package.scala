package remotely.transport.aeron

import remotely.transport.aeron.tests.server.Test1Client
import remotely.{Remote, Endpoint}

import scalaz.concurrent.Task

import remotely.codecs._


package object tests {

  def stringsTest(endpoint: Endpoint): Unit = {
    val size = 1000000//More then 2000000 requires multi-term fragmentation(aeron only)
    val ts0 = System.currentTimeMillis()
    val t = runBigIdStringRequests(endpoint, size)
    val N = 1000
    val successes = (1 to N).map(i => {
      println(s"idString attempt $i")
      val res = t.run
      res.length
    }).map(_ == size).count(_ == true)
    println(s"dt = ${System.currentTimeMillis() - ts0}")
    println(s"Successes: $successes")
  }

  def pingTest(endpoint: Endpoint): Unit = {
    val t = runIdLongRequests(endpoint)_
    //(1 to 100).foreach(_=>t.runAsync(_=>()))
    val times: List[Long] = (1l to 1000l).map(x => t(x).run).foldLeft(List.empty[Long])((ts, t) => t :: ts)
    val sorted = times.sorted
    times foreach println
    println("min: " + sorted.head)
    println("max: " + sorted.last)
    println("avg:" + times.sum / times.length)
  }

  def runIdLongRequests(endpoint:Endpoint)(x:Long): Task[Long] = {
    Task.delay[Remote[Long]] {
      Test1Client.idLong(Remote.local(x))
    }.flatMap(_.runWithoutContext(endpoint))
      .map({
      println(x)
      System.nanoTime() - _})
      .map(_ / 1000)
  }

  def runBigIdStringRequests(endpoint:Endpoint, size:Int): Task[String] = {
    Task.delay[Remote[String]] {
      val s = String.valueOf(Array.fill(size)('a'))
      Test1Client.idString(Remote.local(s))
    }.flatMap(_.runWithoutContext(endpoint))
  }
  
}
