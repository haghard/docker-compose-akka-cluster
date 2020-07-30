package demo

import akka.stream.KillSwitches
import akka.stream.Attributes.InputBuffer
import akka.stream.scaladsl.{Flow, FlowWithContext, Keep, MergeHub, Sink, Source}

import scala.concurrent.Future
import scala.util.{Failure, Success}

package object either {

  //Similar to `io.moia.streamee.either.tapErrors` but doesn't require a `BroadcastHub` instance
  def tapErrors[In, CtxIn, Out, CtxOut, Mat, E](
    f: Sink[(E, CtxOut), Any] ⇒ FlowWithContext[In, CtxIn, Out, CtxOut, Mat]
  ): FlowWithContext[In, CtxIn, Either[E, Out], CtxOut, Future[Mat]] = {
    val flow =
      Flow.fromMaterializer {
        case (mat, attr) ⇒
          val ((errorTap, switch), errors) =
            MergeHub
              .source[(E, CtxOut)](1) //attr.get[InputBuffer].map(_.max).getOrElse(1)
              .viaMat(KillSwitches.single)(Keep.both)
              .toMat(Sink.asPublisher(false))(Keep.both)
              .run()(mat)

          val flow: Flow[(In, CtxIn), (Either[E, Out], CtxOut), Mat] =
            f(errorTap)
              .map(Right.apply)
              .asFlow
              .alsoTo(
                Flow[Any]
                  .to(Sink.onComplete {
                    case Success(_)     ⇒ switch.shutdown()
                    case Failure(cause) ⇒ switch.abort(cause)
                  })
              )

          flow.merge(Source.fromPublisher(errors).map { case (e, ctxOut) ⇒ (Left(e), ctxOut) })
      }
    FlowWithContext.fromTuples(flow)
  }

  def tapFlowErrors[T](
    f: Sink[(String, T), Any] ⇒ Flow[T, T, akka.NotUsed]
  ): Flow[T, Either[String, T], Future[akka.NotUsed]] =
    Flow.fromMaterializer {
      case (mat, attributes) ⇒
        val ((errorTap, switch), errors) =
          MergeHub
            .source[(String, T)](attributes.get[InputBuffer].map(_.max).getOrElse(1))
            .viaMat(KillSwitches.single)(Keep.both)
            .toMat(Sink.asPublisher(false))(Keep.both)
            .run()(mat)

        val flow: Flow[T, Either[String, T], akka.NotUsed] =
          f(errorTap)
            .map(Right.apply)
            .alsoTo(
              Flow[Any]
                .to(Sink.onComplete {
                  case Success(_)     ⇒ switch.shutdown()
                  case Failure(cause) ⇒ switch.abort(cause)
                })
            )

        flow.merge(Source.fromPublisher(errors).map { case (e, _) ⇒ Left(e) })
    }
}
