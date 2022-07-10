package demo.hashing

import spray.json.{JsArray, JsObject, JsString}

final case class CropCircle(
  name: String,
  map: Map[String, Set[String]] = Map[String, Set[String]]().withDefaultValue(Set.empty)
) {
  private val ClusterType = "cluster"
  private val GroupType   = "member"
  private val ElementType = "shard"

  def add(memberId: String, entityId: String): CropCircle =
    copy(map = map.updated(memberId, map(memberId) + entityId))

  def :+(memberId: String, entityId: String): CropCircle =
    copy(map = map.updated(memberId, map(memberId) + entityId))

  override def toString: String = {
    val en = map.keySet.toVector.map { k =>
      val entries = map(k).toVector.map { actorPath =>
        JsObject(
          "name"     -> JsString(actorPath),
          "type"     -> JsString(ElementType),
          "children" -> JsArray()
        )
      }

      JsObject(
        "name"     -> JsString(k),
        "type"     -> JsString(GroupType),
        "children" -> JsArray(entries)
      )
    }

    JsObject(
      "name"     -> JsString(name),
      "type"     -> JsString(ClusterType),
      "children" -> JsArray(en)
    ).compactPrint
  }
}
