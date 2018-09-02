package com.softwaremill.sttp.http4s

import cats.effect.IO
import cats.instances.string._
import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.testing.ConvertToFuture
import com.softwaremill.sttp.testing.streaming.StreamingTest
import fs2.{Chunk, Stream, text}

import scala.concurrent.Future

class Http4sHttpStreamingTest extends StreamingTest[IO, Stream[IO, Byte]] {

  override implicit val backend: SttpBackend[IO, Stream[IO, Byte]] = Http4sBackend[IO]().unsafeRunSync()
  override implicit val convertToFuture: ConvertToFuture[IO] = new ConvertToFuture[IO] {
    override def toFuture[T](value: IO[T]): Future[T] = value.unsafeToFuture()
  }

  override def bodyProducer(body: String): Stream[IO, Byte] =
    Stream.chunk(Chunk.array(body.getBytes("utf-8")))

  override def bodyConsumer(stream: Stream[IO, Byte]): IO[String] =
    stream
      .through(text.utf8Decode)
      .compile
      .foldMonoid
}
