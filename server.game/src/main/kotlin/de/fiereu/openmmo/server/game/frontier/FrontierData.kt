package de.fiereu.openmmo.server.game.frontier

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * r5-frontier: the Battle Frontier tables exported from the decomps by
 * codegen/tools/gen_frontier_data.py into resources/frontier/frontier.json. Species are national
 * dex numbers, moves decomp move ids, items ITEM_ suffixes, natures their index (HARDY = 0).
 */

/** struct FacilityMon (battle_frontier_mons.h): one prepared set of a frontier species. */
data class FacilityMon(
    val species: Int,
    val moves: List<Int>,
    /** gBattleFrontierHeldItems entry, an ITEM_ suffix ("NONE" for none). */
    val item: String,
    /** F_EV_SPREAD_* bits: HP, ATK, DEF, SPEED, SPATK, SPDEF from bit 0. */
    val evSpread: Int,
    val nature: Int,
)

/** struct BattleFrontierTrainer. [monSet] indexes the facility's mon table. */
data class FacilityTrainer(
    val name: String,
    val facilityClass: String,
    /** TRAINER_PIC_ suffix. */
    val pic: String,
    /** TRAINER_CLASS_ suffix. */
    val trainerClass: String,
    val monSet: List<Int>,
    val speechBefore: String,
    val speechWin: String,
    val speechLose: String,
)

/** struct FrontierBrainMon (frontier_util.c sFrontierBrainsMons). */
data class BrainMon(
    val species: Int,
    val item: String,
    val fixedIv: Int,
    val nature: Int,
    /** HP, ATK, DEF, SPEED, SPATK, SPDEF. */
    val evs: List<Int>,
    val moves: List<Int>,
)

data class FrontierBrain(
    val name: String,
    val pic: String,
    val trainerClass: String,
    val silver: List<BrainMon>,
    val gold: List<BrainMon>,
)

/** struct BattleTowerPokemon as used by the Trainer Hill and Trainer Tower tables. */
data class TowerMon(
    val species: Int,
    val item: String,
    val moves: List<Int>,
    /** HP, ATK, DEF, SPEED, SPATK, SPDEF. */
    val evs: List<Int>,
    val ivs: List<Int>,
    val abilityNum: Int,
    val personality: Int,
    val friendship: Int,
    val nickname: String,
)

data class TowerTrainer(
    val name: String,
    val facilityClass: String,
    val pic: String,
    val trainerClass: String,
    val speechBefore: String,
    val speechWin: String,
    val speechLose: String,
    val speechAfter: String,
    val mons: List<TowerMon>,
)

/** One Trainer Hill floor (2 trainers) or Trainer Tower floor (1-3 trainers). */
data class TowerFloor(val type: String, val prize: Int, val trainers: List<TowerTrainer>)

/** struct PikeWildMon. */
data class PikeWildMon(val species: Int, val levelDelta: Int, val moves: List<Int>)

/** struct PyramidWildMon; [ability] -1 is ABILITY_RANDOM. */
data class PyramidWildMon(val species: Int, val level: Int, val ability: Int, val moves: List<Int>)

data class PyramidFloorTemplate(val numItems: Int, val numTrainers: Int, val runMultiplier: Int)

data class ExchangeEntry(
    /** "Decor1", "Decor2", "Vitamins" or "HoldItems": which clerk sells it. */
    val clerk: String,
    val cost: Int,
    /** "item" or "decor". */
    val kind: String,
    /** ITEM_ or DECOR_ suffix. */
    val id: String,
)

data class FacilitySet(val mons: List<FacilityMon>, val trainers: List<FacilityTrainer>)

/** Everything the facilities draw from, loaded once. */
@Singleton
class FrontierData @Inject constructor() {
  private val root: JsonObject =
      Json.parseToJsonElement(
              checkNotNull(javaClass.getResourceAsStream("/frontier/frontier.json")) {
                    "frontier/frontier.json missing"
                  }
                  .bufferedReader()
                  .readText())
          .jsonObject

  val frontier: FacilitySet =
      FacilitySet(
          root.arr("mons").map { facilityMon(it.jsonObject) },
          root.arr("trainers").map { trainer(it.jsonObject) })

  /** The Battle Tents: "slateport" (Factory rules), "verdanturf" (Palace), "fallarbor" (Arena). */
  val tents: Map<String, FacilitySet> =
      root.obj("tents").mapValues { (_, v) ->
        val o = v.jsonObject
        FacilitySet(
            o.arr("mons").map { facilityMon(it.jsonObject) },
            o.arr("trainers").map { trainer(it.jsonObject) })
      }

  val brains: Map<Facility, FrontierBrain> =
      root.obj("brains").entries.associate { (k, v) ->
        val o = v.jsonObject
        Facility.valueOf(k.uppercase()) to
            FrontierBrain(
                o.str("name"),
                o.str("pic"),
                o.str("class"),
                o.arr("silver").map { brainMon(it.jsonObject) },
                o.arr("gold").map { brainMon(it.jsonObject) })
      }

  /** sLvl50Mons / sLvlOpenMons: four tables of three, picked by streak (GetBattlePikeWildMonHeaderId). */
  val pikeWild: Map<String, List<List<PikeWildMon>>> =
      root.obj("pikeWild").mapValues { (_, v) ->
        v.jsonArray.map { t ->
          t.jsonArray.map {
            val o = it.jsonObject
            PikeWildMon(o.int("species"), o.int("levelDelta"), o.ints("moves"))
          }
        }
      }

  private val pyramid = root.obj("pyramid")

