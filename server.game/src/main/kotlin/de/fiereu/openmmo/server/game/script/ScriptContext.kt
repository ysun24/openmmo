package de.fiereu.openmmo.server.game.script

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.DynamicWarp
import de.fiereu.openmmo.common.dialog.DialogLine
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.net.game.packets.dialog.DialogMessageArg
import de.fiereu.openmmo.net.game.packets.dialog.TextPokemonSpeciesArg
import de.fiereu.openmmo.net.game.packets.dialog.TextStringArg
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.progression.ScriptProgress
import de.fiereu.openmmo.server.game.script.vm.ScriptServices
import de.fiereu.openmmo.server.game.script.vm.ScriptVm
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.services.DialogPresentation
import de.fiereu.openmmo.server.game.services.DialogService
import de.fiereu.openmmo.server.game.services.MapEntryScripts
import de.fiereu.openmmo.server.game.services.ScriptMovementService
import de.fiereu.openmmo.server.game.services.ScriptWarpService
import de.fiereu.openmmo.server.game.services.ShopService
import de.fiereu.openmmo.server.game.services.StoryClientState
import de.fiereu.openmmo.server.game.services.StoryPlayerService
import de.fiereu.openmmo.server.game.services.StoryService
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore

/** What a [Script] uses to talk to the player it interacted with and read or write story state. */
class ScriptContext
internal constructor(
    internal val session: SessionContext,
    internal val state: PlayerState,
    /** The npc the player talked to, or -1 for a sign. */
    val entityId: Long,
    internal val dialog: DialogService,
    internal val story: StoryService,
    internal val movement: ScriptMovementService,
    internal val warp: ScriptWarpService? = null,
    internal val player: StoryPlayerService? = null,
    internal val battles: BattleService? = null,
    internal val characters: CharacterStore? = null,
    internal val maps: MapManager? = null,
    internal val entryScripts: MapEntryScripts? = null,
    internal val shops: ShopService? = null,
    /** r3-items: day care, decoration shops, badges. */
    internal val progress: ScriptProgress? = null,
    /** r5-scripts: what the decomp script interpreter reaches besides the above. */
    internal val services: ScriptServices? = null,
) {
  /** r5-scripts: the interpreter running this script, while one is. */
  internal var vm: ScriptVm? = null
  private val characterId: Long?
    get() = state.characterId

  val facingDirection: Direction
    get() = state.facingDirection

  val isFemale: Boolean
    get() = movement.playerGender(state) == FEMALE

  internal val playerEntityId: Long
    get() = checkNotNull(state.characterId) { "Scene has no selected character" }

  internal val playerName: String
    get() = state.characterId?.let { characters?.getCharacter(it)?.info?.name }.orEmpty()

  internal fun send(packet: Any) = session.send(packet)

  /**
   * The decomp's string buffers (STR_VAR_1..3), filled by the buffer* verbs, sent with each box.
   */
  private val buffers = sortedMapOf<Int, DialogMessageArg>()

  /** The decomp's bufferspeciesname: STR_VAR_[strVar] (1..3) becomes the species' name. */
  fun bufferSpeciesName(strVar: Int, dexId: Int) {
    buffers[checkStrVar(strVar)] =
        TextPokemonSpeciesArg(
            partySlot = 1, stringVariable = strVar.toByte(), speciesId = dexId.toShort())
  }

  /** The decomp's bufferitemname: STR_VAR_[strVar] (1..3) becomes the item's name. */
  fun bufferItemName(strVar: Int, item: ItemDef) = bufferString(strVar, item.name)

  /** The decomp's bufferstring with literal text. */
  fun bufferString(strVar: Int, text: String) {
    buffers[checkStrVar(strVar)] = TextStringArg(strVar.toByte(), text)
  }

  private fun checkStrVar(strVar: Int): Int {
    require(strVar in 1..3) { "STR_VAR_$strVar does not exist" }
    return strVar
  }

  /**
   * The rival's name. FireRed lets the player pick one and an MMO has nobody to ask, so Kanto uses
   * the game's first preset (GREEN); Emerald's rival is whoever of May and Brendan the player is
   * not.
   */
  internal val rivalName: String
    get() =
        when (Region.byWireValue(state.regionId.toByte())) {
          Region.HOENN -> if (isFemale) "BRENDAN" else "MAY"
          else -> "GREEN"
        }

  /** Every box carries the buffers and the rival's name, so the client can fill the text. */
  internal fun messageArgs(): List<DialogMessageArg> =
      buffers.values + TextStringArg(TextStringArg.RIVAL, rivalName)

  private fun presentation() = DialogPresentation(messageArgs = messageArgs())

  /** Show [line] as a sign and wait for the player to close it. */
  suspend fun sign(line: DialogLine) =
      dialog.showAndWait(session, state, line.textId, SIGN, -1, presentation())

  /** Show [line] from the interacted entity and wait for the player to go on. */
  suspend fun say(line: DialogLine) =
      dialog.showAndWait(session, state, line.textId, NPC, entityId, presentation())

  /** Show [line] from a cutscene npc addressed by its decomp local id. */
  suspend fun sayNpc(localId: Int, line: DialogLine) =
      dialog.showAndWait(
          session,
          state,
          line.textId,
          NPC,
          movement.npcEntityId(state, localId) ?: -1,
          presentation(),
      )

  /** Shows dialog with a species-name variable. */
  suspend fun sayNpcWithSpeciesName(localId: Int, line: DialogLine, speciesId: Int) {
    bufferSpeciesName(1, speciesId)
    sayNpc(localId, line)
  }

  /** Opens the Emerald starter picker. */
  suspend fun chooseHoennStarter(): Int = dialog.chooseHoennStarter(session, state)

  /** Ask a ROM-backed yes/no question from the interacted entity. */
  suspend fun askYesNo(line: DialogLine): Boolean =
      dialog.askYesNo(session, state, line.textId, entityId, messageArgs())

  /** Ask a ROM-backed yes/no question from a cutscene npc. */
  suspend fun askYesNoNpc(localId: Int, line: DialogLine): Boolean =
      dialog.askYesNo(
          session,
          state,
          line.textId,
          movement.npcEntityId(state, localId) ?: -1,
          messageArgs(),
      )

  /** True if the story [flag] is set. Keys come from the content layer, for example HoennFlags. */
  fun isFlagSet(flag: String): Boolean = characterId?.let { story.isFlagSet(it, flag) } ?: false

  fun setFlag(flag: String) {
    characterId?.let {
      story.setFlag(it, flag)
      StoryClientState.flagUpdate(state.regionId.toByte(), flag, enabled = true)?.let(session::send)
    }
  }

  fun clearFlag(flag: String) {
    characterId?.let {
      story.clearFlag(it, flag)
      StoryClientState.flagUpdate(state.regionId.toByte(), flag, enabled = false)
          ?.let(session::send)
    }
  }

  /** The story var [key], or 0 if it was never set. */
  fun getVar(key: String): Int = characterId?.let { story.getVar(it, key) } ?: 0

  fun setVar(key: String, value: Int) {
    characterId?.let { story.setVar(it, key, value) }
  }

  suspend fun givePokemon(dexId: Int, level: Int, vararg moveIds: Int) =
      checkNotNull(player) { STORY_PLAYER_UNAVAILABLE }
          .givePokemon(session, state, dexId, level, moveIds.toList())

  fun healParty() = checkNotNull(player) { STORY_PLAYER_UNAVAILABLE }.healParty(session, state)

  suspend fun giveItem(item: ItemDef, quantity: Int = 1): Boolean =
      checkNotNull(player) { STORY_PLAYER_UNAVAILABLE }.giveItem(session, state, item, quantity)

  /** Take an item back out of the bag, the decomp removeitem. False when the bag lacks it. */
  suspend fun takeItem(item: ItemDef, quantity: Int = 1): Boolean = giveItem(item, -quantity)

  /**
   * Opens the mart window on [items], the decomp pokemart. It does not wait: the player shops while
   * the script ends, because nothing in the capture tells the server when the window closed.
   */
  fun pokemart(vararg items: ItemDef) =
      checkNotNull(shops) { "Shop service is unavailable" }.open(session, entityId, items.toList())

  /**
   * Opens a decoration mart on the decomp stock list [label] (pokemartdecoration). Like [pokemart]
   * it does not wait; "Please come again!" follows when the player closes it.
   */
  fun decorationShop(label: String) {
    val charId = state.characterId ?: return
    checkNotNull(progress) { "Progress service is unavailable" }
        .decorations
        .open(charId, label, entityId)
  }

  /** Run a non-catchable, non-escapable story battle and wait for its result. */
  suspend fun battle(dexId: Int, level: Int, vararg moveIds: Int): BattleResult =
      checkNotNull(battles) { "Battle service is unavailable" }
          .startScriptedBattle(session, dexId, level, moveIds.toList())

  /** Fight the decomp trainer with this id, using the region the player is standing in. */
  suspend fun trainerBattle(trainerId: Int): BattleResult {
    val region =
        checkNotNull(Region.byWireValue(state.regionId.toByte())) {
          "Scene ran in unknown region ${state.regionId}"
        }
    return checkNotNull(battles) { "Battle service is unavailable" }
        .startTrainerBattle(session, region, trainerId)
  }

  /**
   * Walk the map npc with decomp local id [localId] (its entityIdx) through [steps] and wait for
   * the whole path to finish. This is applymovement plus waitmovement for an npc.
   */
  suspend fun moveNpc(localId: Int, vararg steps: MovementStep) =
      movement.moveNpc(session, state, localId, steps.toList())

  /** Starts concurrent NPC movement paths. */
  suspend fun moveNpcs(vararg paths: Pair<Int, List<MovementStep>>) =
      movement.moveNpcs(session, state, paths.toList())

  /** Starts player and NPC paths together. */
  suspend fun moveSelfAndNpcs(
      selfSteps: List<MovementStep>,
      vararg paths: Pair<Int, List<MovementStep>>,
  ) = movement.moveSelfAndNpcs(session, state, selfSteps, paths.toList())

  /** Walk the player's own avatar through [steps] and wait for it to finish. */
  suspend fun moveSelf(vararg steps: MovementStep) =
      movement.moveSelf(session, state, steps.toList())

  /** Show a normally hidden map npc (its decomp local id) to this player, the decomp addobject. */
  fun showNpc(localId: Int) = movement.showNpc(session, state, localId)

  /** Shows a hidden NPC at a new position. */
  fun showNpcAt(localId: Int, x: Int, y: Int) = movement.showNpcAt(session, state, localId, x, y)

  /** Repositions an existing NPC. */
  fun repositionNpc(localId: Int, x: Int, y: Int) =
      movement.repositionNpc(session, state, localId, x, y)

  /** Relocate the player's overworld entity as part of a cutscene. */
  fun repositionSelf(x: Int, y: Int, facing: Direction) =
      movement.repositionSelf(session, state, x, y, facing)

  /** Remove a cutscene npc and its collision (`removeobject`). */
  fun removeNpc(localId: Int) = movement.removeNpc(session, state, localId)

  /** Set where a MAP_DYNAMIC warp sends this player (the decomp setdynamicwarp). */
  fun setDynamicWarp(regionId: Int, bankId: Int, mapId: Int, x: Int, y: Int, facing: Direction) =
      movement.setDynamicWarp(
          state,
          DynamicWarp(
              regionId.toByte(), bankId.toByte(), mapId.toByte(), x.toShort(), y.toShort(), facing))

  /**
   * Warps the player without door movement, then runs the destination map's entry scripts on this
   * same coroutine, the way the decomp's warp continues into the new map's scripts.
   */
  suspend fun warp(regionId: Int, bankId: Int, mapId: Int, x: Int, y: Int, facing: Direction) {
    val warpService = checkNotNull(warp) { "Script warp service is unavailable" }
    state.scriptOwnsMapEntry = true
    try {
      warpService.warp(
          session,
          state,
          DynamicWarp(
              regionId.toByte(),
              bankId.toByte(),
              mapId.toByte(),
              x.toShort(),
              y.toShort(),
              facing,
          ),
      )
      val destination = maps?.getMap(regionId, bankId, mapId) ?: return
      val scripts = entryScripts ?: return
      scripts.onEntry(state, destination).forEach { it.run(this) }
      state.characterId?.let { charId ->
        scripts.atCoordinate(charId, destination, state.x.toInt(), state.y.toInt())?.run(this)
      }
    } finally {
      state.scriptOwnsMapEntry = false
    }
  }

  // ---------------------------------------------------------------- r5-scripts: for specials

  /**
   * The decomp's VarGet: story vars (0x4000+), special vars (0x8000+), else the value itself. This
   * is the ONE accessor for VAR_0x8000-0x800F / VAR_RESULT / VAR_SPECIAL_* (r5 merge): inside the
   * interpreter they are the VM's gSpecialVar_* slots; a hand-ported script running without the VM
   * keeps them in this context.
   */
  fun varGet(id: Int): Int {
    vm?.let {
      return it.varGet(id)
    }
    return when {
      id >= 0x8000 && id < 0x8100 -> localSpecialVars[id - 0x8000]
      id in 0x4000..0x7FFF -> getVar("$regionName/VAR_0x%04X".format(id))
      else -> id
    }
  }

  /** The decomp's VarSet (u16). */
  fun varSet(id: Int, value: Int) {
    vm?.let {
      it.varSet(id, value)
      return
    }
    when {
      id >= 0x8000 && id < 0x8100 -> localSpecialVars[id - 0x8000] = value and 0xFFFF
      id in 0x4000..0x7FFF -> setVar("$regionName/VAR_0x%04X".format(id), value and 0xFFFF)
    }
  }

  private val localSpecialVars = IntArray(0x100)

  /** A var by its decomp name (`VAR_0x8004`, `VAR_RESULT`, `VAR_CONTEST_RANK`), else null. */
  fun varId(name: String): Int? =
      HEX_VAR.matchEntire(name)?.groupValues?.get(1)?.toInt(16)
          ?: (vm?.img ?: de.fiereu.openmmo.server.game.script.vm.ScriptImage.of(regionName))
              ?.varIdOrNull(name)

  /** VarGet by decomp name; a story var the image does not know is read by its region key. */
  fun varGet(name: String): Int {
    val id = varId(name)
    return when {
      id != null && (vm != null || id >= 0x8000) -> varGet(id)
      else -> getVar("$regionName/$name")
    }
  }

  fun varSet(name: String, value: Int) {
    val id = varId(name)
    if (id != null && (vm != null || id >= 0x8000)) varSet(id, value)
    else setVar("$regionName/$name", value and 0xFFFF)
  }

  /** FlagGet / FlagSet / FlagClear by the decomp's flag id (trainer and special flags too). */
  fun flagGet(id: Int): Boolean = vm?.flagGet(id) ?: false

  fun flagSet(id: Int) {
    vm?.flagSet(id)
  }

  fun flagClear(id: Int) {
    vm?.flagClear(id)
  }

  /** gSpecialVar_0x8000 + [n] (VAR_0x8004 is specialVar(4)). */
  fun specialVar(n: Int): Int = varGet(0x8000 + n)

  fun setSpecialVar(n: Int, value: Int) = varSet(0x8000 + n, value)

  /** gSpecialVar_Result. */
  var result: Int
    get() = varGet(VAR_RESULT)
    set(v) = varSet(VAR_RESULT, v)

  /** The decomp region whose scripts run: "kanto" (FireRed) or "hoenn" (Emerald). */
  val regionName: String
    get() = if (state.regionId == 1) "hoenn" else "kanto"

  /** The coin case (one per character, shared by both regions; r5-minigames' coin store). */
  suspend fun coins(): Int {
    val c = characterId ?: return 0
    return services?.coins?.coins(c) ?: getVar(COINS_KEY)
  }

  /** AddCoins / RemoveCoins through the coin store; false when out of 0..9999. */
  suspend fun addCoins(delta: Int): Boolean {
    val c = characterId ?: return false
    val store = services?.coins
    if (store != null) return store.addCoins(session, c, delta)
    val now = getVar(COINS_KEY) + delta
    if (now !in 0..9999) return false
    setVar(COINS_KEY, now)
    return true
  }

  /** A decomp constant of the running region's scripts (e.g. "SPECIES_RAYQUAZA"), or null. */
  fun constant(name: String): Int? = vm?.img?.constOrNull(name)

  /** Fire-and-forget presentation (s2c 0xCE ScriptUiPacket, see PROTOCOL.md r5-scripts). */
  fun ui(kind: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
    services?.ui?.send(session, kind, body)
  }

  /** A presentation the script waits on; the client's answer, or [cancelled]. */
  suspend fun ask(
      kind: String,
      cancelled: Int,
      body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
  ): Int = services?.ui?.ask(session, kind, cancelled, body) ?: cancelled

  /** The decomp's gStringVar4 for a following `message gStringVar4` (raw decomp text). */
  fun setStringVar4(text: String) {
    vm?.state?.stringVar4 = text
  }

  /** Shows the decomp text with this label (any script text of the region) and waits. */
  suspend fun sayLabel(label: String) {
    val vm = vm ?: return
    val addr = vm.img.labels[label] ?: return
    vm.say(vm.img.texts[addr])
  }

  /** A flag key the interpreter set: badges refresh the badge case. */
  internal fun onFlagSet(key: String) {
    if (key.contains("FLAG_BADGE0") && key.endsWith("_GET")) {
      val charId = state.characterId ?: return
      progress?.progress?.push(session, charId)
    }
  }

  private companion object {
    const val VAR_RESULT = 0x800D
    const val COINS_KEY = "coins/COINS"
    private val HEX_VAR = Regex("VAR_0x([0-9A-Fa-f]{4})")

    // Sign boxes have no speaker, npc boxes point at the entity.
    const val SIGN = 3
    const val NPC = 4
    const val FEMALE: Byte = 1
    const val STORY_PLAYER_UNAVAILABLE = "Story player service is unavailable"
  }
}
