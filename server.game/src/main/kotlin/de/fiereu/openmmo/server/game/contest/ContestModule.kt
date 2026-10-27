package de.fiereu.openmmo.server.game.contest

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.net.game.packets.contest.ContestActionPacket
import de.fiereu.openmmo.server.game.battle.ItemNames
import de.fiereu.openmmo.server.game.script.ScriptSpecial
import de.fiereu.openmmo.server.game.services.WarpService
import de.fiereu.openmmo.server.game.services.command.ChatCommand
import de.fiereu.openmmo.server.game.services.command.CommandContext
import de.fiereu.openmmo.server.game.services.storyItemStacksPacket
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val log = KotlinLogging.logger {}

/** Routes c2s 0xD5 ContestActionPacket to the lane's services (PROTOCOL.md "r5-contests"). */
@Singleton
class ContestActionService
@Inject
constructor(
    private val lane: ContestLane,
    private val items: ItemRegistry,
    private val maps: de.fiereu.openmmo.maps.MapManager,
) {
  init {
    lane.contests.heldIs = { held, def -> items.get(held) == def }
  }

  suspend fun onAction(event: PacketEvent<ContestActionPacket>) {
    val session = event.session
    val state = session.attributes[PLAYER_STATE] ?: return
    val charId = state.characterId ?: return
    if (event.packet.json.length > MAX_JSON) return
    val msg =
        try {
          Json.parseToJsonElement(event.packet.json).jsonObject
        } catch (e: Exception) {
          log.debug { "bad contest action: ${e.message}" }
          return
        }
    val t = msg.str("t") ?: return
    val kind = t.substringBefore('.')
    when {
      t == "contest.move" || t == "contest.ready" -> lane.contests.onAction(charId, msg)
      t == "blender.press" || t == "blender.start" -> lane.blender.onAction(charId, msg)
      kind == "pokeblocks" -> pokeblockAction(session, charId, t, msg)
      t == "berry.trees" -> lane.trees.sendMapTrees(session, state)
      t == "conditions" ->
          lane.docs.send(
              session,
              "conditions",
              lane.stats.conditionsJson(lane.characters.getCharacter(charId)?.pokemon.orEmpty()))
      kind in ANSWERS -> lane.docs.answer(session, kind, msg)
    }
  }

  private suspend fun pokeblockAction(
      session: SessionContext,
      charId: Long,
      t: String,
      msg: JsonObject
  ) {
    when (t) {
      "pokeblocks.open" -> {
        if (!lane.trees.hasItem(charId, "POKEBLOCK_CASE"))
            return lane.docs.send(session, "pokeblocks") {
              put("open", kotlinx.serialization.json.JsonPrimitive(false))
              put(
                  "message",
                  kotlinx.serialization.json.JsonPrimitive("You don't have a POKéBLOCK CASE."))
            }
        lane.pokeblocks.sendCase(session, charId, open = true)
      }
      "pokeblocks.feed" -> {
        val slot = msg.int("slot") ?: return
        val mon = msg.long("mon") ?: msg.str("mon")?.toLongOrNull() ?: return
        val (text, _) = lane.pokeblocks.feed(charId, slot, mon)
        lane.pokeblocks.sendCase(session, charId, open = true, message = text)
      }
      "pokeblocks.toss" -> {
        val slot = msg.int("slot") ?: return
        lane.pokeblocks.toss(charId, slot)
        lane.pokeblocks.sendCase(
            session, charId, open = true, message = "The POKéBLOCK was thrown away.")
      }
      else ->
          // pokeblocks.close / pokeblocks.give answer a script that opened the case
          if (!lane.docs.answer(session, "pokeblocks", msg)) lane.docs.close(session, "pokeblocks")
    }
  }

  fun onDisconnect(session: SessionContext, charId: Long) {
    lane.docs.cancelAll(session)
    lane.contests.onLeave(charId)
    leaveHall(charId)
    lane.blender.onLeave(charId)
    lane.pokeblocks.onLeave(charId)
    lane.trees.onLeave(charId)
  }

  /**
   * Leaving mid-contest: the hall has no exits (the hall script's SetExitWarp is the way out), so
   * the player comes back at the lobby counter (14, 4) and the hall state is cleared.
   */
  private fun leaveHall(charId: Long) {
    val stored = lane.characters.getCharacter(charId) ?: return
    val region = stored.info.positionRegionId.toInt()
    val hall = maps.getMapByName(region, "ContestHall") ?: return
    val lobby = maps.getMapByName(region, "LilycoveCity_ContestLobby") ?: return
    if (stored.info.positionBankId != hall.bankId || stored.info.positionMapId != hall.mapId) return
    lane.characters.setStoryVar(charId, "hoenn/VAR_CONTEST_HALL_STATE", 0)
    lane.characters.updatePosition(
        charId, 14, 4, lobby.bankId, lobby.mapId, de.fiereu.openmmo.common.enums.Direction.DOWN)
  }

  companion object {
    const val MAX_JSON = 4096
    private val ANSWERS = setOf("contestEntry", "blender", "berryChoose", "painting", "pokeblocks")
  }
}

/** The evolution engine's view of a monster's beauty (EVO_BEAUTY: Feebas into Milotic). */
fun interface MonBeauty {
  fun beautyOf(monId: Long): Int
}

/**
 * Developer shortcuts for testing the contest lane: `/contestdev kit` (case, pail, berries), `stats
 * c b cu s t sheen` (the lead monster's conditions), `ribbon cat rank`, `block spicy dry sweet
 * bitter sour feel`, `grow hours` (berry trees), `goto lobby|museum|route104|route123`.
 */
