package de.fiereu.openmmo.trainer

import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.trainer.generated.GeneratedHoennTrainers
import de.fiereu.openmmo.trainer.generated.GeneratedKantoTrainers
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A monster on a trainer's team. Empty [moveIds] means the level up moveset is used. */
data class TrainerMon(
    val dexId: Int,
    val level: Int,
    val iv: Int,
    val heldItem: Int,
    val moveIds: List<Int>,
    /** The held item's decomp constant without `ITEM_`, "" for none. */
    val heldItemName: String = "",
)

data class TrainerDef(
    val id: Int,
    val name: String,
    val trainerClass: Int,
    val doubleBattle: Boolean,
    /** What the class pays per level of its last monster. */
    val prizeRate: Int,
    val party: List<TrainerMon>,
    /** `TRAINER_PIC_` suffix (e.g. LEADER_ROXANNE), the front sprite to show. */
    val pic: String = "",
    /** `TRAINER_CLASS_` suffix (e.g. LEADER, ELITE_FOUR, CHAMPION). */
    val className: String = "",
    /** Items the trainer may use in battle, `ITEM_` suffixes. */
    val items: List<String> = emptyList(),
    /** `TRAINER_ENCOUNTER_MUSIC_` suffix. */
    val music: String = "",
)

private data class TrainerKey(val region: Region, val id: Int)

/**
 * The trainers from the decomp, keyed by region and trainer id. The two regions number their
 * trainers from zero, so the region is part of the key.
 */
@Singleton
class TrainerRegistry @Inject constructor() {

  private val trainers = ConcurrentHashMap<TrainerKey, TrainerDef>()

  init {
    GeneratedHoennTrainers.loadInto(this)
    GeneratedKantoTrainers.loadInto(this)
  }

  fun register(region: Region, def: TrainerDef) {
    val previous = trainers.put(TrainerKey(region, def.id), def)
    // Generated data, so a clash means the parser produced two trainers for one id and the second
    // would quietly replace the first.
    check(previous == null) {
      "Two $region trainers share id ${def.id}: ${previous?.name}, ${def.name}"
    }
  }

  fun get(region: Region, id: Int): TrainerDef? = trainers[TrainerKey(region, id)]

  fun size(): Int = trainers.size
}
