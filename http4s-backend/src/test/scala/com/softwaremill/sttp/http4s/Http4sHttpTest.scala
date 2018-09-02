package com.softwaremill.sttp.http4s

import cats.effect.IO
import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.testing.{ConvertToFuture, HttpTest}

import scala.concurrent.Future

class Http4sHttpTest extends HttpTest[IO] {

  override implicit val backend: SttpBackend[IO, Nothing] = Http4sBackend[IO]().unsafeRunSync()
  override implicit val convertToFuture: ConvertToFuture[IO] = new ConvertToFuture[IO] {
    override def toFuture[T](value: IO[T]): Future[T] = value.unsafeToFuture()
  }
}
