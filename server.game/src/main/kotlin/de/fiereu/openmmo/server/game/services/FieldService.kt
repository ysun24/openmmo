package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.EncounterMethod
import de.fiereu.openmmo.common.enums.MovementType
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.common.enums.TileBehavior
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.maps.NpcDef
import de.fiereu.openmmo.net.game.packets.GbaEntityMovePacket
import de.fiereu.openmmo.net.game.packets.field.FieldAction
import de.fiereu.openmmo.net.game.packets.field.FieldActionPacket
import de.fiereu.openmmo.net.game.packets.field.FieldAvatarPacket
import de.fiereu.openmmo.net.game.packets.field.FieldInfoPacket
import de.fiereu.openmmo.net.game.packets.field.FlyDestination
import de.fiereu.openmmo.net.game.packets.field.TravelMode
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.field.FieldMoves
import de.fiereu.openmmo.server.game.field.FieldObstacle
import de.fiereu.openmmo.server.game.field.FieldRules
import de.fiereu.openmmo.server.game.field.FieldText
import de.fiereu.openmmo.server.game.field.FlyDestinations
import de.fiereu.openmmo.server.game.field.ForcedMovement
import de.fiereu.openmmo.server.game.field.ForcedStep
import de.fiereu.openmmo.server.game.field.RawLine
import de.fiereu.openmmo.server.game.field.storyName
import de.fiereu.openmmo.server.game.script.MovementStep
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.script.ScriptRunner
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.StoredCharacter
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private val log = KotlinLogging.logger {}

/**
 * The overworld's field layer: HM obstacles (trees, rocks, boulders), item balls and hidden items,
 * the PC tile, SURF/WATERFALL/DIVE, FLY/TELEPORT/DIG/ESCAPE ROPE, FLASH, SWEET SCENT, the bicycle,
 * running shoes, and trainers' line of sight. Movement asks it what blocks a tile; interaction asks
 * it first whether a field object was used; every map entry resets the per-visit state the decomp
 * keeps in temporary flags.
 */
