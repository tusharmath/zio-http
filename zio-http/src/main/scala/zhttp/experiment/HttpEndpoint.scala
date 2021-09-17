package zhttp.experiment

import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel._
import io.netty.handler.codec.http._
import zhttp.experiment.HttpEndpoint.InvalidMessage
import zhttp.experiment.HttpMessage._
import zhttp.experiment.ServerEndpoint.CanDecode
import zhttp.http._
import zhttp.service.HttpRuntime
import zio.stream.ZStream
import zio.{Chunk, Promise, Queue, UIO, ZIO}

import scala.collection.mutable

sealed trait HttpEndpoint[-R, +E] { self =>
  def orElse[R1 <: R, E1 >: E](other: HttpEndpoint[R1, E1]): HttpEndpoint[R1, E1] = HttpEndpoint.OrElse(self, other)
  def <>[R1 <: R, E1 >: E](other: HttpEndpoint[R1, E1]): HttpEndpoint[R1, E1]     = self orElse other

  private[zhttp] def compile[R1 <: R](zExec: HttpRuntime[R1])(implicit
    evE: E <:< Throwable,
  ): ChannelHandler =
    new ChannelInboundHandlerAdapter { ad =>
      import HttpVersion._
      import HttpResponseStatus._

      type CompleteHttpApp = Http[R, Throwable, CompleteRequest[ByteBuf], AnyResponse[R, Throwable, ByteBuf]]
      private var bQueue: Queue[HttpContent] = _
      private var anyRequest: AnyRequest     = _

      private val app: HttpEndpoint[R, Throwable]              = self.asInstanceOf[HttpEndpoint[R, Throwable]]
      private var isComplete: Boolean                          = false
      private var isBuffered: Boolean                          = false
      private var cHttpApp: CompleteHttpApp                    = Http.empty
      private val cBody: ByteBuf                               = Unpooled.compositeBuffer()
      private var decoder: ContentDecoder[Any, Throwable, Any] = _
      private var completePromise: Promise[Throwable, Any]     = _
      private var isFirst: Boolean                             = true
      private var decoderState: Any                            = _

      override def channelRegistered(ctx: ChannelHandlerContext): Unit = {
        ctx.channel().config().setAutoRead(false)
        ctx.read(): Unit
      }

      override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
        val void = ctx.voidPromise()
        val read = UIO(ctx.read())

        def unsafeWriteEmptyLastContent[A](): Unit = {
          ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT): Unit
        }

        def unsafeWriteLastContent[A](data: ByteBuf): Unit = {
          ctx.writeAndFlush(new DefaultLastHttpContent(data)): Unit
        }

