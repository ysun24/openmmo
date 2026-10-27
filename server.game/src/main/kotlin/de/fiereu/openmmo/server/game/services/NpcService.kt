package de.fiereu.openmmo.server.game.services

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.MovementType
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.maps.NpcDef
import de.fiereu.openmmo.net.game.packets.EntityLeavePacket
import de.fiereu.openmmo.net.game.packets.NpcSpawnPacket
import de.fiereu.openmmo.net.game.packets.NpcUpdatePacket
import de.fiereu.openmmo.server.game.services.social.ExtraNpcSource
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private val log = KotlinLogging.logger {}

@Singleton
class NpcService
@Inject
constructor(
    private val mapManager: MapManager,
    private val characterStore: CharacterStore,
    // r3-social: server-added npcs (the market clerk in every Pokemon Center).
    private val extras: ExtraNpcSource = ExtraNpcSource.NONE,
    // r5-scripts: the map's ON_LOAD / ON_TRANSITION / ON_RESUME scripts run before it spawns.
    private val vmMapScripts:
        javax.inject.Provider<de.fiereu.openmmo.server.game.script.vm.VmMapScripts>? =
        null,
    private val scriptUi: de.fiereu.openmmo.server.game.script.vm.ScriptUiService? = null,
) {

  private val npcEntityIdCounter = AtomicLong(0x1A69000000000000L)
  private val npcEntityIds = mutableMapOf<String, Long>()

  fun getNpcEntityId(regionId: Int, bankId: Int, mapId: Int, entityIdx: Int): Long? {
    return npcEntityIds[key(regionId, bankId, mapId, entityIdx)]
  }

  fun spawnNpcsForMap(ctx: SessionContext, bankId: Int, mapId: Int, regionId: Int) {
    val map = mapManager.getMap(regionId, bankId, mapId) ?: return
    ctx.attributes[PLAYER_STATE]?.let { vmMapScripts?.get()?.onLoad(ctx, it, map) }
    val vmState = de.fiereu.openmmo.server.game.script.vm.VmState.of(ctx)
    val stored = ctx.attributes[PLAYER_STATE]?.characterId?.let(characterStore::getCharacter)
    val storyFlags = stored?.storyFlags.orEmpty()
    val storyVars = stored?.storyVars.orEmpty()

    for (npc in map.npcs) {
      // Decoration slots are not normal NPCs.
      if (npc.hideFlag.substringAfter('/').startsWith(DECORATION_FLAG_PREFIX)) continue
      // r4: the record-mixing base trainer stays hidden (FLAG_HIDE_SECRET_BASE_TRAINER).
      if (npc.hideFlag.endsWith("FLAG_HIDE_SECRET_BASE_TRAINER")) continue

      // Only set hide flags suppress NPCs.
      if (shouldHideNpc(bankId, mapId, npc, storyFlags)) continue
      // Trees cut, rocks smashed and balls taken this visit (the decomp's temporary flags).
      if (ctx.attributes[PLAYER_STATE]?.removedObjects?.contains(npc.entityIdx) == true) continue
      // A boulder the player pushed with STRENGTH stays where they left it.
      val pushed = storyVars["field/boulder/$regionId:$bankId:$mapId:${npc.entityIdx}"] ?: 0
      val placed =
          if (pushed > 0) npc.copy(x = (pushed - 1) shr 8, y = (pushed - 1) and 0xFF) else npc
      val resolved =
          scriptPlacement(
              vmState,
              resolveDynamicGraphics(
                  ctx, applyStoryPlacement(bankId, mapId, placed, storyFlags, storyVars)))
      ctx.send(
          buildSpawnPacket(
              resolved,
              entityIdFor(regionId, bankId, mapId, npc.entityIdx),
              regionId,
              bankId,
              mapId,
          ))
    }
    extras.spawn(ctx, regionId, bankId, mapId)
    // r5-scripts: metatiles the map scripts changed (setmetatile in ON_LOAD / ON_TRANSITION).
    if (vmState.metatiles.isNotEmpty() && scriptUi != null) {
      scriptUi.send(ctx, "metatiles") {
        put(
            "tiles",
            kotlinx.serialization.json.JsonArray(
                vmState.metatiles.entries.map { (k, v) ->
                  kotlinx.serialization.json.buildJsonObject {
                    put("x", k and 0xFFFF)
                    put("y", k shr 16)
                    put("tile", v.first)
                    put("impassable", v.second)
                  }
                }))
      }
    }
  }

  /** r5-scripts: setobjectxyperm / setobjectmovementtype of this player's map scripts. */
  private fun scriptPlacement(
      vm: de.fiereu.openmmo.server.game.script.vm.VmState,
      npc: NpcDef
  ): NpcDef {
    val pos = vm.templatePos[npc.entityIdx]
    val move = vm.templateMovement[npc.entityIdx]?.let(::movementTypeOf)
    if (pos == null && move == null) return npc
    var out = npc
    if (pos != null) out = out.copy(x = pos.first, y = pos.second)
    if (move != null) out = out.copy(movementType = move, facing = facingOf(move) ?: out.facing)
    return out
  }

  private fun movementTypeOf(decompId: Int): MovementType? {
    val name =
        de.fiereu.openmmo.server.game.script.vm.ScriptImage.of("hoenn")
            ?.nameOf("MOVEMENT_TYPE_", decompId)
            ?.removePrefix("MOVEMENT_TYPE_") ?: return null
    return MovementType.entries.firstOrNull { it.name == name }
  }

  private fun facingOf(m: MovementType): Direction? =
      when (m.name) {
        "FACE_UP" -> Direction.UP
        "FACE_DOWN" -> Direction.DOWN
        "FACE_LEFT" -> Direction.LEFT
        "FACE_RIGHT" -> Direction.RIGHT
        else -> null
      }

  /** Allocate (or return) the stable entity id for a map npc by its decomp local id. */
  fun entityIdFor(regionId: Int, bankId: Int, mapId: Int, entityIdx: Int): Long =
      npcEntityIds.getOrPut(key(regionId, bankId, mapId, entityIdx)) {
        npcEntityIdCounter.incrementAndGet()
      }

  /** Spawn a single npc (including a normally hidden one) for one player, for cutscenes. */
  fun spawnNpc(ctx: SessionContext, regionId: Int, bankId: Int, mapId: Int, localId: Int) {
    val npc = findNpc(regionId, bankId, mapId, localId) ?: return
    ctx.send(
        buildSpawnPacket(
            resolveDynamicGraphics(ctx, npc),
            entityIdFor(regionId, bankId, mapId, localId),
            regionId,
            bankId,
            mapId,
        ))
  }

  /** Spawns an NPC at a cutscene position. */
  fun spawnNpcAt(
      ctx: SessionContext,
      regionId: Int,
      bankId: Int,
      mapId: Int,
      localId: Int,
      x: Int,
      y: Int,
  ) {
    val npc = findNpc(regionId, bankId, mapId, localId) ?: return
    ctx.send(
        buildSpawnPacket(
            resolveDynamicGraphics(ctx, npc.copy(x = x, y = y)),
            entityIdFor(regionId, bankId, mapId, localId),
            regionId,
            bankId,
            mapId,
        ))
  }

  /** Repositions an existing NPC. */
  fun repositionNpc(
      ctx: SessionContext,
      regionId: Int,
      bankId: Int,
      mapId: Int,
      localId: Int,
      x: Int,
      y: Int,
  ) {
    val npc = findNpc(regionId, bankId, mapId, localId) ?: return
    ctx.send(
        NpcUpdatePacket(
            entityId = entityIdFor(regionId, bankId, mapId, localId),
            regionId = regionId,
            bankId = bankId,
            mapId = mapId,
            x = x,
            y = y,
            // Captures use 0xF6 followed by the direction for this update packet.
            facing = 0xF6,
            unk = npc.facing.ordinal,
        ))
  }

  /** Removes a cutscene NPC. */
  fun despawnNpc(ctx: SessionContext, regionId: Int, bankId: Int, mapId: Int, localId: Int) {
    val entityId = getNpcEntityId(regionId, bankId, mapId, localId) ?: return
    ctx.send(EntityLeavePacket(entityId))
  }

  private fun findNpc(regionId: Int, bankId: Int, mapId: Int, localId: Int): NpcDef? {
    val npc =
        mapManager.getMap(regionId, bankId, mapId)?.npcs?.firstOrNull { it.entityIdx == localId }
    if (npc == null) log.warn { "npc $localId not found on $regionId:$bankId:$mapId" }
    return npc
  }

  private fun key(regionId: Int, bankId: Int, mapId: Int, entityIdx: Int) =
      "$regionId:$bankId:$mapId:$entityIdx"

  private fun buildSpawnPacket(
      npc: NpcDef,
      entityId: Long,
      regionId: Int,
      bankId: Int,
      mapId: Int,
  ): NpcSpawnPacket {
    val region = requireNotNull(Region.byId(regionId)) { "Unknown region id $regionId" }
    val movementId = npc.movementType.forRegion(region).id
    val unk3 = ((movementId and 0xFF) shl 8) or 0x02
    val unk4 =
        if (movementId in 1..6 || (movementId in 25..52)) {
          ((npc.movementRangeX and 0xFF) shl 8) or (npc.movementRangeY and 0xFF)
        } else {
          0
        }
    return NpcSpawnPacket(
        entityId = entityId,
        spriteRegionId = regionId,
        graphicsId = npc.graphicsId,
        unk3 = unk3,
        unk4 = unk4,
        regionId = regionId,
        bankId = bankId,
        mapId = mapId,
        x = npc.x,
        y = npc.y,
        facing = npc.facing.ordinal,
        unk5 = 2,
        unk6 = 8,
    )
  }

  private fun resolveDynamicGraphics(ctx: SessionContext, npc: NpcDef): NpcDef {
    if (npc.graphicsId != DYNAMIC_GFX_VAR_0 ||
        !(npc.script.contains("Rival", ignoreCase = true) ||
            npc.hideFlag.contains("RIVAL", ignoreCase = true))) {
      return npc
    }
    val playerGender =
        ctx.attributes[PLAYER_STATE]?.characterId?.let(characterStore::getCharacter)?.info?.rivalSex
    // The rival is the opposite gender from the player.
    val graphicsId = if (playerGender == FEMALE) RIVAL_BRENDAN_NORMAL else RIVAL_MAY_NORMAL
    return npc.copy(graphicsId = graphicsId)
  }

  private fun applyStoryPlacement(
      bankId: Int,
      mapId: Int,
      npc: NpcDef,
      storyFlags: Set<String>,
      storyVars: Map<String, Int>,
  ): NpcDef {
    if (bankId != LITTLEROOT_BANK || mapId != LITTLEROOT_MAP || npc.entityIdx != LITTLEROOT_TWIN) {
      return npc
    }
    if (RESCUED_BIRCH_FLAG in storyFlags) return npc
    return if (storyVars[LITTLEROOT_STATE_VAR].orZero() == 0) {
      npc.copy(x = 7, y = 2, facing = Direction.DOWN, movementType = MovementType.FACE_DOWN)
    } else {
      npc.copy(x = 10, y = 1, facing = Direction.UP, movementType = MovementType.FACE_UP)
    }
  }

  private fun Int?.orZero(): Int = this ?: 0

  private fun shouldHideNpc(
      bankId: Int,
      mapId: Int,
      npc: NpcDef,
      storyFlags: Set<String>,
  ): Boolean {
    if (npc.hideFlag in storyFlags) return true
    // Completed rescues must not restore Zigzagoon.
    return bankId == ROUTE_101_BANK &&
        mapId == ROUTE_101_MAP &&
        npc.entityIdx == ROUTE_101_ZIGZAGOON &&
        (RESCUED_BIRCH_FLAG in storyFlags || ROUTE_101_RESCUE_HIDDEN_FLAG in storyFlags)
  }

  private companion object {
    const val DECORATION_FLAG_PREFIX = "FLAG_DECORATION_"
    const val DYNAMIC_GFX_VAR_0 = 240
    const val RIVAL_BRENDAN_NORMAL = 100
    const val RIVAL_MAY_NORMAL = 105
    const val FEMALE: Byte = 1
    const val LITTLEROOT_BANK = 50
    const val LITTLEROOT_MAP = 9
    const val LITTLEROOT_TWIN = 0
    const val RESCUED_BIRCH_FLAG = "hoenn/FLAG_RESCUED_BIRCH"
    const val LITTLEROOT_STATE_VAR = "hoenn/VAR_LITTLEROOT_TOWN_STATE"
    const val ROUTE_101_BANK = 50
    const val ROUTE_101_MAP = 16
    const val ROUTE_101_ZIGZAGOON = 3
    const val ROUTE_101_RESCUE_HIDDEN_FLAG = "hoenn/FLAG_HIDE_ROUTE_101_BIRCH_ZIGZAGOON_BATTLE"
  }
}
