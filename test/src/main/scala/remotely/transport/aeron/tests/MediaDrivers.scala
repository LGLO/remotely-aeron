package remotely.transport.aeron.tests

import uk.co.real_logic.aeron.driver.{MediaDriver, ThreadingMode}

object MediaDrivers extends App{

  val ctx = new MediaDriver.Context().threadingMode(ThreadingMode.DEDICATED)
  val driver = MediaDriver.launch(ctx)
  println("Press Enter to stop Aeron Media Driver")
  Console.readLine()
  driver.close()
  System.exit(0)
}