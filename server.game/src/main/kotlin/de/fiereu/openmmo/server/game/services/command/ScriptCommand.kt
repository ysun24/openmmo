package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.server.game.script.ScriptRunner
import de.fiereu.openmmo.server.game.script.vm.DecompScript
import de.fiereu.openmmo.server.game.script.vm.ScriptImage
import de.fiereu.openmmo.server.game.services.StoryService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * r5-scripts developer command for the decomp script interpreter:
 * - `/script run <label>` runs a script label as if an npc had been talked to (no npc);
 * - `/script flag <FLAG_NAME> [on|off]`, `/script var <VAR_NAME> [value]` read / write story state
 *   by the decomp's own names (the region you stand in), aliases included.
 */
@Singleton
class ScriptCommand
@Inject
constructor(
    private val runner: ScriptRunner,
    private val story: StoryService,
) : ChatCommand {
  override val name = "script"
  override val usage = "/script run <label> | flag <FLAG_X> [on|off] | var <VAR_X> [value] | heal"
  override val description = "runs a decomp script / reads or sets its flags and vars"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val region = if (ctx.state.regionId == 1) "hoenn" else "kanto"
    val img = ScriptImage.of(region) ?: return ctx.reply("No script image for $region.")
    val a = ctx.args
    when (a.getOrNull(0)) {
      "run" -> {
        val label = a.getOrNull(1) ?: return ctx.reply(usage)
        if (!DecompScript.exists(label)) return ctx.reply("No script $label.")
        runner.run(ctx.session, ctx.state, DecompScript(label), entityId = -1)
        ctx.reply("Running $label.")
      }
      "heal" -> {
        runner.run(ctx.session, ctx.state, de.fiereu.openmmo.server.game.script.Script { it.healParty() }, -1)
        ctx.reply("Party healed.")
      }
      "flag" -> {
        val n = a.getOrNull(1)?.uppercase() ?: return ctx.reply(usage)
        val names = aliases(img.flagNames, n) ?: return ctx.reply("No flag $n in $region.")
        when (a.getOrNull(2)) {
          "on" -> names.forEach { story.setFlag(ctx.characterId, "$region/$it") }
          "off" -> names.forEach { story.clearFlag(ctx.characterId, "$region/$it") }
        }
        val set = names.any { story.isFlagSet(ctx.characterId, "$region/$it") }
        ctx.reply("$n is ${if (set) "set" else "clear"}.")
      }
      "var" -> {
        val n = a.getOrNull(1)?.uppercase() ?: return ctx.reply(usage)
        val names = aliases(img.varNames, n) ?: return ctx.reply("No var $n in $region.")
        a.getOrNull(2)?.toIntOrNull()?.let { v ->
          names.forEach { story.setVar(ctx.characterId, "$region/$it", v) }
        }
        ctx.reply("$n = ${story.getVar(ctx.characterId, "$region/${names.first()}")}.")
      }
      else -> ctx.reply(usage)
    }
  }

  private fun aliases(table: Map<Int, List<String>>, name: String): List<String>? =
      table.values.firstOrNull { name in it }
}
