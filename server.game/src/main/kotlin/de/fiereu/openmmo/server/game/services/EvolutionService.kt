package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.SocialListEntryAddPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleExtPacket
import de.fiereu.openmmo.net.game.packets.battle.EvolutionReplyPacket
import de.fiereu.openmmo.net.game.packets.battle.moves.MoveLearnPromptPacket
import de.fiereu.openmmo.pokemon.EvolutionDef
import de.fiereu.openmmo.pokemon.EvolutionMethod
import de.fiereu.openmmo.pokemon.EvolutionRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.ItemNames
import de.fiereu.openmmo.server.game.battle.MoveLearnPrompts
import de.fiereu.openmmo.server.game.battle.MoveLearner
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.battle.acquiredMonsterDelta
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}

/** What asks a monster to evolve, the decomp's EVO_MODE_*. */
enum class EvolutionTrigger {
  /** EVO_MODE_NORMAL: after a level up (battle EXP, Rare Candy). */
  LEVEL_UP,
  /** EVO_MODE_TRADE: the monster was just traded. */
  TRADE,
  /** EVO_MODE_ITEM_USE: a stone was used on it. */
  ITEM,
}

/** An evolution about to play: [consumeItem] is taken from the bag (stones) when it completes. */
internal data class EvoScene(
    val charId: Long,
    val monId: Long,
    val toDex: Int,
    val consumeItem: Int = 0,
    val consumeHeld: Boolean = false,
)

/**
 * The Gen 3 evolution engine (src/pokemon.c GetEvolutionTargetSpecies, src/evolution_scene.c).
 * [tryEvolve] picks the target species for a trigger; [startEvolution] plays the scene on the
 * player's client (OpenMMO extension packet `{"t":"evo"}`), which answers with
 * [EvolutionReplyPacket]: proceed, or B to stop it. A completed evolution changes the species,
 * recomputes the stats keeping the hp lost, learns the new species' moves at the current level
 * (offering a prompt when the moveset is full), creates Shedinja next to Ninjask, and syncs the
 * party. Several evolutions after one battle play one after another.
 */
