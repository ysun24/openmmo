package de.fiereu.openmmo.server.game.minigame.fame

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.server.game.minigame.MinigameChannel
import de.fiereu.openmmo.server.game.minigame.MinigameSave
import de.fiereu.openmmo.server.game.minigame.MinigameTopic
import de.fiereu.openmmo.server.game.minigame.SpecialFn
import de.fiereu.openmmo.server.game.minigame.SpecialTable
import de.fiereu.openmmo.server.game.minigame.action
import de.fiereu.openmmo.server.game.minigame.jsonOf
import de.fiereu.openmmo.server.game.minigame.specialVar
import de.fiereu.openmmo.server.game.services.StoryService
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.story.generated.kanto.KantoFlags
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * FireRed's FAME CHECKER (fame_checker.c): per person a pick state (0 not shown, 1 silhouette,
 * 2 colored) and six flavor-text flags, kept in save section "fame" as {p: [16], f: [16]}.
 * ResetFameChecker starts with only PROF. OAK colored. The map scripts' `famechecker` macro calls
 * SetFlavorTextFlagFromSpecialVars / UpdatePickStateFromSpecialVar8005 with VAR_0x8004 / 8005.
 * Until every map script runs, the decomp's defeat flags of the gym leaders, the ELITE FOUR and
 * GIOVANNI also color the person (their gym / league scripts set the colored state on entry).
 */
@Singleton
class FameCheckerService
@Inject
constructor(private val save: MinigameSave, private val story: StoryService) : MinigameTopic {
  override val topics = setOf("fame")

  data class Fame(val pick: IntArray, val flags: IntArray)

  suspend fun load(charId: Long): Fame {
    val s = save.section(charId, "fame")
    fun arr(k: String) =
        (s[k] as? JsonArray)?.map { it.jsonPrimitive.intOrNull ?: 0 }?.toIntArray()?.takeIf { it.size == N }
    return Fame(arr("p") ?: IntArray(N).also { it[OAK] = COLORED }, arr("f") ?: IntArray(N))
  }

  private suspend fun store(charId: Long, f: Fame) =
      save.setSection(
          charId,
          "fame",
          JsonObject(mapOf("p" to JsonArray(f.pick.map { JsonPrimitive(it) }), "f" to JsonArray(f.flags.map { JsonPrimitive(it) }))))

  /** SetFlavorTextFlagFromSpecialVars. */
  suspend fun setFlavorText(charId: Long, person: Int, index: Int) {
    if (person !in 0 until N || index !in 0..5) return
    val f = load(charId)
    f.flags[person] = f.flags[person] or (1 shl index)
    updatePick(f, person, SILHOUETTE)
    store(charId, f)
  }

  /** UpdatePickStateFromSpecialVar8005. */
  suspend fun setPickState(charId: Long, person: Int, state: Int) {
    if (person !in 0 until N || state !in 0..2) return
    val f = load(charId)
    updatePick(f, person, state)
    store(charId, f)
  }

  private fun updatePick(f: Fame, person: Int, state: Int) {
    if (state == NO_DRAW) return
    if (state == SILHOUETTE && f.pick[person] == COLORED) return
    f.pick[person] = state
  }

  /** FullyUnlockFameChecker (dev). */
  suspend fun unlockAll(charId: Long) = store(charId, Fame(IntArray(N) { COLORED }, IntArray(N) { 0x3F }))

  /** The pick states with the defeat flags applied (see the class comment). */
  private suspend fun view(charId: Long): Fame {
    val f = load(charId)
    for ((flag, p) in DEFEAT_FLAGS) {
      if (story.isFlagSet(charId, flag)) {
        f.pick[p] = COLORED
        f.flags[p] = f.flags[p] or 1
      }
    }
    return f
  }

  suspend fun open(session: SessionContext, charId: Long) {
    if (!story.isFlagSet(charId, KantoFlags.FLAG_GOT_FAME_CHECKER)) {
      MinigameChannel.send(session, "fame", jsonOf("error" to "You don't have the FAME CHECKER yet."))
      return
    }
    val f = view(charId)
    MinigameChannel.send(session, "fame", jsonOf("pick" to f.pick.toList(), "flags" to f.flags.toList()))
  }

  override suspend fun onAction(session: SessionContext, state: PlayerState, topic: String, body: JsonObject) {
    val charId = state.characterId ?: return
    when (body.action()) {
      "open" -> open(session, charId)
      "close" -> MinigameChannel.close(session, "fame")
    }
  }

  companion object {
    const val N = 16
    const val OAK = 0
    const val NO_DRAW = 0
    const val SILHOUETTE = 1
    const val COLORED = 2

    val DEFEAT_FLAGS =
        listOf(
            KantoFlags.FLAG_DEFEATED_BROCK to 2, KantoFlags.FLAG_DEFEATED_MISTY to 3,
            KantoFlags.FLAG_DEFEATED_LT_SURGE to 4, KantoFlags.FLAG_DEFEATED_ERIKA to 5,
            KantoFlags.FLAG_DEFEATED_KOGA to 6, KantoFlags.FLAG_DEFEATED_SABRINA to 7,
            KantoFlags.FLAG_DEFEATED_BLAINE to 8, KantoFlags.FLAG_DEFEATED_LORELEI to 9,
            KantoFlags.FLAG_DEFEATED_BRUNO to 10, KantoFlags.FLAG_DEFEATED_AGATHA to 11,
            KantoFlags.FLAG_DEFEATED_LANCE to 12, KantoFlags.FLAG_DEFEATED_LEADER_GIOVANNI to 15)
  }
}

/** The Fame Checker's specials. */
@Singleton
class FameCheckerSpecials @Inject constructor(private val fame: FameCheckerService) : SpecialTable() {
  override val table: Map<String, SpecialFn> =
      mapOf(
          "SetFlavorTextFlagFromSpecialVars" to { ctx ->
            fame.setFlavorText(ctx.playerEntityId, ctx.specialVar(0x8004), ctx.specialVar(0x8005))
            0
          },
          "UpdatePickStateFromSpecialVar8005" to { ctx ->
            fame.setPickState(ctx.playerEntityId, ctx.specialVar(0x8004), ctx.specialVar(0x8005))
            0
          },
      )
}
