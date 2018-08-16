package demo

import akka.actor.ActorRef
import demo.ClusterJvmMetrics.ConnectSource
import akka.stream.stage.GraphStageLogic.StageActor
import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler, StageLogging}

import scala.collection.mutable
import scala.reflect.ClassTag

//A custom graph stage to create a Source using getActorStage
//https://github.com/Keenworks/SampleActorStage/blob/master/src/main/scala/com/keenworks/sample/sampleactorstage/MessageSource.scala
final class ActorSource[T: ClassTag](sourceFeeder: ActorRef) extends GraphStage[SourceShape[T]] {
  val out: Outlet[T] = Outlet("out")
  override val shape: SourceShape[T] = SourceShape(out)

  override def createLogic(attributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      lazy val actorStage: StageActor = getStageActor(onReceive)
      val buffer = mutable.Queue[T]()

      override def preStart(): Unit = {
        log.info("pre-starting ActorSource")
        sourceFeeder ! ConnectSource(actorStage.ref)
      }

      setHandler(out,
        new OutHandler {
          override def onDownstreamFinish(): Unit = {
            val result = buffer.result
            if (result.nonEmpty) {
              /*
              log.debug(
                "In order to avoid message lost we need to notify the upsteam that " +
                  "consumed elements cannot be handled")
              1. actor ! result - resend maybe
              2. store to internal DB
              */
              completeStage()
            }
            completeStage()
          }

          override def onPull(): Unit = tryPush()
        }
      )

      def tryPush(): Unit = {
        if (isAvailable(out) && buffer.nonEmpty) {
          val element = buffer.dequeue
          push(out, element)
        }
      }

      def onReceive(x: (ActorRef, Any)): Unit = {
        x._2 match {
          case msg: T =>
            buffer enqueue msg
            tryPush()
          case other =>
            failStage(throw new Exception(
              s"Unexpected message type ${other.getClass.getSimpleName}"))
        }
      }
    }
}