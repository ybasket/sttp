package com.softwaremill.sttp.http4s

import java.io.{File, IOException, UnsupportedEncodingException}

import cats._
import cats.implicits._
import java.nio.charset.Charset

import cats.data.NonEmptyList
import com.softwaremill.sttp._
import cats.effect.ConcurrentEffect
import com.softwaremill.sttp.impl.cats.EffectMonadAsyncError
import fs2.{Chunk, Stream}
import org.http4s
import org.http4s.EntityDecoder
import org.http4s.client.Client
import org.http4s.client.blaze.{BlazeClientConfig, Http1Client}

import scala.language.higherKinds

/*
TODO: gzip
- old PR: https://github.com/http4s/http4s/pull/1373
- https://github.com/http4s/http4s/issues/1327
- https://github.com/functional-streams-for-scala/fs2/issues/130
- https://github.com/functional-streams-for-scala/fs2/issues/851
- https://github.com/scodec/scodec-stream ?
 */

class Http4sBackend[F[_]: ConcurrentEffect](client: Client[F], closeClient: Boolean)
    extends SttpBackend[F, Stream[F, Byte]] {

  // this needs to be given explicitly in some places due to diverging implicit expansion errors
  private val fInstance = implicitly[ConcurrentEffect[F]]

  override def send[T](r: Request[T, Stream[F, Byte]]): F[Response[T]] = {
    val request = bodyToHttp4s(r, r.body).map {
      case (entity, extraHeaders) =>
        http4s.Request(
          method = methodToHttp4s(r.method),
          uri = http4s.Uri.unsafeFromString(r.uri.toString),
          headers = http4s.Headers(r.headers.map(h => http4s.Header(h._1, h._2)).toList) ++ extraHeaders,
          body = entity.body
        )
    }

    client.fetch(request) { response =>
      val code = response.status.code
      val body = if (StatusCodes.isSuccess(code)) {
        bodyFromHttp4s(r.response, decompressResponseBody(response)).map(_.asRight)
      } else {
        response.as[Array[Byte]].map(_.asLeft)
      }

      body.map { b =>
        val headers = response.headers.map(h => h.name.value -> h.value).toList
        Response(b, code, response.status.reason, headers, Nil)
      }
    }
  }

  private def methodToHttp4s(m: Method): http4s.Method = m match {
    case Method.GET     => http4s.Method.GET
    case Method.HEAD    => http4s.Method.HEAD
    case Method.POST    => http4s.Method.POST
    case Method.PUT     => http4s.Method.PUT
    case Method.DELETE  => http4s.Method.DELETE
    case Method.OPTIONS => http4s.Method.OPTIONS
    case Method.PATCH   => http4s.Method.PATCH
    case Method.CONNECT => http4s.Method.CONNECT
    case Method.TRACE   => http4s.Method.TRACE
    case _              => http4s.Method.fromString(m.m).right.get
  }

  private def charsetToHttp4s(encoding: String) = http4s.Charset.fromNioCharset(Charset.forName(encoding))

  private def basicBodyToHttp4s(body: BasicRequestBody): F[http4s.Entity[F]] = {
    body match {
      case StringBody(b, encoding, _) =>
        http4s.EntityEncoder.stringEncoder(fInstance, charsetToHttp4s(encoding)).toEntity(b)

      case ByteArrayBody(b, _) =>
        http4s.EntityEncoder.byteArrayEncoder(fInstance).toEntity(b)

      case ByteBufferBody(b, _) =>
        http4s.EntityEncoder.chunkEncoder[F].contramap(Chunk.byteBuffer).toEntity(b)

      case InputStreamBody(b, _) =>
        http4s.EntityEncoder.inputStreamEncoder(fInstance).toEntity(b.pure[F])

      case FileBody(b, _) =>
        http4s.EntityEncoder.fileEncoder.toEntity(b.toFile)
    }
  }

  private def bodyToHttp4s(r: Request[_, Stream[F, Byte]],
                           body: RequestBody[Stream[F, Byte]]): F[(http4s.Entity[F], http4s.Headers)] = {
    body match {
      case NoBody => (http4s.Entity(http4s.EmptyBody: http4s.EntityBody[F]), http4s.Headers.empty).pure[F]

      case b: BasicRequestBody => basicBodyToHttp4s(b).map((_, http4s.Headers.empty))

      case StreamBody(s) =>
        val cl = r.headers
          .find(_._1.equalsIgnoreCase(HeaderNames.ContentLength))
          .map(_._2.toLong)
        (http4s.Entity(s, cl), http4s.Headers.empty).pure[F]

      case MultipartBody(ps) =>
        ps.toVector.map(multipartToHttp4s).sequence.flatMap { parts =>
          val multipart = http4s.multipart.Multipart(parts)
          http4s.EntityEncoder.multipartEncoder.toEntity(multipart).map((_, multipart.headers))
        }
    }
  }

  private def multipartToHttp4s(mp: Multipart): F[http4s.multipart.Part[F]] = {
    val contentDisposition = http4s.Header(HeaderNames.ContentDisposition, mp.contentDispositionHeaderValue)
    val contentTypeHeader = mp.contentType.map(ct => http4s.Header(HeaderNames.ContentType, ct))
    val otherHeaders = mp.additionalHeaders.map(h => http4s.Header(h._1, h._2))
    val allHeaders = List(contentDisposition) ++ contentTypeHeader.toList ++ otherHeaders

    basicBodyToHttp4s(mp.body).map(e => http4s.multipart.Part(http4s.Headers(allHeaders), e.body))
  }

  private def decompressResponseBody(hr: http4s.Response[F]): http4s.Response[F] = {
    val body = hr.headers.get(http4s.headers.`Content-Encoding`) match {
      case Some(encoding)
          if http4s.headers
            .`Accept-Encoding`(NonEmptyList.of(http4s.ContentCoding.gzip, http4s.ContentCoding.`x-gzip`))
            .satisfiedBy(encoding.contentCoding) =>
        hr.body.through(fs2.compress.inflate(nowrap = true))
      case Some(encoding)
          if http4s.headers
            .`Accept-Encoding`(NonEmptyList.of(http4s.ContentCoding.deflate))
            .satisfiedBy(encoding.contentCoding) =>
        hr.body.through(fs2.compress.inflate())
      case Some(encoding) =>
        throw new UnsupportedEncodingException(s"Unsupported encoding: ${encoding.contentCoding.coding}")
      case None => hr.body
    }

    hr.copy(body = body)
  }

  private def bodyFromHttp4s[T](rr: ResponseAs[T, Stream[F, Byte]], hr: http4s.Response[F]): F[T] = {
    def saved(file: File, overwrite: Boolean) = {
      if (!file.exists()) {
        file.getParentFile.mkdirs()
        file.createNewFile()
      } else if (!overwrite) {
        throw new IOException(s"File ${file.getAbsolutePath} exists - overwriting prohibited")
      }

      hr.body.to(fs2.io.file.writeAll(file.toPath)).compile.drain
    }

    rr match {
      case MappedResponseAs(raw, g) =>
        bodyFromHttp4s(raw, hr).map(g)

      case IgnoreResponse =>
        hr.body.compile.drain

      case ResponseAsString(enc) =>
        implicit val charset: http4s.Charset = charsetToHttp4s(enc)
        hr.as[String]

      case ResponseAsByteArray =>
        hr.as[Array[Byte]]

      case r @ ResponseAsStream() =>
        r.responseIsStream(hr.body).pure[F]

      case ResponseAsFile(file, overwrite) =>
        saved(file.toFile, overwrite).map(_ => file)
    }
  }

  override def responseMonad: com.softwaremill.sttp.MonadError[F] = new EffectMonadAsyncError

  override def close(): Unit = if (closeClient) {
    client.shutdownNow()
  }
}

object Http4sBackend {
  private def apply[F[_]: ConcurrentEffect](client: Client[F],
                                            closeClient: Boolean,
                                            options: SttpBackendOptions): SttpBackend[F, Stream[F, Byte]] =
    new FollowRedirectsBackend(new Http4sBackend[F](client, closeClient)) // TODO: options

  def apply[F[_]: ConcurrentEffect](
      options: SttpBackendOptions = SttpBackendOptions.Default,
      blazeOptions: BlazeClientConfig = BlazeClientConfig.defaultConfig): F[SttpBackend[F, Stream[F, Byte]]] =
    Http1Client[F](blazeOptions).map(c => apply(c, closeClient = true, options))
}
