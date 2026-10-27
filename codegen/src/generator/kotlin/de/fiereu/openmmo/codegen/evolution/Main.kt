@file:JvmName("Main")

package de.fiereu.openmmo.codegen.evolution

import de.fiereu.openmmo.codegen.defineTable
import de.fiereu.openmmo.codegen.pokemon.NationalDex
import gg.jte.ContentType
import gg.jte.TemplateEngine
import gg.jte.output.FileOutput
import gg.jte.resolve.DirectoryCodeResolver
import java.io.File

/** One `{EVO_X, param, SPECIES_Y}` row of `gEvolutionTable`, in national dex numbers. */
data class ParsedEvolution(
    val fromDex: Int,
    val method: String,
    /** The numeric parameter (level, friendship, beauty), 0 when the parameter is an item. */
    val param: Int,
    /** The `ITEM_` constant without its prefix for item and trade-item methods, else "". */
    val item: String,
    val toDex: Int,
)

/**
 * Reads `src/data/pokemon/evolution.h` from the canonical decomp. Species constants are internal
 * ids there, so both ends go through the national dex mapping the species generator uses.
 */
class EvolutionParser(private val root: File) {
  private val species = defineTable(File(root, "include/constants/species.h"), "SPECIES_")
  private val national = NationalDex.read(root)

  fun parseAll(): List<ParsedEvolution> {
    val text = File(root, "src/data/pokemon/evolution.h").readText()
    val out = mutableListOf<ParsedEvolution>()
    for (entry in ENTRY.findAll(text)) {
      val from = species[entry.groupValues[1]]?.let(national::get) ?: continue
      for (row in ROW.findAll(entry.groupValues[2])) {
        val (method, rawParam, target) = row.destructured
        val to = species[target]?.let(national::get) ?: continue
        val item = if (rawParam.startsWith("ITEM_")) rawParam.removePrefix("ITEM_") else ""
        val param = if (item.isEmpty()) rawParam.trim().toIntOrNull() ?: 0 else 0
        out += ParsedEvolution(from, method.removePrefix("EVO_"), param, item, to)
      }
    }
    return out
  }

  private companion object {
    val ENTRY = Regex("""\[(SPECIES_\w+)]\s*=\s*\{(\{.*?})\s*},""", RegexOption.DOT_MATCHES_ALL)
    val ROW = Regex("""\{\s*(EVO_\w+)\s*,\s*(\w+)\s*,\s*(SPECIES_\w+)\s*}""")
  }
}

private val HOLD = Regex("""\[ITEM_(\w+)]\s*=\s*\{(.*?)\n    },""", RegexOption.DOT_MATCHES_ALL)
private val HOLD_EFFECT = Regex("""\.holdEffect\s*=\s*HOLD_EFFECT_(\w+)""")
private val HOLD_PARAM = Regex("""\.holdEffectParam\s*=\s*(\d+)""")

fun main(args: Array<String>) {
  require(args.size >= 4) { "Usage: <output-dir> <templates-dir> <class-cache-dir> <decomp-dir>" }
  val outputDir = File(args[0])
  val templatesDir = File(args[1])
  val classCacheDir = File(args[2]).apply { mkdirs() }
  val evolutions = EvolutionParser(File(args[3])).parseAll()
  println("[evolution] parsed ${evolutions.size} evolutions")
  val engine =
      TemplateEngine.create(
          DirectoryCodeResolver(templatesDir.toPath()), classCacheDir.toPath(), ContentType.Plain)
  val pkg = File(outputDir, "de/fiereu/openmmo/pokemon/generated")
  if (pkg.exists()) pkg.deleteRecursively()
  pkg.mkdirs()
  FileOutput(File(pkg, "GeneratedEvolutions.kt").toPath()).use { out ->
    engine.render("EvolutionRegistry.jte", mapOf("rows" to evolutions), out)
  }
  // The GBA numbers its items differently from the wire ids, so species' wild held items
  // (itemCommon / itemRare) are resolved by name through this table.
  val gbaItems =
      defineTable(File(args[3], "include/constants/items.h"), "ITEM_")
          .filterKeys { it != "ITEM_NONE" && !it.startsWith("ITEM_FIELD_ARROW") }
          .entries
          .sortedBy { it.value }
          .distinctBy { it.value }
  // Hold effects straight from src/data/items.h (.holdEffect / .holdEffectParam).
  val holdEffects = HOLD.findAll(File(args[3], "src/data/items.h").readText()).mapNotNull { m ->
    val body = m.groupValues[2]
    val effect = HOLD_EFFECT.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
    val param = HOLD_PARAM.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    listOf(m.groupValues[1], effect, param.toString())
  }.toList()
  FileOutput(File(pkg, "GeneratedHoldEffects.kt").toPath()).use { out ->
    engine.render("HoldEffects.jte", mapOf("rows" to holdEffects), out)
  }
  FileOutput(File(pkg, "GeneratedGbaItemNames.kt").toPath()).use { out ->
    engine.render("GbaItemNames.jte", mapOf("rows" to gbaItems.map { listOf(it.value.toString(), it.key.removePrefix("ITEM_")) }), out)
  }
}
