package zhttp.http.cookie
import zhttp.http.cookie.Cookie.{Request, SameSite}
import io.netty.handler.codec.http.{cookie => jCookie}
import zhttp.http.Path
import zhttp.service.Log

import scala.jdk.CollectionConverters._
sealed trait CookieDecoder[A] {
  type Out

  final def apply(cookie: String): Out = unsafeDecode(cookie, strict = false)
  def unsafeDecode(header: String, strict: Boolean): Out
}

object CookieDecoder {
  val log = Log.withTags("Cookie")

  implicit object RequestCookieDecoder extends CookieDecoder[Cookie.Request] {
    override type Out = List[RequestCookie]

    override def unsafeDecode(header: String, strict: Boolean): List[Cookie[Request]] = {
      val decoder = if (strict) jCookie.ServerCookieDecoder.STRICT else jCookie.ServerCookieDecoder.LAX
      decoder.decodeAll(header).asScala.toList.map { cookie =>
        Cookie(cookie.name(), cookie.value())
      }
    }
  }

  implicit object ResponseCookieDecoder extends CookieDecoder[Cookie.Response] {
    override type Out = ResponseCookie
    override def unsafeDecode(header: String, strict: Boolean): ResponseCookie = {
      val decoder = if (strict) jCookie.ClientCookieDecoder.STRICT else jCookie.ClientCookieDecoder.LAX

      val cookie = decoder.decode(header).asInstanceOf[jCookie.DefaultCookie]

      val response = Cookie.Response(
        domain = Option(cookie.domain()),
        path = Option(cookie.path()).map(Path.decode),
        maxAge = Option(cookie.maxAge()).filter(_ >= 0),
        isSecure = cookie.isSecure(),
        isHttpOnly = cookie.isHttpOnly(),
        sameSite = cookie.sameSite() match {
          case jCookie.CookieHeaderNames.SameSite.Strict => Option(SameSite.Strict)
          case jCookie.CookieHeaderNames.SameSite.Lax    => Option(SameSite.Lax)
          case jCookie.CookieHeaderNames.SameSite.None   => Option(SameSite.None)
          case _                                         => None
        },
      )
      Cookie(cookie.name(), cookie.value(), response)
    }
  }
}
