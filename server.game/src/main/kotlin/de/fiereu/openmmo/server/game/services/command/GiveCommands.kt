package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.MAX_PARTY_SIZE
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.services.WorldStateService
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_GIVE_QUANTITY = 999

/** Developer only: adds bag items to the caller. */
@Singleton
class GiveItemCommand
@Inject
constructor(
    private val items: ItemRegistry,
    private val characterStore: CharacterStore,
    private val worldState: WorldStateService,
) : ChatCommand {
  override val name = "giveitem"
  override val usage = "/giveitem <itemId> [quantity]"
  override val description = "adds items to your bag"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val id = ctx.args.getOrNull(0)?.toIntOrNull() ?: return ctx.reply("Usage: $usage")
    val quantity = (ctx.args.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, MAX_GIVE_QUANTITY)
    val item = items.get(id) ?: return ctx.reply("No item $id.")
    if (!characterStore.addItem(ctx.characterId, id, quantity)) return ctx.reply("Could not save.")
    characterStore.getCharacter(ctx.characterId)?.let {
      worldState.send(ctx.session, it, fullVars = true)
    }
    ctx.reply("Gave $quantity ${item.name}.")
  }
}
