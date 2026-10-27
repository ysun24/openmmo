package de.fiereu.openmmo.web

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the bag and the shop show about an item besides its name. */
data class ItemInfo(
    /** `items`, `balls`, `tms`, `berries` or `key`, the GBA bag pockets. */
    val pocket: String,
    val description: String,
    /** The mart price; a mart buys it back for half, and never when that is 0. */
    val price: Int,
    /**
     * `mon` (used on a party monster), `move` (a monster, then one of its moves), `teach` (a TM or
     * HM), `field` (used on the player) or null.
     */
    val use: String?,
    /** The move a Gen 3 TM/HM teaches, and its index (TM01 = 0, HM01 = 50). */
    val teaches: Int? = null,
    val tmIndex: Int? = null,
    /** A key item that can be registered to the select button. */
    val register: Boolean = false,
)

/**
 * Item pockets and descriptions. Wire ids are `5000 + index` into the pokeblack table (price and
 * field use from there); the pocket and description come from Emerald's `src/data/items.h` and
 * `src/data/text/item_descriptions.h`, matched by name, since the GBA bag is what the web shows.
 * Items Emerald does not have fall back to a pocket guessed from their gen 5 field use.
 */
class ItemCatalog(private val decomp: File) {
  private data class Gba(val pocket: String, val description: String)

  private data class Row(
      val name: String,
      val price: Int,
      val fieldUse: String,
      val useClass: String
  )

  private val pokeblack: List<Row> by lazy {
    val names = File(decomp, "pokeblack/item_names.json")
    val data = File(decomp, "pokeblack/items.json")
    if (!names.isFile || !data.isFile) return@lazy emptyList()
    val nameList =
        de.fiereu.openmmo.common.ExtraItems.overlay(
            Json.parseToJsonElement(names.readText()).jsonArray.map { it.jsonPrimitive.content })
    val rows =
        Json.parseToJsonElement(data.readText()).jsonArray.associate {
          val o = it.jsonObject
          o.getValue("index").jsonPrimitive.int to
              Triple(
                  o["price"]?.jsonPrimitive?.intOrNull ?: 0,
                  o["field_use"]?.jsonPrimitive?.content ?: "none",
                  o["use_class"]?.jsonPrimitive?.content ?: "",
              )
        }
    nameList.mapIndexed { i, n ->
      val (price, fieldUse, useClass) = rows[i] ?: Triple(0, "none", "")
      Row(n, price, fieldUse, useClass)
    }
  }

  // FireRed first (its items.json carries the text), Emerald for what FireRed lacks.
  private val gba: Map<String, Gba> by lazy { parseEmerald() + parseFireRed() }

  private fun parseFireRed(): Map<String, Gba> {
    val f = File(decomp, "pokefirered/src/data/items.json")
    if (!f.isFile) return emptyMap()
    val out = HashMap<String, Gba>()
    for (e in Json.parseToJsonElement(f.readText()).jsonObject.getValue("items").jsonArray) {
      val o = e.jsonObject
      val name = o["english"]?.jsonPrimitive?.content ?: continue
      val pocket = pocketOf(o["pocket"]?.jsonPrimitive?.content) ?: continue
      val desc = o["description_english"]?.jsonPrimitive?.content.orEmpty().let(::flatten)
      out.putIfAbsent(norm(name), Gba(pocket, desc))
    }
    return out
  }

  private fun flatten(s: String) =
      s.replace("\\n", " ")
          .replace("\\l", " ")
          .replace("\\p", " ")
          .replace(Regex("\\s+"), " ")
          .trim()

  private fun pocketOf(c: String?) =
      when (c) {
        "POCKET_POKE_BALLS" -> "balls"
        "POCKET_TM_HM",
        "POCKET_TM_CASE" -> "tms"
        "POCKET_BERRIES",
        "POCKET_BERRY_POUCH" -> "berries"
        "POCKET_KEY_ITEMS" -> "key"
        "POCKET_ITEMS" -> "items"
        else -> null
      }

  fun info(id: Int): ItemInfo? {
    val index = id - ITEM_REGION_BLOCK
    if (index <= 0) return null
    val (name, price, fieldUse, useClass) = pokeblack.getOrNull(index) ?: return null
    // A gen 3 TM number teaches another move than the gen 5 one, so its text would lie.
    val g = gba[norm(name)]?.let { if (TM.matches(name)) it.copy(description = "") else it }
    val pocket =
        g?.pocket
            ?: when {
              useClass == "ball" -> "balls"
              fieldUse == "machine" -> "tms"
              fieldUse == "berry" -> "berries"
              price == 0 -> "key"
              else -> "items"
            }
    val use =
        FIELD_USE[norm(name)]
            ?: when (fieldUse) {
              "medicine" -> "mon"
              "repel" -> "field"
              else -> null
            }
    val register = pocket == "key" && norm(name) in REGISTRABLE
    return ItemInfo(pocket, g?.description.orEmpty(), price, use, register = register)
  }

