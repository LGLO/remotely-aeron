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

import remotely._
import scodec.bits.BitVector

import scalaz.stream.Process

/** This object is required only because original Remotely Environment class
  * has private access modifier on 'serverHandler' method.
  *
  * What copy right header should be applied?
  */
object EnvironmentUtil {
  def serverHandler(env: Environment[_], monitoring: Monitoring): Handler = { bytes =>
    bytes pipe Process.await1[BitVector] evalMap { bs =>
      Server.handle(env)(bs)(monitoring)
    }
  }
}
