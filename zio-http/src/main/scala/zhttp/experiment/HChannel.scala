package zhttp.experiment

import io.netty.channel.{ChannelHandler, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.HttpObject
import zhttp.service.HttpRuntime
import zio.{UIO, ZIO}

trait HChannel[-R, +E, -A, +B] {
  def message(message: A, context: Context[B]): ZIO[R, E, Unit]
  def error(cause: Throwable, context: Context[B]): ZIO[R, E, Unit]

  def compile[R1 <: R](
    zExec: HttpRuntime[R1],
  )(implicit evE: E <:< Throwable): ChannelHandler =
    new ChannelInboundHandlerAdapter {
      private val self = this.asInstanceOf[HChannel[R1, Throwable, HttpObject, HttpObject]]
      override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
        zExec.unsafeRun(ctx)(msg match {
          case msg: HttpObject => self.message(msg, Context(ctx))
          case _               => UIO(ctx.fireChannelRead(msg))
        })
      }

      override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
        zExec.unsafeRun(ctx)(self.error(cause, Context(ctx)))
    }
}

object HChannel {
  def apply[R, E, A, B](
    onRead: (A, Context[B]) => ZIO[R, E, Any] = (a: A, b: Context[B]) => b.fireChannelRead(a),
    onError: (Throwable, Context[B]) => ZIO[R, E, Any] = (a: Throwable, b: Context[B]) => b.fireExceptionCaught(a),
  ): HChannel[R, E, A, B] = new HChannel[R, E, A, B] {
    override def message(message: A, context: Context[B]): ZIO[R, E, Unit]     = onRead(message, context).unit
    override def error(cause: Throwable, context: Context[B]): ZIO[R, E, Unit] = onError(cause, context).unit
  }
}