  /** Per round (0-19), the pyramid's wild monsters, Lv. 50 and open level. */
  val pyramidWild50: List<List<PyramidWildMon>> = pyramidWild("wild50")
  val pyramidWildOpen: List<List<PyramidWildMon>> = pyramidWild("wildOpen")

  /** sPickupItemsLvl50 / sPickupItemsLvlOpen: per round, 10 items. */
  val pyramidPickup50: List<List<String>> =
      pyramid.arr("pickup50").map { r -> r.jsonArray.map { it.jsonPrimitive.content } }
  val pyramidPickupOpen: List<List<String>> =
      pyramid.arr("pickupOpen").map { r -> r.jsonArray.map { it.jsonPrimitive.content } }

  /** sPickupItemSlots: (percentage threshold, item slot) rows, 9 per floor. */
  val pyramidPickupSlots: List<Pair<Int, Int>> =
      pyramid.arr("pickupSlots").map { r -> r.jsonArray.let { it[0].int() to it[1].int() } }

  val pyramidFloorTemplates: List<PyramidFloorTemplate> =
      pyramid.arr("floorTemplates").map {
        val o = it.jsonObject
        PyramidFloorTemplate(o.int("numItems"), o.int("numTrainers"), o.int("runMultiplier"))
      }

  /** sPyramidFloorTemplateOptions: (percentage threshold, template id) rows. */
  val pyramidFloorOptions: List<Pair<Int, Int>> =
      pyramid.arr("floorOptions").map { r -> r.jsonArray.let { it[0].int() to it[1].int() } }

  /** battle_arena.c sMindRatings: move id -> mind points (unlisted moves score 0). */
  val mindRatings: Map<Int, Int> =
      root.obj("mindRatings").entries.associate { (k, v) -> k.toInt() to v.jsonPrimitive.int }

  val exchange: List<ExchangeEntry> =
      root.arr("exchange").map {
        val o = it.jsonObject
        ExchangeEntry(o.str("clerk"), o.int("cost"), o.str("kind"), o.str("id"))
      }

  /** Trainer Hill modes ("normal", "variety", "unique", "expert"): four floors of two trainers. */
  val trainerHill: Map<String, List<TowerFloor>> =
      root.obj("trainerHill").mapValues { (_, v) ->
        v.jsonArray.map { f ->
          TowerFloor(
              "hill", 0, f.jsonObject.arr("trainers").map { towerTrainer(it.jsonObject) })
        }
      }

  /** FRLG Trainer Tower challenge types ("single", "double", "knockout", "mixed"): eight floors. */
  val trainerTower: Map<String, List<TowerFloor>> =
      root.obj("trainerTower").obj("sets").mapValues { (_, v) ->
        v.jsonArray.map { f ->
          val o = f.jsonObject
          TowerFloor(
              o.str("type"),
              o["prize"]?.jsonPrimitive?.intOrNull ?: 0,
              o.arr("trainers").map { towerTrainer(it.jsonObject) })
        }
      }

  /** trainer_tower.c sPrizeList, indexed by a floor's TTPRIZE_* id. */
  val trainerTowerPrizes: List<String> =
      root.obj("trainerTower").arr("prizes").map { it.jsonPrimitive.content }

  /** gFrontierBannedSpecies, as national dex numbers. */
  val bannedSpecies: Set<Int> = root.arr("bannedSpecies").map { it.jsonPrimitive.int }.toSet()

  // --- parsing helpers -------------------------------------------------------------------------

  private fun pyramidWild(key: String) =
      pyramid.arr(key).map { r ->
        r.jsonArray.map {
          val o = it.jsonObject
          PyramidWildMon(o.int("species"), o.int("level"), o.int("ability"), o.ints("moves"))
        }
      }

  private fun facilityMon(o: JsonObject) =
      FacilityMon(o.int("species"), o.ints("moves"), o.str("item"), o.int("evSpread"), o.int("nature"))

  private fun trainer(o: JsonObject) =
      FacilityTrainer(
          o.str("name"),
          o.str("facilityClass"),
          o.str("pic"),
          o.str("class"),
          o.ints("monSet"),
          o.str("before"),
          o.str("win"),
          o.str("lose"))

  private fun brainMon(o: JsonObject) =
      BrainMon(
          o.int("species"),
          o.str("item"),
          o.int("iv"),
          o.int("nature"),
          o.ints("evs"),
          o.ints("moves"))

  private fun towerTrainer(o: JsonObject) =
      TowerTrainer(
          o.str("name"),
          o.str("facilityClass"),
          o.str("pic"),
          o.str("class"),
          o.str("before"),
          o.str("win"),
          o.str("lose"),
          o.str("after"),
          o.arr("mons").map {
            val m = it.jsonObject
            TowerMon(
                m.int("species"),
                m.str("item"),
                m.ints("moves"),
                m.ints("evs"),
                m.ints("ivs"),
                m.int("ability"),
                m.int("personality"),
                m.int("friendship"),
                m.str("nickname"))
          })

  private fun JsonObject.arr(k: String): JsonArray = this[k]?.jsonArray ?: JsonArray(emptyList())

  private fun JsonObject.obj(k: String): JsonObject = this[k]?.jsonObject ?: JsonObject(emptyMap())

  private fun JsonObject.str(k: String): String = this[k]?.jsonPrimitive?.content ?: ""

  private fun JsonObject.int(k: String): Int = this[k]?.jsonPrimitive?.intOrNull ?: 0

  private fun JsonObject.ints(k: String): List<Int> = arr(k).map { it.int() }

  private fun JsonElement.int(): Int = jsonPrimitive.intOrNull ?: 0
}
