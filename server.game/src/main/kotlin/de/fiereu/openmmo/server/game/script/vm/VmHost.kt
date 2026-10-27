package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.maps.NpcDef
import de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket
import de.fiereu.openmmo.net.game.packets.LocalCharacterDeltaPacket
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.dialog.TextStringArg
import de.fiereu.openmmo.net.game.packets.field.FieldAvatarPacket
import de.fiereu.openmmo.net.game.packets.field.TravelMode
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.ItemNames
import de.fiereu.openmmo.server.game.field.FlyDestinations
import de.fiereu.openmmo.server.game.progression.HealLocations
import de.fiereu.openmmo.server.game.script.MovementStep
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.services.DialogPresentation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}

/*
 * What the interpreter does to the world, on top of the ScriptContext a hand-ported script uses.
 * Kept here so ScriptContext only grows the small public api the specials need.
 */

private const val SIGN = 3
private const val NPC = 4

/** The dialog id of the region's dynamic line, whose whole text is `{STR_VAR_9}`. */
fun dynamicTextId(region: String): Int = if (region == "hoenn") 0x1EFFFFFF else 0x0EFFFFFF

internal fun ScriptContext.vmShowText(t: ScriptText, sign: Boolean): CompletableDeferred<Unit> {
  val vm = vm
  val region = vm?.img?.region ?: "kanto"
  var id = t.textId
  var args = messageArgs()
  if (id == 0) {
    id = dynamicTextId(region)
    args = args + TextStringArg(DYNAMIC_STR_VAR, t.content.substringBefore('$'))
  }
  return dialog.show(
      session,
      state,
      id,
      if (sign || entityId < 0) SIGN else NPC,
      entityId,
      DialogPresentation(messageArgs = args))
}

/** TextStringArg variable our clients fill `{STR_VAR_9}` from (ours, the GBA has three). */
const val DYNAMIC_STR_VAR: Byte = 9

internal fun ScriptContext.vmCloseText(send: Boolean) {
  if (send) dialog.hideBox(session)
  else session.attributes.remove(de.fiereu.openmmo.server.game.session.PENDING_DIALOG)
}

internal suspend fun ScriptContext.vmYesNo(textId: Int): Boolean {
  session.attributes.remove(de.fiereu.openmmo.server.game.session.PENDING_DIALOG)
  return dialog.askYesNo(session, state, textId, entityId, messageArgs())
}

/** A list menu (multichoice / elevator): the chosen index, or [cancel] for B. */
internal suspend fun ScriptContext.vmMenu(
    kind: String,
    items: List<String>,
    default: Int,
    cancel: Int,
    columns: Int,
): Int {
  session.attributes.remove(de.fiereu.openmmo.server.game.session.PENDING_DIALOG)
  val ui = services?.ui ?: return cancel
  val raw =
      ui.ask(session, kind, if (cancel < 0) 0 else cancel) {
        put("items", JsonArray(items.map { JsonPrimitive(it.substringBefore('$')) }))
        put("default", default)
        put("cancel", cancel >= 0)
        put("columns", columns)
      }
  return if (raw < 0 || raw >= items.size) (if (cancel < 0) 0 else cancel) else raw
}

private val ScriptContext.charId: Long?
  get() = state.characterId

internal fun ScriptContext.vmCurrentMap() = maps?.getMap(state.regionId, state.bankId, state.mapId)

/**
 * The decomp's object local ids are 1-based (object_events index + 1); the server's npc entityIdx
 * is the 0-based index. Every vm* function below takes the decomp's id.
 */
internal fun npcIdx(localId: Int): Int = localId - 1

internal fun ScriptContext.vmMapNpc(localId: Int): NpcDef? =
    vmCurrentMap()?.npcs?.firstOrNull { it.entityIdx == npcIdx(localId) }

/** The server's bank of decomp map group [group] (Hoenn's groups are numbered from 50). */
internal fun ScriptContext.vmBank(group: Int): Int =
    group + if (state.regionId == 1) HOENN_BANK_OFFSET else 0

const val HOENN_BANK_OFFSET = 50

internal fun ScriptContext.vmOnMap(group: Int, num: Int) =
    state.bankId == vmBank(group) && state.mapId == num

internal suspend fun ScriptContext.vmMoveNpc(
    localId: Int,
    from: ObjPose,
    steps: List<MovementStep>
) {
  moveNpc(npcIdx(localId), *steps.toTypedArray())
}

internal suspend fun ScriptContext.vmEmote(localId: Int, kind: String) {
  val id =
      if (localId == ScriptVm.OBJ_PLAYER) state.characterId ?: return
      else movement.npcEntityId(state, npcIdx(localId)) ?: return
  session.send(FieldAvatarPacket(id, TravelMode.WALK, exclaim = true))
  services?.ui?.send(session, "emote") {
    put("entity", id.toString())
    put("kind", kind)
  }
  delay(EMOTE_MS)
}