@Singleton
class EvolutionService
@Inject
constructor(
    private val characters: CharacterStore,
    private val species: SpeciesRegistry,
    private val evolutions: EvolutionRegistry,
    private val items: ItemRegistry,
    private val moveLearner: MoveLearner,
    private val prompts: MoveLearnPrompts,
    private val sessions: SessionRegistry,
    private val entityIds: EntityIdService,
    /** r5-contests: the beauty condition (EVO_BEAUTY). */
    private val beauty: de.fiereu.openmmo.server.game.contest.MonBeauty =
        de.fiereu.openmmo.server.game.contest.MonBeauty { 0 },
) {
  private val queues = ConcurrentHashMap<Long, ConcurrentLinkedDeque<EvoScene>>()
  private val showing = ConcurrentHashMap<Long, EvoScene>()

  /** Clock for the day/night friendship evolutions (Espeon / Umbreon); tests override it. */
  internal var clock: () -> LocalTime = { LocalTime.now() }

  /**
   * The species [mon] evolves into for [trigger], or null. [item] is the stone used (ITEM). An
   * Everstone stops level and friendship evolutions, as in Gen 3.
   */
  fun tryEvolve(mon: Pokemon, trigger: EvolutionTrigger, item: ItemDef? = null): Int? {
    val heldDef = items.get(mon.heldItem)
    for (evo in evolutions.of(mon.dexId)) {
      val ok =
          when (trigger) {
            EvolutionTrigger.LEVEL_UP -> heldDef != Items.EVERSTONE && levelRule(evo, mon)
            EvolutionTrigger.TRADE ->
                when (evo.method) {
                  EvolutionMethod.TRADE -> true
                  EvolutionMethod.TRADE_ITEM ->
                      heldDef != null && ItemNames.byDecompName(evo.item) == heldDef
                  else -> false
                }
            EvolutionTrigger.ITEM ->
                evo.method == EvolutionMethod.ITEM &&
                    item != null &&
                    ItemNames.byDecompName(evo.item) == item
          }
      if (ok && species.get(evo.toDex) != null) return evo.toDex
    }
    return null
  }

  private fun levelRule(evo: EvolutionDef, mon: Pokemon): Boolean {
    val level = mon.level.toInt()
    val hour = clock().hour
    return when (evo.method) {
      EvolutionMethod.FRIENDSHIP -> mon.friendship >= FRIENDSHIP_EVOLVE
      EvolutionMethod.FRIENDSHIP_DAY -> mon.friendship >= FRIENDSHIP_EVOLVE && hour in 12..23
      EvolutionMethod.FRIENDSHIP_NIGHT -> mon.friendship >= FRIENDSHIP_EVOLVE && hour in 0..11
      EvolutionMethod.LEVEL,
      EvolutionMethod.LEVEL_NINJASK -> level >= evo.param
      EvolutionMethod.LEVEL_ATK_GT_DEF ->
          level >= evo.param && stats(mon)?.let { it.atk > it.def } == true
      EvolutionMethod.LEVEL_ATK_EQ_DEF ->
          level >= evo.param && stats(mon)?.let { it.atk == it.def } == true
      EvolutionMethod.LEVEL_ATK_LT_DEF ->
          level >= evo.param && stats(mon)?.let { it.atk < it.def } == true
      // The upper half of the personality, mod 10: 0-4 Silcoon, 5-9 Cascoon.
      EvolutionMethod.LEVEL_SILCOON -> level >= evo.param && upperPersonality(mon) % 10 <= 4
      EvolutionMethod.LEVEL_CASCOON -> level >= evo.param && upperPersonality(mon) % 10 > 4
      // Shedinja is the extra monster Ninjask's evolution leaves behind, never a target itself.
      EvolutionMethod.LEVEL_SHEDINJA -> false
      // r5-contests: Feebas evolves on a level up once its beauty reaches the param (170).
      EvolutionMethod.BEAUTY -> beauty.beautyOf(mon.id) >= evo.param
      else -> false
    }
  }

  private fun upperPersonality(mon: Pokemon): Int =
      ((mon.seed.toLong() and 0xFFFFFFFFL) ushr 16).toInt()

  private fun stats(mon: Pokemon) =
      species.get(mon.dexId)?.let { StatCalculator.computeAll(it, mon) }

  /** Queues the scene for [monId] evolving into [toDex] and plays it when nothing else is up. */
  fun startEvolution(
      session: SessionContext,
      charId: Long,
      monId: Long,
      toDex: Int,
      consumeItem: Int = 0,
      consumeHeld: Boolean = false,
  ) {
    queues.getOrPut(charId) { ConcurrentLinkedDeque() } +=
        EvoScene(charId, monId, toDex, consumeItem, consumeHeld)
    playNext(session, charId)
  }

  /**
   * After a battle: every monster that levelled up gets its level-up evolution check, in party
   * order. Called once the player is back on the map.
   */
  fun afterBattle(session: SessionContext, charId: Long, leveled: Collection<Long>) {
    if (leveled.isEmpty()) return
    val party = characters.getCharacter(charId)?.pokemon ?: return
    for (mon in party.filter { it.id in leveled && it.hp > 0 }.sortedBy { it.containerSlot }) {
      val to = tryEvolve(mon, EvolutionTrigger.LEVEL_UP) ?: continue
      startEvolution(session, charId, mon.id, to)
    }
  }

  /**
   * The items hook: a stone used on party monster [monId]. Returns null when the scene started (the
   * stone is taken when the evolution completes) or the refusal text.
   */
  fun useEvolutionItem(session: SessionContext, charId: Long, monId: Long, itemId: Int): String? {
    val item = items.get(itemId) ?: return "That item does not exist."
    val stored = characters.getCharacter(charId) ?: return ""
    if ((stored.items[itemId] ?: 0) <= 0) return "You don't have any ${item.name}."
    val mon =
        stored.pokemon.firstOrNull { it.id == monId } ?: return "Choose a POKéMON in your party."
    val to = tryEvolve(mon, EvolutionTrigger.ITEM, item) ?: return "It won't have any effect."
    startEvolution(session, charId, mon.id, to, consumeItem = itemId)
    return null
  }

  /** True when [itemId] is an evolution stone for some species, for the items worker. */
  fun isEvolutionItem(itemId: Int): Boolean {
    val def = items.get(itemId) ?: return false
    return STONES.contains(def)
  }

  /**
   * The trade hook: [mon] just arrived at [charId] by trade. Starts its trade evolution (Kadabra,
   * Haunter, Onix with a Metal Coat...) when the new owner is online. Returns the target species.
   */
  fun onTraded(charId: Long, mon: Pokemon): Int? {
    val to = tryEvolve(mon, EvolutionTrigger.TRADE) ?: return null
    val held = items.get(mon.heldItem)
    val consumesHeld =
        evolutions.of(mon.dexId).any {
          it.method == EvolutionMethod.TRADE_ITEM && ItemNames.byDecompName(it.item) == held
        }
    val session = sessions.getByCharacterId(charId) ?: return to
    startEvolution(session, charId, mon.id, to, consumeHeld = consumesHeld)
    return to
  }

  private fun playNext(session: SessionContext, charId: Long) {
    if (showing.containsKey(charId)) return
    val scene = queues[charId]?.pollFirst() ?: return
    val mon = characters.getCharacter(charId)?.pokemon?.firstOrNull { it.id == scene.monId }
    val from = mon?.let { species.get(it.dexId) }
    val to = species.get(scene.toDex)
    if (mon == null || from == null || to == null) {
      playNext(session, charId)
      return
    }
    showing[charId] = scene
    log.info { "char=$charId: ${from.name} (${mon.id}) is evolving into ${to.name}" }
    session.send(
        BattleExtPacket(
            buildJsonObject {
                  put("t", "evo")
                  put("phase", "start")
                  put("id", mon.id)
                  put("fromDex", from.id)
                  put("toDex", to.id)
                  put("name", mon.nickname.ifEmpty { from.name })
                  put("toName", to.name)
                  put("shiny", mon.isShiny)
                }
                .toString()))
  }

  /** The client's answer to the scene. */
  suspend fun onReply(event: PacketEvent<EvolutionReplyPacket>) {
    val charId = event.session.attributes[PLAYER_STATE]?.characterId ?: return
    val scene = showing[charId] ?: return
    if (scene.monId != event.packet.entityId) return
    showing.remove(charId)
    if (!event.packet.proceed) {
      val mon = characters.getCharacter(charId)?.pokemon?.firstOrNull { it.id == scene.monId }
      val name = mon?.let { it.nickname.ifEmpty { species.get(it.dexId)?.name } } ?: "It"
      event.session.send(notice("Huh? $name stopped evolving!"))
      event.session.send(evoDone(scene.monId, "cancelled", scene.toDex, emptyList()))
    } else {
      complete(event.session, scene)
    }
    playNext(event.session, charId)
  }

  /** Applies an evolution: the species change, stats, moves, Shedinja and the party sync. */
  internal suspend fun complete(session: SessionContext, scene: EvoScene): Pokemon? {
    val charId = scene.charId
    val stored = characters.getCharacter(charId) ?: return null
    val mon = stored.pokemon.firstOrNull { it.id == scene.monId } ?: return null
    val fromDef = species.get(mon.dexId) ?: return null
    val toDef = species.get(scene.toDex) ?: return null
    if (scene.consumeItem != 0) {
      if ((stored.items[scene.consumeItem] ?: 0) <= 0 ||
          !characters.addItem(charId, scene.consumeItem, -1)) {
        session.send(notice("You don't have that item anymore."))
        return null
      }
      session.send(
          itemStackUpdatePacket(
              scene.consumeItem,
              characters.getCharacter(charId)?.items?.get(scene.consumeItem) ?: 0))
    }
    val oldStats = StatCalculator.computeAll(fromDef, mon)
    var evolved = mon.copy(dexId = toDef.id, heldItem = if (scene.consumeHeld) 0 else mon.heldItem)
    val newStats = StatCalculator.computeAll(toDef, evolved)
    // The hp the monster had lost stays lost; the new maximum adds its difference.
    val hp = if (mon.hp <= 0) 0 else (mon.hp + (newStats.hp - oldStats.hp)).coerceIn(1, newStats.hp)
    val moves = evolved.moves.map { PokemonMove(it.id, it.pp) }.toMutableList()
    // Gen 3 teaches the new species' moves for the current level right after evolving.
    val outcome = moveLearner.learn(moves, toDef.id, mon.level - 1, mon.level.toInt())
    evolved = evolved.copy(hp = hp.toShort(), moves = moves)
    characters.updatePokemon(charId, evolved)
    val name = mon.nickname.ifEmpty { fromDef.name }
    log.info { "char=$charId: $name evolved into ${toDef.name}" }
    session.send(notice("Congratulations! Your $name evolved into ${toDef.name}!"))
    for (m in outcome.learned) session.send(notice("${toDef.name} learned ${m.name}!"))
    if (outcome.offered.isNotEmpty()) {
      val offered = outcome.offered.map { it.moveId.toShort() }
      prompts.offer(charId, evolved.id, offered)
      session.send(MoveLearnPromptPacket(evolved.id, offered))
    }
    // Ninjask leaves a Shedinja behind when the party has room.
    if (evolutions.of(fromDef.id).any {
      it.method == EvolutionMethod.LEVEL_NINJASK && it.toDex == toDef.id
    }) {
      evolutions
          .of(fromDef.id)
          .firstOrNull { it.method == EvolutionMethod.LEVEL_SHEDINJA }
          ?.let { sh -> makeShedinja(session, charId, mon, sh.toDex) }
    }
    session.send(acquiredMonsterDelta(evolved, toDef))
    session.send(
        PokemonContainerPacket(
            container = PokemonContainer.PARTY,
            hasChange = true,
            delete = false,
            pokemon =
                characters
                    .getCharacter(charId)
                    ?.pokemon
                    ?.filter { it.container == PokemonContainer.PARTY }
                    ?.sortedBy { it.containerSlot } ?: listOf(evolved)))
    session.send(evoDone(evolved.id, "done", toDef.id, outcome.learned.map { it.name }))
    characters.flushCharacterAsync(charId)
    return evolved
  }

  private suspend fun makeShedinja(session: SessionContext, charId: Long, from: Pokemon, dex: Int) {
    val stored = characters.getCharacter(charId) ?: return
    val party = stored.pokemon.filter { it.container == PokemonContainer.PARTY }
    if (party.size >= 6) return
    val def = species.get(dex) ?: return
    val slot = ((party.maxOfOrNull { it.containerSlot } ?: -1) + 1).toShort()
    val base =
        from.copy(
            id = entityIds.newMonsterId(),
            dexId = dex,
            nickname = "",
            containerSlot = slot,
            heldItem = 0,
            status = 0)
    val shedinja = base.copy(hp = StatCalculator.computeAll(def, base).hp.toShort())
    if (characters.addPokemon(charId, shedinja)) {
      session.send(SocialListEntryAddPacket(shedinja))
      session.send(notice("${def.name} appeared in your party!"))
    }
  }

  private fun evoDone(id: Long, phase: String, toDex: Int, learned: List<String>) =
      BattleExtPacket(
          buildJsonObject {
                put("t", "evo")
                put("phase", phase)
                put("id", id)
                put("toDex", toDex)
                put("learned", JsonArray(learned.map { JsonPrimitive(it) }))
              }
              .toString())

  fun onLogout(charId: Long) {
    queues.remove(charId)
    showing.remove(charId)
  }

  /** True while a scene is waiting for this player's answer. */
  fun evolving(charId: Long): Boolean = showing.containsKey(charId)

  companion object {
    const val FRIENDSHIP_EVOLVE = 220

    private val STONES =
        setOf(
            Items.FIRE_STONE,
            Items.WATER_STONE,
            Items.THUNDERSTONE,
            Items.LEAF_STONE,
            Items.MOON_STONE,
            Items.SUN_STONE)
  }
}
