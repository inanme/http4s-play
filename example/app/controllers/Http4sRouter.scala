package controllers

import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.effect.Async
import cats.effect.IO
import javax.inject.Inject
import org.http4s.dsl.Http4sDsl
import org.http4s.server.play.PlayRouteBuilder
import org.http4s.server.play.RequestContext
import org.http4s.HttpRoutes
import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import scala.concurrent.ExecutionContext

class Http4sRouter @Inject() (implicit executionContext: ExecutionContext) extends SimpleRouter {

  val (dispatcher, dispatcherShutdown) = Dispatcher[IO].allocated.unsafeRunSync()

  type Eff[A] = Kleisli[IO, RequestContext, A]

  val exampleService: HttpRoutes[Eff] = {
    object dsl extends Http4sDsl[Kleisli[IO, RequestContext, *]]
    import dsl._
    HttpRoutes.of[Eff] { case GET -> Root / "hello" =>
      println("test")
      Ok(s"Hello World!")
    }
  }

  override def routes: Routes = {
    import play.api.routing.sird._
    { case GET(p"/hello") =>
      new PlayRouteBuilder[IO](exampleService, None)(Async[IO], dispatcher).build
    }
  }
}