private const val EMOTE_MS = 600L

internal fun ScriptContext.vmShowObject(localId: Int, pose: ObjPose) {
  state.removedObjects.remove(npcIdx(localId))
  showNpcAt(npcIdx(localId), pose.x, pose.y)
}

internal suspend fun ScriptContext.vmHideObject(localId: Int) {
  moveNpc(npcIdx(localId), MovementStep.SET_INVISIBLE)
}

/** removeobject: despawns the npc and sets its map.json flag, like the decomp. */
internal fun ScriptContext.vmRemoveObject(localId: Int, immediate: Boolean) {
  val npc = vmMapNpc(localId)
  if (npc != null && npc.hideFlag.isNotBlank()) setFlag(npc.hideFlag)
  if (!immediate) removeNpc(npcIdx(localId))
}

internal fun ScriptContext.vmSetObjectFlag(group: Int, num: Int, localId: Int) {
  val map = maps?.getMap(state.regionId, vmBank(group), num) ?: return
  val npc = map.npcs.firstOrNull { it.entityIdx == npcIdx(localId) } ?: return
  if (npc.hideFlag.isNotBlank()) setFlag(npc.hideFlag)
}

internal suspend fun ScriptContext.vmTurnNpc(localId: Int, d: Direction) {
  moveNpc(npcIdx(localId), de.fiereu.openmmo.server.game.script.vm.VmMovement.faceStep(d))
}

internal fun ScriptContext.vmTurnSelf(d: Direction) {
  repositionSelf(state.x.toInt(), state.y.toInt(), d)
}

/** Where a warp to (group, num) lands: warp event [warpId]'s tile, or (x, y). */
internal fun ScriptContext.vmWarpDestination(
    group: Int,
    num: Int,
    warpId: Int,
    x: Int,
    y: Int
): Pair<Int, Int>? {
  val map = maps?.getMap(state.regionId, vmBank(group), num) ?: return null
  if (warpId != 0xFF && warpId != 0x7F) {
    map.warps.getOrNull(warpId)?.let {
      return it.x to it.y
    }
  }
  return x to y
}

internal suspend fun ScriptContext.vmWarp(
    group: Int,
    num: Int,
    warpId: Int,
    x: Int,
    y: Int,
    kind: String
) {
  var g = group
  var n = num
  var tx = x
  var ty = y
  if (g == 0x7F && n == 0x7F) {
    // MAP_DYNAMIC: wherever setdynamicwarp pointed.
    val dyn = state.characterId?.let { characters?.getCharacter(it) }?.info?.dynamicWarp ?: return
    g = dyn.bankId.toInt() and 0xFF
    n = dyn.mapId.toInt() and 0xFF
    tx = dyn.x.toInt()
    ty = dyn.y.toInt()
  } else {
    val dest = vmWarpDestination(g, n, warpId, x, y) ?: return
    tx = dest.first
    ty = dest.second
    g = vmBank(g)
  }
  log.info { "script $kind to ${state.regionId}:$g:$n ($tx,$ty)" }
  warp(state.regionId, g, n, tx, ty, state.facingDirection)
}

internal fun ScriptContext.vmHoleDestination(group: Int, num: Int): IntArray? =
    intArrayOf(group, num, state.x.toInt(), state.y.toInt())

// ------------------------------------------------------------------ party, items, money

internal fun ScriptContext.vmParty(): List<Pokemon> =
    charId
        ?.let { characters?.getCharacter(it) }
        ?.pokemon
        ?.filter { it.container == PokemonContainer.PARTY }
        ?.sortedBy { it.containerSlot }
        .orEmpty()

internal fun ScriptContext.vmItemDef(gbaItem: Int): ItemDef? {
  val img = vm?.img ?: return null
  val name = img.nameOf("ITEM_", gbaItem) ?: return null
  return ItemNames.byDecompName(name.removePrefix("ITEM_"))
      ?: ItemNames.byDecompName(name.removePrefix("ITEM_").substringBefore('_'))
      ?: ItemNames.byGbaId(gbaItem)
}

/**
 * Items the wire catalogue (the Gen 5 table the clients speak) has no entry for, FRLG's SILPH
 * SCOPE, LIFT KEY, TRI-PASS... live in a story var per item, so scripts still find them.
 */
private fun ScriptContext.offCatalogueKey(gbaItem: Int): String? =
    vm?.img?.nameOf("ITEM_", gbaItem)?.let { "${vm!!.img.region}/OPENMMO_${it}" }

