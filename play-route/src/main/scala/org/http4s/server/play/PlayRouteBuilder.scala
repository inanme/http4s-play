package org.http4s.server.play

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.Async
import cats.syntax.all._
import fs2.interop.reactivestreams._
import fs2.Chunk
import org.http4s._
import org.http4s.syntax.all._
import org.typelevel.ci._
import play.api.http.HttpEntity.Streamed
import play.api.libs.streams.Accumulator
import play.api.mvc.EssentialAction
import play.api.mvc.Handler
import play.api.mvc.RequestHeader
import play.api.mvc.ResponseHeader
import play.api.mvc.Result

import scala.annotation.nowarn
import scala.concurrent.Future

class PlayRouteBuilder[F[_]](
  service: HttpRoutes[Kleisli[F, RequestContext, *]],
  region: Option[Region]
)(implicit
  F: Async[F],
  dispatcher: Dispatcher[F]
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

  def convertToPlayResponse(response: Response[F]): ResponseHeader =
    ResponseHeader(
      status = response.status.code,
      headers = response.headers.headers.collect {
        case header if !PlayRouteBuilder.AkkaHttpSetsSeparately.contains(header.name) =>
          header.name.toString -> header.value
      }.toMap
    )

  def convertToAkkaStream(fs2Stream: EntityBody[F]): Source[ByteString, _] =
    Source.fromPublisher[ByteString] {
      val stream = fs2Stream.chunks
        .map(chunk => ByteString(chunk.toArray))
      StreamUnicastPublisher(stream, dispatcher)
    }

  val bufferSize = 256

  /**
   * A Play accumulator Sinks HTTP data in, and then pumps out a future of a Result.
   * That Result will have a Source as the response HTTP Entity.
   *
   * Here we create a unattached sink, map its materialized value into a publisher,
   * convert that into an FS2 Stream, then pipe the request body into the http4s request.
   */
  def playRequestToPlayResponse(
    requestHeader: RequestHeader,
    method: Method
  ): Accumulator[ByteString, Result] = {
    val sink: Sink[ByteString, Future[Result]] =
      Sink.asPublisher[ByteString](fanout = false).mapMaterializedValue { publisher =>
        type Eff[A] = Kleisli[F, RequestContext, A]
        val requestBodyStream: EntityBody[F] =
          publisher
            .toStreamBuffered(bufferSize)
            .flatMap(bs => fs2.Stream.chunk(Chunk.array(bs.toArray)))

        val http4sRequest: Request[Kleisli[F, RequestContext, *]] =
          convertToHttp4sRequest(requestHeader, method)
            .withBodyStream(requestBodyStream)
            .mapK(Kleisli.liftK[F, RequestContext])

        val reqCtx: RequestContext = unauthReqToAppContext(http4sRequest, region)
        val http4sResponse: F[Response[F]] =
          service
            .run(http4sRequest)
            .value
            .run(reqCtx)
            .map(_.getOrElse(Response.notFound.mapK(Kleisli.liftK[F, RequestContext])))
            .map(_.mapK(Kleisli.applyK(reqCtx)))

        @nowarn
        val http4sResponse1: F[Response[F]] =
          service
            .run(http4sRequest)
            .getOrElse(Response.notFound[Eff])
            .run(reqCtx)
            .map(_.mapK(Kleisli.applyK(reqCtx)))

        val playResponse: F[Result] =
          for {
            response <- http4sResponse
          } yield Result(
            header = convertToPlayResponse(response),
            body = Streamed(
              data = convertToAkkaStream(response.body),
              contentLength = response.contentLength,
              contentType = response.contentType.map(_.value)
            )
          )
        dispatcher.unsafeToFuture(playResponse)
      }
    Accumulator.apply(sink)
  }

  def build: Handler =
    EssentialAction { requestHeader =>
      Method
        .fromString(requestHeader.method)
        .fold(
          _ => Accumulator.done(play.api.mvc.Results.NotFound),
          playRequestToPlayResponse(requestHeader, _)
        )
    }

}

object PlayRouteBuilder {

  val AkkaHttpSetsSeparately: Set[CIString] =
    Set(ci"Content-Type", ci"Content-Length", ci"Transfer-Encoding")

}
