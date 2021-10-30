package zhttp.http.middleware

import io.netty.handler.codec.http.HttpHeaderNames
import pdi.jwt._
import pdi.jwt.algorithms.JwtHmacAlgorithm
import zhttp.http.HeaderExtension.BasicSchemeName
import zhttp.http.HttpError.Unauthorized
import zhttp.http.{Header, HeaderExtension, HttpApp, HttpError}
import zio.ZIO

sealed trait AuthMiddleware[-R, +E] { self =>
  def apply[R1 <: R, E1 >: E](app: HttpApp[R1, E1]): HttpApp[R1, E1] = AuthMiddleware.execute(self, app)

  def ++[R1 <: R, E1 >: E](other: AuthMiddleware[R1, E1]): AuthMiddleware[R1, E1] =
    self combine other

  def combine[R1 <: R, E1 >: E](other: AuthMiddleware[R1, E1]): AuthMiddleware[R1, E1] =
    AuthMiddleware.Combine(self, other)

}
object AuthMiddleware {
  private final case class AuthFunction[R, E](f: List[Header] => ZIO[R, E, Boolean], h: List[Header])
      extends AuthMiddleware[R, E]
  private final case class JwtSecret(secretKey: String, algo: Seq[JwtHmacAlgorithm])
      extends AuthMiddleware[Any, Nothing]
  private final case class Combine[R, E](self: AuthMiddleware[R, E], other: AuthMiddleware[R, E])
      extends AuthMiddleware[R, E]

  def jwt(secretKey: String, algo: Seq[JwtHmacAlgorithm] = Seq(JwtAlgorithm.HS512)): AuthMiddleware[Any, Nothing] =
    JwtSecret(secretKey, algo)

  def basicAuth[R, E](f: (String, String) => ZIO[R, E, Boolean]): AuthMiddleware[R, E] = AuthFunction(
    { h =>
      HeadersHolder(h).getBasicAuthorizationCredentials match {
        case Some((username, password)) => f(username, password)
        case None                       => ZIO.succeed(false)
      }
    },
    List(Header(HttpHeaderNames.WWW_AUTHENTICATE, BasicSchemeName)),
  )

  def authFunction[R, E](
    f: List[Header] => ZIO[R, E, Boolean],
    h: List[Header] = List.empty[Header],
  ): AuthMiddleware[R, E] = AuthFunction(f, h)

  private[zhttp] def execute[R, E](mid: AuthMiddleware[R, E], app: HttpApp[R, E]): HttpApp[R, E] = mid match {
    case AuthFunction(f, h)         =>
      HttpApp.fromFunctionM { req =>
        for {
          bool <- f(req.headers)
          res  <-
            if (bool) ZIO.succeed(app)
            else ZIO.succeed(HttpApp.response(HttpError.Unauthorized().toResponse.addHeaders(h)))
        } yield res
      }
    case JwtSecret(secretKey, algo) =>
      HttpApp.fromFunction {
        _.getHeader("X-ACCESS-TOKEN")
          .flatMap(header => Jwt.decode(header.value.toString, secretKey, algo).toOption)
          .fold[HttpApp[R, E]](HttpApp.error(Unauthorized()))(_ => app)
      }
    case Combine(self, other)       => other(self(app))

  }

  final case class HeadersHolder(headers: List[Header]) extends HeaderExtension[HeadersHolder] { self =>
    override def addHeaders(headers: List[Header]): HeadersHolder =
      HeadersHolder(self.headers ++ headers)

    override def removeHeaders(headers: List[String]): HeadersHolder =
      HeadersHolder(self.headers.filterNot(h => headers.contains(h.name)))
  }

}
