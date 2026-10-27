package de.fiereu.openmmo.server.game.services.social

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.maps.WarpTile
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.services.FieldService
import de.fiereu.openmmo.server.game.services.WarpService
import de.fiereu.openmmo.server.game.services.command.ChatCommand
import de.fiereu.openmmo.server.game.services.command.CommandContext
import de.fiereu.openmmo.server.game.services.trade.TradeService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one developer warp. It keeps the character's progress, party and bag (unlike /story):
 * - `/tp <region>:<bank>:<map> <x> <y> [up|down|left|right]` (the gateway e2e uses this one),
 * - `/tp <region> <bank> <map> <x> <y> [facing]`,
 * - `/tp <MapName> <x> <y>`: a map of the caller's region by its decomp name (`PewterCity_Gym`).
 *
 * Hoenn banks are the decomp group + 50.
 */
@Singleton
class TeleportCommand
@Inject
constructor(
    private val maps: MapManager,
    private val warps: WarpService,
    private val battles: BattleService,
    private val trades: TradeService,
    private val field: FieldService,
) : ChatCommand {
  override val name = "tp"
  override val usage =
      "/tp <r>:<bank>:<map> <x> <y> [facing] | /tp <r> <bank> <map> <x> <y> | /tp <MapName> <x> <y>"
  override val description = "warps to a map tile (developer)"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val a = ctx.args
    if (ctx.state.inDialog) return ctx.reply("Finish what you are talking to first.")
    if (battles.inBattle(ctx.characterId)) return ctx.reply("Finish the battle first.")
    if (trades.isTrading(ctx.characterId)) return ctx.reply("Finish your trade first.")
    val first = a.getOrNull(0) ?: return ctx.reply("Usage: $usage")
    val numbers = a.map { it.toIntOrNull() }
    val (key, rest) =
        when {
          ':' in first -> first.split(':').mapNotNull { it.toIntOrNull() } to a.drop(1)
          numbers.size >= 5 && numbers.take(5).all { it != null } ->
              numbers.take(3).map { it!! } to a.drop(3)
          else -> {
            // A decomp map name of the caller's region.
            val x = a.getOrNull(1)?.toIntOrNull() ?: return ctx.reply("Usage: $usage")
            val y = a.getOrNull(2)?.toIntOrNull() ?: return ctx.reply("Usage: $usage")
            if (!field.devWarp(ctx.session, ctx.state, first, x, y)) {
              return ctx.reply("No map called $first here.")
            }
            return ctx.reply("Warped to $first ($x, $y).")
          }
        }
    val x = rest.getOrNull(0)?.toIntOrNull()
    val y = rest.getOrNull(1)?.toIntOrNull()
    if (key.size != 3 || x == null || y == null) return ctx.reply("Usage: $usage")
    val facing =
        when (rest.getOrNull(2)?.lowercase()) {
          "up" -> Direction.UP
          "left" -> Direction.LEFT
          "right" -> Direction.RIGHT
          else -> Direction.DOWN
        }
    val label = key.joinToString(":")
    val map = maps.getMap(key[0], key[1], key[2]) ?: return ctx.reply("No map $label.")
    if (x !in 0 until map.width || y !in 0 until map.height) return ctx.reply("Off the map.")
    warps.executeWarp(
        ctx.session,
        ctx.characterId,
        WarpTile(
            x = 0,
            y = 0,
            targetRegionId = key[0].toByte(),
            targetBankId = key[1].toByte(),
            targetMapId = key[2].toByte(),
            targetX = x,
            targetY = y,
            exitFacing = facing,
        ),
    )
    ctx.reply("Warped to $label ($x, $y).")
  }
}
