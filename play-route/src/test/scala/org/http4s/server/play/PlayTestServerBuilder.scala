package org.http4s.server.play

import cats.effect._
import java.net.InetSocketAddress
import org.http4s.server.defaults.IPv4SocketAddress
import org.http4s.server.Server
import org.http4s.server.ServerBuilder
import org.http4s.server.ServiceErrorHandler
import org.http4s.HttpRoutes
import play.api.Configuration
import play.api.Environment
import play.api.Mode
import scala.collection.immutable
import scala.concurrent.ExecutionContext

class PlayTestServerBuilder[F[_]](
  hostname: String,
  services: Vector[(HttpRoutes[F], String)],
  executionContext: ExecutionContext,
  port: Int
)(implicit val F: ConcurrentEffect[F])
    extends ServerBuilder[F] {
  type Self = PlayTestServerBuilder[F]

  private implicit val ec: ExecutionContext = executionContext

  private def copy(
    hostname: String = hostname,
    port: Int = port,
    executionContext: ExecutionContext = executionContext,
    services: Vector[(HttpRoutes[F], String)] = services
  ): Self =
    new PlayTestServerBuilder(hostname, services, executionContext, port)

  override def resource: Resource[F, Server] = Resource
    .make {
      F.delay {
        val serverA = {
          import play.core.server.{ AkkaHttpServer, _ }
          val serverConfig =
            ServerConfig(
              port = Some(port),
              address = hostname
            ).copy(configuration = Configuration.load(Environment.simple()), mode = Mode.Test)
          AkkaHttpServer.fromRouterWithComponents(serverConfig) { _ =>
            services
              .map { case (service, prefix) =>
                PlayRouteBuilder
                  .withPrefix(prefix, new PlayRouteBuilder(service).build)
              }
              .foldLeft(PartialFunction.empty: _root_.play.api.routing.Router.Routes)(_.orElse(_))
          }
        }

        serverA
      }
    } { serverA =>
      F.delay {
        serverA.stop()
      }
    }
    .flatMap { _ =>
      Resource.eval(F.delay {
        new Server {
          override def toString: String = s"PlayServer($address)"

          override def address: InetSocketAddress = new InetSocketAddress(hostname, port)

          override def isSecure: Boolean = false
        }
      })
    }

  override def bindSocketAddress(socketAddress: InetSocketAddress): PlayTestServerBuilder[F] = this

  override def withServiceErrorHandler(
    serviceErrorHandler: ServiceErrorHandler[F]
  ): PlayTestServerBuilder[F] = this

  override def withBanner(banner: immutable.Seq[String]): PlayTestServerBuilder[F] = this
}

object PlayTestServerBuilder {
  def apply[F[_]](implicit F: ConcurrentEffect[F]): PlayTestServerBuilder[F] =
    new PlayTestServerBuilder(
      hostname = IPv4SocketAddress.getHostString,
      services = Vector.empty,
      port = org.http4s.server.defaults.HttpPort,
      executionContext = ExecutionContext.global
    )
}