internal suspend fun ScriptContext.vmAddItem(gbaItem: Int, qty: Int): Boolean {
  if (qty == 0) return true
  val def = vmItemDef(gbaItem)
  if (def == null) {
    val key = offCatalogueKey(gbaItem) ?: return false
    val have = getVar(key)
    if (have + qty < 0) return false
    setVar(key, have + qty)
    log.info { "off-catalogue item $key now ${have + qty}" }
    return true
  }
  if (qty < 0 && vmItemCount(gbaItem) < -qty) return false
  return giveItem(def, qty)
}

internal fun ScriptContext.vmItemCount(gbaItem: Int): Int {
  val def = vmItemDef(gbaItem) ?: return offCatalogueKey(gbaItem)?.let(::getVar) ?: 0
  val items = services?.items ?: return 0
  val stored = charId?.let { characters?.getCharacter(it) } ?: return 0
  return items.idsOf(def).sumOf { stored.items[it] ?: 0 }
}

/** POCKET_* of an item (1 items, 2 balls, 3 TMs, 4 berries, 5 key items). */
internal fun ScriptContext.vmItemPocket(gbaItem: Int): Int {
  val name = vm?.img?.nameOf("ITEM_", gbaItem) ?: return 1
  return when {
    name.endsWith("_BALL") -> 2
    name.startsWith("ITEM_TM") || name.startsWith("ITEM_HM") -> 3
    name.endsWith("_BERRY") -> 4
    gbaItem >= KEY_ITEMS_FROM -> 5
    else -> 1
  }
}

private const val KEY_ITEMS_FROM = 259

internal fun ScriptContext.vmItemName(gbaItem: Int, qty: Int): String {
  val def = vmItemDef(gbaItem)
  val base =
      def?.name?.uppercase()
          ?: vm?.img?.nameOf("ITEM_", gbaItem)?.removePrefix("ITEM_")?.replace('_', ' ')
          ?: "?"
  return if (qty > 1 && !base.endsWith("S")) base + "S" else base
}

internal fun ScriptContext.vmMoveName(move: Int): String =
    services?.moves?.get(move)?.name?.uppercase() ?: "MOVE"

internal fun ScriptContext.vmDecorationName(id: Int): String =
    vm?.img?.nameOf("DECOR_", id)?.removePrefix("DECOR_")?.replace('_', ' ') ?: "DECORATION"

internal fun ScriptContext.vmAddDecoration(id: Int, delta: Int): Boolean {
  val c = charId ?: return false
  val key = "openmmo/DECOR_$id"
  val have = characters?.getCharacter(c)?.storyVars?.get(key) ?: 0
  if (have + delta < 0) return false
  characters?.setStoryVar(c, key, have + delta)
  return true
}

internal fun ScriptContext.vmDecorationCount(id: Int): Int =
    charId?.let { characters?.getCharacter(it) }?.storyVars?.get("openmmo/DECOR_$id") ?: 0

internal fun ScriptContext.vmTrainerName(id: Int): String =
    vmRegion().let { services?.trainers?.get(it, id)?.name } ?: "TRAINER"

internal fun ScriptContext.vmTrainerClassName(id: Int): String =
    vmRegion().let { services?.trainers?.get(it, id)?.className?.replace('_', ' ') } ?: ""

internal fun ScriptContext.vmRegion(): Region =
    Region.byWireValue(state.regionId.toByte()) ?: Region.KANTO

internal fun ScriptContext.vmMonName(slot: Int): String {
  val mon = vmParty().getOrNull(slot) ?: return ""
  return mon.nickname.ifBlank { services?.species?.get(mon.dexId)?.name?.uppercase() ?: "" }
}

/** givemon / giveegg: 0 to the party, 1 to the PC, 2 nowhere (MON_GIVEN_TO_*). */
internal suspend fun ScriptContext.vmGiveMon(
    dex: Int,
    level: Int,
    gbaItem: Int,
    egg: Boolean
): Int {
  val c = charId ?: return 2
  val store = characters ?: return 2
  val party = vmParty()
  if (party.size < 6) {
    val mon = givePokemon(dex, level) ?: return 2
    val held =
        if (gbaItem != 0) vmItemDef(gbaItem)?.let { services?.items?.idOrNull(it) } ?: 0 else 0
    if (egg || held != 0) {
      store.updatePokemon(c, mon.copy(isEgg = egg, heldItem = held))
      session.send(
          PokemonContainerPacket(
              container = PokemonContainer.PARTY,
              hasChange = true,
              delete = false,
              pokemon = vmParty()))
    }
    if (!egg) store.registerSeen(c, listOf(dex))
    return 0
  }
  val factory = services?.monFactory ?: return 2
  val rolled = factory.create(dex, level, BattleRng()) ?: return 2
  val stored = store.getCharacter(c) ?: return 2
  val slot = (stored.pcStorage.maxOfOrNull { it.containerSlot.toInt() } ?: -1) + 1
  val mon =
      rolled.copy(
          ownerId = c,
          container = PokemonContainer.PC,
          containerSlot = slot.toShort(),
          ot = stored.info.name,
          isEgg = egg)
  return if (store.addPokemon(c, mon)) 1 else 2
}

