package de.fiereu.openmmo.client.world

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The text behind the server's dialog ids. The server sends only a text id (the same table codegen
 * builds its DialogLine constants from, committed under `codegen/dialog/<region>.json`), so the
 * client resolves it locally from that file.
 */
class DialogText(private val dir: File) {
  private val tables = mutableMapOf<String, Map<Int, String>>()

  fun raw(region: String, textId: Int): String? = table(region)[textId]

  /** Looks the id up in every region, for boxes sent while the map is not known yet. */
  fun rawAnyRegion(textId: Int): String? =
      listOf("kanto", "hoenn").firstNotNullOfOrNull { table(it)[textId] }

  @Synchronized
  private fun table(region: String): Map<Int, String> =
      tables.getOrPut(region) {
        // The ROM-resolved table, plus decomp-only lines with synthetic ids
        // (`<region>.extra.json`, and r5's `<region>.script.json` for the script interpreter).
        listOf("$region.json", "$region.extra.json", "$region.script.json")
            .map { File(dir, it) }
            .filter { it.exists() }
            .flatMap { f ->
              Json.parseToJsonElement(f.readText()).jsonObject.getValue("lines").jsonArray.map {
                val o = it.jsonObject
                o.getValue("id").jsonPrimitive.int to o.getValue("text").jsonPrimitive.content
              }
            }
            .toMap()
      }

  companion object {
    /** Lines per page in the GBA text box. */
    const val LINES_PER_PAGE = 2

    /**
     * Splits decomp text into pages: `\p` starts a new box, `\n` a new line, `\l` scrolls the box a
     * line, `$` ends the string. Placeholders are filled from [vars]; unknown control codes are
     * dropped.
     */
    fun paginate(text: String, vars: Map<String, String> = emptyMap()): List<List<String>> {
      var t = text.substringBefore('$')
      t =
          Regex("""\{([A-Z0-9_]+)(?: [^}]*)?\}""").replace(t) { m ->
            vars[m.groupValues[1]] ?: if (m.groupValues[1] in SPACING) " " else ""
          }
      val pages = mutableListOf<List<String>>()
      for (box in t.split("\\p")) {
        val lines = box.split("\\n", "\\l").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue
        lines.chunked(LINES_PER_PAGE).forEach { pages += it }
      }
      return pages.ifEmpty { listOf(listOf("")) }
    }

    private val SPACING = setOf("SPACE")
  }
}
