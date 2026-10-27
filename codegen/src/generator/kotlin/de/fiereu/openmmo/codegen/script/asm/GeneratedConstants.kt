package de.fiereu.openmmo.codegen.script.asm

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The C constants the decomp's build generates from its json data instead of a header
 * (map_groups.h, layouts.h, heal_locations.h, map_event_ids.h), recreated from the same json.
 */
object GeneratedConstants {
  private val json = Json { ignoreUnknownKeys = true }

  /** MAP_* = group << 8 | index, LAYOUT_* = 1-based, HEAL_LOCATION_* = 1-based, LOCALID_*. */
  fun all(decomp: File): Map<String, Long> {
    val out = LinkedHashMap<String, Long>()
    val groups =
        json.parseToJsonElement(File(decomp, "data/maps/map_groups.json").readText()).jsonObject
    val order = groups.getValue("group_order").jsonArray.map { it.jsonPrimitive.content }
    for ((g, groupName) in order.withIndex()) {
      val maps = groups[groupName]?.jsonArray ?: continue
      for ((n, m) in maps.withIndex()) {
        val dir = m.jsonPrimitive.content
        val mapJson = File(decomp, "data/maps/$dir/map.json")
        if (!mapJson.isFile) continue
        val root = json.parseToJsonElement(mapJson.readText()).jsonObject
        val id = root["id"]?.jsonPrimitive?.content ?: continue
        out[id] = (g.toLong() shl 8) or n.toLong()
      }
    }
    val layouts = File(decomp, "data/layouts/layouts.json")
    if (layouts.isFile) {
      val arr = json.parseToJsonElement(layouts.readText()).jsonObject.getValue("layouts").jsonArray
      for ((i, l) in arr.withIndex()) {
        val id = (l as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: continue
        out[id] = (i + 1).toLong()
      }
    }
    val heal = File(decomp, "src/data/heal_locations.json")
    if (heal.isFile) {
      val root = json.parseToJsonElement(heal.readText()).jsonObject
      val arr = (root["heal_locations"] ?: root.values.first()).jsonArray
      for ((i, l) in arr.withIndex()) {
        val id = l.jsonObject["id"]?.jsonPrimitive?.content ?: continue
        out[id] = (i + 1).toLong()
      }
    }
    for (ids in localIds(decomp).values) for ((k, v) in ids) out.putIfAbsent(k, v)
    return out
  }

  /** constants/tms_hms.inc's X-macro enum: ITEM_TM_<MOVE> / ITEM_HM_<MOVE> after ITEM_TM01/HM01. */
  fun tmHmExpressions(decomp: File): Map<String, String> {
    val f = File(decomp, "include/constants/tms_hms.h")
    if (!f.isFile) return emptyMap()
    val text = f.readText()
    val out = LinkedHashMap<String, String>()
    for ((kind, first) in listOf("TM" to "ITEM_TM01", "HM" to "ITEM_HM01")) {
      val block = text.substringAfter("#define FOREACH_$kind(F)", "").substringBefore("\n\n")
      val body =
          block.lineSequence().takeWhile {
            it.isBlank() || it.trimEnd().endsWith("\\") || it.contains("F(")
          }
      var i = 0
      for (m in Regex("""F\((\w+)\)""").findAll(body.joinToString("\n"))) {
        out["ITEM_${kind}_${m.groupValues[1]}"] = "($first + $i)"
        i++
      }
    }
    return out
  }

  /** Per map directory: LOCALID_* name -> 1-based object event index. */
  fun localIds(decomp: File): Map<String, Map<String, Long>> {
    val out = LinkedHashMap<String, Map<String, Long>>()
    File(decomp, "data/maps")
        .listFiles()
        ?.sortedBy { it.name }
        ?.forEach { dir ->
          val mapJson = File(dir, "map.json")
          if (!mapJson.isFile) return@forEach
          val root = json.parseToJsonElement(mapJson.readText()).jsonObject
          val objs = root["object_events"]?.jsonArray ?: return@forEach
          val ids = LinkedHashMap<String, Long>()
          for ((i, o) in objs.withIndex()) {
            val id = o.jsonObject["local_id"]?.jsonPrimitive?.content ?: continue
            ids[id] = (i + 1).toLong()
          }
          out[dir.name] = ids
        }
    return out
  }
}
