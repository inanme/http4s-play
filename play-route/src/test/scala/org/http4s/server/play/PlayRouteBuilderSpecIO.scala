package org.http4s.server.play

import akka.stream.Materializer
import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.IO
import cats.effect.Resource
import org.http4s.dsl.Http4sDsl
import org.http4s.HttpRoutes
import org.scalatestplus.play.components.WithApplicationComponents
import play.api.mvc.Result
import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.test.FakeRequest
import play.api.test.Helpers
import play.api.test.Helpers._
import play.api.Application
import play.api.BuiltInComponents
import play.api.BuiltInComponentsFromContext
import play.api.NoHttpFiltersComponents
import play.api.Play

import scala.concurrent.Future
import weaver.IOSuite

object PlayRouteBuilderSpecIO extends IOSuite {

  override type Res = Application

  override def sharedResource: Resource[IO, Application] =
    for {
      implicit0(dispatcher: Dispatcher[IO]) <- Dispatcher[IO]
      app                                   <- newApplication
    } yield app

  def newApplication(implicit dispatcher: Dispatcher[IO]): Resource[IO, Application] =
    Resource.make[IO, Application](IO.delay {
      val app: Application = (new ApplicationWrapper).newApplication
      Play.start(app)
      app
    })(app => IO.delay(Play.stop(app)))

  private class ApplicationWrapper(implicit dispatcher: Dispatcher[IO])
    extends WithApplicationComponents {
    override def components: BuiltInComponents = new BuiltInComponentsFromContext(context)
      with NoHttpFiltersComponents {

      val router: SimpleRouter = new SimpleRouter {

        val healthRoutes: HttpRoutes[Kleisli[IO, RequestContext, *]] = {
          object dsl extends Http4sDsl[Kleisli[IO, RequestContext, *]];
          import dsl._
          HttpRoutes.of[Kleisli[IO, RequestContext, *]] { case GET -> Root / "health" =>
            Ok(s"Ok!")
          }
        }

        override def routes: Routes = {
          import play.api.routing.sird._
          { case GET(p"/health") =>
            new PlayRouteBuilder[IO](healthRoutes, None).build
          }
        }
      }
    }
  }

  test(
    "PlayRouteBuilder should delegate Play request to Http4s request and the same for response"
  ) { app =>
    import play.api.test.Helpers.GET
    import play.api.test.Helpers.route
    implicit val materializer: Materializer = app.materializer

    IO.blocking {
      val Some(result: Future[Result]) = route(app, FakeRequest(GET, "/health"))
      assert.same(Helpers.contentAsString(result), "Ok!")
    }
  }

}
