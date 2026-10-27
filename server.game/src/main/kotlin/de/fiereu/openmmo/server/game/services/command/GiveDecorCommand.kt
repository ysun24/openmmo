package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.progression.Gen3Data
import de.fiereu.openmmo.server.game.progression.PlayerProgressService
import de.fiereu.openmmo.server.game.progression.ProgressKeys
import de.fiereu.openmmo.server.game.storage.CharacterStore
import javax.inject.Inject

/** r4: `/givedecor <id|all> [qty]` puts decorations in the PC, for testing the secret bases. */
class GiveDecorCommand
@Inject
constructor(
    private val characters: CharacterStore,
    private val progress: PlayerProgressService,
) : ChatCommand {
  override val name = "givedecor"
  override val usage = "/givedecor <decorId|all> [quantity]"
  override val description = "puts decorations in your PC (developer)"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val a = ctx.args.getOrNull(0) ?: return ctx.reply("Usage: $usage")
    val qty = (ctx.args.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 99)
    val ids =
        if (a.equals("all", true)) Gen3Data.decorations.keys.toList()
        else listOfNotNull(a.toIntOrNull()?.takeIf { it in Gen3Data.decorations })
    if (ids.isEmpty()) return ctx.reply("Unknown decoration $a.")
    characters.commitDurably(ctx.characterId) { s ->
      s.copy(
          storyVars =
              s.storyVars.toMutableMap().apply {
                ids.forEach {
                  put(ProgressKeys.decor(it), minOf(99, (this[ProgressKeys.decor(it)] ?: 0) + qty))
                }
              })
    }
    progress.push(ctx.session, ctx.characterId)
    ctx.reply("Put ${ids.size} kind(s) of decoration in your PC.")
  }
}
