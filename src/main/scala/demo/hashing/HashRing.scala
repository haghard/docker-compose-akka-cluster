package demo.hashing

import scala.collection.MapView
import scala.collection.immutable.SortedMap
import spray.json.{JsArray, JsNumber, JsObject, JsString}

/*
  Nodes in the cluster own ranges/buckets of all the possible tokens.
  By default it outputs tokens in the range of -263 to 263 - 1,
 */
case class HashRing(private val ring: SortedMap[Long, String], start: Long, end: Long, step: Long) {

  /**
    * Alias for [[add]] method
    */
  def :+(node: String): Option[(HashRing, Set[(Long, String)])] =
    add(node)

  /**
    *
    * Adds a node to the node ring.
    * Note that the instance is immutable and this operation returns a new instance.
    *
    * When we add new node, it changes the ownership of some ranges by splitting it up.
    */
  def add(node: String): Option[(HashRing, Set[(Long, String)])] =
    if (nodes.contains(node))
      None
    else {
      val ringStep = nodes.size + 1
      val takeOvers = (start + (step * nodes.size) until end by (step * ringStep))
        .map(pId ⇒ (pId, lookup(pId).head))
        .toSet

      val updatedRing = takeOvers.foldLeft(ring) {
        case (ring, (pId, _)) ⇒ ring.updated(pId, node)
      }

      Some(HashRing(updatedRing, start, end, step) → takeOvers)
    }

  /**
    * Alias for [[remove]] method
    */
  def :-(node: String): Option[(HashRing, List[Long])] =
    remove(node)

  def remove(node: String): Option[(HashRing, List[Long])] =
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

      Some(HashRing(m, start, end, step) → ranges(node))
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

  def toCropCircle: String = {
    val en = ranges.keySet.toVector.map { k ⇒
      val entries = ranges(k).toVector.map { i ⇒
        JsObject(
          "name"     → JsNumber(i),
          "type"     → JsString("member"),
          "children" → JsArray()
        )
      }

      JsObject(
        "name"     → JsString(k),
        "type"     → JsString("shard"),
        "children" → JsArray(entries)
      )
    }

    JsObject(
      "name"     → JsString("fsa-ring"),
      "type"     → JsString("cluster"),
      "children" → JsArray(en)
    ).compactPrint
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

object HashRing {

  def apply(
    name: String,
    start: Long = Long.MinValue,
    end: Long = Long.MaxValue,
    step: Long = 9223372036854773L //2501 partitions, change if you need more
  ): HashRing =
    HashRing(
      (start until end by step)
        .foldLeft(SortedMap[Long, String]()) { (acc, c) ⇒
          acc + (c → name)
        },
      start,
      end,
      step
    )
}
