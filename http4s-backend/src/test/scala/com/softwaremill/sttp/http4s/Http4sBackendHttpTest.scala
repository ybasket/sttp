package com.softwaremill.sttp.http4s

import cats.effect.{ContextShift, IO}
import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.impl.cats._
import com.softwaremill.sttp.testing.{ConvertToFuture, HttpTest}

class Http4sBackendHttpTest extends HttpTest[IO] {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(implicitly)

  override implicit val backend: SttpBackend[IO, Nothing] = Http4sBackend[IO]().unsafeRunSync()
  override implicit val convertToFuture: ConvertToFuture[IO] = convertCatsIOToFuture
}
