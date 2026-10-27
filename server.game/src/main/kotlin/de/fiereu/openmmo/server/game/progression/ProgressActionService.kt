package de.fiereu.openmmo.server.game.progression

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.dialog.generated.hoenn.Misc as HoennMisc
import de.fiereu.openmmo.dialog.generated.kanto.Misc as KantoMisc
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket
import de.fiereu.openmmo.net.game.packets.LocalCharacterDeltaPacket
import de.fiereu.openmmo.net.game.packets.progress.ProgressAction
import de.fiereu.openmmo.net.game.packets.progress.ProgressActionPacket
import de.fiereu.openmmo.server.game.config.GameServerConfig
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.script.ScriptRunner
import de.fiereu.openmmo.server.game.services.FieldItemService
import de.fiereu.openmmo.server.game.services.notice
import de.fiereu.openmmo.server.game.session.OPEN_SHOP
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton

private val log = KotlinLogging.logger {}

/** "Please come again!" once a mart or decoration shop closes (the msgbox after pokemart). */
private object PleaseComeAgain : Script {
  override suspend fun run(ctx: ScriptContext) =
      if (Region.byWireValue(ctx.state.regionId.toByte()) == Region.HOENN) {
        ctx.say(HoennMisc.gText_PleaseComeAgain)
      } else {
        ctx.say(KantoMisc.Text_PleaseComeAgain)
      }
}

/** Handles [ProgressActionPacket] and the join / leave / step hooks of this branch. */
@Singleton
class ProgressActionService
@Inject
constructor(
    private val characters: CharacterStore,
    private val items: ItemRegistry,
    private val fieldItems: FieldItemService,
    private val progress: PlayerProgressService,
    private val scripts: ScriptProgress,
    private val decorations: DecorationService,
    private val daycare: DaycareService,
    private val escapeRope: EscapeRopeService,
    private val scriptRunner: ScriptRunner,
    private val config: GameServerConfig,
) {

  suspend fun onAction(event: PacketEvent<ProgressActionPacket>) {
    val session = event.session
    val state = session.attributes[PLAYER_STATE] ?: return
    val charId = state.characterId ?: return
    val p = event.packet
    when (p.action) {
      ProgressAction.USE_ON_MOVE ->
          fieldItems.handle(session, p.itemId.toInt(), p.monId, p.arg1, p.arg2)
      ProgressAction.LEARN_REPLY -> {
        fieldItems.answerLearn(charId, p.arg1)?.let { session.send(notice(it)) }
        progress.pushParty(session, charId)
        progress.push(session, charId)
      }
      ProgressAction.REGISTER_ITEM -> register(session, charId, p.itemId.toInt())
      ProgressAction.USE_REGISTERED -> {
        val id = characters.getCharacter(charId)?.storyVars?.get(ProgressKeys.REGISTERED_ITEM) ?: 0
        if (id == 0) session.send(notice("Register a KEY ITEM in the BAG first."))
        else fieldItems.handle(session, id, 0, -1, -1)
      }
      ProgressAction.DAYCARE_DEPOSIT -> scripts.answer(charId, p.monId)
      ProgressAction.DAYCARE_WITHDRAW -> scripts.answer(charId, p.arg1.toLong())
      ProgressAction.DAYCARE_CANCEL -> scripts.answer(charId, null)
      ProgressAction.DECOR_BUY -> {
        val refused = decorations.buy(charId, p.arg1, p.arg2)
        val decor = Gen3Data.decorations[p.arg1]
        session.send(
            notice(
                refused
                    ?: "Here you go! ${decor?.name ?: "It"} x${p.arg2} was sent to your PC."))
        sendMoney(session, charId)
        progress.push(session, charId)
      }
      ProgressAction.SHOP_CLOSED -> shopClosed(session, charId)
      ProgressAction.REFRESH -> progress.push(session, charId)
    }
  }

  private fun register(session: SessionContext, charId: Long, itemId: Int) {
    val stored = characters.getCharacter(charId) ?: return
    if (itemId != 0 && (stored.items[itemId] ?: 0) <= 0) {
      session.send(notice("You don't have that item."))
      return
    }
    characters.setStoryVar(charId, ProgressKeys.REGISTERED_ITEM, itemId)
    val name = items.get(itemId)?.name
    session.send(notice(if (name != null) "Registered the $name." else "Cancelled the registration."))
    progress.push(session, charId)
  }

  /** The mart or decoration window closed: the clerk's "Please come again!". */
  private fun shopClosed(session: SessionContext, charId: Long) {
    val state = session.attributes[PLAYER_STATE] ?: return
    val mart = session.attributes.remove(OPEN_SHOP)
    val decor = progress.closeDecorShop(charId)
    if (mart == null && !decor) return
    if (decor) progress.push(session, charId)
    if (state.inDialog) return
    scriptRunner.run(session, state, PleaseComeAgain, mart?.npcEntityId ?: -1)
  }

  private fun sendMoney(session: SessionContext, charId: Long) {
    val money = characters.getCharacter(charId)?.info?.money ?: return
    session.send(CurrencyBalancePacket(money, 0))
    session.send(LocalCharacterDeltaPacket(money = money))
  }

  /**
   * The character is in the world: the admin bootstrap account's characters get ADMIN and
   * DEVELOPER (so testers need no database edits), play time starts, the state goes out.
   */
  fun onJoin(session: SessionContext, charId: Long) {
    val stored = characters.getCharacter(charId) ?: return
    if (stored.info.userId in config.adminUserIds) {
      val perms =
          stored.info.permissions or CharacterPermissions.ADMIN or CharacterPermissions.DEVELOPER
      if (perms != stored.info.permissions) {
        characters.updateCharacter(stored.info.copy(permissions = perms))
        log.info { "char=$charId belongs to an admin account, granted ADMIN" }
      }
    }
    progress.onJoin(charId)
    progress.push(session, charId)
  }

  fun onLeave(charId: Long) {
    scripts.answer(charId, null)
    progress.onLeave(charId)
  }

  /** Every accepted step: the escape point, day care growth, eggs. */
  fun onStep(session: SessionContext, charId: Long, map: MapDef, x: Int, y: Int) {
    escapeRope.onStep(charId, map, x, y)
    val lines = daycare.onStep(charId)
    if (lines.isNotEmpty()) daycare.notifyHatch(session, charId, lines)
  }
}
