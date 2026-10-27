package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PARTY = 6
private const val MAX_PC_SLOTS = 14 * 30

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
  override val description = "adds a monster to your party (or the PC when it is full)"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val dexId = ctx.args.getOrNull(0)?.toIntOrNull()
    if (dexId == null) {
      ctx.reply("Usage: $usage")
      return
    }
    val level = (ctx.args.getOrNull(1)?.toIntOrNull() ?: 5).coerceIn(1, 100)
    val stored = characterStore.getCharacter(ctx.characterId) ?: return
    val rolled = wildMons.create(dexId, level, BattleRng())
    if (rolled == null) {
      ctx.reply("Unknown species $dexId.")
      return
    }
    if (stored.pokemon.size >= MAX_PARTY) return toPc(ctx, rolled, dexId, level)
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

  /** A full party sends the monster to the first free PC slot, as a caught one would go. */
  private suspend fun toPc(ctx: CommandContext, rolled: Pokemon, dexId: Int, level: Int) {
    var placed: Pokemon? = null
    val ok =
        characterStore.commitDurably(ctx.characterId) { s ->
          val used = s.pcStorage.map { it.containerSlot.toInt() }.toSet()
          val slot = (0 until MAX_PC_SLOTS).firstOrNull { it !in used } ?: return@commitDurably null
          val mon =
              rolled.copy(
                  ownerId = ctx.characterId,
                  container = PokemonContainer.PC,
                  containerSlot = slot.toShort(),
                  ot = s.info.name,
              )
          placed = mon
          s.copy(pcStorage = (s.pcStorage + mon).toMutableList())
        }
    val mon = placed
    if (!ok || mon == null) return ctx.reply("Your party and PC are full.")
    val pc = characterStore.getCharacter(ctx.characterId)?.pcStorage ?: emptyList()
    ctx.session.send(PokemonContainerPacket(PokemonContainer.PC, true, false, pc))
    ctx.reply("Party full: sent dex #$dexId at level $level to the PC (slot ${mon.containerSlot}).")
  }
}
