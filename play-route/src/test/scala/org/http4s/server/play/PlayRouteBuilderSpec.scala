package org.http4s.server.play

import akka.stream.Materializer
import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.effect.Async
import cats.effect.IO
import org.http4s.dsl.Http4sDsl
import org.http4s.HttpRoutes
import org.scalatestplus.play.components.OneAppPerSuiteWithComponents
import org.scalatestplus.play.PlaySpec
import play.api.mvc.Result
import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.test.FakeRequest
import play.api.test.Helpers
import play.api.test.Helpers._
import play.api.BuiltInComponents
import play.api.BuiltInComponentsFromContext
import play.api.NoHttpFiltersComponents
import scala.concurrent.Future

class PlayRouteBuilderSpec extends PlaySpec with OneAppPerSuiteWithComponents {

  override def components: BuiltInComponents = new BuiltInComponentsFromContext(context)
    with NoHttpFiltersComponents {

    lazy val router = new SimpleRouter {

      val (dispatcher, _)       = Dispatcher[IO].allocated.unsafeRunSync()
      implicit val dispatcherIO: Dispatcher[IO] = dispatcher

      val exampleService: HttpRoutes[Kleisli[IO, RequestContext, *]] = {
        object dsl extends Http4sDsl[Kleisli[IO, RequestContext, *]]; import dsl._
        HttpRoutes.of[Kleisli[IO, RequestContext, *]] { case GET -> Root / "hello" =>
          Ok(s"Hello World!")
        }
      }

      override def routes: Routes = {
        import play.api.routing.sird._
        { case GET(p"/hello") =>
          new PlayRouteBuilder[IO](exampleService, None).build
        }
      }
    }
  }

  "The OneAppPerSuiteWithComponents trait" must {
    "provide an Application" in {
      import play.api.test.Helpers.GET
      import play.api.test.Helpers.route
      val Some(result: Future[Result])        = route(app, FakeRequest(GET, "/hello"))
      implicit val materializer: Materializer = app.materializer
      Helpers.contentAsString(result) must be("Hello World!")
    }
  }
}
