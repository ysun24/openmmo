package de.fiereu.openmmo.server.game.minigame

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.server.game.minigame.gamecorner.GameCornerService
import de.fiereu.openmmo.server.game.services.command.ChatCommand
import de.fiereu.openmmo.server.game.services.command.CommandContext
import javax.inject.Inject
import javax.inject.Singleton

/** r5-minigames dev / admin chat commands. */
object MinigameCommands {
  /** Dev: `/coins <n>` sets the coin case (and gives FireRed's FLAG_GOT_COIN_CASE). */
  @Singleton
  class Coins
  @Inject
  constructor(private val save: MinigameSave, private val corner: GameCornerService) : ChatCommand {
    override val name = "coins"
    override val usage = "/coins <0-9999>"
    override val description = "sets your COIN CASE (and gives the coin case)"
    override val permission = CharacterPermissions.DEVELOPER

    override suspend fun run(ctx: CommandContext) {
      val n = ctx.args.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..MAX_COINS }
          ?: return ctx.reply("Usage: $usage")
      save.setCoins(ctx.characterId, n)
      ctx.state.characterId?.let {
        // The coin case itself: FireRed's flag (the bag item needs /giveitem).
        corner.giveCoinCaseFlag(it)
      }
      corner.pushCoins(ctx.session, ctx.characterId)
      ctx.reply("You now have $n COINS.")
    }
  }
}
