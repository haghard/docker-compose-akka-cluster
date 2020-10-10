package demo.hashing

import scala.collection.MapView
import scala.collection.immutable.SortedMap
import spray.json.{JsArray, JsNumber, JsObject, JsString}

/*
  Nodes take ownership over sub ranges within [-2 to 63  ...  2 to 63 - 1]
  This is an immutable data structure and therefore all modification operations return new instance of HashRing.
  Each node owns a range of those values

  Similar to https://github.com/justin-db/JustinDB/blob/844a3f6f03192ff3e8248a15712fecd754e06fbc/justin-ring/src/main/scala/justin/db/consistenthashing/Ring.scala
  and https://github.com/apache/cassandra/blob/trunk/src/java/org/apache/cassandra/dht/Murmur3Partitioner.java
 */
case class HashRing(private val ring: SortedMap[Long, String], start: Long, end: Long, step: Long) {

  /** Alias for [[add]] method
    */
  def :+(node: String): Option[(HashRing, Set[(Long, String)])] =
    add(node)

  /** Adds a node on ring.
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

      val updatedRing = takeOvers.foldLeft(ring) { case (ring, (pId, _)) ⇒
        ring.updated(pId, node)
      }

      Some(HashRing(updatedRing, start, end, step) → takeOvers)
    }

  /** Alias for [[remove]] method
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

  def last: Long = ring.lastKey

  def first: Long = ring.firstKey

  def size: Int = ring.size

  def nodes: Set[String] = ring.values.toSet

  def ranges: MapView[String, List[Long]] =
    ring.groupBy(_._2).view.mapValues(_.keys.toList.sorted)

  def lookup(hash: Long, rf: Int = 1): Vector[String] =
    (ring.valuesIteratorFrom(hash) ++ ring.valuesIteratorFrom(ring.firstKey))
      .take(Math.min(rf, nodes.size))
      .toVector

  def showSubRange(startKey: Long, endKey: Long): String = {
    var cur = startKey
    val sb  = new StringBuilder
    sb.append("\n")
      .append("Token")
      .append("\t\t\t")
      .append("Shard")
      .append("\n")
    val iter = ring.keysIteratorFrom(startKey)
    while (iter.hasNext && cur <= endKey) {
      val token = iter.next
      cur = token
      sb.append(token).append("\t\t").append(ring(token)).append("\n")
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
      "name"     → JsString("ring"),
      "type"     → JsString("cluster"),
      "children" → JsArray(en)
    ).compactPrint
  }

  override def toString: String = {
    val sb   = new StringBuilder
    val iter = ring.keysIteratorFrom(ring.firstKey)
    while (iter.hasNext) {
      val key = iter.next
      sb.append(s"[${ring(key)}:$key]").append("->")
    }
    sb.toString
  }
}

object HashRing {

  def apply(
    name: String,
    start: Long = Long.MinValue,
    end: Long = Long.MaxValue,
    step: Long = 9223372036854773L //2500 partitions, change if you need more
  ): HashRing =
    HashRing(
      (start until end by step)
        .foldLeft(SortedMap[Long, String]())((acc, c) ⇒ acc + (c → name)),
      start,
      end,
      step
    )
}
/*
val h = HashRing("a", -8, 8, 2)
 */