@Singleton
class FieldService
@Inject
constructor(
    private val mapManager: MapManager,
    private val characterStore: CharacterStore,
    private val storyService: StoryService,
    private val npcService: NpcService,
    private val presenceService: PresenceService,
    private val scriptRunner: ScriptRunner,
    private val items: ItemRegistry,
    private val encounterService: EncounterService,
    private val species: de.fiereu.openmmo.pokemon.SpeciesRegistry,
) {

  // ------------------------------------------------------------------ state and info

  private fun region(state: PlayerState): Region = Region.byId(state.regionId) ?: Region.KANTO

  private fun stored(state: PlayerState): StoredCharacter? =
      state.characterId?.let(characterStore::getCharacter)

  private fun currentMap(state: PlayerState): MapDef? =
      mapManager.getMap(state.regionId, state.bankId, state.mapId)

  /** The player's bag holds the Bicycle. */
  private fun hasBike(stored: StoredCharacter): Boolean =
      bikeIds.any { (stored.items[it] ?: 0) > 0 }

  private val bikeIds: List<Int> by lazy {
    items.all().filter { it.name.equals("Bicycle", true) }.flatMap(items::idsOf)
  }

  private val escapeRopeIds: List<Int> by lazy {
    items.all().filter { it.name.equals("Escape Rope", true) }.flatMap(items::idsOf)
  }

  private val itemfinderIds: List<Int> by lazy {
    items
        .all()
        .filter { it.name.equals("Dowsing MCHN", true) || it.name.equals("Itemfinder", true) }
        .flatMap(items::idsOf)
  }

  /** Running shoes: the decomp's FLAG_SYS_B_DASH. */
  private fun hasRunningShoes(state: PlayerState, stored: StoredCharacter): Boolean =
      "${region(state).storyName}/FLAG_SYS_B_DASH" in stored.storyFlags

  /** What FieldInfoPacket says about [state] on [map]; [uiKind] asks the client to open a UI. */
  fun info(state: PlayerState, map: MapDef, uiKind: Int = 0): FieldInfoPacket? {
    val stored = stored(state) ?: return null
    val region = region(state)
    val x = state.x.toInt()
    val y = state.y.toInt()
    var flags = 0
    if (map.requiresFlash) flags = flags or FieldInfoPacket.FLAG_DARK
    if (state.flash) flags = flags or FieldInfoPacket.FLAG_FLASH
    if (hasRunningShoes(state, stored) && FieldRules.canRun(map, x, y))
        flags = flags or FieldInfoPacket.FLAG_CAN_RUN
    if (FieldRules.canRide(map, x, y, state.travel)) flags = flags or FieldInfoPacket.FLAG_CAN_BIKE
    if (hasBike(stored)) flags = flags or FieldInfoPacket.FLAG_HAS_BIKE
    if (state.strength) flags = flags or FieldInfoPacket.FLAG_STRENGTH
    if (map.allowEscaping) flags = flags or FieldInfoPacket.FLAG_CAN_ESCAPE
    val moves =
        FieldAction.entries.filter {
          FieldMoves.moveOf.containsKey(it) &&
              FieldMoves.usable(region, it, stored.pokemon, stored.storyFlags)
        }
    val fly =
        FlyDestinations.forRegion(region)
            .filter { it.flag in stored.storyFlags }
            .mapNotNull { town ->
              mapManager.getMapByName(region.wireValue.toInt(), town.mapName)?.let {
                FlyDestination(it.bankId.toInt() and 0xFF, it.mapId.toInt() and 0xFF)
              }
            }
    if (uiKind != 0) state.uiSeq++
    return FieldInfoPacket(flags, moves, fly, state.uiSeq, if (uiKind != 0) uiKind else 0)
  }

  fun sendInfo(ctx: SessionContext, state: PlayerState, uiKind: Int = 0) {
    val map = currentMap(state) ?: return
    info(state, map, uiKind)?.let(ctx::send)
  }

  /** Tell the player and its observers how it travels now. */
  fun broadcastAvatar(ctx: SessionContext, state: PlayerState) {
    val id = state.characterId ?: return
    val p =
        FieldAvatarPacket(id, state.travel, state.running, female = presenceService.isFemale(id))
    ctx.send(p)
    presenceService.broadcastToObservers(ctx, p)
  }

  /**
   * After every arrival on a map: a warp/login ([warped]) or a seamless connection. Clears what the
   * decomp keeps in temporary flags, fixes the travel mode for the new tile, marks FLY towns
   * visited and refreshes the client's field state.
   */
  fun onMapEnter(ctx: SessionContext, state: PlayerState, map: MapDef, warped: Boolean) {
    val stored = stored(state) ?: return
    val before = state.travel
    if (warped) {
      state.removedObjects.clear()
      state.flash = false
      state.strength = false
    }
    val x = state.x.toInt()
    val y = state.y.toInt()
    val tile = map.tileAt(x, y)
    state.travel =
        when {
          map.mapTypeName == "MAP_TYPE_UNDERWATER" -> TravelMode.UNDERWATER
          tile?.behavior?.isSurfable == true &&
              (before == TravelMode.SURF || before == TravelMode.UNDERWATER || warped) ->
              TravelMode.SURF
          before == TravelMode.SURF || before == TravelMode.UNDERWATER -> TravelMode.WALK
          (before == TravelMode.MACH_BIKE || before == TravelMode.ACRO_BIKE) && !map.allowCycling ->
              TravelMode.WALK
          else -> before
        }
    if (warped) state.elevation = FieldRules.arrivalElevation(map, x, y, state.travel)

    val region = region(state)
    // Towns mark themselves visited for FLY (setflag FLAG_WORLD_MAP_* / FLAG_VISITED_* in their
    // on-transition scripts); the last one visited is where TELEPORT returns to.
    FlyDestinations.byMapName(region, map.name)?.let { town ->
      if (town.flag !in stored.storyFlags) storyService.setFlag(stored.info.id, town.flag)
      storyService.setVar(stored.info.id, LAST_TOWN_VAR, FlyDestinations.all.indexOf(town) + 1)
    }
    // DIG and the ESCAPE ROPE return to where the player last stood outdoors (the decomp's
    // escape warp, set when entering a map that allows escaping).
    if (map.isOutdoors) {
      storyService.setVar(stored.info.id, ESCAPE_VAR, packPos(map, x, y))
    }
    sendInfo(ctx, state)
    if (before != state.travel || warped) broadcastAvatar(ctx, state)
  }

  /** The step that was just accepted: keep the outdoor escape point and the running flag. */
  fun onStepDone(ctx: SessionContext, state: PlayerState, map: MapDef, running: Boolean) {
    val stored = stored(state) ?: return
    val run = running && state.travel == TravelMode.WALK && hasRunningShoes(state, stored)
    if (run != state.running) {
      state.running = run
      presenceService.broadcastToObservers(
          ctx,
          FieldAvatarPacket(
              stored.info.id, state.travel, run, female = stored.info.rivalSex.toInt() == 1))
    }
  }

  // ------------------------------------------------------------------ obstacles

  /** What blocks (x, y) on [map] for this player: an uncut tree, an unsmashed rock, a boulder. */
  fun obstacleAt(state: PlayerState, map: MapDef, x: Int, y: Int): FieldObstacle? {
    val stored = stored(state) ?: return null
    for (npc in map.npcs) {
      val kind = obstacleKind(map, npc) ?: continue
      if (npc.entityIdx in state.removedObjects) continue
      if (npc.hideFlag.isNotEmpty() && npc.hideFlag in stored.storyFlags) continue
      val (nx, ny) =
          if (kind == FieldObstacle.Kind.BOULDER) boulderPos(stored, map, npc) else npc.x to npc.y
      if (nx == x && ny == y) return FieldObstacle(npc.entityIdx, kind)
    }
    return null
  }

  private fun obstacleKind(map: MapDef, npc: NpcDef): FieldObstacle.Kind? {
    val kanto = map.regionId.toInt() == 0
    return when {
      npc.script == "EventScript_CutTree" ||
          npc.graphicsId == (if (kanto) GFX_K_CUT_TREE else GFX_H_CUT_TREE) ->
          FieldObstacle.Kind.CUT_TREE
      npc.script == "EventScript_RockSmash" ||
          npc.graphicsId == (if (kanto) GFX_K_ROCK else GFX_H_ROCK) ->
          FieldObstacle.Kind.ROCK_SMASH_ROCK
      npc.script == "EventScript_StrengthBoulder" ||
          npc.graphicsId == (if (kanto) GFX_K_BOULDER else GFX_H_BOULDER) ->
          FieldObstacle.Kind.BOULDER
      npc.item.isNotEmpty() -> FieldObstacle.Kind.ITEM_BALL
      else -> null
    }
  }

  private fun boulderKey(map: MapDef, localId: Int) =
      "field/boulder/${map.regionId}:${map.bankId.toInt() and 0xFF}:${map.mapId.toInt() and 0xFF}:$localId"

  /** A boulder's position for this player: where they last pushed it, or its map.json spot. */
  fun boulderPos(stored: StoredCharacter, map: MapDef, npc: NpcDef): Pair<Int, Int> {
    val v = stored.storyVars[boulderKey(map, npc.entityIdx)] ?: 0
    return if (v <= 0) npc.x to npc.y else ((v - 1) shr 8) to ((v - 1) and 0xFF)
  }

  /** STRENGTH pushed boulder [localId] to (x, y): persist it and slide it for the player. */
  fun pushBoulder(
      ctx: SessionContext,
      state: PlayerState,
      map: MapDef,
      localId: Int,
      x: Int,
      y: Int
  ) {
    val charId = state.characterId ?: return
    val stored = stored(state) ?: return
    val npc = map.npcs.firstOrNull { it.entityIdx == localId } ?: return
    val (fx, fy) = boulderPos(stored, map, npc)
    storyService.setVar(charId, boulderKey(map, localId), ((x shl 8) or y) + 1)
    val dir =
        when {
          x > fx -> Direction.RIGHT
          x < fx -> Direction.LEFT
          y > fy -> Direction.DOWN
          else -> Direction.UP
        }
    ctx.send(
        GbaEntityMovePacket(
            entityId =
                npcService.entityIdFor(
                    map.regionId.toInt(),
                    map.bankId.toInt() and 0xFF,
                    map.mapId.toInt() and 0xFF,
                    localId),
            bankId = map.bankId.toInt() and 0xFF,
            mapId = map.mapId.toInt() and 0xFF,
            x = x,
            y = y,
            movementMode = 2,
            direction = dir,
        ))
    log.info { "Boulder $localId pushed to ($x, $y) for char=$charId" }
  }

  // ------------------------------------------------------------------ interactions

  /**
   * The player pressed A on map npc [npc]. Handles HM obstacles, item balls and trainers; false
   * when the npc is none of those and its own script should run.
   */
  fun onNpcInteract(
      session: SessionContext,
      state: PlayerState,
      map: MapDef,
      npc: NpcDef,
      entityId: Long
  ): Boolean {
    val region = region(state)
    val text = FieldText.of(region)
    val kind = obstacleKind(map, npc)
    return when {
      kind == FieldObstacle.Kind.CUT_TREE -> {
        run(session, state, entityId) { ctx ->
          obstacleScript(ctx, state, map, npc, FieldAction.CUT, text.wantToCut, text.cantCut)
        }
        true
      }
      kind == FieldObstacle.Kind.ROCK_SMASH_ROCK -> {
        run(session, state, entityId) { ctx ->
          obstacleScript(
              ctx, state, map, npc, FieldAction.ROCK_SMASH, text.wantToSmash, text.cantSmash)
        }
        true
      }
      kind == FieldObstacle.Kind.BOULDER -> {
        run(session, state, entityId) { ctx -> strengthScript(ctx, state, text) }
        true
      }
      kind == FieldObstacle.Kind.ITEM_BALL -> {
        run(session, state, entityId) { ctx -> itemBallScript(ctx, state, npc, text) }
        true
      }
      (npc.trainer?.trainerId ?: 0) > 0 -> {
        run(session, state, entityId) { ctx -> trainerTalk(ctx, state, map, npc) }
        true
      }
      else -> false
    }
  }

  /**
   * The player pressed A facing ([fx], [fy]) where no npc stands: hidden items, the PC, water (SURF
   * / WATERFALL) and dive spots. True when handled.
   */
  fun onTileInteract(
      session: SessionContext,
      state: PlayerState,
      map: MapDef,
      fx: Int,
      fy: Int
  ): Boolean {
    val stored = stored(state) ?: return false
    val region = region(state)
    val text = FieldText.of(region)
    // Hidden items are found by facing them (bg_event hidden_item).
    map.hiddenItems
        .firstOrNull { it.x == fx && it.y == fy && it.flag !in stored.storyFlags }
        ?.let { h ->
          val item = itemByConstant(h.item)
          if (item != null) {
            run(session, state, -1) { ctx ->
              if (ctx.giveItem(item, h.quantity)) {
                ctx.setFlag(h.flag)
                ctx.bufferString(2, item.name.uppercase())
                ctx.sign(text.foundItem)
              }
            }
            return true
          }
        }
    val facing = map.tileAt(fx, fy)?.behavior
    if (facing == TileBehavior.PC) {
      // EventScript_PC: Pokemon Centers open the storage system, the bedroom PC the item storage.
      val kind = if (map.npcs.any { it.script.contains("Nurse") }) UI_PC_MONS else UI_PC_ITEMS
      run(session, state, -1) { ctx ->
        ctx.sign(text.bootedPc)
        // The storage menu comes after the box, as on the GBA.
        sendInfo(session, state, kind)
      }
      return true
    }
    val x = state.x.toInt()
    val y = state.y.toInt()
    // Waterfall: only climbed surfing north.
    if (facing == TileBehavior.WATERFALL) {
      if (state.travel == TravelMode.SURF &&
          state.facingDirection == Direction.UP &&
          FieldMoves.usable(region, FieldAction.WATERFALL, stored.pokemon, stored.storyFlags)) {
        run(session, state, -1) { ctx ->
          if (ctx.askYesNo(text.wantToWaterfall)) waterfall(ctx, state, map)
        }
      } else {
        run(session, state, -1) { ctx -> ctx.sign(text.cantWaterfall) }
      }
      return true
    }
    // Facing water from the shore: offer SURF.
    if (FieldRules.canStartSurf(map, x, y, state.facingDirection, state.elevation, state.travel) &&
        FieldMoves.usable(region, FieldAction.SURF, stored.pokemon, stored.storyFlags)) {
      run(session, state, -1) { ctx ->
        val mon = FieldMoves.partyMonWith(stored.pokemon, FieldMoves.SURF)
        ctx.bufferString(1, mon?.let(::monName) ?: "")
        if (ctx.askYesNo(text.wantToSurf)) startSurf(ctx.session, state, map)
      }
      return true
    }
    // Dive spots (TrySetDiveWarp): A while surfing on deep water, or underwater below a surface.
    if (region == Region.HOENN &&
        FieldMoves.usable(region, FieldAction.DIVE, stored.pokemon, stored.storyFlags)) {
      val line =
          when {
            state.travel == TravelMode.SURF && diveTarget(map, x, y, Direction.DIVE) != null ->
                text.wantToDive
            state.travel == TravelMode.UNDERWATER &&
                diveTarget(map, x, y, Direction.EMERGE) != null -> text.wantToSurface
            else -> null
          }
      if (line != null) {
        run(session, state, -1) { ctx -> if (ctx.askYesNo(line)) dive(ctx, state, map) }
        return true
      }
    }
    return false
  }

  private fun run(
      session: SessionContext,
      state: PlayerState,
      entityId: Long,
      body: suspend (ScriptContext) -> Unit
  ) {
    scriptRunner.run(session, state, Script { body(it) }, entityId)
  }

  private fun monName(mon: de.fiereu.openmmo.common.Pokemon): String =
      mon.nickname.ifEmpty { species.get(mon.dexId)?.name ?: "POKéMON" }.uppercase()

  /** EventScript_CutTree / EventScript_RockSmash. */
  private suspend fun obstacleScript(
      ctx: ScriptContext,
      state: PlayerState,
      map: MapDef,
      npc: NpcDef,
      action: FieldAction,
      want: de.fiereu.openmmo.common.dialog.DialogLine,
      cant: de.fiereu.openmmo.common.dialog.DialogLine,
  ) {
    val stored = stored(state) ?: return
    val region = region(state)
    if (!FieldMoves.usable(region, action, stored.pokemon, stored.storyFlags)) {
      ctx.sign(cant)
      return
    }
    val mon = FieldMoves.partyMonWith(stored.pokemon, FieldMoves.moveOf.getValue(action))
    ctx.bufferString(1, mon?.let(::monName) ?: "")
    ctx.bufferString(2, if (action == FieldAction.CUT) "CUT" else "ROCK SMASH")
    if (!ctx.askYesNo(want)) return
    ctx.say(FieldText.of(region).usedMove)
    removeObstacle(ctx.session, state, map, npc.entityIdx)
    if (action == FieldAction.ROCK_SMASH) {
      // special RockSmashWildEncounter
      state.characterId?.let {
        encounterService.encounterNow(
            ctx.session, it, map, EncounterMethod.ROCK_SMASH, force = false)
      }
    }
  }

  /** removeobject for a cut tree / smashed rock / taken ball, until the next map change. */
  private fun removeObstacle(
      session: SessionContext,
      state: PlayerState,
      map: MapDef,
      localId: Int
  ) {
    state.removedObjects += localId
    npcService.despawnNpc(
        session,
        map.regionId.toInt(),
        map.bankId.toInt() and 0xFF,
        map.mapId.toInt() and 0xFF,
        localId)
  }

  /** EventScript_StrengthBoulder. */
  private suspend fun strengthScript(ctx: ScriptContext, state: PlayerState, text: FieldText) {
    val stored = stored(state) ?: return
    val region = region(state)
    if (!FieldMoves.usable(region, FieldAction.STRENGTH, stored.pokemon, stored.storyFlags)) {
      ctx.sign(text.cantStrength)
      return
    }
    if (state.strength) {
      ctx.sign(text.strengthActive)
      return
    }
    if (!ctx.askYesNo(text.wantToStrength)) return
    activateStrength(ctx, state, text)
  }

  private suspend fun activateStrength(ctx: ScriptContext, state: PlayerState, text: FieldText) {
    val stored = stored(state) ?: return
    val mon = FieldMoves.partyMonWith(stored.pokemon, FieldMoves.STRENGTH)
    ctx.bufferString(1, mon?.let(::monName) ?: "")
    state.strength = true
    ctx.say(text.usedStrength)
    sendInfo(ctx.session, state)
  }

  /** An item ball: finditem. */
  private suspend fun itemBallScript(
      ctx: ScriptContext,
      state: PlayerState,
      npc: NpcDef,
      text: FieldText
  ) {
    val item = itemByConstant(npc.item) ?: return
    val map = currentMap(state) ?: return
    if (!ctx.giveItem(item, 1)) return
    val flag = npc.flag.ifEmpty { npc.hideFlag }
    if (flag.isNotEmpty()) ctx.setFlag(flag)
    removeObstacle(ctx.session, state, map, npc.entityIdx)
    ctx.bufferString(2, item.name.uppercase())
    ctx.sign(text.foundItem)
  }

  /** The decomp ITEM_* constant as our item: names normalized (POKé BALL = ITEM_POKE_BALL). */
  fun itemByConstant(constant: String): ItemDef? {
    val want = constant.removePrefix("ITEM_").replace("_", "")
    return itemIndex[want]
  }

  private val itemIndex: Map<String, ItemDef> by lazy {
    items.all().associateBy { normalize(it.name) }
  }

  private fun normalize(name: String): String =
      name.uppercase().replace("É", "E").filter { it.isLetterOrDigit() }

  // ------------------------------------------------------------------ surfing and water

  /** Hop onto the faced water tile and start surfing (FLDEFF_USE_SURF). */
  private fun startSurf(session: SessionContext, state: PlayerState, map: MapDef) {
    val charId = state.characterId ?: return
    val dir = state.facingDirection
    val x = state.x + dir.dx
    val y = state.y + dir.dy
    moveSelf(session, state, map, x, y, dir)
    state.travel = TravelMode.SURF
    state.elevation = FieldRules.WATER
    broadcastAvatar(session, state)
    sendInfo(session, state)
    log.info { "char=$charId started surfing at ($x, $y)" }
  }

  /** Surfing into a shore tile (COLLISION_STOP_SURFING). Called by movement after the step. */
  fun stopSurf(session: SessionContext, state: PlayerState) {
    state.travel = TravelMode.WALK
    state.elevation = FieldRules.LAND
    broadcastAvatar(session, state)
    sendInfo(session, state)
  }

  /** Puts the player at (x, y) and tells it and its observers. */
  private fun moveSelf(
      session: SessionContext,
      state: PlayerState,
      map: MapDef,
      x: Int,
      y: Int,
      dir: Direction
  ) {
    val charId = state.characterId ?: return
    characterStore.updatePosition(charId, x.toShort(), y.toShort(), facing = dir)
    state.x = x.toShort()
    state.y = y.toShort()
    state.facingDirection = dir
    val p =
        GbaEntityMovePacket(
            entityId = charId,
            bankId = map.bankId.toInt() and 0xFF,
            mapId = map.mapId.toInt() and 0xFF,
            x = x,
            y = y,
            movementMode = 2,
            direction = dir)
    session.send(p)
    presenceService.broadcastToObservers(session, p)
  }

  private suspend fun waterfall(ctx: ScriptContext, state: PlayerState, map: MapDef) {
    val stored = stored(state) ?: return
    val (x, y) = FieldRules.waterfallClimb(map, state.x.toInt(), state.y.toInt()) ?: return
    val mon = FieldMoves.partyMonWith(stored.pokemon, FieldMoves.WATERFALL)
    ctx.bufferString(1, mon?.let(::monName) ?: "")
    ctx.bufferString(2, "WATERFALL")
    ctx.say(FieldText.of(region(state)).usedMove)
    moveSelf(ctx.session, state, map, x, y, Direction.UP)
  }

  /** The dive/emerge connection of [map] and the target tile there, when it is valid. */
  private fun diveTarget(map: MapDef, x: Int, y: Int, dir: Direction): MapDef? {
    val here = map.tileAt(x, y)?.behavior ?: return null
    if (dir == Direction.DIVE && here != TileBehavior.DEEP_WATER) return null
    val conn = map.connections.firstOrNull { it.direction == dir } ?: return null
    val target =
        mapManager.getMap(map.regionId, conn.targetBank.toByte(), conn.targetMap.toByte())
            ?: return null
    val t = target.tileAt(x, y) ?: return null
    if (t.blocksMovement()) return null
    if (dir == Direction.EMERGE && !t.behavior.isSurfable) return null
    return target
  }

  private suspend fun dive(ctx: ScriptContext, state: PlayerState, map: MapDef) {
    val x = state.x.toInt()
    val y = state.y.toInt()
    val dir = if (state.travel == TravelMode.UNDERWATER) Direction.EMERGE else Direction.DIVE
    val target = diveTarget(map, x, y, dir) ?: return
    state.travel = if (dir == Direction.DIVE) TravelMode.UNDERWATER else TravelMode.SURF
    ctx.warp(
        target.regionId.toInt(),
        target.bankId.toInt() and 0xFF,
        target.mapId.toInt() and 0xFF,
        x,
        y,
        state.facingDirection)
  }

  // ------------------------------------------------------------------ field actions

  /** c2s FieldActionPacket: a field move from the party menu, the bike, an Escape Rope. */
  fun onAction(event: PacketEvent<FieldActionPacket>) {
    val session = event.session
    val state = session.attributes[PLAYER_STATE] ?: return
    if (state.inDialog || state.justWarped) return
    val stored = stored(state) ?: return
    val map = currentMap(state) ?: return
    val region = region(state)
    val a = event.packet.action
    val text = FieldText.of(region)
    if (FieldMoves.moveOf.containsKey(a) &&
        !FieldMoves.usable(region, a, stored.pokemon, stored.storyFlags)) {
      session.send(
          notice(
              if (FieldMoves.existsIn(region, a))
                  "Can't use that until you have the right BADGE and a POKéMON that knows it."
              else "There is no such move here."))
      return
    }
    val x = state.x.toInt()
    val y = state.y.toInt()
    val fx = x + state.facingDirection.dx
    val fy = y + state.facingDirection.dy
    when (a) {
      FieldAction.CUT,
      FieldAction.ROCK_SMASH -> {
        val o = obstacleAt(state, map, fx, fy)
        val want =
            if (a == FieldAction.CUT) FieldObstacle.Kind.CUT_TREE
            else FieldObstacle.Kind.ROCK_SMASH_ROCK
        if (o == null || o.kind != want) return cantHere(session)
        val npc = map.npcs.first { it.entityIdx == o.localId }
        val entityId =
            npcService.entityIdFor(
                map.regionId.toInt(),
                map.bankId.toInt() and 0xFF,
                map.mapId.toInt() and 0xFF,
                o.localId)
        run(session, state, entityId) { ctx ->
          val mon = FieldMoves.partyMonWith(stored.pokemon, FieldMoves.moveOf.getValue(a))
          ctx.bufferString(1, mon?.let(::monName) ?: "")
          ctx.bufferString(2, if (a == FieldAction.CUT) "CUT" else "ROCK SMASH")
          ctx.say(text.usedMove)
          removeObstacle(session, state, map, npc.entityIdx)
          if (a == FieldAction.ROCK_SMASH)
              encounterService.encounterNow(
                  session, stored.info.id, map, EncounterMethod.ROCK_SMASH, false)
        }
      }
      FieldAction.STRENGTH ->
          if (state.strength) run(session, state, -1) { it.sign(text.strengthActive) }
          else run(session, state, -1) { activateStrength(it, state, text) }
      FieldAction.FLASH -> {
        if (!map.requiresFlash || state.flash) return cantHere(session)
        state.flash = true
        session.send(
            notice(
                "${FieldMoves.partyMonWith(stored.pokemon, FieldMoves.FLASH)?.let(::monName)} used FLASH!"))
        sendInfo(session, state)
      }
      FieldAction.SURF -> {
        if (!FieldRules.canStartSurf(
            map, x, y, state.facingDirection, state.elevation, state.travel))
            return cantHere(session)
        run(session, state, -1) { ctx ->
          ctx.bufferString(
              1, FieldMoves.partyMonWith(stored.pokemon, FieldMoves.SURF)?.let(::monName) ?: "")
          ctx.bufferString(2, "SURF")
          ctx.say(text.usedMove)
          startSurf(session, state, map)
        }
      }
      FieldAction.WATERFALL -> {
        if (state.travel != TravelMode.SURF ||
            state.facingDirection != Direction.UP ||
            FieldRules.waterfallClimb(map, x, y) == null)
            return cantHere(session)
        run(session, state, -1) { waterfall(it, state, map) }
      }
      FieldAction.DIVE -> {
        val dir = if (state.travel == TravelMode.UNDERWATER) Direction.EMERGE else Direction.DIVE
        if ((state.travel != TravelMode.SURF && state.travel != TravelMode.UNDERWATER) ||
            diveTarget(map, x, y, dir) == null)
            return cantHere(session)
        run(session, state, -1) { dive(it, state, map) }
      }
      FieldAction.FLY -> {
        // SetUpFieldMove_Fly: outdoors only.
        if (!map.isOutdoors) return cantHere(session)
        val bank = event.packet.arg
        val mapId = event.packet.arg2
        val target = mapManager.getMap(region.wireValue.toInt(), bank, mapId)
        val town = target?.let { FlyDestinations.byMapName(region, it.name) }
        if (target == null || town == null || town.flag !in stored.storyFlags) {
          // Without a target the client gets the fly map.
          sendInfo(session, state, UI_FLY_MAP)
          return
        }
        val female = stored.info.rivalSex.toInt() == 1
        flyTo(
            session,
            state,
            target,
            if (female) town.femaleX else town.x,
            if (female) town.femaleY else town.y)
      }
      FieldAction.TELEPORT -> {
        if (!map.isOutdoors && !map.allowEscaping) return cantHere(session)
        // Teleport returns to the last heal location (lastHealLocation), else the last town.
        val healed =
            FlyDestinations.all
                .getOrNull(
                    (stored.storyVars[
                            de.fiereu.openmmo.server.game.progression.HealLocations.HEAL_TOWN]
                        ?: 0) - 1)
                ?.takeIf { it.region == region }
        val town =
            healed
                ?: FlyDestinations.all
                    .getOrNull((stored.storyVars[LAST_TOWN_VAR] ?: 0) - 1)
                    ?.takeIf { it.region == region }
                ?: FlyDestinations.forRegion(region).first()
        val target = mapManager.getMapByName(region.wireValue.toInt(), town.mapName) ?: return
        flyTo(session, state, target, town.x, town.y)
      }
      FieldAction.DIG,
      FieldAction.ESCAPE_ROPE -> {
        if (!map.allowEscaping) return cantHere(session)
        if (a == FieldAction.ESCAPE_ROPE) {
          val id =
              escapeRopeIds.firstOrNull { (stored.items[it] ?: 0) > 0 }
                  ?: return tell(session, "You have no ESCAPE ROPE.")
          run(session, state, -1) { ctx ->
            if (characterStore.addItem(stored.info.id, id, -1)) {
              session.send(
                  storyItemStacksPacket(
                      characterStore.getCharacter(stored.info.id)?.items ?: emptyMap()))
              escape(ctx, state, region)
            }
          }
        } else run(session, state, -1) { escape(it, state, region) }
      }
      FieldAction.SWEET_SCENT -> {
        val tile = map.tileAt(x, y)
        val method =
            if (state.travel == TravelMode.SURF) EncounterMethod.WATER else EncounterMethod.LAND
        val ok =
            (method == EncounterMethod.WATER || tile?.behavior?.isLandEncounter == true) &&
                encounterService.encounterNow(session, stored.info.id, map, method, force = true)
        if (!ok) session.send(notice("Looks like there's nothing here…"))
      }
      FieldAction.BIKE -> {
        if (!hasBike(stored)) return tell(session, "You don't have a BIKE.")
        if (state.travel == TravelMode.MACH_BIKE || state.travel == TravelMode.ACRO_BIKE) {
          setTravel(session, state, TravelMode.WALK)
          return
        }
        if (!FieldRules.canRide(map, x, y, state.travel))
            return tell(session, "No cycling allowed here.")
        val mode =
            if (region == Region.HOENN && event.packet.arg == 1) TravelMode.ACRO_BIKE
            else TravelMode.MACH_BIKE
        setTravel(session, state, mode)
      }
      FieldAction.DISMOUNT -> {
        // FRLG's Cycling Road keeps the player on the bike.
        if (state.travel == TravelMode.MACH_BIKE || state.travel == TravelMode.ACRO_BIKE)
            setTravel(session, state, TravelMode.WALK)
      }
      FieldAction.ITEMFINDER -> {
        if (itemfinderIds.none { (stored.items[it] ?: 0) > 0 })
            return tell(session, "You don't have an ITEMFINDER.")
        session.send(notice(itemfinderReading(stored, map, x, y)))
      }
    }
  }

  private fun cantHere(session: SessionContext) = tell(session, "Can't use that here.")

  private fun tell(session: SessionContext, text: String) {
    session.send(notice(text))
  }

  private fun setTravel(session: SessionContext, state: PlayerState, mode: TravelMode) {
    state.travel = mode
    state.running = false
    broadcastAvatar(session, state)
    sendInfo(session, state)
  }

  /** ItemFinder_CheckForHiddenItems: the nearest unfound hidden item on screen (15x10). */
  fun itemfinderReading(stored: StoredCharacter, map: MapDef, x: Int, y: Int): String {
    val near =
        map.hiddenItems
            .filter { it.flag !in stored.storyFlags && abs(it.x - x) <= 7 && abs(it.y - y) <= 5 }
            .minByOrNull { abs(it.x - x) + abs(it.y - y) }
            ?: return "… … … … Nope! There's no response."
    if (near.x == x && near.y == y)
        return "Oh! The ITEMFINDER's responding! There's an item buried right underfoot!"
    val dx = near.x - x
    val dy = near.y - y
    val dir =
        if (abs(dx) >= abs(dy)) (if (dx > 0) "east" else "west")
        else (if (dy > 0) "south" else "north")
    return "The ITEMFINDER is responding! Something is buried to the $dir."
  }

  /**
   * Gen 3 forced movement after a step that landed on ([x], [y]) moving [moved] (ice, currents,
   * walk/slide tiles, spinners, muddy slopes, the Cycling Road): plays the steps as a short
   * cutscene the player cannot steer. True when something moved the player.
   */
  fun startForcedMovement(
      session: SessionContext,
      state: PlayerState,
      map: MapDef,
      x: Int,
      y: Int,
      moved: Direction,
  ): Boolean {
    val path =
        ForcedMovement.path(map, x, y, moved, state.elevation, state.travel) { ox, oy ->
          obstacleAt(state, map, ox, oy)
        }
    if (path.isEmpty()) return false
    val steps =
        path.map { s ->
          val fast = s.kind != ForcedStep.Kind.WALK
          when (s.dir) {
            Direction.UP -> if (fast) MovementStep.FAST_UP else MovementStep.WALK_UP
            Direction.DOWN -> if (fast) MovementStep.FAST_DOWN else MovementStep.WALK_DOWN
            Direction.LEFT -> if (fast) MovementStep.FAST_LEFT else MovementStep.WALK_LEFT
            else -> if (fast) MovementStep.FAST_RIGHT else MovementStep.WALK_RIGHT
          }
        }
    log.info {
      "Forced movement for char=${state.characterId} from ($x, $y): ${path.map { it.dir }}"
    }
    run(session, state, -1) { ctx -> ctx.moveSelf(*steps.toTypedArray()) }
    return true
  }

  /** Developer teleport (`/tp <MapName> x y`): warp to map [name] (decomp dir name) at (x, y). */
  fun devWarp(session: SessionContext, state: PlayerState, name: String, x: Int, y: Int): Boolean {
    val target = mapManager.getMapByName(state.regionId, name) ?: return false
    flyTo(session, state, target, x, y)
    return true
  }

  private fun flyTo(session: SessionContext, state: PlayerState, target: MapDef, x: Int, y: Int) {
    run(session, state, -1) { ctx ->
      state.travel = TravelMode.WALK
      ctx.warp(
          target.regionId.toInt(),
          target.bankId.toInt() and 0xFF,
          target.mapId.toInt() and 0xFF,
          x,
          y,
          Direction.DOWN)
    }
  }

  private suspend fun escape(ctx: ScriptContext, state: PlayerState, region: Region) {
    val stored = stored(state) ?: return
    val v = stored.storyVars[ESCAPE_VAR] ?: 0
    if (v <= 0) {
      val town = FlyDestinations.forRegion(region).first()
      val target = mapManager.getMapByName(region.wireValue.toInt(), town.mapName) ?: return
      ctx.warp(
          target.regionId.toInt(),
          target.bankId.toInt() and 0xFF,
          target.mapId.toInt() and 0xFF,
          town.x,
          town.y,
          Direction.DOWN)
      return
    }
    val (bank, mapId, x, y) = unpackPos(v)
    state.travel = TravelMode.WALK
    ctx.warp(state.regionId, bank, mapId, x, y, Direction.DOWN)
  }

  // ------------------------------------------------------------------ trainers

  private fun defeatedFlag(map: MapDef, npc: NpcDef): String {
    val r = Region.byId(map.regionId.toInt()) ?: Region.KANTO
    return "${r.storyName}/TRAINER_DEFEATED_${npc.trainer?.trainerId}"
  }

  /** Talking to a trainer: battle if undefeated, else the post-battle line. */
  private suspend fun trainerTalk(
      ctx: ScriptContext,
      state: PlayerState,
      map: MapDef,
      npc: NpcDef
  ) {
    val spot = npc.trainer ?: return
    if (ctx.isFlagSet(defeatedFlag(map, npc))) {
      if (spot.postText != 0) ctx.say(RawLine(spot.postText))
      return
    }
    trainerBattle(ctx, state, map, npc)
  }

  private suspend fun trainerBattle(
      ctx: ScriptContext,
      state: PlayerState,
      map: MapDef,
      npc: NpcDef
  ) {
    val spot = npc.trainer ?: return
    if (spot.introText != 0) ctx.sayNpc(npc.entityIdx, RawLine(spot.introText))
    val result = ctx.trainerBattle(spot.trainerId)
    if (result == BattleResult.VICTORY) {
      ctx.setFlag(defeatedFlag(map, npc))
      if (spot.defeatText != 0) ctx.sayNpc(npc.entityIdx, RawLine(spot.defeatText))
    }
    log.info { "Trainer ${spot.constant} battle for char=${state.characterId}: $result" }
  }

  /** The directions a trainer looks in: its fixed facing, or all of a look-around trainer's. */
  private fun watchedDirections(npc: NpcDef): List<Direction> =
      when (npc.movementType) {
        MovementType.FACE_UP -> listOf(Direction.UP)
        MovementType.FACE_DOWN -> listOf(Direction.DOWN)
        MovementType.FACE_LEFT -> listOf(Direction.LEFT)
        MovementType.FACE_RIGHT -> listOf(Direction.RIGHT)
        MovementType.LOOK_AROUND ->
            listOf(Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT)
        MovementType.FACE_DOWN_AND_UP -> listOf(Direction.DOWN, Direction.UP)
        MovementType.FACE_LEFT_AND_RIGHT -> listOf(Direction.LEFT, Direction.RIGHT)
        else ->
            when (npc.movementType.name) {
              "FACE_UP_AND_LEFT" -> listOf(Direction.UP, Direction.LEFT)
              "FACE_UP_AND_RIGHT" -> listOf(Direction.UP, Direction.RIGHT)
              "FACE_DOWN_AND_LEFT" -> listOf(Direction.DOWN, Direction.LEFT)
              "FACE_DOWN_AND_RIGHT" -> listOf(Direction.DOWN, Direction.RIGHT)
              "FACE_DOWN_UP_AND_LEFT" -> listOf(Direction.DOWN, Direction.UP, Direction.LEFT)
              "FACE_DOWN_UP_AND_RIGHT" -> listOf(Direction.DOWN, Direction.UP, Direction.RIGHT)
              "FACE_UP_LEFT_AND_RIGHT" -> listOf(Direction.UP, Direction.LEFT, Direction.RIGHT)
              "FACE_DOWN_LEFT_AND_RIGHT" -> listOf(Direction.DOWN, Direction.LEFT, Direction.RIGHT)
              else -> listOf(npc.facing)
            }
      }

  /**
   * CheckForTrainersWantingBattle after a step: the first undefeated trainer that sees the player
   * (same row/column, within its sight, nothing solid between) walks up and battles. True when one
   * did, so the step's encounter roll is skipped.
   */
  fun checkTrainerSight(
      session: SessionContext,
      state: PlayerState,
      map: MapDef,
      px: Int,
      py: Int
  ): Boolean {
    val stored = stored(state) ?: return false
    val hit = spottingTrainer(stored, state, map, px, py) ?: return false
    val (npc, dir, dist) = hit
    val entityId =
        npcService.entityIdFor(
            map.regionId.toInt(),
            map.bankId.toInt() and 0xFF,
            map.mapId.toInt() and 0xFF,
            npc.entityIdx)
    run(session, state, entityId) { ctx ->
      ctx.send(FieldAvatarPacket(entityId, TravelMode.WALK, exclaim = true))
      kotlinx.coroutines.delay(EXCLAIM_MS)
      val walk =
          when (dir) {
            Direction.UP -> MovementStep.WALK_UP
            Direction.DOWN -> MovementStep.WALK_DOWN
            Direction.LEFT -> MovementStep.WALK_LEFT
            else -> MovementStep.WALK_RIGHT
          }
      val face =
          when (dir) {
            Direction.UP -> MovementStep.FACE_UP
            Direction.DOWN -> MovementStep.FACE_DOWN
            Direction.LEFT -> MovementStep.FACE_LEFT
            else -> MovementStep.FACE_RIGHT
          }
      ctx.moveNpc(npc.entityIdx, *(listOf(face) + List(dist - 1) { walk } + face).toTypedArray())
      val back =
          when (dir.opposite()) {
            Direction.UP -> MovementStep.FACE_UP
            Direction.DOWN -> MovementStep.FACE_DOWN
            Direction.LEFT -> MovementStep.FACE_LEFT
            else -> MovementStep.FACE_RIGHT
          }
      ctx.moveSelf(back)
      trainerBattle(ctx, state, map, npc)
    }
    return true
  }

  /** (trainer, direction it looks, distance) of a trainer that sees (px, py), or null. */
  internal fun spottingTrainer(
      stored: StoredCharacter,
      state: PlayerState,
      map: MapDef,
      px: Int,
      py: Int
  ): Triple<NpcDef, Direction, Int>? {
    for (npc in map.npcs) {
      val spot = npc.trainer ?: continue
      if (spot.sight <= 0 || spot.trainerId <= 0) continue
      if (npc.hideFlag.isNotEmpty() && npc.hideFlag in stored.storyFlags) continue
      if (defeatedFlag(map, npc) in stored.storyFlags) continue
      for (dir in watchedDirections(npc)) {
        val dist = (px - npc.x) * dir.dx + (py - npc.y) * dir.dy
        val inLine = if (dir.dx != 0) py == npc.y else px == npc.x
        if (!inLine || dist < 1 || dist > spot.sight) continue
        // The approach path must be open (TrainerSeesPlayer -> CheckPathBetweenTrainerAndPlayer).
        var clear = true
        for (i in 1 until dist) {
          val tx = npc.x + dir.dx * i
          val ty = npc.y + dir.dy * i
          val t = map.tileAt(tx, ty)
          if (t == null || t.blocksMovement() || obstacleAt(state, map, tx, ty) != null) {
            clear = false
            break
          }
        }
        if (clear) return Triple(npc, dir, dist)
      }
    }
    return null
  }

  private fun packPos(map: MapDef, x: Int, y: Int): Int =
      ((map.bankId.toInt() and 0xFF) shl 24) or
          ((map.mapId.toInt() and 0xFF) shl 16) or
          ((x and 0xFF) shl 8) or
          (y and 0xFF)

  private fun unpackPos(v: Int): List<Int> =
      listOf((v ushr 24) and 0xFF, (v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF)

  companion object {
    const val UI_PC_MONS = 1
    const val UI_PC_ITEMS = 2
    const val UI_FLY_MAP = 3
    const val LAST_TOWN_VAR = "field/lastTown"
    const val ESCAPE_VAR = "field/escape"
    const val EXCLAIM_MS = 700L
    // OBJ_EVENT_GFX_* per game (include/constants/event_objects.h).
    const val GFX_K_CUT_TREE = 95
    const val GFX_K_ROCK = 96
    const val GFX_K_BOULDER = 97
    const val GFX_H_CUT_TREE = 82
    const val GFX_H_ROCK = 86
    const val GFX_H_BOULDER = 87
  }
}
