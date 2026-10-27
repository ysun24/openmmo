package de.fiereu.openmmo.codegen.trainer

/** A monster on a trainer's team. Empty [moveIds] means the level up moveset is used. */
data class ParsedTrainerMon(
    val dexId: Int,
    val level: Int,
    val iv: Int,
    val heldItem: Int,
    val moveIds: List<Int>,
    /** The held item's decomp constant without `ITEM_`, "" for none. */
    val heldItemName: String = "",
)

data class ParsedTrainer(
    val id: Int,
    val name: String,
    val trainerClass: Int,
    val doubleBattle: Boolean,
    val prizeRate: Int,
    val party: List<ParsedTrainerMon>,
    /** `TRAINER_PIC_` suffix, e.g. LEADER_ROXANNE. */
    val pic: String = "",
    /** `TRAINER_CLASS_` suffix, e.g. LEADER. */
    val className: String = "",
    /** The items the trainer may use in battle, `ITEM_` suffixes. */
    val items: List<String> = emptyList(),
    /** `TRAINER_ENCOUNTER_MUSIC_` suffix, e.g. FEMALE. */
    val music: String = "",
)