  private fun parseEmerald(): Map<String, Gba> {
    val items = File(decomp, "pokeemerald/src/data/items.h")
    val descs = File(decomp, "pokeemerald/src/data/text/item_descriptions.h")
    if (!items.isFile || !descs.isFile) return emptyMap()
    val text =
        Regex("""static const u8 (\w+)\[\] = _\(((?:\s*"[^"]*")+)\s*\);""")
            .findAll(descs.readText())
            .associate { m ->
              m.groupValues[1] to
                  Regex("\"([^\"]*)\"")
                      .findAll(m.groupValues[2])
                      .joinToString("") { it.groupValues[1] }
                      .replace("\\n", " ")
                      .replace("\\l", " ")
                      .replace("\\p", " ")
                      .replace(Regex("\\s+"), " ")
                      .trim()
            }
    val out = HashMap<String, Gba>()
    for (block in items.readText().split(Regex("""\n\s*\[ITEM_\w+\] ="""))) {
      val name = Regex("""\.name = _\("([^"]*)"\)""").find(block)?.groupValues?.get(1) ?: continue
      val desc = Regex("""\.description = (\w+)""").find(block)?.groupValues?.get(1)
      val pocket =
          when (Regex("""\.pocket = (\w+)""").find(block)?.groupValues?.get(1)) {
            "POCKET_POKE_BALLS" -> "balls"
            "POCKET_TM_HM" -> "tms"
            "POCKET_BERRIES" -> "berries"
            "POCKET_KEY_ITEMS" -> "key"
            "POCKET_ITEMS" -> "items"
            else -> continue
          }
      out.putIfAbsent(norm(name), Gba(pocket, desc?.let(text::get).orEmpty()))
    }
    return out
  }

  private companion object {
    const val ITEM_REGION_BLOCK = 5000

    /**
     * How the server's FieldItemEffects uses an item out of battle, where the gen 5 field use says
     * otherwise (status heals, PP items, Rare Candy, stones, vitamins, EV berries, the Escape
     * Rope). Keys are [norm]alised names.
     */
    val FIELD_USE: Map<String, String> =
        (listOf(
                "antidote",
                "burnheal",
                "iceheal",
                "awakening",
                "paralyzeheal",
                "fullheal",
                "healpowder",
                "lavacookie",
                "rarecandy",
                "firestone",
                "waterstone",
                "thunderstone",
                "leafstone",
                "moonstone",
                "sunstone",
                "hpup",
                "protein",
                "iron",
                "carbos",
                "calcium",
                "zinc",
                "pomegberry",
                "kelpsyberry",
                "qualotberry",
                "hondewberry",
                "grepaberry",
                "tamatoberry",
                "oranberry",
                "sitrusberry",
                "cheriberry",
                "chestoberry",
                "pechaberry",
                "rawstberry",
                "aspearberry",
                "lumberry",
                "fullrestore",
                "revive",
                "maxrevive",
                "revivalherb",
                "energypowder",
                "energyroot",
                "berryjuice",
                "elixir",
                "maxelixir")
            .associateWith { "mon" } +
            listOf("ether", "maxether", "leppaberry", "ppup", "ppmax").associateWith { "move" } +
            listOf("escaperope", "repel", "superrepel", "maxrepel", "oldrod", "goodrod", "superrod")
                .associateWith { "field" })

    /** Key items the GBA lets the player register to SELECT. */
    val REGISTRABLE =
        setOf(
            "bicycle",
            "machbike",
            "acrobike",
            "oldrod",
            "goodrod",
            "superrod",
            "itemfinder",
            "dowsingmchn",
            "vsseeker",
            "wailmerpail",
            "sprayduck",
            "pokeflute",
            "townmap",
            "coinCase".lowercase(),
            "devonscope",
            "silphscope")
    val TM = Regex("[TH]M\\d+")

    fun norm(s: String) =
        s.lowercase().replace("é", "e").replace(Regex("[^a-z0-9]"), "").let {
          // GBA abbreviations the gen 5 names spell out, or the other way round.
          when (it) {
            "parlyzheal" -> "paralyzeheal"
            "parcel" -> "oaksparcel"
            else -> it
          }
        }
  }
}