internal fun ScriptContext.vmSetMonMove(slot: Int, moveSlot: Int, move: Int) {
  val c = charId ?: return
  val mon = vmParty().getOrNull(slot) ?: return
  val pp = services?.moves?.get(move)?.pp ?: 0
  val moves = mon.moves.toMutableList()
  while (moves.size <= moveSlot) moves += de.fiereu.openmmo.common.PokemonMove(0, 0)
  moves[moveSlot] = de.fiereu.openmmo.common.PokemonMove(move.toShort(), pp.toByte())
  characters?.updatePokemon(c, mon.copy(moves = moves.take(4)))
}

internal fun ScriptContext.vmSetFateful(slot: Int) {
  val c = charId ?: return
  val mon = vmParty().getOrNull(slot) ?: return
  characters?.updatePokemon(c, mon.copy(isFatefulEncounter = true))
}

internal fun ScriptContext.vmIsFateful(slot: Int): Boolean =
    vmParty().getOrNull(slot)?.isFatefulEncounter ?: false

internal fun ScriptContext.vmMoney(): Int =
    charId?.let { characters?.getCharacter(it) }?.info?.money ?: 0

internal suspend fun ScriptContext.vmAddMoney(delta: Int): Boolean {
  val c = charId ?: return false
  val store = characters ?: return false
  val have = vmMoney()
  val amount = if (have + delta < 0) -have else delta
  if (!store.addMoney(c, amount)) return false
  val money = vmMoney()
  session.send(CurrencyBalancePacket(money, 0))
  session.send(LocalCharacterDeltaPacket(money = money))
  return true
}

internal suspend fun ScriptContext.vmAddCoins(delta: Int): Boolean = addCoins(delta)

internal suspend fun ScriptContext.vmWildBattle(
    dex: Int,
    level: Int,
    gbaItem: Int,
    legendary: Boolean
): BattleResult {
  val name =
      if (gbaItem != 0) vm?.img?.nameOf("ITEM_", gbaItem)?.removePrefix("ITEM_") ?: "" else ""
  val battles = services?.battles ?: return BattleResult.FAILED
  charId?.let { characters?.registerSeen(it, listOf(dex)) }
  return battles.startScriptedWildBattle(session, dex, level, name)
}

internal fun ScriptContext.vmMart(gbaItems: List<Int>) {
  val defs = gbaItems.mapNotNull { vmItemDef(it) }
  if (defs.isNotEmpty()) pokemart(*defs.toTypedArray())
}

internal fun ScriptContext.vmSetMetatile(x: Int, y: Int, tile: Int, impassable: Boolean) {
  val vm = vm ?: return
  // Script coordinates are map coordinates; ScrCmd_setmetatile adds MAP_OFFSET itself.
  val mx = x
  val my = y
  vm.state.metatiles[(my shl 16) or (mx and 0xFFFF)] = tile to impassable
  if (vm.immediate) return
  services?.ui?.send(session, "metatile") {
    put("x", mx)
    put("y", my)
    put("tile", tile)
    put("impassable", impassable)
  }
}

internal fun ScriptContext.vmSetLayout(layout: Int) {
  services?.ui?.send(session, "layout") { put("layout", layout) }
}

internal fun ScriptContext.vmFlash(level: Int) {
  state.flash = level == 0
  services?.ui?.send(session, "fx") {
    put("kind", "flash")
    put("level", level)
  }
}

/** setrespawn: the heal location the next white-out returns to (HealLocations' vars). */
internal fun ScriptContext.vmSetRespawn(healLocation: Int) {
  val img = vm?.img ?: return
  val c = charId ?: return
  val mapConst = img.constOrNull("OPENMMO_HEAL_$healLocation") ?: return
  val bank = vmBank(mapConst shr 8)
  val num = mapConst and 0xFF
  val map = maps?.getMap(state.regionId, bank, num) ?: return
  val town = services?.heal?.townOf(vmRegion(), map.name)
  val index = town?.let { FlyDestinations.all.indexOf(it) + 1 } ?: 0
  characters?.setStoryVars(
      c,
      buildMap {
        if (index > 0) put(HealLocations.HEAL_TOWN, index)
        put(HealLocations.HEAL_MAP, (state.regionId shl 16) or (bank shl 8) or num)
      })
}
