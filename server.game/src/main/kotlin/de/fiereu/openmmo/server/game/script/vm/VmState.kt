package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.network.SessionAttribute
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.Direction
import java.util.concurrent.ConcurrentHashMap

/** Where the decomp keeps an object event: its tile and facing, as this player's scripts left it. */
data class ObjPose(val x: Int, val y: Int, val facing: Direction)

/**
 * The trainer battle a `trainerbattle` command configured (battle_setup.c's static trainer battle
 * variables): mode, opponents, the speeches and the scripts to continue with.
 */
data class TrainerBattleSetup(
    val mode: Int,
    val opponentA: Int,
    val localId: Int,
    val introA: Int,
    val defeatA: Int,
    val cannotBattle: Int = 0,
    val scriptRetA: Int = 0,
    val endScript: Int = 0,
    val opponentB: Int = 0,
    val introB: Int = 0,
    val defeatB: Int = 0,
    val scriptRetB: Int = 0,
    /** Started by a trainer seeing the player rather than by talking to it. */
    val approaching: Boolean = false,
)

/**
 * A player's script engine state that lives outside a single script run, like the decomp's
 * globals: gSpecialVar_* (0x8000+), the special flags (0x4000+), the configured trainer / wild
 * battle, per-map object positions and template overrides, and the last battle outcome.
 */
class VmState {
  val specialVars = IntArray(0x100)
  val specialFlags: MutableSet<Int> = ConcurrentHashMap.newKeySet()

  @Volatile var trainer: TrainerBattleSetup? = null

  /** The second trainer of a two-trainer approach (TryPrepareSecondApproachingTrainer). */
  @Volatile var pendingSecondTrainer: TrainerBattleSetup? = null

  /** setwildbattle: species (national dex), level, held item (GBA id). */
  @Volatile var wild: Triple<Int, Int, Int>? = null

  /** gBattleOutcome of the last script battle (B_OUTCOME_*). */
  @Volatile var battleOutcome: Int = 0

  /** Which map the per-map state below belongs to. */
  @Volatile var mapKey: Int = -1

  /** Where this player's scripts moved each object (decomp local id) of the current map. */
  val objects = ConcurrentHashMap<Int, ObjPose>()

  /** setobjectxyperm / setobjectmovementtype: template changes applied to the next spawn. */
  val templatePos = ConcurrentHashMap<Int, Pair<Int, Int>>()
  val templateMovement = ConcurrentHashMap<Int, Int>()

  /** setmetatile: (x, y) -> (metatile id, impassable), resent after a map reload. */
  val metatiles = ConcurrentHashMap<Int, Pair<Int, Boolean>>()

  /** setescapewarp / setdivewarp / setholewarp / setwarp destinations. */
  val warps = ConcurrentHashMap<String, IntArray>()

  /** Mystery event / RAM script base (setvaddress). */
  @Volatile var vaddrOffset: Int = 0

  /** FRLG: the next message box is a sign (signmsg / normalmsg). */
  @Volatile var signBox: Boolean = false

  /** gStringVar4 as the last special that filled it left it (raw decomp text). */
  @Volatile var stringVar4: String = ""

  @Volatile var savedBgm: Int = 0

  /** setweather's weather for doweather, -1 = the map's own. */
  @Volatile var weather: Int = -1

  /** setfieldeffectargument (gFieldEffectArguments). */
  val fieldEffectArgs = IntArray(8)

  /** Floors addelevmenuitem queued for showelevmenu. */
  val elevatorItems = mutableListOf<IntArray>()

  /** Reset when the player arrives on another map (ClearTempFieldEventData + object templates). */
  fun enterMap(key: Int) {
    if (mapKey == key) return
    mapKey = key
    objects.clear()
    templatePos.clear()
    templateMovement.clear()
    metatiles.clear()
  }

  companion object {
    val KEY = SessionAttribute.of<VmState>("scriptVmState")

    fun of(session: SessionContext): VmState = session.attributes.getOrPut(KEY) { VmState() }
  }
}
