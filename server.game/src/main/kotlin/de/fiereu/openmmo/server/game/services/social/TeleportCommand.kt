package de.fiereu.openmmo.server.game.services.social

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.maps.WarpTile
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.services.WarpService
import de.fiereu.openmmo.server.game.services.command.ChatCommand
import de.fiereu.openmmo.server.game.services.command.CommandContext
import de.fiereu.openmmo.server.game.services.trade.TradeService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `/tp <region>:<bank>:<map> <x> <y> [up|down|left|right]` — a developer warp, used by the gateway
 * e2e to reach a mart and a Pokemon Center without walking there. Unlike /story it keeps the
 * character's progress, party and bag.
 */
@Singleton
class TeleportCommand
@Inject
constructor(
    private val maps: MapManager,
    private val warps: WarpService,
    private val battles: BattleService,
    private val trades: TradeService,
) : ChatCommand {
  override val name = "tp"
  override val usage = "/tp <region>:<bank>:<map> <x> <y> [facing]"
  override val description = "warps to a map tile (developer)"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val key = ctx.args.getOrNull(0)?.split(':')?.mapNotNull { it.toIntOrNull() }
    val x = ctx.args.getOrNull(1)?.toIntOrNull()
    val y = ctx.args.getOrNull(2)?.toIntOrNull()
    if (key == null || key.size != 3 || x == null || y == null) return ctx.reply("Usage: $usage")
    val facing =
        when (ctx.args.getOrNull(3)?.lowercase()) {
          "up" -> Direction.UP
          "left" -> Direction.LEFT
          "right" -> Direction.RIGHT
          else -> Direction.DOWN
        }
    val map = maps.getMap(key[0], key[1], key[2]) ?: return ctx.reply("No map ${ctx.args[0]}.")
    if (x !in 0 until map.width || y !in 0 until map.height) return ctx.reply("Off the map.")
    if (ctx.state.inDialog) return ctx.reply("Finish what you are talking to first.")
    if (battles.inBattle(ctx.characterId)) return ctx.reply("Finish the battle first.")
    if (trades.isTrading(ctx.characterId)) return ctx.reply("Finish your trade first.")
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
    ctx.reply("Warped to ${ctx.args[0]} ($x, $y).")
  }
}
