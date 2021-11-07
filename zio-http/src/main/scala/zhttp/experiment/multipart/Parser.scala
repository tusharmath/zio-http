package zhttp.experiment.multipart

import java.nio.charset.StandardCharsets

import io.netty.util.CharsetUtil
import zio.Chunk
import zio.stream.ZTransducer

sealed trait State
case object NotStarted   extends State
case object PartHeader   extends State
case object PartData     extends State
case object PartComplete extends State
case object End          extends State

sealed trait PartMeta
case class PartContentDisposition(name: String, filename: Option[String]) extends PartMeta
case class PartContentType(ContentType: String, charset: Option[String])  extends PartMeta
case class PartContentTransferEncoding(encoding: String)                  extends PartMeta

sealed trait Message
case object Boundary                              extends Message
final case class MetaInfo(
  contentDisposition: PartContentDisposition,
  contentType: Option[PartContentType] = None,
  transferEncoding: Option[PartContentTransferEncoding] = None,
) extends Message
final case class ChunkedData(chunkedData: String) extends Message
case object BodyEnd                               extends Message
case object Empty                                 extends Message

class Parser(boundary: String) {
  val boundaryBytes: Chunk[Byte]   = Chunk.fromArray(boundary.getBytes(CharsetUtil.UTF_8))
  val CRLFBytes: Chunk[Byte]       = Chunk.fromArray(Array[Byte]('\r', '\n'))
  val doubleCRLFBytes: Chunk[Byte] = Chunk.fromArray(Array[Byte]('\r', '\n', '\r', '\n'))
  val dashDashBytesN: Chunk[Byte]  = Chunk.fromArray(Array[Byte]('-', '-'))
  val startByte: Chunk[Byte]       = dashDashBytesN ++ boundaryBytes
  val delimiter: Chunk[Byte]       = CRLFBytes ++ startByte
  var state: State                 = NotStarted
  var matchIndex: Int              = 0 // matching index of boundary and double dash
  var CRLFIndex: Int               = 0
  var tempData: Chunk[Byte]        = Chunk.empty
  var partChunk: Chunk[Byte]       = Chunk.empty
  def getMessages(input: Chunk[Byte], startIndex: Int = 0, outChunk: Chunk[Message] = Chunk.empty): Chunk[Message] = {
    state match {
      case NotStarted   => {
        var i            = startIndex
        var outChunkTemp = outChunk
        // Look for starting Boundary
        while (i < input.length && state == NotStarted) {
          if (input.byte(i) == delimiter.byte(matchIndex)) {
            i = i + 1
            matchIndex = matchIndex + 1
            tempData = tempData ++ Chunk(input.byte(i))
            if (matchIndex == delimiter.length) { // match complete
              state = PartHeader                  // start getting part header data
              matchIndex = 0
              tempData = Chunk.empty              // discard boundary bytes
            }
          } else {
            println("invalid input")
            i = input.length // Invalid input. Break the loop
          }
        }
        if (i < input.length && state != NotStarted) { // more data is there in input
          outChunkTemp = getMessages(input, i, outChunk ++ Chunk(Boundary))
        }
        outChunkTemp
      }
      case PartHeader   => {
        var i            = startIndex
        var outChunkTemp = outChunk
        // Look until double CRLF
        while (i < input.length && state == PartHeader) {
          if (doubleCRLFBytes.byte(matchIndex) == input.byte(i)) {
            matchIndex = matchIndex + 1
          } else {
            // do look behind check
            if (doubleCRLFBytes.byte(0) == input.byte(i)) {
              matchIndex = 1
            } else {
              matchIndex = 0
            }
          }
          tempData = tempData ++ Chunk(input.byte(i))
          i = i + 1
          if (matchIndex == doubleCRLFBytes.length) {
            // todo: Add header parsing logic here Parse and create Header Chunk
            matchIndex = 0
            val headerString       = new String(tempData.toArray, StandardCharsets.UTF_8)
            val metaInfo: MetaInfo = headerString
              .split("\r\n")
              .foldLeft(MetaInfo(PartContentDisposition("", None), None, None))((metaInfo, aHeader) => {
                val subPart       = aHeader.split(";").map(_.trim())
                val subPartHeader = subPart.head.split(":")
                val metaInfoType  = subPartHeader.head
                val directiveData = subPart.tail
                  .map(s => {
                    s.split("=").map(_.trim) match {
                      case Array(v1, v2) => (v1, v2)
                      case _             => ("", "") // should throw error here
                    }
                  })
                if (metaInfoType == "Content-Disposition") {
                  metaInfo.copy(contentDisposition =
                    directiveData
                      .foldLeft(PartContentDisposition("", None))((acc, value) => {
                        println(value)
                        if (value._1.toLowerCase == "name") {
                          acc.copy(name = value._2)
                        } else if (value._1.toLowerCase == "filename") {
                          acc.copy(filename = Some(value._2))
                        } else {
                          acc
                        }
                      }),
                  )
                } else if (metaInfoType == "Content-Type") {
                  if (directiveData.isEmpty) {
                    metaInfo.copy(contentType = Some(PartContentType(subPartHeader.tail.head.trim, None)))
                  } else {
                    metaInfo.copy(contentType =
                      Some(
                        directiveData
                          .foldLeft(PartContentType(subPartHeader.tail.head.trim, None))((acc, value) => {
                            if (value._1.toLowerCase == "charset") {
                              acc.copy(charset = Some(value._2))
                            }
                            acc
                          }),
                      ),
                    )
                  }
                } else if (metaInfoType == "Content-Transfer-Encoding") {
                  metaInfo.copy(transferEncoding = Some(PartContentTransferEncoding(subPartHeader.tail.head.trim)))
                } else {
                  metaInfo
                }
              })
            outChunkTemp = outChunkTemp ++ Chunk(metaInfo)
            tempData = Chunk.empty
            state = PartData
          }
        }
        if (i < input.length && state != PartHeader) {
          outChunkTemp = getMessages(input, i, outChunkTemp)
        }
        outChunkTemp
      }
      case PartData     => {
        var i            = startIndex
        var outChunkTemp = outChunk
        // Look until boundary delimiter
        while (i < input.length && state == PartData) {
          if (delimiter.byte(matchIndex) == input.byte(i)) {
            matchIndex = matchIndex + 1
            tempData = tempData ++ Chunk(input.byte(i))
          } else {
            matchIndex = 0
            partChunk = partChunk ++ tempData
            tempData = Chunk.empty
            // do look behind check
            if (delimiter.byte(matchIndex) == input.byte(i)) {
              matchIndex = 1
              tempData = Chunk(input.byte(i))
            } else {
              partChunk = partChunk.appended(input.byte(i))
            }
          }
          if (matchIndex == delimiter.length) {
            outChunkTemp =
              outChunkTemp ++ Chunk(ChunkedData(new String(partChunk.toArray, StandardCharsets.UTF_8)), Boundary)
            partChunk = Chunk.empty
            matchIndex = 0
            tempData = Chunk.empty
            state = PartComplete
          }
          i = i + 1
        }
        if (i < input.length && state != PartData) {
          outChunkTemp = getMessages(input, i, outChunkTemp)
        }
        outChunkTemp
      }
      case PartComplete => {
        var i               = startIndex
        var outputChunkTemp = outChunk
        while (i < input.length && state == PartComplete) {
          if (dashDashBytesN.byte(matchIndex) == input.byte(i)) {
            matchIndex = matchIndex + 1
          } else {
            matchIndex = 0
          }
          if (CRLFBytes.byte(CRLFIndex) == input.byte(i)) {
            CRLFIndex = CRLFIndex + 1
          } else {
            CRLFIndex = 0
          }
          if (matchIndex == 0 && CRLFIndex == 0) {
            println("invalid input")
          }
          if (CRLFIndex == CRLFBytes.length) {
            CRLFIndex = 0
            matchIndex = 0
            state = PartHeader
          }
          if (matchIndex == dashDashBytesN.length) {
            matchIndex = 0
            outputChunkTemp = outputChunkTemp ++ Chunk(BodyEnd)
            state = End
          }
          i = i + 1
        }
        if (i < input.length && state == PartHeader) {
          outputChunkTemp = getMessages(input, i - 1, outputChunkTemp)
        }
        outputChunkTemp
      }
      case End          => outChunk
    }
  }
  val byteToMessageTransducer: ZTransducer[Any, Nothing, Chunk[Byte], Message]                                     =
    ZTransducer.fromFunction[Chunk[Byte], Chunk[Message]](a => getMessages(a)).mapChunks(_.flatten)

//  def byteStreamToMessageStream(input: UStream[Chunk[Byte]]) = input.transduce(byteToMessageTransducer)
}
