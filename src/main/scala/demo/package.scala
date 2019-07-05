import akka.actor.Address

package object demo {

  case class Replica(addr: Address) extends Comparable[Replica] {
    override def compareTo(other: Replica): Int =
      Address.addressOrdering.compare(addr, other.addr)

    override def toString: String =
      addr.host.flatMap(h ⇒ addr.port.map(p ⇒ s"{$h:${p}}")).getOrElse("")
  }
}
