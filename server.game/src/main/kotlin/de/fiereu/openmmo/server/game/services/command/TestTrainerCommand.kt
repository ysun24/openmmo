package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.server.game.services.BattleService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Dev only: fights a decomp trainer by id, so trainer battles can be tested without a script. */
@Singleton
class TestTrainerCommand
@Inject
constructor(
    private val battleService: BattleService,
    private val scope: CoroutineScope,
) : ChatCommand {
  override val name = "testtrainer"
  override val usage = "/testtrainer <trainerId> [kanto|hoenn]"
  override val description = "starts a battle against the decomp trainer with that id"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val id = ctx.args.getOrNull(0)?.toIntOrNull()
    if (id == null) {
      ctx.reply("Usage: $usage")
      return
    }
    val region = if (ctx.args.getOrNull(1).equals("hoenn", true)) Region.HOENN else Region.KANTO
    // The battle waits for the client, which must not hold up this packet's thread.
    scope.launch {
      val result = battleService.startTrainerBattle(ctx.session, region, id)
      ctx.reply("Trainer battle over: $result")
    }
  }
}
