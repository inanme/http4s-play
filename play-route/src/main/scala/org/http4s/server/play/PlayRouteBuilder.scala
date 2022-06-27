package org.http4s.server.play

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.OptionT
import cats.effect.Async
import cats.effect.ConcurrentEffect
import cats.effect.IO
import cats.syntax.all._
import fs2.interop.reactivestreams._
import fs2.Chunk
import org.http4s.server.play.PlayRouteBuilder.PlayAccumulator
import org.http4s.server.play.PlayRouteBuilder.PlayRouting
import org.http4s.server.play.PlayRouteBuilder.PlayTargetStream
import org.http4s.EmptyBody
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
import scala.concurrent.Promise

class PlayRouteBuilder[F[_]](service: HttpRoutes[F])(implicit
  F: ConcurrentEffect[F],
  executionContext: ExecutionContext
) {

  type UnwrappedKleisli = Request[F] => OptionT[F, Response[F]]
  private[this] val unwrappedRun: UnwrappedKleisli = service.run

  def requestHeaderToRequest(requestHeader: RequestHeader, method: Method): Request[F] =
    Request(
      method = method,
      uri = Uri.unsafeFromString(requestHeader.uri),
      headers =
        Headers.apply(requestHeader.headers.toMap.toList.flatMap { case (headerName, values) =>
          values.map { value =>
            Header.Raw(CIString(headerName), value)
          }
        }),
      body = EmptyBody
    )

  def convertStream(responseStream: EntityBody[F]): PlayTargetStream = {
    val entityBody: fs2.Stream[F, ByteString] =
      responseStream.chunks.map(chunk => ByteString(chunk.toArray))

    Source.fromPublisher(entityBody.toUnicastPublisher)
  }

  def effectToFuture[T](eff: F[T]): Future[T] = {
    val promise = Promise[T]

    F.runAsync(eff) {
      case Left(bad) =>
        IO(promise.failure(bad))
      case Right(good) =>
        IO(promise.success(good))
    }.unsafeRunSync()

    promise.future
  }

  /**
   * A Play accumulator Sinks HTTP data in, and then pumps out a future of a Result.
   * That Result will have a Source as the response HTTP Entity.
   *
   * Here we create a unattached sink, map its materialized value into a publisher,
   * convert that into an FS2 Stream, then pipe the request body into the http4s request.
   */
  def playRequestToPlayResponse(requestHeader: RequestHeader, method: Method): PlayAccumulator = {
    val sink: Sink[ByteString, Future[Result]] = {
      Sink.asPublisher[ByteString](fanout = false).mapMaterializedValue { publisher =>
        val requestBodyStream: fs2.Stream[F, Byte] =
          publisher.toStream.flatMap(bs => fs2.Stream.chunk(Chunk.bytes(bs.toArray)))

        val http4sRequest: Request[F] =
          requestHeaderToRequest(requestHeader, method).withBodyStream(requestBodyStream)

        /** The .get here is safe because this was already proven in the pattern match of the caller * */
        val wrappedResponse: F[Response[F]] = unwrappedRun(http4sRequest).value.map(_.get)
        val wrappedResult: F[Result] = wrappedResponse.map { response: Response[F] =>
          Result(
            header = convertResponseToHeader(response),
            body = Streamed(
              data = convertStream(response.body),
              contentLength = response.contentLength,
              contentType =
                response.contentType.map(it => it.mediaType.mainType + "/" + it.mediaType.subType)
            )
          )
        }

        effectToFuture[Result](Async.shift(executionContext) *> wrappedResult)
      }
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
        val computeRequestHeader: F[Option[Response[F]]] = F.delay {
          val playRequest = requestHeaderToRequest(requestHeader, method)
          val optionalResponse: OptionT[F, Response[F]] =
            unwrappedRun.apply(playRequest)
          val efff: F[Option[Response[F]]] = optionalResponse.value
          efff
        }.flatten

        val matches = effectToFuture[Boolean](
          Async.shift(executionContext) *> computeRequestHeader.map(_.isDefined)
        )

        Await.result(matches, Duration.Inf)
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
