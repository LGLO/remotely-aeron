package remotely.transport.aeron.tests

import remotely.{Field, Protocol, Signature, Type, codecs}
import scodec.Codec

object Test1Protocol {

  import remotely.codecs._

  implicit lazy val sigCodec: Codec[List[Signature]] = codecs.list(Signature.signatureCodec)

  val definition = Protocol.empty
    .codec[Int]
    .codec[Long]
    .codec[String]
    .specify0("zeroInt", Type[Int])
    .specify0("zeroLong", Type[Long])
    .specify0("zeroString", Type[String])
    .specify1("idInt", Field.strict[Int]("a"), Type[Int])
    .specify1("idLong", Field.strict[Long]("a"), Type[Long])
    .specify1("idString", Field.strict[String]("a"), Type[String])
    .specify2("addInt", Field.strict[Int]("a"), Field.strict[Int]("b"), Type[Int])

}

