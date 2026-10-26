package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket
import de.fiereu.openmmo.net.game.packets.LocalCharacterDeltaPacket
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

private const val MAX_GRANT = 1_000_000

@Singleton
class GiveMoneyCommand @Inject constructor(private val characterStore: CharacterStore) :
    ChatCommand {
  override val name = "givemoney"
  override val usage = "/givemoney <amount>"
  override val description = "dev: adds money"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val amount = ctx.args.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..MAX_GRANT * 100 }
    if (amount == null) return ctx.reply("Usage: $usage")
    val current = characterStore.getCharacter(ctx.characterId)?.info?.money ?: return
    if (current.toLong() + amount > Int.MAX_VALUE) return ctx.reply("That would overflow.")
    if (!characterStore.addMoney(ctx.characterId, amount)) return ctx.reply("Could not save it.")
    val money = characterStore.getCharacter(ctx.characterId)?.info?.money ?: 0
    ctx.session.send(CurrencyBalancePacket(money, 0))
    ctx.session.send(LocalCharacterDeltaPacket(money = money))
    ctx.reply("Gave $amount money, you now have $money.")
  }
}