@Singleton
class ContestDevCommand
@Inject
constructor(
    private val lane: ContestLane,
    private val items: ItemRegistry,
    private val characters: CharacterStore,
    private val maps: MapManager,
    private val warp: WarpService,
) : ChatCommand {
  override val name = "contestdev"
  override val usage =
      "/contestdev kit | stats c b cu s t sheen | ribbon cat rank | block s d sw b so feel | grow hours | goto lobby|museum|route104"
  override val description = "contests, Pokéblocks and berry trees for testing"
  override val permission = CharacterPermissions.DEVELOPER

  override suspend fun run(ctx: CommandContext) {
    val charId = ctx.characterId
    val a = ctx.args
    val lead =
        characters
            .getCharacter(charId)
            ?.pokemon
            ?.sortedBy { it.containerSlot }
            ?.firstOrNull { !it.isEgg }
    when (a.firstOrNull()) {
      "kit" -> {
        val give =
            listOf("POKEBLOCK_CASE" to 1, "WAILMER_PAIL" to 1, "RARE_CANDY" to 3) +
                lane.data.berries.take(20).map { it.item.removePrefix("ITEM_") to 5 }
        for ((n, q) in give) {
          val def = ItemNames.byDecompName(n) ?: continue
          val id = items.idOrNull(def) ?: continue
          if (n.endsWith("BERRY") ||
              n == "RARE_CANDY" ||
              (characters.getCharacter(charId)?.items?.get(id) ?: 0) == 0)
              characters.addItem(charId, id, q)
        }
        characters.getCharacter(charId)?.items?.let { ctx.session.send(storyItemStacksPacket(it)) }
        characters.setStoryFlag(charId, "hoenn/FLAG_RECEIVED_POKEBLOCK_CASE")
        ctx.reply("POKéBLOCK CASE, WAILMER PAIL and berries added.")
      }
      "stats" -> {
        val m = lead ?: return ctx.reply("No monster.")
        val n = a.drop(1).map { it.toIntOrNull()?.coerceIn(0, 255) ?: 0 } + List(6) { 0 }
        val c =
            lane.stats
                .get(m.id)
                .copy(
                    cool = n[0],
                    beauty = n[1],
                    cute = n[2],
                    smart = n[3],
                    tough = n[4],
                    sheen = n[5])
        lane.stats.set(m.id, c)
        ctx.reply(
            "${m.nickname}: cool ${c.cool} beauty ${c.beauty} cute ${c.cute} smart ${c.smart} tough ${c.tough} sheen ${c.sheen}")
      }
      "ribbon" -> {
        val m = lead ?: return ctx.reply("No monster.")
        val cat = a.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 4) ?: 0
        val r = a.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 4) ?: 0
        lane.stats.set(m.id, lane.stats.get(m.id).withRibbon(cat, r))
        ctx.reply("${m.nickname}: ${Contest.CATEGORY_NAMES[cat]} ribbon $r")
      }
      "block" -> {
        val n = a.drop(1).map { it.toIntOrNull()?.coerceIn(0, 255) ?: 0 } + List(6) { 0 }
        val f = intArrayOf(n[0], n[1], n[2], n[3], n[4])
        val color = BlenderEngine.pokeblockColor(listOf(1, 2), f, 0)
        val ok = lane.pokeblocks.add(charId, Pokeblock(color, n[0], n[1], n[2], n[3], n[4], n[5]))
        ctx.reply(if (ok) "Pokéblock added." else "The case is full.")
      }
      "grow" -> {
        val h = a.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 24 * 30) ?: 1
        lane.trees.fastForward(charId, h * 60)
        lane.trees.sendMapTrees(ctx.session, ctx.state)
        ctx.reply("Berry trees grew $h hours.")
      }
      "goto" -> {
        val (map, x0, y0) =
            when (a.getOrNull(1)) {
              "museum" -> Triple("LilycoveCity_LilycoveMuseum_2F", 11, 9)
              "route104" -> Triple("Route104", 13, 21)
              "route123" -> Triple("Route123", 68, 11)
              else -> Triple("LilycoveCity_ContestLobby", 14, 4)
            }
        val x = a.getOrNull(2)?.toIntOrNull() ?: x0
        val y = a.getOrNull(3)?.toIntOrNull() ?: y0
        val facing =
            when (a.getOrNull(4)) {
              "down" -> Direction.DOWN
              "left" -> Direction.LEFT
              "right" -> Direction.RIGHT
              else -> Direction.UP
            }
        val def = maps.getMapByName(1, map) ?: return ctx.reply("No map $map")
        warp.executeWarp(
            ctx.session,
            charId,
            de.fiereu.openmmo.maps.WarpTile(
                x = 0,
                y = 0,
                targetRegionId = def.regionId,
                targetBankId = def.bankId,
                targetMapId = def.mapId,
                targetX = x,
                targetY = y,
                exitFacing = facing))
      }
      else -> ctx.reply(usage)
    }
  }
}

/** The r5-contests bindings, the one module the component lists. */
@Module
interface ContestModule {
  @Binds @IntoSet fun contestSpecials(h: ContestSpecials): ScriptSpecial

  @Binds
  @IntoSet
  fun wailmerPail(h: WailmerPailFromBag): de.fiereu.openmmo.server.game.services.BagItemScript

  @Binds @IntoSet fun contestDevCommand(c: ContestDevCommand): ChatCommand

  @Binds fun contestRepository(impl: JooqContestRepository): ContestRepository

  companion object {
    @Provides
    @Singleton
    fun monBeauty(stats: ContestStatsService): MonBeauty = MonBeauty {
      stats.getBlocking(it).beauty
    }
  }
}
