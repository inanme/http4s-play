package org.http4s.server.play

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.std.Dispatcher
import cats.effect.Async
import cats.syntax.all._
import fs2.interop.reactivestreams._
import fs2.Chunk
import org.http4s.server.play.PlayRouteBuilder.PlayAccumulator
import org.http4s.server.play.PlayRouteBuilder.PlayRouting
import org.http4s.server.play.PlayRouteBuilder.PlayTargetStream
import org.http4s.EntityBody
import org.http4s.Header
import org.http4s.Headers
import org.http4s.HttpRoutes
import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri
import org.typelevel.ci._
import play.api.http.HttpEntity.Streamed
import play.api.libs.streams.Accumulator
import play.api.mvc._
import scala.concurrent.duration.Duration
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class PlayRouteBuilder[F[_]](service: HttpRoutes[F], dispatcher: Dispatcher[F])(implicit
  F: Async[F],
  executionContext: ExecutionContext
) {

  def convertToHttp4sRequest(requestHeader: RequestHeader, method: Method): Request[F] =
    Request(
      method = method,
      uri = Uri.unsafeFromString(requestHeader.uri),
      headers =
        Headers.apply(requestHeader.headers.toMap.toList.flatMap { case (headerName, values) =>
          values.map { value =>
            Header.Raw(CIString(headerName), value)
          }
        })
    )

  def convertToAkkaStream(fs2Stream: EntityBody[F]): F[PlayTargetStream] =
    fs2Stream.chunks
      .map(chunk => ByteString(chunk.toArray))
      .toUnicastPublisher
      .use(res =>
        F.delay {
          Source.fromPublisher[ByteString](res)
        }
      )

  val bufferSize = 512

  /**
   * A Play accumulator Sinks HTTP data in, and then pumps out a future of a Result.
   * That Result will have a Source as the response HTTP Entity.
   *
   * Here we create a unattached sink, map its materialized value into a publisher,
   * convert that into an FS2 Stream, then pipe the request body into the http4s request.
   */
  def playRequestToPlayResponse(requestHeader: RequestHeader, method: Method): PlayAccumulator = {
    val sink: Sink[ByteString, Future[Result]] =
      Sink.asPublisher[ByteString](fanout = false).mapMaterializedValue { publisher =>
        val requestBodyStream: EntityBody[F] =
          publisher
            .toStreamBuffered(bufferSize)
            .flatMap(bs => fs2.Stream.chunk(Chunk.array(bs.toArray)))

        val http4sRequest: Request[F] =
          convertToHttp4sRequest(requestHeader, method).withBodyStream(requestBodyStream)

        /** The .get here is safe because this was already proven in the pattern match of the caller * */
        val http4sResponse: F[Response[F]] = service.run(http4sRequest).value.map(_.get)
        val playResponse: F[Result] = for {
          response     <- http4sResponse
          responseBody <- convertToAkkaStream(response.body)
        } yield Result(
          header = convertResponseToHeader(response),
          body = Streamed(
            data = responseBody,
            contentLength = response.contentLength,
            contentType =
              response.contentType.map(it => it.mediaType.mainType + "/" + it.mediaType.subType)
          )
        )
        dispatcher.unsafeToFuture(playResponse)
      }
    Accumulator.apply(sink)
  }

  def convertResponseToHeader(response: Response[F]): ResponseHeader =
    ResponseHeader(
      status = response.status.code,
      headers = response.headers.headers.collect {
        case header if !PlayRouteBuilder.AkkaHttpSetsSeparately.contains(header.name) =>
          header.name.toString -> header.value
      }.toMap
    )

  def routeMatches(requestHeader: RequestHeader): Boolean = {
    Method
      .fromString(requestHeader.method)
      .map { method =>
        val http4sRequest: Request[F]    = convertToHttp4sRequest(requestHeader, method)
        val optionalResponse: F[Boolean] = service.run(http4sRequest).value.map(_.isDefined)
        val future                       = dispatcher.unsafeToFuture(optionalResponse)
        Await.result(future, Duration.Inf)
      }
      .getOrElse(false)
  }

  def build: PlayRouting = {
    case requestHeader if routeMatches(requestHeader) =>
      EssentialAction { requestHeader =>
        playRequestToPlayResponse(
          requestHeader,
          Method.fromString(requestHeader.method).right.get
        )
      }
  }

}

object PlayRouteBuilder {

  type PlayRouting = PartialFunction[RequestHeader, Handler]

  type PlayAccumulator = Accumulator[ByteString, Result]

  type PlayTargetStream = Source[ByteString, _]

  /** Borrowed from Play for now * */
  def withPrefix(
    prefix: String,
    t: _root_.play.api.routing.Router.Routes
  ): _root_.play.api.routing.Router.Routes =
    if (prefix == "/") {
      t
    } else {
      val p = if (prefix.endsWith("/")) prefix else prefix + "/"
      val prefixed: PartialFunction[RequestHeader, RequestHeader] = {
        case rh: RequestHeader if rh.path.startsWith(p) =>
          val newPath = rh.path.drop(p.length - 1)
          rh.withTarget(rh.target.withPath(newPath))
      }
      Function.unlift(prefixed.lift.andThen(_.flatMap(t.lift)))
    }

  val AkkaHttpSetsSeparately: Set[CIString] =
    Set(ci"Content-Type", ci"Content-Length", ci"Transfer-Encoding")

}
