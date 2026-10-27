package de.fiereu.openmmo.server.game.minigame.link

import de.fiereu.openmmo.server.game.minigame.MinigameChannel
import de.fiereu.openmmo.server.game.minigame.MinigameMenus
import de.fiereu.openmmo.server.game.minigame.MinigameRewards
import de.fiereu.openmmo.server.game.minigame.SpecialFn
import de.fiereu.openmmo.server.game.minigame.SpecialTable
import de.fiereu.openmmo.server.game.minigame.jsonOf
import de.fiereu.openmmo.server.game.minigame.records.RecordsService
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Pokémon Center 2F attendants (Common_EventScript_UnionRoomAttendant / WirelessClubAttendant /
 * DirectCornerAttendant, data/event_scripts.s + cable_club.inc) for online players: the Union Room,
 * the wireless minigame lobby, and the Direct Corner's TRADE CENTER / COLOSSEUM / RECORD CORNER.
 */
@Singleton
class LinkScripts
@Inject
constructor(
    private val union: UnionRoomService,
    private val links: LinkGameService,
    private val records: RecordsService,
    private val menus: MinigameMenus,
) {
  val scripts: Map<String, Script> =
      mapOf(
          "Common_EventScript_UnionRoomAttendant" to Script { openUnion(it) },
          "CableClub_EventScript_UnionRoomAttendant" to Script { openUnion(it) },
          "Common_EventScript_WirelessClubAttendant" to Script { openLobby(it) },
          "CableClub_EventScript_WirelessClubAttendant" to Script { openLobby(it) },
          "Common_EventScript_DirectCornerAttendant" to Script { directCorner(it) },
          "CableClub_EventScript_DirectCornerAttendant" to Script { directCorner(it) },
      )

  private fun openUnion(ctx: ScriptContext) {
    ctx.dialog.close(ctx.session, ctx.state)
    union.enter(ctx.playerEntityId)
  }

  private fun openLobby(ctx: ScriptContext) {
    ctx.dialog.close(ctx.session, ctx.state)
    links.openLobby(ctx.session, ctx.playerEntityId)
  }

  private suspend fun directCorner(ctx: ScriptContext) {
    val id = ctx.playerEntityId
    val kanto = ctx.state.regionId == 0
    val opts =
        listOf("TRADE CENTER", "COLOSSEUM") +
            (if (kanto) emptyList() else listOf("RECORD CORNER")) +
            "CANCEL"
    when (opts.getOrNull(menus.choose(ctx.session, id, "DIRECT CORNER", opts))) {
      "TRADE CENTER",
      "COLOSSEUM" -> openUnion(ctx)
      "RECORD CORNER" -> {
        ctx.dialog.close(ctx.session, ctx.state)
        val mixed = records.mix(ctx.session, id)
        MinigameChannel.send(ctx.session, "records", records.state(id))
        mixed
      }
    }
  }
}

/** The wireless minigame / Union Room specials. */
@Singleton
class LinkSpecials
@Inject
constructor(
    private val union: UnionRoomService,
    private val links: LinkGameService,
    private val characters: CharacterStore,
    private val rewards: MinigameRewards,
) : SpecialTable() {
  private fun party(ctx: ScriptContext) =
      characters.getCharacter(ctx.playerEntityId)?.pokemon?.map { it.dexId } ?: emptyList()

  private suspend fun showRecords(ctx: ScriptContext, game: String): Int {
    val r = links.records(ctx.playerEntityId)[game]
    MinigameChannel.send(
        ctx.session, "lobby", jsonOf("records" to jsonOf("game" to game, "values" to r)))
    rewards.tell(ctx.session, "$game records: ${r ?: "none yet"}")
    return 0
  }

  override val table: Map<String, SpecialFn> =
      mapOf(
          "IsWirelessAdapterConnected" to { _ -> 1 },
          "IsPokemonJumpSpeciesInParty" to
              { ctx ->
                if (party(ctx).any { it in PokemonJump.SPECIES }) 1 else 0
              },
          "IsDodrioInParty" to { ctx -> if (85 in party(ctx)) 1 else 0 },
          "ChooseMonForWirelessMinigame" to { _ -> 0 },
          "ShowPokemonJumpRecords" to { ctx -> showRecords(ctx, "pokejump") },
          "ShowDodrioBerryPickingRecords" to { ctx -> showRecords(ctx, "dodrio") },
          "ShowBerryCrushRankings" to { ctx -> showRecords(ctx, "berrycrush") },
          "TryBecomeLinkLeader" to
              { ctx ->
                links.openLobby(ctx.session, ctx.playerEntityId)
                0
              },
          "TryJoinLinkGroup" to
              { ctx ->
                links.openLobby(ctx.session, ctx.playerEntityId)
                0
              },
          "InitUnionRoom" to
              { ctx ->
                union.enter(ctx.playerEntityId)
                0
              },
          "RunUnionRoom" to
              { ctx ->
                union.enter(ctx.playerEntityId)
                0
              },
          "ShowWirelessCommunicationScreen" to
              { ctx ->
                union.push(ctx.playerEntityId)
                0
              },
          "BufferUnionRoomPlayerName" to
              { ctx ->
                val others = union.visible().filter { it != ctx.playerEntityId }
                ctx.bufferString(
                    1, others.firstOrNull()?.let { characters.getCharacter(it)?.info?.name } ?: "")
                others.size
              },
          "Script_ResetUnionRoomTrade" to { _ -> 0 },
      )
}
