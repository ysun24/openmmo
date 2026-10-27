package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.server.game.field.FieldMoves
import de.fiereu.openmmo.server.game.field.FlyDestinations
import de.fiereu.openmmo.server.game.field.storyName
import de.fiereu.openmmo.server.game.services.FieldService
import de.fiereu.openmmo.server.game.services.StoryService
import de.fiereu.openmmo.server.game.services.storyItemStacksPacket
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Developer shortcut for testing the field layer (r3-field): every badge, the running shoes, every
 * FLY town, a Bicycle, and the field moves taught across the party (four per monster, in slot
 * order). `/fielddev cut surf ...` teaches only the named moves.
 */
@Singleton
class FieldDevCommand
@Inject
constructor(
    private val story: StoryService,
    private val characters: CharacterStore,
    private val items: ItemRegistry,
    private val moves: MoveRegistry,
    private val field: FieldService,
) : ChatCommand {
  override val name = "fielddev"
  override val usage = "/fielddev [move ...]"
  override val description = "badges, running shoes, FLY towns, a bike and HM moves for testing"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val region = Region.byId(ctx.state.regionId) ?: Region.KANTO
    val charId = ctx.characterId
    for (b in 1..8) story.setFlag(charId, FieldMoves.badgeFlag(region, b))
    story.setFlag(charId, "${region.storyName}/FLAG_SYS_B_DASH")
    FlyDestinations.forRegion(region).forEach { story.setFlag(charId, it.flag) }
    items
        .all()
        .firstOrNull { it.name.equals("Bicycle", true) }
        ?.let { bike ->
          val id = items.idOf(bike)
          if ((characters.getCharacter(charId)?.items?.get(id) ?: 0) == 0)
              characters.addItem(charId, id, 1)
        }
    items
        .all()
        .firstOrNull { it.name.equals("Escape Rope", true) }
        ?.let { characters.addItem(charId, items.idOf(it), 3) }
    val wanted =
        if (ctx.args.isEmpty())
            listOf(
                FieldMoves.CUT,
                FieldMoves.SURF,
                FieldMoves.STRENGTH,
                FieldMoves.ROCK_SMASH,
                FieldMoves.FLY,
                FieldMoves.FLASH,
                FieldMoves.WATERFALL,
                FieldMoves.DIVE,
                FieldMoves.TELEPORT,
                FieldMoves.DIG,
                FieldMoves.SWEET_SCENT)
        else
            ctx.args.mapNotNull { a ->
              FieldMoves.moveOf.entries
                  .firstOrNull { it.key.name.replace("_", "").equals(a.replace("_", ""), true) }
                  ?.value
            }
    val party = characters.getCharacter(charId)?.pokemon?.sortedBy { it.containerSlot }.orEmpty()
    if (party.isEmpty()) return ctx.reply("Get a POKéMON first (/givemon).")
    wanted.chunked(4).zip(party).forEach { (chunk, mon) ->
      characters.updatePokemon(
          charId,
          mon.copy(
              moves = chunk.map { PokemonMove(it.toShort(), (moves.get(it)?.pp ?: 10).toByte()) }))
    }
    characters.getCharacter(charId)?.items?.let { ctx.session.send(storyItemStacksPacket(it)) }
    field.sendInfo(ctx.session, ctx.state)
    val taught = minOf(wanted.size, party.size * 4)
    ctx.reply(
        "Field test kit: 8 badges, running shoes, FLY towns, bike; $taught field moves taught.")
  }
}

/** Developer teleport for testing the field layer: `/fieldtp <MapName> <x> <y>`. */
@Singleton
class FieldTpCommand @Inject constructor(private val field: FieldService) : ChatCommand {
  override val name = "fieldtp"
  override val usage = "/fieldtp <MapName> <x> <y>"
  override val description = "warps you to a map of your region by its decomp name"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val name = ctx.args.getOrNull(0) ?: return ctx.reply("Usage: $usage")
    val x = ctx.args.getOrNull(1)?.toIntOrNull() ?: return ctx.reply("Usage: $usage")
    val y = ctx.args.getOrNull(2)?.toIntOrNull() ?: return ctx.reply("Usage: $usage")
    if (!field.devWarp(ctx.session, ctx.state, name, x, y)) ctx.reply("No map called $name here.")
  }
}
