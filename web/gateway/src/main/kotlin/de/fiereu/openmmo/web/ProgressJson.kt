package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.BattleNames
import de.fiereu.openmmo.common.MonStatus
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.net.game.packets.progress.ProgressStatePacket
import de.fiereu.openmmo.progression.Gen3Data
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The r3-items state sections (PROTOCOL.md "r3-items"): `dex`, `trainer`, `daycare`, `decor` and
 * `learn`, from the server's [ProgressStatePacket]. Pure, like [StateJson].
 */
class ProgressJson(private val names: Names) {

  fun sections(s: Sources): Map<String, JsonElement> {
    val p = s.progress
    return linkedMapOf(
        "dex" to (p?.let { dex(it) } ?: JsonNull),
        "trainer" to (p?.let { trainer(s, it) } ?: JsonNull),
        "daycare" to (p?.let { daycare(it) } ?: JsonNull),
        "decor" to (p?.let { decor(it) } ?: JsonNull),
        "learn" to (p?.let { learn(s, it) } ?: JsonNull),
    )
  }

  /**
   * Seen and caught counts, national and regional, and one row per seen species. Caught rows carry
   * the pokedex page (category, height, weight, text), as the games only show those once owned.
   */
  fun dex(p: ProgressStatePacket): JsonElement = buildJsonObject {
    val seen = ProgressStatePacket.ids(p.seen).filter { it in 1..Gen3Data.NATIONAL_DEX_SIZE }
    val caught = ProgressStatePacket.ids(p.caught).filter { it in 1..Gen3Data.NATIONAL_DEX_SIZE }
    val caughtSet = caught.toSet()
    put("region", if (p.region == 1) "hoenn" else "kanto")
    put("seen", seen.size)
    put("caught", caught.size)
    val regional = if (p.region == 1) Gen3Data.hoennDex else Gen3Data.kantoDex
    put("regionalSeen", seen.count { it in regional })
    put("regionalCaught", caught.count { it in regional })
    put("regionalSize", regional.size)
    put("nationalSize", Gen3Data.NATIONAL_DEX_SIZE)
    putJsonArray("regional") { regional.forEach { add(it) } }
    putJsonArray("entries") {
      for (d in seen) {
        addJsonObject {
          put("dex", d)
          put("name", names.species(d))
          put("caught", d in caughtSet)
          putJsonArray("types") { names.types(d).forEach { add(it) } }
          val hoennNo = Gen3Data.hoennDex.indexOf(d)
          if (hoennNo >= 0) put("hoenn", hoennNo + 1)
          if (d in caughtSet) {
            names.dexEntry(d, p.region)?.let { e ->
              put("category", e.category)
              put("heightM", e.heightDm / 10.0)
              put("weightKg", e.weightHg / 10.0)
              put("text", e.description)
            }
          }
        }
      }
    }
  }

  /** The trainer card: name, money, badges, play time, pokedex count, and the admin bit. */
  fun trainer(s: Sources, p: ProgressStatePacket): JsonElement = buildJsonObject {
    put("name", s.world.characterName ?: "")
    put("money", s.world.overworld?.money ?: s.tradeMoney ?: s.market.money)
    put("playSeconds", s.playSeconds.takeIf { it > 0 } ?: p.playSeconds)
    put("dexSeen", ProgressStatePacket.ids(p.seen).count { it in 1..Gen3Data.NATIONAL_DEX_SIZE })
    put("dexCaught", ProgressStatePacket.ids(p.caught).count { it in 1..Gen3Data.NATIONAL_DEX_SIZE })
    putJsonObject("badges") {
      putJsonArray("kanto") { for (b in 0 until 8) add(p.badges shr b and 1 == 1) }
      putJsonArray("hoenn") { for (b in 0 until 8) add(p.badges shr (b + 8) and 1 == 1) }
    }
    putJsonArray("badgeNames") {
      (if (p.region == 1) HOENN_BADGES else KANTO_BADGES).forEach { add(it) }
    }
    put("region", if (p.region == 1) "hoenn" else "kanto")
    put("registered", p.registeredItem.toInt().takeIf { it != 0 })
    put("registeredName", p.registeredItem.toInt().takeIf { it != 0 }?.let(names::item))
    put("admin", p.permissions and ADMIN != 0)
    put("developer", p.permissions and DEVELOPER != 0)
  }

