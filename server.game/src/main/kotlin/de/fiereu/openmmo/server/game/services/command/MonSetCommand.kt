package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.server.game.battle.ItemNames
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dev only (r3-battle): edits a party monster's battle state for testing. `/monset <slot 1-6>
 * status <none|slp|psn|tox|brn|frz|par>`, `... held <ITEM_NAME|none>`, `... friendship <0-255>`,
 * `... hp <n>`, `... level <n>`.
 */
@Singleton
class MonSetCommand
@Inject
constructor(
    private val characters: CharacterStore,
    private val items: ItemRegistry,
    private val battles: BattleService,
) : ChatCommand {
  override val name = "monset"
  override val usage = "/monset <slot> <status|held|friendship|hp|level> <value>"
  override val description = "sets a party monster's status, held item, friendship, hp or level"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val slot = ctx.args.getOrNull(0)?.toIntOrNull()
    val field = ctx.args.getOrNull(1)?.lowercase()
    val value = ctx.args.getOrNull(2)
    if (slot == null || field == null || value == null) return ctx.reply("Usage: $usage")
    if (battles.inBattle(ctx.characterId)) return ctx.reply("Not during a battle.")
    val party =
        characters
            .getCharacter(ctx.characterId)
            ?.pokemon
            ?.filter { it.container == PokemonContainer.PARTY }
            ?.sortedBy { it.containerSlot } ?: return
    val mon = party.getOrNull(slot - 1) ?: return ctx.reply("No monster in slot $slot.")
    val updated =
        when (field) {
          "status" ->
              mon.copy(
                  status =
                      when (value.lowercase()) {
                        "slp" -> 3
                        "psn" -> StatusCondition.POISON
                        "tox" -> StatusCondition.TOXIC
                        "brn" -> StatusCondition.BURN
                        "frz" -> StatusCondition.FREEZE
                        "par" -> StatusCondition.PARALYSIS
                        else -> 0
                      })
          "held" -> {
            val id =
                if (value.equals("none", true)) 0 else ItemNames.wireId(items, value.uppercase())
            if (id == 0 && !value.equals("none", true)) return ctx.reply("Unknown item $value.")
            mon.copy(heldItem = id)
          }
          "friendship" -> mon.copy(friendship = (value.toIntOrNull() ?: 70).coerceIn(0, 255))
          "hp" -> mon.copy(hp = (value.toIntOrNull() ?: 1).coerceAtLeast(0).toShort())
          "level" -> {
            val level = (value.toIntOrNull() ?: mon.level.toInt()).coerceIn(1, 100)
            val def = de.fiereu.openmmo.pokemon.SpeciesRegistry().get(mon.dexId)
            val xp =
                def?.let {
                  de.fiereu.openmmo.server.game.battle.ExpCurves.totalXpFor(it.growthRate, level)
                } ?: mon.xp
            mon.copy(level = level.toByte(), xp = xp)
          }
          else -> return ctx.reply("Usage: $usage")
        }
    characters.updatePokemon(ctx.characterId, updated)
    ctx.session.send(
        PokemonContainerPacket(
            container = PokemonContainer.PARTY,
            hasChange = true,
            delete = false,
            pokemon =
                characters
                    .getCharacter(ctx.characterId)
                    ?.pokemon
                    ?.filter { it.container == PokemonContainer.PARTY }
                    ?.sortedBy { it.containerSlot } ?: emptyList()))
    ctx.reply("Slot $slot: $field = $value")
  }
}
