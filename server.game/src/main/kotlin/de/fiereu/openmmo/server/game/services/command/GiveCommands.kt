package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.MAX_PARTY_SIZE
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket
import de.fiereu.openmmo.net.game.packets.LocalCharacterDeltaPacket
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Dev-only grants for testing trading features. All of them need the DEVELOPER permission bit,
 * which ordinary characters do not have; set it in the database (characters.permissions | 256)
 * or use a seeded dev character.
 */

private const val MAX_LEVEL = 100
private const val MAX_GRANT = 1_000_000

@Singleton
class GiveMonCommand
@Inject
constructor(
    private val characterStore: CharacterStore,
    private val factory: WildMonFactory,
    private val species: SpeciesRegistry,
) : ChatCommand {
  override val name = "givemon"
  override val usage = "/givemon <dexId> [level]"
  override val description = "dev: adds a monster to your party"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val dexId = ctx.args.getOrNull(0)?.toIntOrNull()
    val level = (ctx.args.getOrNull(1)?.toIntOrNull() ?: 5).coerceIn(1, MAX_LEVEL)
    if (dexId == null || species.get(dexId) == null) return ctx.reply("Usage: $usage")
    val stored = characterStore.getCharacter(ctx.characterId) ?: return
    if (stored.pokemon.size >= MAX_PARTY_SIZE) return ctx.reply("Your party is full.")
    val rolled = factory.create(dexId, level, BattleRng()) ?: return ctx.reply("Unknown species.")
    val mon =
        rolled.copy(
            ownerId = ctx.characterId,
            container = PokemonContainer.PARTY,
            containerSlot = stored.pokemon.size.toShort(),
            ot = stored.info.name,
        )
    if (!characterStore.addPokemon(ctx.characterId, mon)) return ctx.reply("Could not save it.")
    val party = characterStore.getCharacter(ctx.characterId)?.pokemon.orEmpty()
    ctx.session.send(PokemonContainerPacket(PokemonContainer.PARTY, true, false, party))
    ctx.reply(
        "Gave ${species.get(dexId)?.name} Lv$level (id ${mon.id}) in party slot ${mon.containerSlot}.")
  }
}

@Singleton
class GiveItemCommand
@Inject
constructor(private val characterStore: CharacterStore, private val items: ItemRegistry) :
    ChatCommand {
  override val name = "giveitem"
  override val usage = "/giveitem <itemId> [quantity]"
  override val description = "dev: adds items to your bag"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val itemId = ctx.args.getOrNull(0)?.toIntOrNull()
    val qty = (ctx.args.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, MAX_GRANT)
    val item = itemId?.let(items::get) ?: return ctx.reply("Usage: $usage")
    if (!characterStore.addItem(ctx.characterId, itemId, qty))
        return ctx.reply("Could not save it.")
    val held = characterStore.getCharacter(ctx.characterId)?.items?.get(itemId) ?: qty
    ctx.reply("Gave ${qty}x ${item.name} (item $itemId), you now hold $held.")
  }
}

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
