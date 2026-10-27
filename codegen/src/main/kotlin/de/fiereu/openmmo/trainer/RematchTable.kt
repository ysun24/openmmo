package de.fiereu.openmmo.trainer

import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.trainer.generated.GeneratedHoennRematches
import de.fiereu.openmmo.trainer.generated.GeneratedKantoRematches

/**
 * One trainer's rematch parties (FRLG `sRematches`, Emerald `gRematchTable`): [ids] holds the first
 * battle's trainer id and then each rematch tier's, -1 where FRLG skips a tier. [map] is the decomp
 * map constant the trainer stands on. [wally] marks Emerald's Victory Road Wally, rematchable only
 * once beaten there.
 */
data class RematchEntry(val ids: List<Int>, val map: String, val wally: Boolean = false) {
  val first: Int
    get() = ids.first()
}

/** The rematch tables per region, looked up by any trainer id the entry contains. */
object RematchTable {
  private val byRegion: Map<Region, List<RematchEntry>> =
      mapOf(
          Region.KANTO to GeneratedKantoRematches.entries,
          Region.HOENN to GeneratedHoennRematches.entries,
      )

  fun entries(region: Region): List<RematchEntry> = byRegion[region].orEmpty()

  /** The entry whose first battle is [firstTrainerId] (the trainer npc's own id), or null. */
  fun byFirst(region: Region, firstTrainerId: Int): RematchEntry? =
      entries(region).firstOrNull { it.first == firstTrainerId }
}