        def writeStreamContent[A](stream: ZStream[R, Throwable, ByteBuf]) = {
          stream.process.map { pull =>
            def loop: ZIO[R, Throwable, Unit] = pull
              .foldM(
                {
                  case None        => UIO(ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, void)).unit
                  case Some(error) => ZIO.fail(error)
                },
                chunks =>
                  for {
                    _ <- ZIO.foreach_(chunks)(buf => UIO(ctx.write(new DefaultHttpContent(buf), void)))
                    _ <- UIO(ctx.flush())
                    _ <- loop
                  } yield (),
              )

            loop
          }.useNow.flatten
        }

        def run[A](
          http: Http[R, Throwable, A, AnyResponse[R, Throwable, ByteBuf]],
          a: A,
        ): ZIO[R, Throwable, Unit] =
          http
            .executeAsZIO(a)
            .foldM(
              {
                case Some(cause) => UIO(unsafeWriteAndFlushErrorResponse(cause))
                case None        => UIO(unsafeWriteAndFlushNotFoundResponse())
              },
              res =>
                for {
                  _ <- UIO(ctx.write(decodeResponse(res), void))
                  _ <- res.content match {
                    case Content.Empty             => UIO { unsafeWriteEmptyLastContent() }
                    case Content.Complete(data)    => UIO { unsafeWriteLastContent(data) }
                    case Content.Streaming(stream) => writeStreamContent(stream)
                    case Content.FromSocket(_)     => ???
                  }
                } yield (),
            )

        def unsafeWriteAnyResponse[A](res: AnyResponse[R, Throwable, ByteBuf]): Unit = {
          ctx.write(decodeResponse(res), void): Unit
        }

        def unsafeRun[A](http: Http[R, Throwable, A, AnyResponse[R, Throwable, ByteBuf]], a: A): Unit = {
          http.execute(a).evaluate match {
            case HttpResult.Effect(resM) =>
              unsafeRunZIO {
                resM.foldM(
                  {
                    case Some(cause) => UIO(unsafeWriteAndFlushErrorResponse(cause))
                    case None        => UIO(unsafeWriteAndFlushNotFoundResponse())
                  },
                  res =>
                    for {
                      _ <- UIO(unsafeWriteAnyResponse(res))
                      _ <- res.content match {
                        case Content.Empty             => UIO(unsafeWriteAndFlushNotFoundResponse())
                        case Content.Complete(data)    => UIO(unsafeWriteLastContent(data))
                        case Content.Streaming(stream) => writeStreamContent(stream)
                        case Content.FromSocket(_)     => ???
                      }
                    } yield (),
                )
              }

            case HttpResult.Success(a) =>
              unsafeWriteAnyResponse(a)
              a.content match {
                case Content.Empty             => unsafeWriteAndFlushNotFoundResponse()
                case Content.Complete(data)    => unsafeWriteLastContent(data)
                case Content.Streaming(stream) => unsafeRunZIO(writeStreamContent(stream))
                case Content.FromSocket(_)     => ???
              }

            case HttpResult.Failure(e) => unsafeWriteAndFlushErrorResponse(e)
            case HttpResult.Empty      => unsafeWriteAndFlushNotFoundResponse()
          }
        }

        def unsafeWriteAndFlushErrorResponse(cause: Throwable): Unit = {
          ctx.writeAndFlush(serverErrorResponse(cause), void): Unit
        }

        def unsafeWriteAndFlushNotFoundResponse(): Unit = {
          ctx.writeAndFlush(notFoundResponse, void): Unit
        }

        def unsafeRunZIO(program: ZIO[R, Throwable, Any]): Unit = zExec.unsafeRun(ctx) {
          program
        }

        def decodeContent(
          content: ByteBuf,
          decoder: ContentDecoder[Any, Throwable, Any],
          isLast: Boolean,
        ): Unit = {
          decoder match {
            case ContentDecoder.Text =>
              cBody.writeBytes(content)
              if (isLast) {
                unsafeRunZIO(ad.completePromise.succeed(cBody.toString(HTTP_CHARSET)))
              } else {
                ctx.read(): Unit
              }

            case ContentDecoder.Custom(state, run) =>
              if (ad.isFirst) {
                ad.decoderState = state
                ad.isFirst = false
              }
              val nState = ad.decoderState

              unsafeRunZIO(for {
                (publish, state) <- run(Chunk.fromArray(content.array()), nState, isLast)
                _                <- publish match {
                  case Some(out) => ad.completePromise.succeed(out)
                  case None      => ZIO.unit
                }
                _                <- UIO {
                  ad.decoderState = state
                  if (!isLast) ctx.read(): Unit
                }
              } yield ())

          }
        }

        msg match {
          case jRequest: HttpRequest =>
            // TODO: Unnecessary requirement
            // `autoRead` is set when the channel is registered in the event loop.
            // The explicit call here is added to make unit tests work properly
            ctx.channel().config().setAutoRead(false)

            val endpoint = getMatchingEndpoint(jRequest)
            if (endpoint == null) unsafeWriteAndFlushNotFoundResponse()
            else {
              endpoint match {
                case ServerEndpoint.HttpLazy(http) =>
                  unsafeRun(
                    http,
                    new LazyRequest {
                      override def decodeContent[R0, E0, B](
                        decoder: ContentDecoder[R0, E0, B],
                      ): ZIO[R0, E0, B] =
                        for {
                          p <- Promise.make[E0, B]
                          _ <- UIO {
                            ad.decoder = decoder.asInstanceOf[ContentDecoder[Any, Throwable, B]]
                            ad.completePromise = p.asInstanceOf[Promise[Throwable, Any]]
                            ctx.read(): Unit
                          }
                          b <- p.await
                        } yield b

                      override def method: Method        = Method.fromHttpMethod(jRequest.method())
                      override def url: URL              = URL.fromString(jRequest.uri()).getOrElse(null)
                      override def headers: List[Header] = Header.make(jRequest.headers())

                    },
                  )

                case ServerEndpoint.Empty =>
                  unsafeWriteAndFlushNotFoundResponse()

                case ServerEndpoint.HttpAny(http) =>
                  unsafeRun(http, ())

                case ServerEndpoint.HttpComplete(http) =>
                  ad.anyRequest = AnyRequest.from(jRequest)
                  ad.cHttpApp = http
                  ad.isComplete = true
                  ctx.read(): Unit

                case ServerEndpoint.HttpAnyRequest(http) =>
                  unsafeRun(http, AnyRequest.from(jRequest))

                case ServerEndpoint.HttpBuffered(http) =>
                  ad.isBuffered = true

                  unsafeRunZIO {
                    for {
                      _ <- setupBufferedQueue
                      _ <- read
                      _ <- run(http, makeBufferedRequest(AnyRequest.from(jRequest)))
                    } yield ()
                  }
              }
            }

          case msg: LastHttpContent =>
            if (decoder != null) {
              decodeContent(msg.content(), decoder, true)
            } else if (ad.isBuffered) {
              unsafeRunZIO { bQueue.offer(msg) }
            } else if (ad.isComplete) {
              ad.cBody.writeBytes(msg.content())
              unsafeRun(ad.cHttpApp, makeCompleteRequest(anyRequest))
            }

          case msg: HttpContent =>
            if (decoder != null) {
              decodeContent(msg.content(), decoder, false)
            } else if (ad.isBuffered) {
              unsafeRunZIO { bQueue.offer(msg) *> read }
            } else if (ad.isComplete) {
              cBody.writeBytes(msg.content())
              ctx.read(): Unit
            }

          case msg => ctx.fireExceptionCaught(InvalidMessage(msg)): Unit
        }
      }

      private def getMatchingEndpoint(request: HttpRequest): ServerEndpoint[R, Throwable] = {
        val stack = mutable.Stack(app)
        while (stack.nonEmpty) {
          stack.pop() match {
            case HttpEndpoint.OrElse(self, other) =>
              stack.push(other)
              stack.push(self)

            case HttpEndpoint.Default(se, check) =>
              if (check.is(request)) return se

            case null => return null
          }
        }
        null
      }

      // TODO: use `new Stream` to implement a more performant queue
      private def setupBufferedQueue: UIO[Queue[HttpContent]] = for {
        q <- Queue.bounded[HttpContent](1)
        _ <- UIO(ad.bQueue = q)
      } yield q

      private def makeCompleteRequest(anyRequest: AnyRequest) = {
        CompleteRequest(anyRequest, ad.cBody)
      }

      private def makeBufferedRequest(anyRequest: AnyRequest): BufferedRequest[ByteBuf] = {
        anyRequest.toBufferedRequest {
          bQueue.mapM {
            case cnt: LastHttpContent => bQueue.shutdown.as(cnt.content())
            case cnt                  => UIO(cnt.content())
          }
        }
      }

      private def decodeResponse(res: AnyResponse[_, _, _]): HttpResponse = {
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, res.status.asJava, Header.disassemble(res.headers))
      }

      private val notFoundResponse =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, false)

      private def serverErrorResponse(cause: Throwable): HttpResponse = {
        val content  = cause.toString
        val response = new DefaultFullHttpResponse(
          HTTP_1_1,
          INTERNAL_SERVER_ERROR,
          Unpooled.copiedBuffer(content, HTTP_CHARSET),
        )
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length)
        response
      }

    }
}

