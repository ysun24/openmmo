package de.fiereu.openmmo.server.game.session

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.net.game.packets.field.TravelMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session player state. Scripts run on their own coroutine while packets are answered on the
 * mailbox coroutine, and both threads touch every field here, so all of them are volatile.
 */
data class PlayerState(
    val userId: Int,
    @field:Volatile var characterId: Long? = null,
    @field:Volatile var justWarped: Boolean = false,
    @field:Volatile var facingDirection: Direction = Direction.DOWN,
    @field:Volatile var inDialog: Boolean = false,
    @field:Volatile var dialogNpcEntityId: Long = 0,
    @field:Volatile var dialogSeqId: Int = 0,
    @field:Volatile var regionId: Int = 1,
    @field:Volatile var bankId: Int = 51,
    @field:Volatile var mapId: Int = 3,
    @field:Volatile var x: Short = 4,
    @field:Volatile var y: Short = 2,
    @field:Volatile var elevation: Int = 0,
    /** Trusts one source tile after scripted movement. */
    @field:Volatile var acceptNextMoveSource: Boolean = false,
    /**
     * A script is warping and will run the destination's entry scripts itself. The arrival must not
     * start a second copy, which [inDialog] alone cannot prevent because a stray dialog packet
     * clears it while the script is parked on the map load.
     */
    @field:Volatile var scriptOwnsMapEntry: Boolean = false,
    /** Maps the client already holds. A warp sends deleteCache, which empties this. */
    val loadedMaps: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
    /** How the avatar travels (walk, bike, surf, underwater). Kept across seamless connections. */
    @field:Volatile var travel: TravelMode = TravelMode.WALK,
    /** r4: the Acro Bike trick state the client last reported (FieldAction.ACRO). */
    @field:Volatile
    var acro: de.fiereu.openmmo.net.game.packets.field.AcroTrick =
        de.fiereu.openmmo.net.game.packets.field.AcroTrick.NONE,
    /** The last step's running flag, for observers' animation. */
    @field:Volatile var running: Boolean = false,
    /** r4: whose secret base the player is in (character id), null outside a base. */
    @field:Volatile var secretBaseOwner: Long? = null,
    /** FLASH lit this map (FLAG_SYS_USE_FLASH, cleared on map change). */
    @field:Volatile var flash: Boolean = false,
    /** STRENGTH is active (FLAG_SYS_USE_STRENGTH, cleared on map change). */
    @field:Volatile var strength: Boolean = false,
    /**
     * Local ids of field objects cut, smashed or taken on the current map this visit (the decomp's
     * temporary flags, cleared on every map change).
     */
    val removedObjects: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
    /** Counts server requests to open a client UI (FieldInfoPacket.uiSeq). */
    @field:Volatile var uiSeq: Int = 0,
    /**
     * FRLG VS Seeker answers on the current map: trainer npc local id -> the rematch party's
     * trainer id (the save's trainerRematches, cleared on every map change by
     * MapResetTrainerRematches).
     */
    val rematchReady: MutableMap<Int, Int> = ConcurrentHashMap(),
    /** FRLG trainerRematchStepCounter: steps since the VS Seeker found rematches (full at 100). */
    @field:Volatile var vsSeekerSteps: Int = 100,
)

/** Packs a map address into one key for [PlayerState.loadedMaps]. */
fun mapCacheKey(regionId: Int, bankId: Int, mapId: Int): Int =
    (regionId shl 16) or (bankId shl 8) or mapId
