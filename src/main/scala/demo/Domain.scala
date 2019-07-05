/*
package demo

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.scaladsl.Effect
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey, EventSourcedEntity}

//https://doc.akka.io/docs/akka/2.5.23/typed/cluster-sharding.html
object Domain {

  // Command
  trait Command

  final case class AddMsg(whom: String, msg: String)(val replyTo: ActorRef[MsgAdded]) extends Command

  // Response
  final case class MsgAdded(whom: String, numberOfPeople: Int)

  // Event
  final case class Msg(whom: String)

  // State
  private final case class ChatState(names: Set[String]) {
    def add(name: String): ChatState =
      copy(names = names + name)

    def size: Int = names.size
  }

  private val commandHandler: (ChatState, Command) ⇒ Effect[Msg, ChatState] = { (_, cmd) ⇒
    cmd match {
      case cmd: AddMsg ⇒
        Effect.persist(Msg(cmd.whom))
          .thenRun(state ⇒ cmd.replyTo ! MsgAdded(cmd.whom, state.size))
        //greet(cmd)
    }
  }

  /*private def greet(cmd: Greet): Effect[Greeted, KnownPeople] =
    Effect.persist(Greeted(cmd.whom)).thenRun(state ⇒ cmd.replyTo ! Greeting(cmd.whom, state.numberOfPeople))*/

  private val eventHandler: (ChatState, Msg) ⇒ ChatState =
  { (state, evt) ⇒
    state.add(evt.whom)
  }

  val entityTypeKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("chats")

  def persistentEntity(shard: ActorRef[ClusterSharding.ShardCommand], entityId: String): Behavior[Command] =
    EventSourcedEntity(
      entityTypeKey = entityTypeKey,
      entityId = entityId,
      emptyState = ChatState(Set.empty),
      commandHandler,
      eventHandler
    )
}
 */
