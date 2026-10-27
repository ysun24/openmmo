package de.fiereu.openmmo.maps

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.MovementType

data class NpcDef(
    val entityIdx: Int,
    val graphicsId: Int,
    val x: Int,
    val y: Int,
    val elevation: Int,
    val movementType: MovementType,
    val movementRangeX: Int,
    val movementRangeY: Int,
    val trainerType: Int,
    val facing: Direction,
    val script: String = "0x0",
    /** Story flag that hides this npc by default, or "" when it is always shown. */
    val hideFlag: String = "",
    /** The ITEM_* constant an item ball gives (its script's finditem), or "". */
    val item: String = "",
    /** The decomp object's flag, namespaced, set once an item ball is taken; "" when none. */
    val flag: String = "",
    /** Trainer battle data when this npc is a trainer, else null. */
    val trainer: TrainerSpot? = null,
)

/** A trainer npc's `trainerbattle` data and its line of sight. */
data class TrainerSpot(
    /** opponents.h id in this region, the key into the trainer registry. */
    val trainerId: Int,
    val constant: String,
    /** trainer_sight_or_berry_tree_id: how many tiles the trainer sees. */
    val sight: Int,
    /** Dialog text ids (0 when unknown). */
    val introText: Int,
    val defeatText: Int,
    val postText: Int,
)
