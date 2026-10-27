package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.Tile2D
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.MetatileBehaviorNames
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.common.enums.TileBehavior
import de.fiereu.openmmo.dialog.generated.hoenn.SecretBase as SecretBaseText
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.net.game.packets.field.PlacedDecoration
import de.fiereu.openmmo.net.game.packets.field.SecretBaseAction
import de.fiereu.openmmo.net.game.packets.field.SecretBaseActionPacket
import de.fiereu.openmmo.net.game.packets.field.SecretBaseStatePacket
import de.fiereu.openmmo.progression.SecretBaseData
import de.fiereu.openmmo.server.game.field.FieldMoves
import de.fiereu.openmmo.server.game.field.FieldText
import de.fiereu.openmmo.server.game.field.SecretBaseRules
import de.fiereu.openmmo.server.game.progression.ProgressKeys
import de.fiereu.openmmo.server.game.script.MovementStep
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.script.ScriptRunner
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.SecretBaseRepository
import de.fiereu.openmmo.server.game.storage.SecretBaseRow
import de.fiereu.openmmo.server.game.storage.SocialRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/**
 * Emerald's secret bases (r4; secret_base.c, decoration.c, data/scripts/secret_base.inc): SECRET
 * POWER at a spot makes the player's one base there, the base map is entered through the spot and
 * left through its MAP_DYNAMIC warp, its PC places and puts away decorations (bought at the
 * decoration marts), and friends find the base at the same spot. One base per character, persisted
 * in `secret_bases`.
 */
