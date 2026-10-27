package de.fiereu.openmmo.client.net

import de.fiereu.network.PacketEvent
import de.fiereu.openmmo.client.gfx.GbaGame
import de.fiereu.openmmo.client.gfx.PretData
import de.fiereu.openmmo.client.world.DialogText
import de.fiereu.openmmo.client.world.DialogView
import de.fiereu.openmmo.client.world.Entity
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.client.world.ShopOffer
import de.fiereu.openmmo.client.world.ShopView
import de.fiereu.openmmo.client.world.WorldState
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.net.game.packets.DialogDataPacket
import de.fiereu.openmmo.net.game.packets.DialogStatePacket
import de.fiereu.openmmo.net.game.packets.EntityFaceTurnPacket
import de.fiereu.openmmo.net.game.packets.EntityInteractPacket
import de.fiereu.openmmo.net.game.packets.EntityPresencePacket
import de.fiereu.openmmo.net.game.packets.ExchangeItemRequestPacket
import de.fiereu.openmmo.net.game.packets.FieldItemUsePacket
import de.fiereu.openmmo.net.game.packets.LocalCharacterDeltaPacket
import de.fiereu.openmmo.net.game.packets.LocalPlayerStatePacket
import de.fiereu.openmmo.net.game.packets.MapTransitionPacket
import de.fiereu.openmmo.net.game.packets.NpcAnimationPacket
import de.fiereu.openmmo.net.game.packets.NpcSpawnPacket
import de.fiereu.openmmo.net.game.packets.NpcUpdatePacket
import de.fiereu.openmmo.net.game.packets.RenderScreenPacket
import de.fiereu.openmmo.net.game.packets.ShopCatalogPacket
import de.fiereu.openmmo.net.game.packets.ShopSellRequestPacket
import de.fiereu.openmmo.net.game.packets.SlotSwap
import de.fiereu.openmmo.net.game.packets.SwapInventorySlotsPacket
import de.fiereu.openmmo.net.game.packets.TileInteractPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSideAddPokemonPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSidePartyPacket
import de.fiereu.openmmo.net.game.packets.dialog.DialogActionPacket
import de.fiereu.openmmo.net.game.packets.dialog.DialogActionResponsePacket
import de.fiereu.openmmo.net.game.packets.dialog.TextPokemonSpeciesArg
import de.fiereu.openmmo.net.game.packets.dialog.TextStringArg
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val log = KotlinLogging.logger {}

/**
 * The overworld half of the game connection: npcs, scripted movement, dialog boxes and the mart.
 * [GameClient] hands it every packet it does not model itself, and it returns whether it took it.
 */
