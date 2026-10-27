package de.fiereu.openmmo.client.world

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.net.game.packets.field.FieldInfoPacket
import de.fiereu.openmmo.net.game.packets.field.TravelMode

/** Map coordinates the server addresses everything by. Region 0 is Kanto, 1 is Hoenn. */
data class MapKey(val regionId: Int, val bankId: Int, val mapId: Int)

data class Entity(
    val id: Long,
    val name: String,
    val map: MapKey,
    val x: Int,
    val y: Int,
    val facing: Direction,
    val isPlayer: Boolean,
    /** Decomp OBJ_EVENT_GFX_* id for npcs, -1 for players (drawn with the player sprite). */
    val graphicsId: Int = -1,
    val visible: Boolean = true,
    /** How it travels: a [de.fiereu.openmmo.net.game.packets.field.TravelMode]. */
    val travel: TravelMode = TravelMode.WALK,
    val running: Boolean = false,
    /** Grows by one per ledge hop the server reported (movement mode 6). */
    val jumpSeq: Int = 0,
    /** Grows by one per trainer "!" over this entity. */
    val exclaimSeq: Int = 0,
)

/**
 * One chat log line. [channel] is the [de.fiereu.openmmo.net.game.packets.social.ChatChannel] name
 * for player chat (LOCAL, GLOBAL, TRADE, GUILD, WHISPER, SYSTEM) and empty for server notices; [to]
 * is the whisper target.
 */
data class ChatLine(
    val sender: String,
    val message: String,
    val channel: String = "",
    val to: String = "",
)

/**
 * Everything the client knows about the world. Written from the netty thread and read from the UI,
 * so every access goes through the lock and readers get immutable snapshots.
 */
class WorldState {
  data class Snapshot(
      val characterName: String?,
      val selfId: Long?,
      val map: MapKey?,
      val mapWidth: Int,
      val mapHeight: Int,
      val entities: Map<Long, Entity>,
      val chat: List<ChatLine>,
      val inWorld: Boolean,
      val overworld: OverworldState.View? = null,
      /** The server's last FieldInfoPacket: dark map, flash, usable field moves, FLY towns. */
      val field: FieldInfoPacket? = null,
  ) {
    val self: Entity?
      get() = selfId?.let { entities[it] }
  }

  private val lock = Any()
  private var characterName: String? = null
  private var selfId: Long? = null
  private var map: MapKey? = null
  private var mapWidth = 0
  private var mapHeight = 0
  private val entities = linkedMapOf<Long, Entity>()
  private val chat = ArrayDeque<ChatLine>()
  private var inWorld = false

  /** Dialog, shop, bag and known maps; see [OverworldState]. */
  val overworld = OverworldState()

  fun snapshot(): Snapshot =
      synchronized(lock) {
        Snapshot(
            characterName,
            selfId,
            map,
            mapWidth,
            mapHeight,
            entities.toMap(),
            chat.toList(),
            inWorld,
            overworld.view(),
            field)
      }

  private var field: FieldInfoPacket? = null

  fun setField(info: FieldInfoPacket) = synchronized(lock) { field = info }

  fun selectCharacter(id: Long, name: String) =
      synchronized(lock) {
        selfId = id
        characterName = name
      }

  fun enterMap(key: MapKey, width: Int, height: Int, clearEntities: Boolean) =
      synchronized(lock) {
        map = key
        mapWidth = width
        mapHeight = height
        inWorld = false
        if (clearEntities) entities.clear()
      }

  fun upsert(entity: Entity) =
      synchronized(lock) {
        entities[entity.id] = entity
        if (entity.id == selfId) inWorld = true
      }

  fun move(id: Long, x: Int, y: Int, facing: Direction) =
      synchronized(lock) {
        val old = entities[id] ?: return@synchronized
        entities[id] = old.copy(x = x, y = y, facing = facing)
      }

  /** Moves an entity onto another map, as a seamless connection or a server reposition does. */
  fun relocate(id: Long, key: MapKey, x: Int, y: Int, facing: Direction) =
      synchronized(lock) {
        val old = entities[id] ?: return@synchronized
        entities[id] = old.copy(map = key, x = x, y = y, facing = facing)
      }

  /** Switches the current map without a reload, keeping the entities that are already known. */
  fun switchMap(key: MapKey, width: Int, height: Int) =
      synchronized(lock) {
        map = key
        mapWidth = width
        mapHeight = height
      }

  fun update(id: Long, f: (Entity) -> Entity) =
      synchronized(lock) { entities[id]?.let { entities[id] = f(it) } }

  fun remove(id: Long) = synchronized(lock) { entities.remove(id) }

  fun addChat(line: ChatLine) =
      synchronized(lock) {
        chat.addLast(line)
        while (chat.size > 200) chat.removeFirst()
      }

  fun setInWorld(value: Boolean) = synchronized(lock) { inWorld = value }
}