  fun daycare(p: ProgressStatePacket): JsonElement {
    val d = p.daycare
    if (d.open == 0 && d.mons.isEmpty() && !d.eggWaiting) return JsonNull
    return buildJsonObject {
      put(
          "open",
          when (d.open) {
            1 -> "deposit"
            2,
            3 -> "withdraw"
            else -> null
          })
      putJsonArray("mons") {
        d.mons.forEachIndexed { i, m ->
          addJsonObject {
            put("index", i)
            put("id", m.id.toString())
            put("dex", m.dexId)
            put("name", m.nickname.ifEmpty { names.species(m.dexId) })
            put("level", m.level.toInt())
            put("gained", d.levelsGained.getOrElse(i) { 0 })
            put("fee", d.fees.getOrElse(i) { 100 })
          }
        }
      }
      put("egg", d.eggWaiting)
    }
  }

  fun decor(p: ProgressStatePacket): JsonElement {
    if (p.decorations.isEmpty() && p.decorShop.isEmpty()) return JsonNull
    return buildJsonObject {
      putJsonArray("owned") {
        p.decorations.forEach { st ->
          val d = Gen3Data.decorations[st.decorId.toInt()]
          addJsonObject {
            put("id", st.decorId.toInt())
            put("name", d?.name ?: "#${st.decorId}")
            put("category", d?.category?.lowercase())
            put("qty", st.quantity.toInt())
          }
        }
      }
      put(
          "shop",
          if (p.decorShop.isEmpty()) JsonNull
          else
              buildJsonArray {
                p.decorShop.forEach { id ->
                  val d = Gen3Data.decorations[id.toInt()]
                  addJsonObject {
                    put("id", id.toInt())
                    put("name", d?.name ?: "#$id")
                    put("category", d?.category?.lowercase())
                    put("price", d?.price ?: 0)
                  }
                }
              })
    }
  }

  /** A move waiting for room (Rare Candy, an evolution): the monster's moves and the new one. */
  fun learn(s: Sources, p: ProgressStatePacket): JsonElement {
    val l = p.learn ?: return JsonNull
    val mon = s.party.firstOrNull { it.id == l.monId }?.let { withExtras(it, p) } ?: return JsonNull
    return buildJsonObject {
      put("mon", mon.id.toString())
      put("name", mon.nickname.ifEmpty { names.species(mon.dexId) })
      put("move", moveJson(l.moveId.toInt(), null, null))
      putJsonArray("moves") {
        mon.moves.forEachIndexed { slot, m ->
          if (m.id.toInt() != 0) add(moveJson(m.id.toInt(), m.pp.toInt(), maxPp(mon, slot)))
        }
      }
    }
  }

  private fun moveJson(id: Int, pp: Int?, maxPp: Int?) = buildJsonObject {
    put("id", id)
    put("name", names.move(id))
    put("type", BattleNames.moveType(id))
    put("power", BattleNames.power(id))
    put("pp", pp ?: names.maxPp(id))
    put("maxPp", maxPp ?: names.maxPp(id))
    put("desc", names.moveDescription(id))
  }

  /** The move's max PP with the monster's PP Ups. */
  fun maxPp(mon: Pokemon, slot: Int): Int =
      MonStatus.maxPp(names.maxPp(mon.moves[slot].id.toInt()), mon.ppBonuses, slot)

  companion object {
    /** [mon] with the status and PP bonuses the progress packet carries for it. */
    fun withExtras(mon: Pokemon, p: ProgressStatePacket?): Pokemon {
      val e = p?.extras?.firstOrNull { it.monId == mon.id } ?: return mon
      return mon.copy(status = e.status.toInt(), ppBonuses = e.ppBonuses)
    }

    const val DEVELOPER = 0x100
    const val ADMIN = 0x200
    val KANTO_BADGES =
        listOf("BOULDER", "CASCADE", "THUNDER", "RAINBOW", "SOUL", "MARSH", "VOLCANO", "EARTH")
    val HOENN_BADGES =
        listOf("STONE", "KNUCKLE", "DYNAMO", "HEAT", "BALANCE", "FEATHER", "MIND", "RAIN")
  }
}
