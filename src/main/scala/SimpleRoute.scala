package main

import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.util.ByteString

import akka.http.scaladsl.server.Route

class SimpleRoute(host: String) extends Directives {

  val route: Route =
    (get & path("intro")) {
      complete {
        HttpResponse(status =StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(host)))
      }
    }

}
