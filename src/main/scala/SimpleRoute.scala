package main

import akka.actor.ActorRef
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.util.ByteString

import akka.http.scaladsl.server.Route

import scala.concurrent.ExecutionContext

class SimpleRoute(cluster: ActorRef, host: String)(implicit ex: ExecutionContext) extends Directives {

  import akka.pattern.ask

  val route: Route =
    (get & path("members")) {
      complete {
        (cluster ? 'Members).mapTo[String] { _ =>
          HttpResponse(status = StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(host)))
        }
      }
    }

}
