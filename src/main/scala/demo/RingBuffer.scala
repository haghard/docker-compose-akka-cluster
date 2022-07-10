package demo

object RingBuffer {
  def nextPowerOfTwo(value: Int): Int =
    1 << (32 - Integer.numberOfLeadingZeros(value - 1))
}

class RingBuffer[T: scala.reflect.ClassTag] private (capacity: Int, mask: Int, buffer: Array[T]) {
  private var tail: Long = 0L
  private var head: Long = 0L

  def this(capacity: Int) =
    this(
      RingBuffer.nextPowerOfTwo(capacity),
      RingBuffer.nextPowerOfTwo(capacity) - 1,
      Array.ofDim[T](RingBuffer.nextPowerOfTwo(capacity))
    )

  def offer(e: T): Boolean = {
    val wrapPoint = tail - capacity
    if (head <= wrapPoint) false
    else {
      val ind = (tail & mask).toInt
      buffer(ind) = e
      tail = tail + 1
      true
    }
  }

  def poll: Option[T] =
    if (head >= tail) None
    else {
      val index      = (head & mask).toInt
      val element: T = buffer(index)
      buffer(index) = null.asInstanceOf[T]
      head = head + 1
      Some(element)
    }

  def peek: Option[T] =
    if (head >= tail) None
    else {
      val index = head.toInt & mask
      Some(buffer(index))
    }

  def entries: Array[T] = {
    var head0 = head
    val copy  = if (tail > capacity) Array.ofDim[T](capacity) else Array.ofDim[T](tail.toInt - head.toInt)
    var i     = 0
    while (head0 < tail) {
      val ind = (head0 & mask).toInt
      println(head0 + "/" + tail + " - " + ind)
      copy(i) = buffer(ind)
      i += 1
      head0 += 1
    }
    copy
  }

  def size: Int = (tail - head).toInt

  override def toString =
    s"nextHead: [$head/${head.toInt & mask}] nextTail:[$tail/${tail.toInt & mask}] buffer: ${buffer.mkString(",")}"
}
