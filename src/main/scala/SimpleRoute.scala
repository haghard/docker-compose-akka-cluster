package main

import akka.actor.ActorRef
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Route, _}
import akka.util.ByteString

import scala.concurrent.{ExecutionContext, Future}

class SimpleRoute(cluster: ActorRef, host: String)(implicit ex: ExecutionContext) extends Directives {

  import akka.pattern.ask

  import scala.concurrent.duration._

  implicit val _ = akka.util.Timeout(5 seconds)

  val route: Route =
    (get & path("members")) {
      complete {
        query()

      }
    }

  def query: Future[HttpResponse] = {
    (cluster ask 'Members).mapTo[String].map  { resp: String =>
      HttpResponse(status = StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(host)))
    }
  }
}
