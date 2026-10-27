package de.fiereu.openmmo.server.game.services.command

import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.server.game.services.notice
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton

private val log = KotlinLogging.logger {}

/**
 * Admin only: `/grant <player> developer|admin|none` sets another online character's rank, so
 * testers get the dev commands without a database edit. Admin implies developer; `none` takes
 * both away. Only admins (the bootstrap account's characters, see GameServerConfig) may run it.
 */
@Singleton
class GrantCommand
@Inject
constructor(
    private val characters: CharacterStore,
    private val sessions: SessionRegistry,
) : ChatCommand {
  override val name = "grant"
  override val usage = "/grant <player> developer|admin|none"
  override val description = "admin: gives a player developer or admin rights"
  override val permission = CharacterPermissions.ADMIN

  override suspend fun run(ctx: CommandContext) {
    val target = ctx.args.getOrNull(0) ?: return ctx.reply("Usage: $usage")
    val rank = ctx.args.getOrNull(1)?.lowercase() ?: return ctx.reply("Usage: $usage")
    val bits =
        when (rank) {
          "developer",
          "dev" -> CharacterPermissions.DEVELOPER
          "admin" -> CharacterPermissions.DEVELOPER or CharacterPermissions.ADMIN
          "none" -> 0
          else -> return ctx.reply("Usage: $usage")
        }
    val id =
        sessions.onlineCharacterIds().firstOrNull {
          characters.getCharacter(it)?.info?.name.equals(target, ignoreCase = true)
        } ?: return ctx.reply("$target is not online.")
    val info = characters.getCharacter(id)?.info ?: return ctx.reply("$target is not online.")
    val cleared =
        info.permissions and (CharacterPermissions.DEVELOPER or CharacterPermissions.ADMIN).inv()
    characters.updateCharacter(info.copy(permissions = cleared or bits))
    characters.flushCharacterAsync(id)
    log.info { "char=${ctx.characterId} set ${info.name}'s rank to $rank" }
    sessions.getByCharacterId(id)?.send(notice("${ctx.character.info.name} made you $rank."))
    ctx.reply("${info.name} is now $rank.")
  }
}
