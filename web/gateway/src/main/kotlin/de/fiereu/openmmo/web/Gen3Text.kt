package de.fiereu.openmmo.web

import java.io.File

/** One pokedex page: the decomp's PokedexEntry (height in decimetres, weight in hectograms). */
data class DexEntry(
    val category: String,
    val heightDm: Int,
    val weightHg: Int,
    val description: String,
)

/**
 * Texts the pokedex and the TM case show, read from the decomp: `src/data/pokemon/
 * pokedex_entries.h` + `pokedex_text.h` (FireRed's for Kanto, Emerald's for Hoenn and as the
 * fallback) and Emerald's `src/data/text/move_descriptions.h`.
 */
class Gen3Text(private val decomp: File) {
  private val entries = HashMap<String, Map<Int, DexEntry>>()

  /** The entry of national dex [dex] as [game] (pokefirered / pokeemerald) words it. */
  fun dexEntry(dex: Int, game: String): DexEntry? =
      synchronized(entries) { entries.getOrPut(game) { parseDex(game) } }[dex]
          ?: if (game != EMERALD) dexEntry(dex, EMERALD) else null

  private fun parseDex(game: String): Map<Int, DexEntry> {
    val consts = File(decomp, "$game/include/constants/pokedex.h")
    val entriesFile = File(decomp, "$game/src/data/pokemon/pokedex_entries.h")
    // FireRed keeps its own wording beside LeafGreen's; pokedex_text.h only includes one.
    val textFile =
        listOf("pokedex_text_fr.h", "pokedex_text.h")
            .map { File(decomp, "$game/src/data/pokemon/$it") }
            .first { it.isFile || it.name == "pokedex_text.h" }
    if (!consts.isFile || !entriesFile.isFile || !textFile.isFile) return emptyMap()
    val natBlock = consts.readText().substringAfter("enum {").substringBefore("};")
    val national =
        Regex("""\b(NATIONAL_DEX_\w+)\b""")
            .findAll(natBlock)
            .map { it.groupValues[1] }
            .distinct()
            .withIndex()
            .associate { (i, n) -> n to i }
    val texts =
        Regex("""const u8 (\w+)\[\] = _\(((?:\s*"[^"]*")+)\s*\);""")
            .findAll(textFile.readText())
            .associate { m -> m.groupValues[1] to joinStrings(m.groupValues[2]) }
    val out = HashMap<Int, DexEntry>()
    for (m in
        Regex("""\[(NATIONAL_DEX_\w+)\]\s*=\s*\{(.*?)\n\s*\},""", RegexOption.DOT_MATCHES_ALL)
            .findAll(entriesFile.readText())) {
      val dex = national[m.groupValues[1]] ?: continue
      if (dex == 0) continue
      val body = m.groupValues[2]
      val cat = Regex("""\.categoryName = _\("([^"]*)"\)""").find(body)?.groupValues?.get(1)
      val h = Regex("""\.height = (\d+)""").find(body)?.groupValues?.get(1)?.toInt()
      val w = Regex("""\.weight = (\d+)""").find(body)?.groupValues?.get(1)?.toInt()
      // FireRed splits the text in two pages (description, unusedDescription in Emerald).
      val d = Regex("""\.description = (\w+)""").find(body)?.groupValues?.get(1)
      if (cat == null || h == null || w == null) continue
      out[dex] = DexEntry(cat, h, w, d?.let(texts::get).orEmpty())
    }
    return out
  }

  private val moveDescriptions: Map<Int, String> by lazy {
    val consts = File(decomp, "$EMERALD/include/constants/moves.h")
    val f = File(decomp, "$EMERALD/src/data/text/move_descriptions.h")
    if (!consts.isFile || !f.isFile) return@lazy emptyMap()
    val ids =
        Regex("""#define (MOVE_\w+)\s+(\d+)""").findAll(consts.readText()).associate {
          it.groupValues[1] to it.groupValues[2].toInt()
        }
    val text = f.readText()
    val strings =
        Regex("""const u8 (\w+)\[\] = _\(((?:\s*"[^"]*")+)\s*\);""").findAll(text).associate {
          it.groupValues[1] to joinStrings(it.groupValues[2])
        }
    Regex("""\[(MOVE_\w+) - 1\]\s*=\s*(\w+)""")
        .findAll(text)
        .mapNotNull {
          val id = ids[it.groupValues[1]] ?: return@mapNotNull null
          val s = strings[it.groupValues[2]] ?: return@mapNotNull null
          id to s
        }
        .toMap()
  }

  fun moveDescription(moveId: Int): String? = moveDescriptions[moveId]

  companion object {
    const val EMERALD = "pokeemerald"
    const val FIRERED = "pokefirered"

    fun joinStrings(parts: String): String =
        Regex("\"([^\"]*)\"")
            .findAll(parts)
            .joinToString("") { it.groupValues[1] }
            .replace("\\n", " ")
            .replace("\\l", " ")
            .replace("\\p", " ")
            .replace("{POKEBLOCK}", "POKéBLOCK")
            .replace(Regex("\\s+"), " ")
            .trim()
  }
}
