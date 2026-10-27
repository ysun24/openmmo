package de.fiereu.openmmo.server.game.minigame

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.server.game.minigame.gamecorner.GameCornerService
import de.fiereu.openmmo.server.game.services.command.ChatCommand
import de.fiereu.openmmo.server.game.services.command.CommandContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch

/** r5-minigames dev / admin chat commands. */
object MinigameCommands {
  /** Dev: `/famecheck all` = FullyUnlockFameChecker (and the FAME CHECKER itself). */
  @Singleton
  class Fame
  @Inject
  constructor(
      private val fame: de.fiereu.openmmo.server.game.minigame.fame.FameCheckerService,
      private val story: de.fiereu.openmmo.server.game.services.StoryService,
  ) : ChatCommand {
    override val name = "famecheck"
    override val usage = "/famecheck all|<person 0-15> <flavor 0-5>"
    override val description = "dev: unlock FAME CHECKER entries"
    override val permission = CharacterPermissions.DEVELOPER

    override suspend fun run(ctx: CommandContext) {
      story.setFlag(ctx.characterId, de.fiereu.openmmo.story.generated.kanto.KantoFlags.FLAG_GOT_FAME_CHECKER)
      if (ctx.args.getOrNull(0) == "all") fame.unlockAll(ctx.characterId)
      else {
        val p = ctx.args.getOrNull(0)?.toIntOrNull() ?: return ctx.reply("Usage: $usage")
        fame.setFlavorText(ctx.characterId, p, ctx.args.getOrNull(1)?.toIntOrNull() ?: 0)
      }
      ctx.reply("FAME CHECKER updated.")
    }
  }

  /** Places the dev warp knows: region, bank, map, x, y (decomp map_groups order). */
  val PLACES =
      mapOf(
          "celadon" to listOf(0, 10, 14, 9, 12),
          "prizeroom" to listOf(0, 10, 15, 3, 8),
          "pc2f-kanto" to listOf(0, 10, 13, 5, 4),
          "pc1f-kanto" to listOf(0, 10, 12, 7, 6),
          "twoisland" to listOf(0, 33, 0, 5, 7),
          "vermilion" to listOf(0, 3, 5, 22, 35),
          "mauville" to listOf(1, 10, 3, 11, 9),
          "pc2f-hoenn" to listOf(1, 10, 6, 5, 4),
          "pc1f-hoenn" to listOf(1, 10, 5, 7, 6),
          "lilycove" to listOf(1, 13, 10, 11, 13),
          "southernisland" to listOf(1, 26, 9, 13, 22),
          // in front of a machine / table / clerk, facing it (5th: 0 up 1 down 2 left 3 right)
          "celadonslot" to listOf(0, 10, 14, 10, 7, 3),
          "celadonclerk" to listOf(0, 10, 14, 6, 3, 0),
          "prizeclerk" to listOf(0, 10, 15, 4, 3, 0),
          "mauvilleslot" to listOf(1, 10, 3, 1, 6, 3),
          "roulette" to listOf(1, 10, 3, 14, 7, 0),
          "mauvilleclerk" to listOf(1, 10, 3, 11, 3, 0),
          "mauvilleprizes" to listOf(1, 10, 3, 13, 3, 0),
      )

  /** Dev: `/mgwarp <place>` jumps to a Game Corner / Pokémon Center 2F / harbor. */
  @Singleton
  class Warp
  @Inject
  constructor(
      private val warps: de.fiereu.openmmo.server.game.services.ScriptWarpService,
      private val scope: kotlinx.coroutines.CoroutineScope,
  ) : ChatCommand {
    override val name = "mgwarp"
    override val usage = "/mgwarp <${PLACES.keys.joinToString("|")}>"
    override val description = "dev: warp to a minigame location"
    override val permission = CharacterPermissions.DEVELOPER

    override suspend fun run(ctx: CommandContext) {
      val p = PLACES[ctx.args.getOrNull(0)?.lowercase()] ?: return ctx.reply("Usage: $usage")
      scope.launch {
        warps.warp(
            ctx.session,
            ctx.state,
            de.fiereu.openmmo.common.DynamicWarp(
                p[0].toByte(), p[1].toByte(), p[2].toByte(), p[3].toShort(), p[4].toShort(),
                when (p.getOrNull(5)) {
                  1 -> de.fiereu.openmmo.common.enums.Direction.DOWN
                  2 -> de.fiereu.openmmo.common.enums.Direction.LEFT
                  3 -> de.fiereu.openmmo.common.enums.Direction.RIGHT
                  else -> de.fiereu.openmmo.common.enums.Direction.UP
                }))
      }
    }
  }

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
