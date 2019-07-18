package demo.hashing

import scala.collection.MapView
import scala.collection.immutable.SortedMap

case class Ring(private val ring: SortedMap[Long, String], start: Long, end: Long, step: Long) {

  /**
    * Alias for [[add]] method
    */
  def :+(node: String): Option[(Ring, Set[(Long, String)])] =
    add(node)

  /**
    *
    * Adds a node to the node ring.
    * Note that the instance is immutable and this operation returns a new instance.
    *
    * When we add new node, it changes the ownership of some ranges by splitting it up.
    */
  def add(node: String): Option[(Ring, Set[(Long, String)])] =
    if (nodes.contains(node))
      None
    else {
      // this could be improved e.g. we should rely on least taken resources
      val ringStep = nodes.size + 1
      val takeOvers = (start until end by (step * ringStep))
        .map(pId ⇒ (pId, lookup(pId).head))
        .toSet

      val updatedRing = takeOvers.foldLeft(ring) {
        case (acc, (pId, _)) ⇒ acc.updated(pId, node)
      }

      Option((Ring(updatedRing, start, end, step) → takeOvers))
    }

  /**
    * Alias for [[remove]] method
    */
  def :-(node: String): Option[Ring] =
    remove(node)

  def remove(node: String): Option[Ring] =
    if (!nodes.contains(node))
      None
    else {
      val m = ranges(node) match {
        case Nil ⇒ ring
        case h :: t ⇒
          if (t.size == 0) ring - h
          else if (t.size == 1) ring - (h, t.head)
          else if (t.size == 2) ring - (h, t.head, t(1))
          else ring - (h, t.head, t.tail: _*)
      }

      Some(Ring(m, start, end, step))
    }

  def lookup(hash: Long, rf: Int = 1): Vector[String] =
    (ring.keysIteratorFrom(hash) ++ ring.keysIteratorFrom(ring.firstKey))
      .take(Math.min(rf, nodes.size))
      .map(ring(_))
      .toVector

  def last: Long = ring.lastKey

  def first: Long = ring.firstKey

  def size: Int = ring.size

  def nodes: Set[String] = ring.values.toSet

  def ranges: MapView[String, List[Long]] =
    ring.groupBy(_._2).view.mapValues(_.keys.toList.sorted)

  def showSubRange(startKey: Long, endKey: Long): String = {
    var cur  = startKey
    val sb   = new StringBuilder
    val iter = ring.keysIteratorFrom(startKey)
    while (iter.hasNext && cur <= endKey) {
      val key = iter.next
      cur = key
      sb.append(s"[${key}: ${ring(key)}]").append("->")
    }
    sb.toString
  }

  override def toString: String = {
    val sb   = new StringBuilder
    val iter = ring.keysIteratorFrom(ring.firstKey)
    while (iter.hasNext) {
      val key = iter.next
      sb.append(s"[${ring(key)}:${key}]").append("->")
    }
    sb.toString
  }
}

object Ring {

  def apply(
    name: String,
    start: Long = Long.MinValue,
    end: Long = Long.MaxValue,
    step: Long = 6917529027641080L //2667 partitions, change  if you need more
  ): Ring =
    Ring(
      (start until end by step)
        .foldLeft(SortedMap[Long, String]()) { (acc, c) ⇒
          acc + (c → name)
        },
      start,
      end,
      step
    )
}
