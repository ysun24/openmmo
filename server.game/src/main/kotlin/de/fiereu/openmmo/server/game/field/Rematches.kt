package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.trainer.RematchEntry

/**
 * Gen 3 trainer rematch rules: FireRed's VS Seeker (src/vs_seeker.c) and Emerald's Match Call
 * rematches (src/battle_setup.c). [fought] says whether a trainer id has been beaten.
 */
object Rematches {
  /** FRLG: the VS Seeker charges over this many steps after each use. */
  const val VS_SEEKER_CHARGE = 100

  /** Emerald STEP_COUNTER_MAX: steps between random rematch rolls. */
  const val MATCH_CALL_STEPS = 255

  /** FRLG: a trainer with a rematch party answers the VS Seeker 70% of the time. */
  const val VS_SEEKER_YES_PERCENT = 70

  /** Emerald: each registered trainer on the map wants a rematch 31% of the time. */
  const val MATCH_CALL_PERCENT = 31

  /** FRLG IsTrainerVisibleOnScreen: within 7 tiles across and 5 up/down of the player. */
  fun inVsSeekerRange(px: Int, py: Int, tx: Int, ty: Int): Boolean =
      tx in px - 7..px + 7 && ty in py - 5..py + 5

  /**
   * GetNextAvailableRematchTrainer: the first tier after the original whose trainer is unbeaten
   * (skipping SKIP tiers), the last tier once all are beaten; 0 when the entry has no rematch.
   */
  fun frlgNextTier(e: RematchEntry, fought: (Int) -> Boolean): Int {
    for (j in 1 until e.ids.size) {
      val id = e.ids[j]
      if (id == -1 || fought(id)) continue
      return j
    }
    return e.ids.size - 1
  }

  /**
   * TryGetRematchTrainerIdGivenGameState: a tier whose story gate is not met drops to the highest
   * non-skipped tier below it. [flag] says whether a FRLG story flag (without region) is set.
   */
  fun frlgGatedTier(e: RematchEntry, tier: Int, flag: (String) -> Boolean): Int {
    val gate =
        when (tier) {
          1 -> "FLAG_GOT_VS_SEEKER"
          2 -> "FLAG_WORLD_MAP_CELADON_CITY"
          3 -> "FLAG_WORLD_MAP_FUCHSIA_CITY"
          4 -> "FLAG_SYS_GAME_CLEAR"
          5 -> "FLAG_SYS_CAN_LINK_WITH_RS"
          else -> return tier
        }
    if (flag(gate)) return tier
    var t = tier
    while (--t != 0) if (e.ids[t] != -1) return t
    return 0
  }

  /** FRLG GetRematchTrainerId: the party a VS Seeker rematch battles. */
  fun frlgRematchTrainer(
      e: RematchEntry,
      fought: (Int) -> Boolean,
      flag: (String) -> Boolean
  ): Int {
    val tier = frlgNextTier(e, fought)
    return e.ids[frlgGatedTier(e, tier, flag)]
  }

  /** Emerald GetRematchTrainerIdFromTable: the first unbeaten tier, else the last. */
  fun emeraldRematchTrainer(e: RematchEntry, fought: (Int) -> Boolean): Int {
    for (i in 1 until e.ids.size) if (!fought(e.ids[i])) return e.ids[i]
    return e.ids.last()
  }

  /** Whether a decomp map constant (MAP_ROUTE118) names the map dir [mapName] (Route118). */
  fun sameMap(constant: String, mapName: String): Boolean =
      constant.removePrefix("MAP_").replace("_", "") == mapName.uppercase().replace("_", "")

  /** The ready flag of an Emerald rematch (persisted like the save's trainerRematches). */
  fun readyFlag(region: Region, firstTrainerId: Int): String =
      "${region.storyName}/REMATCH_READY_$firstTrainerId"
}
