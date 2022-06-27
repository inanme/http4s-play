package controllers

import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.effect.Async
import cats.effect.IO
import javax.inject.Inject
import org.http4s.dsl.io._
import org.http4s.server.play.PlayRouteBuilder
import org.http4s.HttpRoutes
import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import scala.concurrent.ExecutionContext

class Http4sRouter @Inject() (implicit executionContext: ExecutionContext) extends SimpleRouter {

  val (dispatcher, dispatcherShutdown) = Dispatcher[IO].allocated.unsafeRunSync()

  val exampleService: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "hello" =>
    println("test")
    Ok(s"Hello World!")
  }

  override def routes: Routes =
    new PlayRouteBuilder[IO](exampleService)(Async[IO], dispatcher).build
}
