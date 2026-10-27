package de.fiereu.openmmo.web.assets

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * r5-minigames assets (PROTOCOL.md section "r5-minigames"): the slot machine reel icons of both
 * games, the roulette's board icons, the Fame Checker's people and texts (fame_checker.c +
 * data/text/fame_checker.inc) and Emerald's Easy Chat word groups (src/data/easy_chat). The
 * graphics are the decomp's indexed PNGs copied as they are.
 */
class MinigameExport(private val decomp: File, private val out: Output) {
  private fun copy(game: String, rel: String, dest: String): String? {
    val f = File(decomp, "$game/$rel")
    if (!f.isFile) return null
    return out.write("minigames/$dest", f.readBytes())
  }

  fun export(): JsonObject = buildJsonObject {
    put(
        "slots",
        buildJsonObject {
          copy("pokefirered", "graphics/slot_machine/firered/reel_icons.png", "frlg_reel_icons.png")
              ?.let { put("frlgIcons", it) }
          copy("pokefirered", "graphics/slot_machine/firered/clefairy.png", "frlg_clefairy.png")
              ?.let { put("frlgClefairy", it) }
          put(
              "emeraldSymbols",
              buildJsonArray {
                for (i in 1..7) {
                  copy("pokeemerald", "graphics/slot_machine/reel_symbols/$i.png", "em_symbol_$i.png")
                      ?.let { add(JsonPrimitive(it)) }
                }
              })
          copy("pokeemerald", "graphics/slot_machine/reel_time_pikachu.png", "em_reeltime_pikachu.png")
              ?.let { put("reelTimePikachu", it) }
        })
    put(
        "roulette",
        buildJsonObject {
          for (n in listOf("wynaut", "azurill", "skitty", "makuhita", "ball", "shroomish", "tailow")) {
            copy("pokeemerald", "graphics/roulette/$n.png", "roulette_$n.png")?.let { put(n, it) }
          }
        })
    put("fameChecker", fameChecker())
    put("easyChat", easyChat())
  }

  // --- Fame Checker ------------------------------------------------------------------------------

  /** Label -> its text, from a data/text .inc of `Label:: .string "..."` blocks. */
  private fun incStrings(game: String, rel: String): Map<String, String> {
    val f = File(decomp, "$game/$rel")
    if (!f.isFile) return emptyMap()
    val out = LinkedHashMap<String, String>()
    var label: String? = null
    val sb = StringBuilder()
    fun flush() {
      label?.let { out[it] = clean(sb.toString()) }
      sb.clear()
    }
    for (line in f.readLines()) {
      val l = Regex("""^(\w+)::?""").find(line)
      if (l != null && !line.startsWith("\t")) {
        flush()
        label = l.groupValues[1]
        continue
      }
      Regex("""\.string\s+"(.*)"""").find(line)?.let { sb.append(it.groupValues[1]) }
    }
    flush()
    return out
  }

  /** Decomp string escapes to plain text: \n and \l are line breaks, \p a page break. */
  private fun clean(s: String) =
      s.replace(Regex("""\{[A-Z_]+(?: [A-Z_]+)?\}""")) { m ->
            when (m.value) {
              "{PLAYER}" -> "{PLAYER}"
              "{RIVAL}" -> "{RIVAL}"
              else -> ""
            }
          }
          .replace("\\p", "\n\n")
          .replace("\\n", "\n")
          .replace("\\l", "\n")
          .replace("$", "")
          .replace("é", "é")

  /** A C pointer table `static const u8 *const name[...] = { a, b, ... };` as its entries. */
  private fun table(src: String, name: String): List<String> {
    val m = Regex("""$name\s*\[[^\]]*\]\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL).find(src)
        ?: return emptyList()
    return m.groupValues[1]
        .replace(Regex("//[^\n]*"), "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
  }

  private fun fameChecker(): JsonObject {
    val srcFile = File(decomp, "pokefirered/src/fame_checker.c")
    if (!srcFile.isFile) return JsonObject(emptyMap())
    val src = srcFile.readText()
    val texts =
        incStrings("pokefirered", "data/text/fame_checker.inc") +
            incStrings("pokefirered", "data/text/strings.inc")
    val strings = File(decomp, "pokefirered/src/strings.c").takeIf { it.isFile }?.readText() ?: ""
    fun cString(label: String): String =
        texts[label]
            ?: Regex("""$label\[\]\s*=\s*_\("(.*?)"\);""").find(strings + src)?.groupValues?.get(1)?.let(::clean)
            ?: label
    val names = table(src, "sFameCheckerNameAndQuotesPointers")
    val flavor = table(src, "sFameCheckerFlavorTextPointers")
    val origins = table(src, "sFlavorTextOriginLocationTexts")
    val objects = table(src, "sFlavorTextOriginObjectNameTexts")
    val persons =
        listOf(
            "OAK", "DAISY", "BROCK", "MISTY", "LT. SURGE", "ERIKA", "KOGA", "SABRINA",
            "BLAINE", "LORELEI", "BRUNO", "AGATHA", "LANCE", "BILL", "MR. FUJI", "GIOVANNI")
    return buildJsonObject {
      put(
          "people",
          buildJsonArray {
            for (i in persons.indices) {
              add(
                  buildJsonObject {
                    put("id", i)
                    put("name", persons[i])
                    names.getOrNull(i)?.let { put("title", cString(it)) }
                    names.getOrNull(persons.size + i)?.let { put("quote", cString(it)) }
                    put(
                        "texts",
                        JsonArray((0 until 6).map { j -> JsonPrimitive(flavor.getOrNull(i * 6 + j)?.let(::cString) ?: "") }))
                    put(
                        "origins",
                        JsonArray((0 until 6).map { j -> JsonPrimitive(origins.getOrNull(i * 6 + j)?.let(::cString) ?: "") }))
                    put(
                        "objects",
                        JsonArray((0 until 6).map { j -> JsonPrimitive(objects.getOrNull(i * 6 + j)?.let(::cString) ?: "") }))
                  })
            }
          })
    }
  }

  // --- Easy Chat -------------------------------------------------------------------------------

  /**
   * The word groups (EC_GROUP_* order of include/constants/easy_chat.h), each its words in id
   * order: word id = group << 9 | index (EC_WORD).
   */
  private fun easyChat(): JsonObject {
    val dir = File(decomp, "pokeemerald/src/data/easy_chat")
    val header = File(decomp, "pokeemerald/include/constants/easy_chat.h")
    if (!dir.isDirectory || !header.isFile) return JsonObject(emptyMap())
    val groups =
        Regex("""#define EC_GROUP_(\w+)\s+(0x[0-9A-Fa-f]+|\d+)""")
            .findAll(header.readText())
            .map { it.groupValues[1] to Integer.decode(it.groupValues[2]) }
            .filter { it.first != "MAX" }
            .toList()
    return buildJsonObject {
      put(
          "groups",
          buildJsonArray {
            for ((name, id) in groups.sortedBy { it.second }) {
              val f = File(dir, "easy_chat_group_${name.lowercase()}.h")
              val words =
                  if (f.isFile)
                      Regex("""_\("(.*?)"\)""").findAll(f.readText()).map { it.groupValues[1] }.toList()
                  else emptyList()
              add(
                  buildJsonObject {
                    put("id", id)
                    put("name", name.replace('_', ' '))
                    put("words", JsonArray(words.map { JsonPrimitive(it) }))
                  })
            }
          })
    }
  }
}
