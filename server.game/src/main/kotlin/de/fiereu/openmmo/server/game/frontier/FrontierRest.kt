package de.fiereu.openmmo.server.game.frontier

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.enums.PokemonStat
import java.time.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * A challenge put on hold with REST (Emerald: the "Save and quit the game?" between battles, the
 * Pyramid's start-menu save; frontierChallengePaused / the per-facility save fields). One at a
 * time, as in SaveBlock2. [desk] is the desk that resumes it (e.g. "tower:singles", "pyramid"),
 * [key] the streak, [step] the next battle / room / floor, [team] the entrants as they are now (HP,
 * status and PP carry over where the facility does not heal), [extra] facility state (the trainers
 * already fought, the Pike's healing flag, the Pyramid's floor grid, position, light and bag...).
 */
class FrontierRest(
    val desk: String,
    val key: String,
    val step: Int,
    val team: List<Pokemon>,
    val extra: JsonObject = JsonObject(emptyMap()),
) {
  fun int(k: String, def: Int = 0) = extra[k]?.jsonPrimitive?.intOrNull ?: def

  fun bool(k: String) = extra[k]?.jsonPrimitive?.booleanOrNull ?: false

  fun string(k: String) = extra[k]?.jsonPrimitive?.contentOrNull.orEmpty()

  fun ints(k: String): List<Int> = extra[k]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty()

  fun mons(k: String): List<Pokemon> =
      extra[k]?.jsonArray?.map { FrontierMonJson.decode(it.jsonObject) }.orEmpty()

  fun toJson(): JsonObject = buildJsonObject {
    put("desk", desk)
    put("key", key)
    put("step", step)
    put("team", FrontierMonJson.encodeAll(team))
    put("extra", extra)
  }

  companion object {
    fun fromJson(o: JsonObject): FrontierRest? =
        runCatching {
              FrontierRest(
                  o.getValue("desk").jsonPrimitive.content,
                  o.getValue("key").jsonPrimitive.content,
                  o.getValue("step").jsonPrimitive.int,
                  o.getValue("team").jsonArray.map { FrontierMonJson.decode(it.jsonObject) },
                  o["extra"]?.jsonObject ?: JsonObject(emptyMap()))
            }
            .getOrNull()

    fun ints(v: List<Int>): JsonElement = JsonArray(v.map { JsonPrimitive(it) })
  }
}

/** Every field of a [Pokemon], for the paused challenge's team (rentals are no party members). */
object FrontierMonJson {
  fun encodeAll(mons: List<Pokemon>): JsonArray = buildJsonArray {
    mons.forEach { add(encode(it)) }
  }

  fun encode(m: Pokemon): JsonObject = buildJsonObject {
    put("id", m.id)
    put("owner", m.ownerId)
    put("container", m.container.name)
    put("slot", m.containerSlot.toInt())
    put("dex", m.dexId)
    put("seed", m.seed)
    put("ot", m.ot)
    put("nick", m.nickname)
    put("level", m.level.toInt())
    put("hp", m.hp.toInt())
    put("xp", m.xp)
    put("evs", JsonArray(PokemonStat.entries.map { JsonPrimitive(m.eVs[it]!!.toInt() and 0xFF) }))
    put("ivs", JsonArray(PokemonStat.entries.map { JsonPrimitive(m.iVs[it]!!.toInt() and 0xFF) }))
    put(
        "moves",
        buildJsonArray {
          m.moves.forEach {
            add(
                buildJsonObject {
                  put("id", it.id.toInt())
                  put("pp", it.pp.toInt())
                })
          }
        })
    put("shiny", m.isShiny)
    put("hidden", m.hasHiddenAbility)
    put("alpha", m.isAlpha)
    put("secret", m.isSecret)
    put("fateful", m.isFatefulEncounter)
    put("raid", m.isRaidEncounter)
    put("caught", m.caughtAt.toString())
    put("egg", m.isEgg)
    put("status", m.status)
    put("friendship", m.friendship)
    put("held", m.heldItem)
    put("ppBonuses", m.ppBonuses)
  }

  fun decode(o: JsonObject): Pokemon {
    fun i(k: String) = o.getValue(k).jsonPrimitive.int
    fun b(k: String) = o.getValue(k).jsonPrimitive.boolean
    fun stats(k: String, into: de.fiereu.openmmo.common.enums.PokemonStats) {
      o.getValue(k).jsonArray.forEachIndexed { n, v ->
        into[PokemonStat.entries[n]] = v.jsonPrimitive.int.toByte()
      }
    }
    val evs = EVs().also { stats("evs", it) }
    val ivs = IVs().also { stats("ivs", it) }
    return Pokemon(
        id = o.getValue("id").jsonPrimitive.long,
        ownerId = o.getValue("owner").jsonPrimitive.long,
        container = PokemonContainer.valueOf(o.getValue("container").jsonPrimitive.content),
        containerSlot = i("slot").toShort(),
        dexId = i("dex"),
        seed = i("seed"),
        ot = o.getValue("ot").jsonPrimitive.content,
        nickname = o.getValue("nick").jsonPrimitive.content,
        level = i("level").toByte(),
        hp = i("hp").toShort(),
        xp = i("xp"),
        eVs = evs,
        iVs = ivs,
        moves =
            o.getValue("moves").jsonArray.map {
              val mv = it.jsonObject
              PokemonMove(
                  mv.getValue("id").jsonPrimitive.int.toShort(),
                  mv.getValue("pp").jsonPrimitive.int.toByte())
            },
        isShiny = b("shiny"),
        hasHiddenAbility = b("hidden"),
        isAlpha = b("alpha"),
        isSecret = b("secret"),
        isFatefulEncounter = b("fateful"),
        isRaidEncounter = b("raid"),
        caughtAt = LocalDateTime.parse(o.getValue("caught").jsonPrimitive.content),
        isEgg = b("egg"),
        status = i("status"),
        friendship = i("friendship"),
        heldItem = i("held"),
        ppBonuses = i("ppBonuses"),
    )
  }
}
