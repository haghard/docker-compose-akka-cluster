package demo

import java.util
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets._
import java.util.concurrent.ConcurrentSkipListSet

import scala.collection.immutable.SortedSet

package object hashing {

  @simulacrum.typeclass
  trait Hashing[Shard] {
    def seed: Long

    def name: String

    def withShards(shard: util.Collection[Shard]): Hashing[Shard] = {
      val iter = shard.iterator
      while (iter.hasNext) {
        add(iter.next)
      }
      this
    }

    def toBinary(shard: Shard): Array[Byte]

    def remove(shard: Shard): Boolean

    def add(shard: Shard): Boolean

    def replicaFor(key: String, rf: Int): Set[Shard]

    def validated(shard: Shard): Boolean
  }

  /**
    * Highest Random Weight (HRW) hashing
    * https://github.com/clohfink/RendezvousHash
    * https://www.pvk.ca/Blog/2017/09/24/rendezvous-hashing-my-baseline-consistent-distribution-method/
    * A random uniform way to partition your keyspace up among the available nodes
    *
    *
    *
    */
  @simulacrum.typeclass
  trait Rendezvous[Shard] extends Hashing[Shard] {
    override val seed     = 512L
    override val name     = "rendezvous-hashing"
    protected val members = new ConcurrentSkipListSet[Shard]()

    override def remove(shard: Shard): Boolean =
      members.remove(shard)

    override def add(shard: Shard): Boolean =
      if (validated(shard)) members.add(shard) else false

    override def replicaFor(key: String, rf: Int): Set[Shard] = {
      if (rf > members.size)
        throw new Exception("Replication factor more than the number of the ranges on a ring")

      var candidates = SortedSet.empty[(Long, Shard)]((x: (Long, Shard), y: (Long, Shard)) ⇒ -x._1.compare(y._1))
      val iter       = members.iterator
      while (iter.hasNext) {
        val shard           = iter.next
        val keyBytes        = key.getBytes(UTF_8)
        val nodeBytes       = toBinary(shard)
        val keyAndShard     = ByteBuffer.allocate(keyBytes.length + nodeBytes.length).put(keyBytes).put(nodeBytes)
        val shardHash128bit = CassandraHash.hash3_x64_128(keyAndShard, 0, keyAndShard.array.length, seed)(1)
        candidates = candidates + (shardHash128bit → shard)
      }
      candidates.take(rf).map(_._2)
    }

    override def toString: String = {
      val iter = members.iterator
      val sb   = new StringBuilder
      while (iter.hasNext) {
        val shard = iter.next
        sb.append(s"[${shard}]").append("->")
      }
      sb.toString
    }
  }

  /*
    https://community.oracle.com/blogs/tomwhite/2007/11/27/consistent-hashing
    https://www.datastax.com/dev/blog/token-allocation-algorithm
    http://docs.basho.com/riak/kv/2.2.3/learn/concepts/vnodes/

    We want to have an even split of the token range so that load can be well distributed between nodes,
      as well as the ability to add new nodes and have them take a fair share of the load without the necessity
      to move data between the existing nodes
   */
  @simulacrum.typeclass
  trait Consistent[Shard] extends Hashing[Shard] {
    import scala.collection.JavaConverters._
    import java.util.{SortedMap ⇒ JSortedMap, TreeMap ⇒ JTreeMap}

    private val numberOfVNodes = 4
    override val seed          = 512L
    override val name          = "consistent-hashing"

    private val ring: JSortedMap[Long, Shard] = new JTreeMap[Long, Shard]()

    private def writeInt(arr: Array[Byte], i: Int, offset: Int): Array[Byte] = {
      arr(offset) = (i >>> 24).toByte
      arr(offset + 1) = (i >>> 16).toByte
      arr(offset + 2) = (i >>> 8).toByte
      arr(offset + 3) = i.toByte
      arr
    }

    override def remove(shard: Shard): Boolean =
      (0 to numberOfVNodes).foldLeft(true) { (acc, vNodeId) ⇒
        val vNodeSuffix = Array.ofDim[Byte](4)
        writeInt(vNodeSuffix, vNodeId, 0)
        val bytes          = toBinary(shard) ++ vNodeSuffix
        val nodeHash128bit = CassandraHash.hash3_x64_128(ByteBuffer.wrap(bytes), 0, bytes.length, seed)(1)
        acc & shard == ring.remove(nodeHash128bit)
      }

    override def add(shard: Shard): Boolean =
      //Hash each node to several numberOfVNodes
      if (validated(shard)) {
        (0 to numberOfVNodes).foldLeft(true) { (acc, i) ⇒
          val suffix = Array.ofDim[Byte](4)
          writeInt(suffix, i, 0)
          val shardBytes = toBinary(shard) ++ suffix
          val nodeHash128bit =
            CassandraHash.hash3_x64_128(ByteBuffer.wrap(shardBytes), 0, shardBytes.length, seed)(1)
          acc & (shard == ring.put(nodeHash128bit, shard))
        }
      } else false

    override def replicaFor(key: String, rf: Int): Set[Shard] = {
      if (rf > ring.keySet.size)
        throw new Exception("Replication factor more than the number of the ranges on a ring")

      val keyBytes = key.getBytes(UTF_8)
      val keyHash  = CassandraHash.hash3_x64_128(ByteBuffer.wrap(keyBytes), 0, keyBytes.length, seed)(1)
      if (ring.containsKey(keyHash)) {
        ring.keySet.asScala.take(rf).map(ring.get).to[scala.collection.immutable.Set]
      } else {
        val clockWiseSubRing = ring.tailMap(keyHash)
        val replicas         = clockWiseSubRing.keySet.asScala.take(rf).map(ring.get).to[scala.collection.immutable.Set]
        //println(s"got ${candidates.mkString(",")} till the end of range")
        if (replicas.size < rf) {
          //println(s"the end is reached. need ${rest} more")
          //we must be at the end of the ring so we move to the beginning
          replicas ++ ring.keySet.asScala.take(rf - replicas.size).map(ring.get).to[scala.collection.immutable.Set]
        } else replicas
      }
    }

    override def toString: String = {
      val iter = ring.keySet.iterator
      val sb   = new StringBuilder
      while (iter.hasNext) {
        val key = iter.next
        sb.append(s"[${key}: ${ring.get(key)}]").append("->")
      }
      sb.toString
    }
  }

  object Consistent {
    implicit def instance0 = new Consistent[String] {
      override def toBinary(node: String): Array[Byte] = node.getBytes(UTF_8)
      override def validated(node: String): Boolean    = true
    }
  }

  object Rendezvous {
    implicit def instance0 = new Rendezvous[String] {
      override def toBinary(node: String): Array[Byte] = node.getBytes(UTF_8)
      override def validated(node: String): Boolean    = true
    }

    implicit def instance2 = new Rendezvous[demo.Replica] {
      override def toBinary(node: demo.Replica): Array[Byte] =
        s"${node.addr.host}:${node.addr.port}".getBytes(UTF_8)
      override def validated(shard: demo.Replica): Boolean = true
    }
  }
}
