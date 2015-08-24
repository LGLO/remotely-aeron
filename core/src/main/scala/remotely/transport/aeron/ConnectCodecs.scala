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

import shapeless.Lazy // needed for implicit codecs
import scodec._
import scodec.codecs._
import remotely.codecs.utf8
import scodec.Attempt.{Failure, Successful}
import uk.co.real_logic.agrona.DirectBuffer

sealed trait ConnectProtocol

case class CapabilitiesRequest(client: Channel) extends ConnectProtocol

case class StreamIdRequest(client: Channel) extends ConnectProtocol

case class DisconnectRequest(stream: StreamId) extends ConnectProtocol

object ConnectCodecs {

  implicit val cq: Codec[CapabilitiesRequest] = utf8.as[CapabilitiesRequest]
  implicit val siq: Codec[StreamIdRequest] = utf8.as[StreamIdRequest]
  implicit val dq: Codec[DisconnectRequest] = int32.as[DisconnectRequest]

  val connectCodec: Codec[ConnectProtocol] = Codec.coproduct[ConnectProtocol].discriminatedByIndex(uint8)

  def encode(m: ConnectProtocol): DirectBuffer =
    connectCodec.encode(m) match {
      case Successful(bv) => toDirectBuffer(bv)
      case Failure(err) => throw new RuntimeException(s"Couldn't encode $m")
    }

  def decode(buf: DirectBuffer, offset: Int, length: Int): ConnectProtocol = {
    val bv = fromDirectBuffer(buf, offset, length)
    connectCodec.decodeValue(bv) match {
      case Successful(a) => a
      case Failure(err) => throw new RuntimeException(s"Couldn't decode with $connectCodec")
    }
  }

}