class OverworldClient(
    private val world: WorldState,
    private val send: (Any) -> Unit,
    private val onChange: () -> Unit,
) {
  /** The decomp checkouts; the dialog tables sit next to them under codegen/dialog. */
  @Volatile var dataRoot: File = File("decomp")

  private val text by lazy { DialogText(File(dataRoot.absoluteFile.parentFile, "codegen/dialog")) }
  private val pret = ConcurrentHashMap<GbaGame, PretData>()
  private val itemNames: List<String> by lazy {
    val f = File(dataRoot, "pokeblack/item_names.json")
    if (!f.exists()) emptyList()
    else Json.parseToJsonElement(f.readText()).jsonArray.map { it.jsonPrimitive.content }
  }
  private val speciesNames: Map<Int, String> by lazy {
    val f = File(dataRoot, "pokeemerald/include/constants/pokedex.h")
    if (!f.exists()) return@lazy emptyMap()
    // NATIONAL_DEX_ entries are an enum starting at NATIONAL_DEX_NONE = 0.
    Regex("""^\s*NATIONAL_DEX_(\w+),""", RegexOption.MULTILINE)
        .findAll(f.readText().substringAfter("NATIONAL_DEX_NONE"))
        .mapIndexed { i, m -> i + 1 to m.groupValues[1] }
        .toMap()
  }

  /** Steps of scripted movement still playing, per entity. */
  private val moving = ConcurrentHashMap<Long, Int>()
  private val timer: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "overworld-anim").apply { isDaemon = true }
      }

  /**
   * Where the server's movement the client is animating ends, so a late reset does not fight it.
   */
  fun selfBusy(): Boolean {
    val self = world.snapshot().selfId ?: return false
    return (moving[self] ?: 0) > 0
  }

  /**
   * Wire item ids are `region * 1000 + index` into item_names.json, always in the region 5 table.
   */
  fun itemName(id: Int): String =
      (if (id >= ITEM_REGION_BLOCK) itemNames.getOrNull(id - ITEM_REGION_BLOCK) else null) ?: "#$id"

  fun itemIdOf(name: String): Int? {
    name.toIntOrNull()?.let {
      return it
    }
    val want = norm(name)
    // Several indices share a name; the server names an item by its lowest.
    return itemNames
        .withIndex()
        .firstOrNull { it.index > 0 && norm(it.value) == want }
        ?.let { ITEM_REGION_BLOCK + it.index }
  }

  private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "").replace("é", "e")

  fun handle(event: PacketEvent<*>): Boolean {
    when (val p = event.packet) {
      is NpcSpawnPacket -> onNpcSpawn(p)
      is NpcUpdatePacket -> onNpcUpdate(p)
      is EntityFaceTurnPacket ->
          world.update(p.entityId) { it.copy(facing = dirOf(p.facing.toInt())) }
      is NpcAnimationPacket -> log.debug { "npc ${p.entityId} animation ${p.animation}" }
      is EntityPresencePacket -> log.debug { "entity ${p.entityId} presence ${p.status}" }
      is DialogDataPacket -> onScriptedMovement(p)
      is DialogActionPacket -> onDialog(p)
      is DialogStatePacket -> {
        world.overworld.setInScript(p.active)
        if (!p.active) world.overworld.setDialog(null)
      }
      is ShopCatalogPacket -> onShop(p)
      is LocalCharacterDeltaPacket -> p.money?.let(world.overworld::setMoney)
      is LocalPlayerStatePacket -> world.overworld.setMoney(p.money)
      is BattleSidePartyPacket -> {
        val stacks = p.pokemon.filter { it.entityId and 0xFFFF == ITEM_TAG }
        if (stacks.isEmpty() || stacks.size != p.pokemon.size) return false
        world.overworld.setBag(
            stacks.associate { it.frontSpriteId.toInt() to it.backSpriteId.toInt() })
      }
      is BattleSideAddPokemonPacket -> {
        val s = p.pokemon
        if (s.entityId and 0xFFFF != ITEM_TAG) return false
        world.overworld.setStack(s.frontSpriteId.toInt(), s.backSpriteId.toInt())
        val name = itemName(s.frontSpriteId.toInt())
        world.overworld.updateShop {
          it.copy(status = "$name x${s.backSpriteId}  ₽${world.overworld.view().money ?: 0}")
        }
      }
      is MapTransitionPacket -> world.overworld.setTransitioning(true)
      is RenderScreenPacket -> if (p.renderScreen) world.overworld.setTransitioning(false)
      else -> return false
    }
    onChange()
    return true
  }

  // --- npcs -------------------------------------------------------------------------------------

  private fun onNpcSpawn(p: NpcSpawnPacket) {
    world.upsert(
        Entity(
            id = p.entityId,
            name = "",
            map = MapKey(p.regionId, p.bankId, p.mapId),
            x = p.x,
            y = p.y,
            facing = dirOf(p.facing),
            isPlayer = false,
            graphicsId = p.graphicsId,
        ))
    log.debug { "npc ${p.entityId} gfx=${p.graphicsId} at (${p.x},${p.y})" }
  }

  private fun onNpcUpdate(p: NpcUpdatePacket) {
    // Captures send 0xF6 in facing with the real direction in the next byte.
    val facing = dirOf(if (p.facing == 0xF6) p.unk else p.facing)
    world.relocate(p.entityId, MapKey(p.regionId, p.bankId, p.mapId), p.x, p.y, facing)
  }

  /**
   * An applymovement sequence for one entity, despite the packet's name. Played step by step with
   * the server's own timings, so the cutscene the server waits on and the one shown line up.
   */
  private fun onScriptedMovement(p: DialogDataPacket) {
    val steps = p.data.map { it.toInt() and 0xFF }
    moving.merge(p.entityId, steps.size, Int::plus)
    world.overworld.setScriptLocked(selfBusy())
    var delay = 0L
    for (action in steps) {
      timer.schedule({ applyStep(p.entityId, action) }, delay, TimeUnit.MILLISECONDS)
      delay += stepDuration(action)
    }
  }

  private fun applyStep(entityId: Long, action: Int) {
    when (action) {
      in 0x00..0x03 -> world.update(entityId) { it.copy(facing = dirOf(action)) }
      in 0x10..0x13 -> walk(entityId, dirOf(action - 0x10))
      in 0x1D..0x20 -> walk(entityId, dirOf(action - 0x1D))
      0x23 -> world.update(entityId) { it.copy(facing = Direction.LEFT) }
      0x24 -> world.update(entityId) { it.copy(facing = Direction.RIGHT) }
      0x60 -> world.update(entityId) { it.copy(visible = false) }
      else -> {}
    }
    if (moving.computeIfPresent(entityId) { _, n -> (n - 1).takeIf { it > 0 } } == null) {
      moving.remove(entityId)
    }
    world.overworld.setScriptLocked(selfBusy())
    onChange()
  }

  private fun walk(entityId: Long, d: Direction) =
      world.update(entityId) {
        it.copy(x = it.x + d.dx, y = it.y + d.dy, facing = d, visible = true)
      }

  private fun stepDuration(action: Int): Long =
      when (action) {
        in 0x10..0x13 -> 250
        in 0x1D..0x24 -> 130
        else -> 120
      }

  // --- dialog -----------------------------------------------------------------------------------

  private fun onDialog(p: DialogActionPacket) {
    val snap = world.snapshot()
    val region = snap.map?.regionId?.let(GbaGame::forRegion)?.dialogRegion
    val raw =
        (region?.let { text.raw(it, p.textId) } ?: text.rawAnyRegion(p.textId))
            ?: if (p.textId == 0) "" else "[text 0x${p.textId.toString(16)}]"
    val vars = mutableMapOf("PLAYER" to (snap.characterName ?: "PLAYER"), "RIVAL" to "RIVAL")
    // A species argument fills the ROM string variable it names (1-based, STR_VAR_1..3).
    p.messageArgs.forEach {
      when (it) {
        is TextPokemonSpeciesArg ->
            vars["STR_VAR_${it.stringVariable.toInt().coerceIn(1, 3)}"] =
                speciesName(it.speciesId.toInt())
        is TextStringArg ->
            if (it.stringVariable == TextStringArg.RIVAL) vars["RIVAL"] = it.text
            else if (it.stringVariable in 1..3) vars["STR_VAR_${it.stringVariable}"] = it.text
        else -> {}
      }
    }
    val actionType = p.actionType.toInt() and 0xFF
    var choices = emptyList<String>()
    var values = emptyList<Int>()
    when (actionType) {
      YES_NO -> {
        choices = listOf("YES", "NO")
        values = listOf(1, 0)
      }
      PICK -> {
        val d = p.detail
        val n = d.getOrNull(0)?.toInt() ?: 0
        if (n > 0 && d.size >= 1 + 2 * n) {
          choices =
              List(n) {
                speciesName(
                    (d[1 + 2 * it].toInt() and 0xFF) or ((d[2 + 2 * it].toInt() and 0xFF) shl 8))
              }
          values = List(n) { it + 1 }
        }
      }
    }
    val view =
        DialogView(
            seq = p.flags.toInt() and 0xFF,
            actionType = actionType,
            textId = p.textId,
            entityId = p.entityId,
            pages = DialogText.paginate(raw, vars),
            choices = choices,
            choiceValues = values,
        )
    log.info {
      "[dialog] ${view.pages.flatten().joinToString(" / ")}" +
          if (choices.isEmpty()) "" else " ${choices}"
    }
    world.overworld.setDialog(view)
    world.overworld.setInScript(true)
    // A box that faces an npc turns the player toward it is the server's job; nothing to do here.
  }

  private fun speciesName(dex: Int): String =
      BattleNames.species(dex).takeUnless { it.startsWith("#") } ?: speciesNames[dex] ?: "#$dex"

  /** A: next page, or answer the box. Returns false if no box is open. */
  fun advance(): Boolean {
    val d = world.overworld.view().dialog ?: return false
    if (!d.onLastPage) {
      world.overworld.updateDialog { it.copy(page = it.page + 1) }
      onChange()
      return true
    }
    val value = if (d.choices.isEmpty()) 0 else d.choiceValues.getOrElse(d.cursor) { 0 }
    answer(d, value)
    return true
  }

  fun moveCursor(delta: Int) {
    world.overworld.updateDialog {
      if (it.choices.isEmpty() || !it.onLastPage) it
      else it.copy(cursor = (it.cursor + delta).mod(it.choices.size))
    }
    world.overworld.updateShop {
      if (it.offers.isEmpty()) it else it.copy(cursor = (it.cursor + delta).mod(it.offers.size))
    }
    onChange()
  }

  /** Picks choice [index] (0-based) of the open box, skipping to its last page first. */
  fun choose(index: Int): Boolean {
    val d = world.overworld.view().dialog ?: return false
    if (d.choices.isEmpty()) return false
    answer(d, d.choiceValues.getOrElse(index) { 0 })
    return true
  }

  /** B on a box. Yes/no answers no; a plain box just advances. */
  fun cancel(): Boolean {
    if (world.overworld.view().shop != null) {
      closeShop()
      return true
    }
    val d = world.overworld.view().dialog ?: return false
    if (d.actionType == YES_NO && d.onLastPage) answer(d, 0) else advance()
    return true
  }

  private fun answer(d: DialogView, value: Int) {
    // The server sends the next box or a close; until then nothing is shown.
    world.overworld.setDialog(null)
    send(DialogActionResponsePacket(d.seq, value))
    onChange()
  }

  // --- interaction ------------------------------------------------------------------------------

  /**
   * The A button in the field: an npc on the tile in front, or across a counter, gets an entity
   * interaction; anything else is a tile interaction the server resolves against its bg events.
   */
  fun interact(): Boolean {
    val view = world.overworld.view()
    if (view.dialog != null) return advance()
    if (view.shop != null) return false
    val snap = world.snapshot()
    val self = snap.self ?: return false
    val key = snap.map ?: return false
    val fx = self.x + self.facing.dx
    val fy = self.y + self.facing.dy
    val npcs = snap.entities.values.filter { !it.isPlayer && it.visible && it.map == key }
    var target = npcs.firstOrNull { it.x == fx && it.y == fy }
    if (target == null && behaviorAt(key, fx, fy) == COUNTER) {
      target = npcs.firstOrNull { it.x == fx + self.facing.dx && it.y == fy + self.facing.dy }
    }
    if (target != null) {
      log.info {
        "interact with npc ${target.id} gfx=${target.graphicsId} at (${target.x},${target.y})"
      }
      // Like the decomp's faceplayer: the npc turns to whoever spoke to it. Only this client sees
      // it.
      world.update(target.id) { it.copy(facing = self.facing.opposite()) }
      send(EntityInteractPacket(target.id, 0))
    } else {
      log.info { "interact with tile ($fx,$fy)" }
      send(TileInteractPacket())
    }
    return true
  }

  private fun behaviorAt(key: MapKey, x: Int, y: Int): Int? {
    val game = GbaGame.forRegion(key.regionId) ?: return null
    val data = pret.getOrPut(game) { PretData(dataRoot, game) }
    val layout = data.layout(key.bankId, key.mapId) ?: return null
    val id = layout.metatileAt(x, y) ?: return null
    val attrs =
        if (id < game.metatilesInPrimary) layout.primary.attributes.getOrNull(id)
        else layout.secondary.attributes.getOrNull(id - game.metatilesInPrimary)
    return attrs?.let(game::behavior)
  }

  // --- shop -------------------------------------------------------------------------------------

  private fun onShop(p: ShopCatalogPacket) {
    if (p.kind != ShopCatalogPacket.OPEN) {
      world.overworld.setShop(null)
      return
    }
    val offers = p.items.map { ShopOffer(it.itemId.toInt(), itemName(it.itemId.toInt()), it.price) }
    log.info { "[shop] ${offers.joinToString { "${it.name} ¥${it.price}" }}" }
    // The clerk's greeting box is replaced by the shop window, as on the GBA.
    world.overworld.setDialog(null)
    world.overworld.setInScript(false)
    world.overworld.setShop(ShopView(p.npcEntityId ?: -1, offers))
  }

  fun buy(itemId: Int, quantity: Int): Boolean {
    if (world.overworld.view().shop == null) return false
    send(ExchangeItemRequestPacket(itemId.toShort(), quantity.toShort(), 0))
    return true
  }

  fun sell(itemId: Int, quantity: Int): Boolean {
    if (world.overworld.view().shop == null) return false
    send(ShopSellRequestPacket((itemId.toLong() shl 16) or ITEM_TAG, quantity.toShort()))
    return true
  }

  /** Use bag item [itemId] in the field, on party monster [pokemonId] (0 for none, a Repel). */
  fun useItem(itemId: Int, pokemonId: Long): Boolean {
    if (world.overworld.view().dialog != null) return false
    send(FieldItemUsePacket(itemId.toShort(), pokemonId))
    return true
  }

  /**
   * Move a monster: list types are [LIST_PARTY] and [LIST_PC], slots are party indices or PC slots,
   * a destination of -1 means "anywhere free". The server answers with both containers.
   */
  fun moveMonster(fromList: Int, fromSlot: Int, toList: Int, toSlot: Int) {
    send(
        SwapInventorySlotsPacket(
            listOf(
                SlotSwap(
                    fromList.toByte(), fromSlot.toShort(), toList.toByte(), toSlot.toShort()))))
  }

  fun closeShop() {
    world.overworld.setShop(null)
    onChange()
  }

  /** Stops the scripted-movement timer; for embedders that run many clients in one process. */
  fun shutdown() {
    timer.shutdownNow()
  }

  companion object {
    const val LIST_PC = 0
    const val LIST_PARTY = 1
    private const val YES_NO = 0x05
    private const val PICK = 0x23
    private const val ITEM_TAG = 0x5000L
    private const val ITEM_REGION_BLOCK = 5000
    private const val COUNTER = 0x80

    private fun dirOf(i: Int): Direction =
        when (i and 0x3) {
          1 -> Direction.UP
          2 -> Direction.LEFT
          3 -> Direction.RIGHT
          else -> Direction.DOWN
        }
  }
}