object HttpEndpoint {

  final case class InvalidMessage(message: Any) extends IllegalArgumentException {
    override def getMessage: String = s"Endpoint could not handle message: ${message.getClass.getName}"
  }

  private[zhttp] final case class Default[R, E](se: ServerEndpoint[R, E], check: Check[HttpRequest] = Check.isTrue)
      extends HttpEndpoint[R, E]

  private[zhttp] final case class OrElse[R, E](self: HttpEndpoint[R, E], other: HttpEndpoint[R, E])
      extends HttpEndpoint[R, E]

  private[zhttp] def mount[R, E](serverEndpoint: ServerEndpoint[R, E]): HttpEndpoint[R, E] =
    Default(serverEndpoint)

  def mount[R, E, A](path: Path, decoder: CanDecode[A])(
    http: Http[R, E, A, AnyResponse[R, E, ByteBuf]],
  ): HttpEndpoint[R, E] = Default(decoder.endpoint(http), Check.startsWith(path))

  def mount[R, E, A](decoder: CanDecode[A])(http: Http[R, E, A, AnyResponse[R, E, ByteBuf]]): HttpEndpoint[R, E] =
    mount(decoder.endpoint(http))

  def mount[R, E, A](http: Http[R, E, A, AnyResponse[R, E, ByteBuf]])(implicit m: CanDecode[A]): HttpEndpoint[R, E] =
    mount(m.endpoint(http))

  def mount[R, E, A](path: Path)(http: Http[R, E, A, AnyResponse[R, E, ByteBuf]])(implicit
    m: CanDecode[A],
  ): HttpEndpoint[R, E] =
    Default(m.endpoint(http), Check.startsWith(path))

  def fail[E](cause: E): HttpEndpoint[Any, E] = mount(ServerEndpoint.fail(cause))

  def empty: HttpEndpoint[Any, Nothing] = mount(ServerEndpoint.empty)

  def collect[A]: MkCollect[A] = new MkCollect(())

  def collectM[A]: MkCollectM[A] = new MkCollectM(())

  final class MkCollect[A](val unit: Unit) extends AnyVal {
    def apply[R, E](pf: PartialFunction[A, AnyResponse[R, E, ByteBuf]])(implicit ev: CanDecode[A]): HttpEndpoint[R, E] =
      HttpEndpoint.mount(Http.collect(pf))
  }

  final class MkCollectM[A](val unit: Unit) extends AnyVal {
    def apply[R, E](pf: PartialFunction[A, ZIO[R, E, AnyResponse[R, E, ByteBuf]]])(implicit
      ev: CanDecode[A],
    ): HttpEndpoint[R, E] =
      HttpEndpoint.mount(Http.collectM(pf))
  }
}