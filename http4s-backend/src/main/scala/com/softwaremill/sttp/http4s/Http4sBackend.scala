package com.softwaremill.sttp.http4s

import java.io.InputStream
import java.nio.charset.UnsupportedCharsetException

import cats.implicits._
import cats.effect.implicits._
import cats.effect.{ConcurrentEffect, ContextShift, IO}
import com.softwaremill.sttp._
import com.softwaremill.sttp.internal._
import com.softwaremill.sttp.impl.cats.EffectMonadAsyncError
import org.http4s
import org.http4s.{Charset, EntityEncoder, HttpVersion, MediaType}
import org.http4s.client._
import org.http4s.client.blaze._
import org.http4s.client.middleware.FollowRedirect
import org.http4s.multipart.{Boundary, Part}
import org.http4s.util.CaseInsensitiveString

import scala.concurrent.ExecutionContext

class Http4sBackend[F[_]: ConcurrentEffect: ContextShift] private (client: Client[F],
                                                                   release: IO[Unit],
                                                                   blockingEC: ExecutionContext)
    extends SttpBackend[F, Nothing] {

  def readUnitResponse(response: http4s.Response[F]): Response[Unit] = {
    new Response(
      ().asRight,
      response.status.code,
      response.status.reason,
      response.headers.toList.map(header => header.name.toString() -> header.value),
      Nil
    )
  }

  private def handleResponseBody[T](response: http4s.Response[F],
                                    responseAs: ResponseAs[T, Nothing],
                                    responseWithoutBody: Response[Unit]): F[T] =
    responseAs match {
      case IgnoreResponse =>
        // getting the body and discarding it
        response.body.compile.drain

      case ResponseAsString(enc) =>
        val charsetName = response.headers
          .get(CaseInsensitiveString(HeaderNames.ContentType))
          .map(_.value)
          .flatMap(encodingFromContentType)
          .getOrElse(enc)

        Charset.fromString(charsetName).leftMap(_ => new UnsupportedCharsetException(charsetName)).liftTo[F] flatMap {
          charset =>
            response.bodyAsText(charset).compile.foldMonoid
        }

      case ResponseAsByteArray =>
        response.body.compile.to[Array]

      case ResponseAsStream() =>
        (new IllegalStateException("Requested a streaming response, trying to read eagerly."): Throwable)
          .raiseError[F, T]

      /*case ResponseAsFile(file, overwrite) =>
        fs2.io.toInputStream[F].apply(response.body).compile.lastOrError flatMap { inputStream =>
          ConcurrentEffect[F].catchNonFatal {
            val f = FileHelpers.saveFile(file.toFile, inputStream, overwrite)
            SttpFile.fromFile(f)
          }
        }*/

      case MappedResponseAs(raw, g) =>
        handleResponseBody(response, raw, responseWithoutBody) map (g(_, responseWithoutBody))

    }

  def convertMethod(method: Method): F[http4s.Method] = http4s.Method.fromString(method.m).liftTo[F]
  def convertUri(uri: Uri): F[http4s.Uri] = http4s.Uri.fromString(uri.toString).liftTo[F]
  def convertHeaders(headers: Seq[(String, String)]): http4s.Headers =
    http4s.Headers(headers.toList.map((http4s.Header.apply _).tupled))
  def convertBody(body: RequestBody[Nothing]): F[http4s.EntityBody[F]] = body match {
    case StringBody(s, encoding, _) =>
      Charset.fromString(encoding).liftTo[F] map { charset =>
        EntityEncoder.stringEncoder(charset).toEntity(s).body
      }

    case NoBody => http4s.EmptyBody.covary[F].pure[F]

    case ByteArrayBody(ba, _) =>
      EntityEncoder.byteArrayEncoder[F].toEntity(ba).body.pure[F]

    case ByteBufferBody(bb, _) =>
      EntityEncoder.byteArrayEncoder[F].toEntity(bb.array()).body.pure[F]

    case InputStreamBody(in, _) =>
      EntityEncoder.inputStreamEncoder[F, InputStream](blockingEC).toEntity(in.pure[F]).body.pure[F]

    case FileBody(file, _) =>
      EntityEncoder.fileEncoder[F](blockingEC).toEntity(file.toFile).body.pure[F]

    case StreamBody(_) =>
      (new IllegalStateException("This backend does not support streaming"): Throwable)
        .raiseError[F, org.http4s.EntityBody[F]]

    case MultipartBody(parts) =>
      parts.toVector.traverse(convertMultipart) map { parts =>
        EntityEncoder.multipartEncoder.toEntity(http4s.multipart.Multipart(parts, Boundary.)).body
      }

  }
  def convertMultipart(multipart: Multipart): F[http4s.multipart.Part[F]] = {
    val headers = convertHeaders(multipart.additionalHeaders.toSeq)
    /*val extHeaders = multipart.contentType.fold(headers) { ct =>
      headers.put(http4s.headers.`Content-Type`(MediaType.multipartType(ct)))
    }*/
    convertBody(multipart.body) map (body => Part(headers, body))
  }

  def convertRequest[T](request: Request[T, Nothing]): F[http4s.Request[F]] = {
    (convertMethod(request.method), convertUri(request.uri), convertBody(request.body)) mapN {
      val headers = convertHeaders(request.headers)
      val extHeaders = Option(request.body)
        .collectFirstSome {
          case brv: BasicRequestBody =>
            brv.defaultContentType.flatMap(ct => http4s.headers.`Content-Type`.parse(ct).toOption)
          case _ => None
        }
        .fold(headers)(headers.put(_))

      http4s.Request(_, _, HttpVersion.`HTTP/1.1`, extHeaders, _)
    }

  }

  def configuredClient[T](request: Request[T, Nothing]): Client[F] = {
    if (request.options.followRedirects) {
      FollowRedirect(request.options.maxRedirects)(client)
    } else {
      client
    }
  }

  override def send[T](request: Request[T, Nothing]): F[Response[T]] = {
    convertRequest(request) flatMap { http4sRequest =>
      configuredClient(request).run(http4sRequest).use { response =>
        val responseWithoutBody = readUnitResponse(response)
        if (request.options.parseResponseIf(response.status.code)) {
          handleResponseBody(response, request.response, responseWithoutBody) map (body =>
            responseWithoutBody.copy(rawErrorBody = body.asRight))
        } else {
          handleResponseBody(response, asByteArray, responseWithoutBody) map (body =>
            responseWithoutBody.copy(rawErrorBody = body.asLeft))
        }
      }
    }
  }

  override def close(): Unit = release.unsafeRunSync()

  /**
    * The monad in which the responses are wrapped. Allows writing wrapper
    * backends, which map/flatMap over the return value of [[send]].
    */
  override def responseMonad: MonadError[F] = new EffectMonadAsyncError[F]()
}

object Http4sBackend {
  def apply[F[_]: ConcurrentEffect: ContextShift]()(implicit ec: ExecutionContext): F[Http4sBackend[F]] = {
    BlazeClientBuilder[F](ec).allocate map {
      case (client, release) =>
        new Http4sBackend[F](client, release.toIO, ec) // TODO: provide proper blockingEC
    }
  }
}
