package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PARTY = 6

/** Dev only: puts a fresh monster into the party so a test character has something to battle. */
@Singleton
class GiveMonCommand
@Inject
constructor(
    private val characterStore: CharacterStore,
    private val wildMons: WildMonFactory,
) : ChatCommand {
  override val name = "givemon"
  override val usage = "/givemon <dexId> [level]"
  override val description = "adds a monster of the given species to your party"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val dexId = ctx.args.getOrNull(0)?.toIntOrNull()
    if (dexId == null) {
      ctx.reply("Usage: $usage")
      return
    }
    val level = (ctx.args.getOrNull(1)?.toIntOrNull() ?: 5).coerceIn(1, 100)
    val stored = characterStore.getCharacter(ctx.characterId) ?: return
    if (stored.pokemon.size >= MAX_PARTY) {
      ctx.reply("Your party is full.")
      return
    }
    val rolled = wildMons.create(dexId, level, BattleRng())
    if (rolled == null) {
      ctx.reply("Unknown species $dexId.")
      return
    }
    val slot = ((stored.pokemon.maxOfOrNull { it.containerSlot } ?: -1) + 1).toShort()
    val mon =
        rolled.copy(
            ownerId = ctx.characterId,
            container = PokemonContainer.PARTY,
            containerSlot = slot,
            ot = stored.info.name,
        )
    if (!characterStore.addPokemon(ctx.characterId, mon)) {
      ctx.reply("Could not add the monster.")
      return
    }
    val party = characterStore.getCharacter(ctx.characterId)?.pokemon ?: emptyList()
    ctx.session.send(PokemonContainerPacket(PokemonContainer.PARTY, true, false, party))
    ctx.reply("Added dex #$dexId at level $level to your party.")
  }
}
