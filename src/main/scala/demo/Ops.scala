package demo

import scala.collection.immutable.{Map, Seq}

trait Ops {

  private val Opt = """(\S+)=(\S+)""".r

  def argsToOpts(args: Seq[String]): Map[String, String] =
    args.collect { case Opt(key, value) => key -> value }.toMap

  def applySystemProperties(options: Map[String, String]): Unit =
    for ((key, value) ← options if key startsWith "-D") {
      println(s"Set $key: $value")
      System.setProperty(key.substring(2), value)
    }
}