@Singleton
class SecretBaseService
@Inject
constructor(
    private val repo: SecretBaseRepository,
    private val social: SocialRepository,
    private val characters: CharacterStore,
    private val maps: MapManager,
    private val scriptRunner: ScriptRunner,
    private val species: de.fiereu.openmmo.pokemon.SpeciesRegistry,
    private val scope: CoroutineScope,
) {
  /** Bases by owner, loaded on first need. Null value = no base. */
  private val bases = ConcurrentHashMap<Long, Optional>()

  private data class Optional(val row: SecretBaseRow?)

  /** Decorated base maps by owner, rebuilt when the decorations change. */
  private val decoratedMaps = ConcurrentHashMap<Long, Pair<List<PlacedDecoration>, MapDef>>()

  private val uiSeq = ConcurrentHashMap<Long, Int>()

  private fun now() = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)

  suspend fun baseOf(charId: Long): SecretBaseRow? =
      bases[charId]?.row ?: repo.get(charId).also { bases[charId] = Optional(it) }

  private fun cached(charId: Long): SecretBaseRow? = bases[charId]?.row

  /** The bases of the player's friends (one-way friends, like a registered base). */
  private suspend fun friendBases(charId: Long): List<SecretBaseRow> {
    val ids = social.friends(charId).map { it.friend.id }
    val rows = repo.ofCharacters(ids)
    rows.forEach { bases[it.characterId] = Optional(it) }
    return rows
  }

  private suspend fun save(row: SecretBaseRow) {
    repo.save(row, now())
    bases[row.characterId] = Optional(row)
    decoratedMaps.remove(row.characterId)
  }

  // ------------------------------------------------------------------ map entry / state

  /** After every arrival: the base state for this map (open entrances, or the base inside). */
  fun onMapEnter(session: SessionContext, state: PlayerState, map: MapDef) {
    val charId = state.characterId ?: return
    if (!SecretBaseData.isBaseMap(map.name)) state.secretBaseOwner = null
    scope.launch {
      runCatching { send(session, state, map) }
          .onFailure { log.warn(it) { "secret base state for $charId" } }
    }
  }

  private suspend fun send(session: SessionContext, state: PlayerState, map: MapDef, ui: Int = 0) {
    val charId = state.characterId ?: return
    val mine = baseOf(charId)
    // A login inside a base map: the player's own base when it is this map (bases are not
    // shared across sessions otherwise).
    if (state.secretBaseOwner == null &&
        SecretBaseData.isBaseMap(map.name) &&
        mine != null &&
        SecretBaseData.baseMapOf(mine.baseId)?.map == map.name) {
      state.secretBaseOwner = charId
    }
    val owner = state.secretBaseOwner
    val p =
        if (owner != null && SecretBaseData.isBaseMap(map.name)) {
          val row = if (owner == charId) mine else baseOf(owner)
          SecretBaseStatePacket(
              inside =
                  if (owner == charId) SecretBaseStatePacket.OWN
                  else SecretBaseStatePacket.VISITING,
              owner = row?.ownerName ?: "",
              baseId = row?.baseId ?: 0,
              decorations = row?.decorations.orEmpty(),
              uiSeq = if (ui != 0) nextUi(charId) else uiSeq[charId] ?: 0,
              uiKind = ui,
              myBaseId = mine?.baseId ?: 0,
          )
        } else {
          val spots = SecretBaseData.spotsOn(map.name)
          val open =
              if (spots.isEmpty()) emptyList()
              else {
                val ids = (listOfNotNull(mine) + friendBases(charId)).map { it.baseId }.toSet()
                spots.filter { it.id in ids }.map { it.x to it.y }
              }
          SecretBaseStatePacket(openSpots = open, myBaseId = mine?.baseId ?: 0)
        }
    session.send(p)
  }

  private fun nextUi(charId: Long): Int = uiSeq.merge(charId, 1, Int::plus) ?: 1

  /**
   * The map the server checks steps on: inside a base, the base map with its owner's decorations
   * drawn (ShowDecorationOnMap_ sets the metatile and its collision; dolls block as objects).
   */
  fun stepMap(state: PlayerState, map: MapDef): MapDef {
    val owner = state.secretBaseOwner ?: return map
    if (!SecretBaseData.isBaseMap(map.name)) return map
    val row = cached(owner) ?: return map
    val have = decoratedMaps[owner]
    if (have != null && have.first == row.decorations) return have.second
    val base = SecretBaseData.baseMaps.firstOrNull { it.map == map.name } ?: return map
    val d = SecretBaseRules.apply(base, row.decorations)
    val overrides = HashMap<Int, Tile2D>()
    for (i in d.cells.indices) {
      val c = d.cells[i]
      val tile = map.tiles.getOrNull(i) ?: continue
      if (c != null) {
        val beh = MetatileBehaviorNames.classify(c.behavior) ?: TileBehavior.NORMAL
        val coll = (tile.collision.toInt() and 0x3C) or (if (c.impassable) 1 else 0)
        overrides[i] = Tile2D(c.metatile.toShort(), coll.toByte(), beh)
      }
    }
    for (i in d.sprites) {
      val tile = overrides[i] ?: map.tiles.getOrNull(i) ?: continue
      overrides[i] = Tile2D(tile.material, (tile.collision.toInt() or 1).toByte(), tile.behavior)
    }
    val decorated = map.withTileOverrides(overrides)
    decoratedMaps[owner] = row.decorations to decorated
    return decorated
  }

  // ------------------------------------------------------------------ the spot

  /** A pressed at ([fx], [fy]): a secret base spot outdoors, or the PC inside. True if handled. */
  fun onTileInteract(
      session: SessionContext,
      state: PlayerState,
      map: MapDef,
      fx: Int,
      fy: Int,
  ): Boolean {
    if (Region.byId(state.regionId) != Region.HOENN) return false
    if (SecretBaseData.isBaseMap(map.name)) return pcInteract(session, state, map, fx, fy)
    val spot =
        SecretBaseData.spotsOn(map.name).firstOrNull { it.x == fx && it.y == fy } ?: return false
    scriptRunner.run(session, state, Script { ctx -> spotScript(ctx, state, map, spot) }, -1)
    return true
  }

  /** SecretBase_EventScript_CheckEntrance and what follows. */
  private suspend fun spotScript(
      ctx: ScriptContext,
      state: PlayerState,
      map: MapDef,
      spot: SecretBaseData.Spot,
  ) {
    val charId = state.characterId ?: return
    val mine = baseOf(charId)
    // The entrance is open: the player's own base, else a friend's base on this spot.
    if (mine?.baseId == spot.id) return enter(ctx, state, map, spot, mine, first = false)
    friendBases(charId)
        .firstOrNull { it.baseId == spot.id }
        ?.let { friend ->
          return enter(ctx, state, map, spot, friend, first = false)
        }
    val stored = characters.getCharacter(charId) ?: return
    val kind = kindAt(map, spot)
    val mon = FieldMoves.partyMonWith(stored.pokemon, SECRET_POWER)
    if (mon == null) {
      ctx.sign(
          when (kind) {
            Kind.TREE -> SecretBaseText.TreeCanBeClimbed
            Kind.SHRUB -> SecretBaseText.ClumpOfGrass
            Kind.CAVE -> SecretBaseText.SmallIndentInWall
          })
      return
    }
    ctx.bufferString(1, monName(mon))
    ctx.bufferString(2, "SECRET POWER")
    if (mine != null) {
      // SecretBase_EventScript_AlreadyHasSecretBase: one base per player.
      ctx.bufferString(1, nearbyName(mine.baseId))
      if (!ctx.askYesNo(SecretBaseText.WouldYouLikeToMoveBases)) return
      if (!ctx.askYesNo(SecretBaseText.AllDecorationsWillBeReturned)) return
      moveOut(charId)
      ctx.bufferString(1, monName(mon))
      if (!ctx.askYesNo(SecretBaseText.MovingCompletedUseSecretPower)) return
      // The decorations went back to the PC with the old base; the new one starts empty.
    } else {
      val ask =
          when (kind) {
            Kind.TREE -> SecretBaseText.TreeUseSecretPower
            Kind.SHRUB -> SecretBaseText.ClumpUseSecretPower
            Kind.CAVE -> SecretBaseText.IndentUseSecretPower
          }
      if (!ctx.askYesNo(ask)) return
      ctx.say(FieldText.of(Region.HOENN).usedMove)
    }
    ctx.sign(
        when (kind) {
          Kind.TREE -> SecretBaseText.VineDroppedDown
          Kind.SHRUB -> SecretBaseText.DiscoveredSmallEntrance
          Kind.CAVE -> SecretBaseText.DiscoveredSmallCavern
        })
    val row = SecretBaseRow(charId, stored.info.name, spot.id)
    enter(ctx, state, map, spot, row, first = true)
  }

  /**
   * EnterSecretBase: the base map at its entrance, with the way back (MAP_DYNAMIC) set to the
   * spot's front. A first visit walks two steps in and asks (SecretBase_EventScript_FirstEntrance);
   * yes puts the player in front of the PC (EnterNewlyCreatedSecretBase).
   */
  private suspend fun enter(
      ctx: ScriptContext,
      state: PlayerState,
      map: MapDef,
      spot: SecretBaseData.Spot,
      row: SecretBaseRow,
      first: Boolean,
  ) {
    val charId = state.characterId ?: return
    val base = SecretBaseData.baseMapOf(spot.id) ?: return
    val baseMap = maps.getMapByName(state.regionId, base.map) ?: return
    val warp = baseMap.warps.getOrNull(base.entranceWarp) ?: return
    val backX = state.x.toInt()
    val backY = state.y.toInt()
    ctx.setDynamicWarp(
        state.regionId, map.bankId.toInt(), map.mapId.toInt(), backX, backY, Direction.DOWN)
    if (!first && row.characterId == charId) {
      save(row.copy(timesEntered = row.timesEntered + 1))
    } else bases.putIfAbsent(row.characterId, Optional(row))
    state.secretBaseOwner = row.characterId
    if (first) {
      // Not saved yet: shown as the player's own base while it decides.
      bases[charId] = Optional(row)
    }
    ctx.warp(
        state.regionId, baseMap.bankId.toInt(), baseMap.mapId.toInt(), warp.x, warp.y, Direction.UP)
    if (!first) return
    ctx.moveSelf(MovementStep.WALK_UP, MovementStep.WALK_UP)
    if (ctx.askYesNo(SecretBaseText.WantToMakeYourSecretBaseHere)) {
      save(row)
      ctx.setFlag("hoenn/FLAG_RECEIVED_SECRET_POWER")
      log.info { "char=$charId made a secret base at ${spot.map} (${spot.id})" }
      ctx.warp(
          state.regionId,
          baseMap.bankId.toInt(),
          baseMap.mapId.toInt(),
          base.pcX,
          base.pcY,
          Direction.UP)
    } else {
      // ClearAndLeaveSecretBase
      bases[charId] = Optional(null)
      state.secretBaseOwner = null
      ctx.warp(state.regionId, map.bankId.toInt(), map.mapId.toInt(), backX, backY, Direction.DOWN)
    }
  }

  /** MoveOutOfSecretBase: the base goes; its decorations were only lent, so the PC keeps them. */
  private suspend fun moveOut(charId: Long) {
    repo.delete(charId)
    bases[charId] = Optional(null)
    decoratedMaps.remove(charId)
    log.info { "char=$charId packed up their secret base" }
  }

  // ------------------------------------------------------------------ the PC

  private fun pcInteract(
      session: SessionContext,
      state: PlayerState,
      map: MapDef,
      fx: Int,
      fy: Int,
  ): Boolean {
    val base = SecretBaseData.baseMaps.firstOrNull { it.map == map.name } ?: return false
    val beh = base.behaviorAt(fx, fy)
    if (beh != "MB_SECRET_BASE_PC" && beh != "MB_SECRET_BASE_REGISTER_PC") return false
    val charId = state.characterId ?: return false
    val owner = state.secretBaseOwner ?: return false
    scriptRunner.run(
        session,
        state,
        Script { ctx ->
          if (owner == charId && cached(charId) != null) {
            // SecretBase_EventScript_PC: DECORATION / PUT AWAY / PACK UP / TURN OFF.
            ctx.sign(SecretBaseText.BootUpPC)
            send(session, state, map, SecretBaseStatePacket.UI_PC)
          } else {
            // A friend's base: the Register PC.
            ctx.bufferString(1, cached(owner)?.ownerName ?: "")
            ctx.sign(SecretBaseText.RegistryInfo)
          }
        },
        -1)
    return true
  }

  /** c2s SecretBaseActionPacket: the PC menu inside the player's own base. */
  suspend fun onAction(event: PacketEvent<SecretBaseActionPacket>) {
    val session = event.session
    val state = session.attributes[PLAYER_STATE] ?: return
    val charId = state.characterId ?: return
    if (state.secretBaseOwner != charId) return
    val row = baseOf(charId) ?: return
    val map = maps.getMap(state.regionId, state.bankId, state.mapId) ?: return
    val base = SecretBaseData.baseMaps.firstOrNull { it.map == map.name } ?: return
    val p = event.packet
    when (p.action) {
      SecretBaseAction.PLACE -> {
        val msg = place(charId, row, base, p.decorId, p.x, p.y, state.x.toInt(), state.y.toInt())
        if (msg != null) session.send(notice(msg))
      }
      SecretBaseAction.PUT_AWAY -> {
        val i = SecretBaseRules.at(row.decorations, p.x, p.y)
        if (i >= 0) save(row.copy(decorations = row.decorations.filterIndexed { j, _ -> j != i }))
        else session.send(notice("There is no decoration there."))
      }
      SecretBaseAction.PACK_UP -> {
        moveOut(charId)
        state.secretBaseOwner = null
        session.send(notice("All decorations were returned to your PC."))
      }
      SecretBaseAction.CLOSE -> {}
    }
    send(session, state, map)
  }

  /** Null when [decorId] went in at ([x], [y]), else the reason. */
  suspend fun place(
      charId: Long,
      row: SecretBaseRow,
      base: SecretBaseData.BaseMap,
      decorId: Int,
      x: Int,
      y: Int,
      playerX: Int,
      playerY: Int,
  ): String? {
    val decor = SecretBaseData.decorations[decorId] ?: return "That is not a decoration."
    val owned = characters.getCharacter(charId)?.storyVars?.get(ProgressKeys.decor(decorId)) ?: 0
    if (row.decorations.count { it.decorId == decorId } >= owned)
        return "There are no more of those in the PC."
    // HasDecorationSpace
    if (row.decorations.size >= SecretBaseData.MAX_DECORATIONS)
        return "No more decorations can be placed. The most that can be placed are 16."
    val d = SecretBaseRules.apply(base, row.decorations)
    if (!SecretBaseRules.canPlace(d, decor, x, y, playerX, playerY))
        return "It can't be placed here."
    save(row.copy(decorations = row.decorations + PlacedDecoration(decorId, x, y)))
    return null
  }

  // ------------------------------------------------------------------ helpers

  private enum class Kind {
    CAVE,
    TREE,
    SHRUB,
  }

  /** GetSecretBaseTypeInFrontOfPlayer, from the base map the spot leads to. */
  private fun kindAt(map: MapDef, spot: SecretBaseData.Spot): Kind {
    val name = SecretBaseData.baseMapOf(spot.id)?.map ?: ""
    return when {
      "Tree" in name -> Kind.TREE
      "Shrub" in name -> Kind.SHRUB
      else -> Kind.CAVE
    }
  }

  /** GetSecretBaseNearbyMapName: the route the base is on. */
  private fun nearbyName(baseId: Int): String {
    val spot = SecretBaseData.spot(baseId) ?: return ""
    return Regex("""([A-Za-z]+)(\d+)""")
        .replace(spot.map) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        .uppercase()
  }

  private fun monName(mon: de.fiereu.openmmo.common.Pokemon): String =
      mon.nickname.ifEmpty { species.get(mon.dexId)?.name ?: "POKéMON" }.uppercase()

  companion object {
    /** MOVE_SECRET_POWER. */
    const val SECRET_POWER = 290
  }
}
