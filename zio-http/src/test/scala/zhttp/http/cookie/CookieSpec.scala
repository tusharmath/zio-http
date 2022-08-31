package zhttp.http.cookie

import zhttp.http.Path
import zhttp.http.cookie.Cookie.SameSite
import zio.durationInt
import zio.test._
import zio.test.Assertion.{equalTo, isLeft, isRight, startsWithString}

object CookieSpec extends ZIOSpecDefault {

  override def spec =
    suite("CookieSpec")(
      suite("getter")(
        test("request") {
          val cookieGen = for {
            name    <- Gen.alphaNumericString
            content <- Gen.alphaNumericString
          } yield (name, content) -> Cookie(name, content, Cookie.Request)
          check(cookieGen) { case (name, content) -> cookie =>
            assertTrue(cookie.content == content) && assertTrue(cookie.name == name)
          }
        },
        test("response") {
          val responseCookieGen = for {
            name     <- Gen.alphaNumericString
            content  <- Gen.alphaNumericString
            response <- for {
              domain     <- Gen.option(Gen.alphaNumericString)
              path       <- Gen.option(Gen.elements(Path.root / "a", Path.root / "a" / "b"))
              maxAge     <- Gen.option(Gen.long)
              sameSite   <- Gen.option(Gen.fromIterable(Cookie.SameSite.values))
              isSecure   <- Gen.boolean
              isHttpOnly <- Gen.boolean
            } yield Cookie.Response(domain, path, isSecure, isHttpOnly, maxAge, sameSite)
          } yield (name, content) -> Cookie(name, content, response)

          check(responseCookieGen) { case (name, content) -> cookie =>
            assertTrue(cookie.content == content) &&
            assertTrue(cookie.name == name) &&
            assertTrue(cookie.domain == cookie.target.domain) &&
            assertTrue(cookie.path == cookie.target.path) &&
            assertTrue(cookie.maxAge.map(_.toSeconds) == cookie.target.maxAge) &&
            assertTrue(cookie.sameSite == cookie.target.sameSite) &&
            assertTrue(cookie.isSecure == cookie.target.isSecure) &&
            assertTrue(cookie.isHttpOnly == cookie.target.isHttpOnly)
          }
        },
      ),
      suite("encode")(
        test("request") {
          val cookie    = Cookie("name", "value")
          val cookieGen = Gen.fromIterable(
            Seq(
              cookie                      -> "name=value",
              cookie.withContent("other") -> "name=other",
              cookie.withName("name1")    -> "name1=value",
            ),
          )
          checkAll(cookieGen) { case (cookie, expected) => assertTrue(cookie.encode == Right(expected)) }
        },
        test("response") {
          val cookie = Cookie("name", "content").toResponse

          val cookieGen = Gen.fromIterable(
            Seq(
              cookie                            -> equalTo("name=content"),
              cookie.withDomain("abc.com")      -> equalTo("name=content; Domain=abc.com"),
              cookie.withHttpOnly(true)         -> equalTo("name=content; HTTPOnly"),
              cookie.withPath(Path.root / "a")  -> equalTo("name=content; Path=/a"),
              cookie.withSameSite(SameSite.Lax) -> equalTo("name=content; SameSite=Lax"),
              cookie.withSecure(true)           -> equalTo("name=content; Secure"),
              cookie.withMaxAge(1 day)          -> startsWithString("name=content; Max-Age=86400; Expires="),
            ),
          )

          checkAll(cookieGen) { case (cookie, assertion) => assert(cookie.encode)(isRight(assertion)) }
        },
        test("invalid encode") {
          val cookie = Cookie("1", null)
          assert(cookie.encode)(isLeft)
        },
      ),
      suite("decode")(
        test("request") {
          val cookie  = Cookie("name", "value")
          val program = cookie.encode.flatMap(Cookie.decode[Cookie.Request](_))
          assertTrue(program == Right(List(cookie)))
        },
        test("decode response") {
          val responseCookieGen = for {
            name     <- Gen.alphaNumericStringBounded(1, 4)
            content  <- Gen.alphaNumericStringBounded(1, 4)
            response <- for {
              domain     <- Gen.option(Gen.alphaNumericStringBounded(1, 4))
              path       <- Gen.option(Gen.elements(Path.root / "a", Path.root / "a" / "b"))
              maxAge     <- Gen.option(Gen.long(1, 86400))
              sameSite   <- Gen.option(Gen.fromIterable(Cookie.SameSite.values))
              isSecure   <- Gen.boolean
              isHttpOnly <- Gen.boolean
            } yield Cookie.Response(domain, path, isSecure, isHttpOnly, maxAge, sameSite)
          } yield Cookie(name, content, response)

          check(responseCookieGen) { case cookie =>
            val encoded = cookie.encode(true)
            val decoded = encoded.flatMap(Cookie.decode[Cookie.Response](_, true))
            assert(decoded)(isRight(equalTo(cookie)))
          }
        },
      ),
      test("signature") {
        val cookie = Cookie("name", "value")
        val signed = cookie.toResponse.sign("ABC")

        assertTrue(signed.toRequest.unSign("ABC").contains(cookie)) &&
        assertTrue(signed.toRequest.unSign("PQR").isEmpty)
      },
    )
}