import zhttp.experiment.multipart.{ChunkedData, Message, Parser, Part}
import zhttp.http._
import zhttp.service.Server
import zio._
import zio.stream._

object HelloWorld extends App {
  def extractMessage(msg: Message): Chunk[Byte] = msg match {
    case ChunkedData(chunkedData) => chunkedData
    case _                        => Chunk.empty
  }

  def h1 = HttpApp.fromHttp {
    Http.collectM[Request] { case req =>
      req.decodeContent(ContentDecoder.text).map { content =>
        Response(data = HttpData.fromText(content))
      }
    }
  }

  def h2 = HttpApp.fromHttp {
    Http.collectM[Request] { case req =>
      req.decodeContent(ContentDecoder.backPressure).map { content =>
        Response(data = HttpData.fromStream(ZStream.fromChunkQueue(content)))
      }
    }
  }

  def h3 = {
    HttpApp.collectM { case req =>
      req.decodeContent(ContentDecoder.backPressure).map { content =>
        val s                            = ZStream.fromQueue(content)
        println(req.headers)
        val boundryValue: Option[String] = req.headers.filter(_.name == "Content-Type") match {
          case ::(head, _) =>
            println(head.value.toString)
            head.value.toString.split(";").toList.filter(_.contains("boundary=")) match {
              case ::(head, _) =>
                head.split("=").toList match {
                  case ::(_, next) => Some(next.head)
                  case Nil         => None
                }
              case Nil         => None
            }
          case Nil         => None
        }
        boundryValue match {
          case Some(value) =>
            Response(data =
              HttpData.fromStream(
                new Parser(value)
                  .decodeMultipart(s)
                  .flatMap {
                    case Part.Empty           => ZStream.empty
                    case Part.File(_, d)      =>
                      d.take(1).map(extractMessage).mapChunks(_.flatten)
                    case Part.Attribute(_, _) => ZStream.empty
                  },
              ),
            )
          case None        => Response.status(status = Status.INTERNAL_SERVER_ERROR)
        }

      }
    }
  }

  def app = h3

  // Run it like any simple app
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    Server.start(8090, app).exitCode
}
