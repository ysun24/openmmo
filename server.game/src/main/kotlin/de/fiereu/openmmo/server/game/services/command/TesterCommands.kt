package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.MonStatus
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.maps.WarpTile
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.server.game.progression.PlayerProgressService
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.services.WarpService
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Developer only: `/tp <region> <bank> <map> <x> <y>` moves the caller anywhere (not /warp: a
 * client-side /w would swallow it).
 */
@Singleton
class WarpCommand
@Inject
constructor(
    private val maps: MapManager,
    private val warps: WarpService,
    private val battles: BattleService,
) : ChatCommand {
  override val name = "tp"
  override val usage = "/tp <region> <bank> <map> <x> <y>"
  override val description = "dev: warps to a map (region 0 Kanto, 1 Hoenn)"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val n = ctx.args.map { it.toIntOrNull() }
    if (n.size < 5 || n.any { it == null }) return ctx.reply("Usage: $usage")
    val (region, bank, map, x, y) = n.map { it!! }
    if (ctx.state.inDialog || battles.inBattle(ctx.characterId)) {
      return ctx.reply("Finish what you are doing first.")
    }
    if (maps.getMap(region, bank, map) == null) return ctx.reply("No map $region:$bank:$map.")
    val tile =
        WarpTile(
            x = ctx.state.x.toInt(),
            y = ctx.state.y.toInt(),
            targetRegionId = region.toByte(),
            targetBankId = bank.toByte(),
            targetMapId = map.toByte(),
            targetX = x,
            targetY = y,
        )
    warps.executeWarp(ctx.session, ctx.characterId, tile)
  }
}

/** Developer only: `/setstatus <slot> psn|tox|brn|frz|par|slp|none`, to test status cures. */
@Singleton
class SetStatusCommand
@Inject
constructor(
    private val characters: CharacterStore,
    private val progress: PlayerProgressService,
) : ChatCommand {
  override val name = "setstatus"
  override val usage = "/setstatus <party slot 0-5> psn|tox|brn|frz|par|slp|none"
  override val description = "dev: gives a party monster a status condition"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val slot = ctx.args.getOrNull(0)?.toIntOrNull() ?: return ctx.reply("Usage: $usage")
    val status =
        when (ctx.args.getOrNull(1)?.lowercase()) {
          "psn" -> MonStatus.POISON
          "tox" -> MonStatus.TOXIC
          "brn" -> MonStatus.BURN
          "frz" -> MonStatus.FREEZE
          "par" -> MonStatus.PARALYSIS
          "slp" -> 3
          "none" -> MonStatus.NONE
          else -> return ctx.reply("Usage: $usage")
        }
    val mon =
        characters
            .getCharacter(ctx.characterId)
            ?.pokemon
            ?.sortedBy { it.containerSlot }
            ?.getOrNull(slot) ?: return ctx.reply("No monster in slot $slot.")
    characters.updatePokemon(ctx.characterId, mon.copy(status = status))
    val party = characters.getCharacter(ctx.characterId)?.pokemon.orEmpty()
    ctx.session.send(PokemonContainerPacket(PokemonContainer.PARTY, true, false, party))
    progress.push(ctx.session, ctx.characterId)
    ctx.reply("Slot $slot is now ${MonStatus.label(status) ?: "healthy"}.")
  }
}
