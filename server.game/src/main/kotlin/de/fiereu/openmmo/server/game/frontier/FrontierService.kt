package de.fiereu.openmmo.server.game.frontier

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.frontier.FrontierActionPacket
import de.fiereu.openmmo.net.game.packets.frontier.FrontierStatePacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.progression.Gen3Data
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.battle.FacilityBattleOutcome
import de.fiereu.openmmo.server.game.battle.FacilityBattleSetup
import de.fiereu.openmmo.server.game.battle.FacilityHooks
import de.fiereu.openmmo.server.game.battle.ItemNames
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.progression.ProgressKeys
import de.fiereu.openmmo.server.game.services.BattleService
import de.fiereu.openmmo.server.game.services.itemStackUpdatePacket
import de.fiereu.openmmo.server.game.services.notice
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}

/** Thrown out of an [FrontierRun.ask] when the player closes the screen or leaves. */
class FrontierClosed : CancellationException("frontier screen closed")

/**
 * One player at a facility desk: the screen they see and the challenge coroutine behind it. A
 * screen that needs an answer suspends the challenge until the browser sends one with the same seq.
 */
class FrontierRun(
    val svc: FrontierService,
    val session: SessionContext,
    val charId: Long,
    val playerName: String,
    val desk: String,
) {
  var seq = 0
  private var pending: CompletableDeferred<JsonObject>? = null
  var job: Job? = null
  var screen: JsonObject = JsonObject(emptyMap())
  /** True while a battle is on (the browser hides the facility screen). */
  var inBattle = false
  /** Link multis: the other player's run, whose screen mirrors this one. */
  var mirror: FrontierRun? = null
  val rng: Random = Random.Default

  suspend fun ask(s: JsonObject): JsonObject {
    val d = CompletableDeferred<JsonObject>()
    pending = d
    show(s, expectsAnswer = true)
    return d.await()
  }

  fun show(s: JsonObject, expectsAnswer: Boolean = false) {
    seq++
    screen = s
    svc.push(this, expectsAnswer)
    mirror?.let { m ->
      m.seq++
      m.screen = buildJsonObject {
        for ((k, v) in s) put(k, v)
        put("mirror", true)
      }
      svc.push(m, false)
    }
  }

  fun answer(seqIn: Int, a: JsonObject): Boolean {
    if (seqIn != seq) return false
    val p = pending ?: return false
    pending = null
    p.complete(a)
    return true
  }

  fun cancel() {
    pending?.completeExceptionally(FrontierClosed())
    pending = null
    job?.cancel(FrontierClosed())
  }
}

/** The opposing side of one facility battle. */
data class FacilityOpponent(
    val name: String,
    val pic: String,
    val trainerClass: String,
    val mons: List<Pokemon>,
    val intro: String = "",
    val brain: Boolean = false,
    val wild: Boolean = false,
)

/**
 * r5-frontier: the battle facilities. Players reach a desk by talking to its attendant (see
 * [FrontierDesks]), the desk runs the facility's challenge in a coroutine, and every screen goes
 * to the browser as [FrontierStatePacket] JSON (PROTOCOL.md "r5-frontier").
 */
@Singleton
class FrontierService
@Inject
constructor(
    val data: FrontierData,
    val mons: FrontierMonFactory,
    val repo: FrontierRepository,
    private val battles: Provider<BattleService>,
    val characters: CharacterStore,
    val species: SpeciesRegistry,
    val moves: MoveRegistry,
    val items: ItemRegistry,
    val typeChart: de.fiereu.openmmo.typechart.TypeChart,
    private val sessions: SessionRegistry,
    private val scope: CoroutineScope,
) {
  private val runs = ConcurrentHashMap<Long, FrontierRun>()
  private val saves = ConcurrentHashMap<Long, FrontierSave>()
  /** Link multis hosts waiting for a partner: host char id -> their run. */
  val linkHosts = ConcurrentHashMap<Long, LinkHost>()

  class LinkHost(val run: FrontierRun, val mode: LevelMode, val team: List<Pokemon>) {
    val partner = CompletableDeferred<Pair<FrontierRun, List<Pokemon>>>()
  }

  // --- records ---------------------------------------------------------------------------------

  suspend fun save(charId: Long): FrontierSave = saves[charId] ?: repo.load(charId).also { saves[charId] = it }

  suspend fun persist(charId: Long) {
    val s = saves[charId] ?: return
    runCatching { repo.save(charId, s) }.onFailure { log.error(it) { "frontier save char=$charId" } }
  }

  // --- entry points ----------------------------------------------------------------------------

  /** Opens [desk] for the player (an attendant, the exchange clerks, a chat command). */
  fun open(session: SessionContext, desk: String) {
    val state = session.attributes[PLAYER_STATE] ?: return
    val charId = state.characterId ?: return
    if (battles.get().inBattle(charId)) return
    val name = characters.getCharacter(charId)?.info?.name ?: "PLAYER"
    runs[charId]?.let { old ->
      if (old.job?.isActive == true) {
        // A challenge is already running: just show its screen again.
        push(old, true)
        return
      }
    }
    val run = FrontierRun(this, session, charId, name, desk)
    runs[charId] = run
    run.job =
        scope.launch {
          try {
            FrontierChallenges(this@FrontierService, run).runDesk()
          } catch (_: FrontierClosed) {
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            log.error(e) { "frontier desk $desk failed for char=$charId" }
            session.send(notice("The facility had a problem: ${e.message}"))
          } finally {
            persist(charId)
            if (runs[charId] === run) {
              runs.remove(charId)
              run.screen = JsonObject(emptyMap())
              session.send(FrontierStatePacket("""{"open":false}"""))
            }
            linkHosts.remove(charId)
          }
        }
  }

  /** The browser's answer to the current screen, or a close. */
  suspend fun onAction(event: PacketEvent<FrontierActionPacket>) {
    val charId = event.session.attributes[PLAYER_STATE]?.characterId ?: return
    val a = runCatching { Json.parseToJsonElement(event.packet.json).jsonObject }.getOrNull() ?: return
    when (a["a"]?.jsonPrimitive?.contentOrNull) {
      "answer" -> {
        val run = runs[charId] ?: return
        run.answer(a["seq"]?.jsonPrimitive?.intOrNull ?: -1, a)
      }
      "close" -> runs[charId]?.cancel()
      "open" -> {
        // The browser may open the read-only Frontier Pass card on its own.
        val desk = a["desk"]?.jsonPrimitive?.contentOrNull ?: return
        if (desk == "card") open(event.session, desk)
      }
    }
  }

  fun onLogout(charId: Long) {
    runs.remove(charId)?.cancel()
    linkHosts.remove(charId)
    saves.remove(charId)
  }

  fun push(run: FrontierRun, expectsAnswer: Boolean) {
    val s = saves[run.charId]
    val json = buildJsonObject {
      put("open", true)
      put("seq", run.seq)
      put("desk", run.desk)
      put("answer", expectsAnswer)
      put("bp", s?.battlePoints ?: 0)
      put("symbols", JsonObject(Facility.entries.associate { it.key to JsonPrimitive(s?.symbolsOf(it) ?: 0) }))
      put("screen", run.screen)
    }
    run.session.send(FrontierStatePacket(json.toString()))
  }

  // --- helpers for the challenges --------------------------------------------------------------

  fun party(charId: Long): List<Pokemon> =
      characters
          .getCharacter(charId)
          ?.pokemon
          ?.filter { it.container == PokemonContainer.PARTY }
          ?.sortedBy { it.containerSlot }
          .orEmpty()

  fun speciesName(dex: Int): String = species.get(dex)?.name?.uppercase() ?: "???"

  fun moveName(id: Int): String = moves.get(id)?.name?.uppercase() ?: "-"

  fun itemName(wireId: Int): String = if (wireId == 0) "" else items.get(wireId)?.name ?: ""

  fun itemNameOf(decomp: String): String = ItemNames.byDecompName(decomp)?.name ?: decomp.replace('_', ' ')

  fun natureName(mon: Pokemon): String = mon.nature.name

  /** A monster as the screens show it. */
  fun card(mon: Pokemon): JsonObject {
    val def = species.get(mon.dexId)
    val max = def?.let { StatCalculator.computeAll(it, mon) }
    return buildJsonObject {
      put("id", mon.id)
      put("dex", mon.dexId)
      put("name", mon.nickname.ifBlank { speciesName(mon.dexId) })
      put("species", speciesName(mon.dexId))
      put("level", mon.level.toInt())
      put("hp", mon.hp.toInt())
      put("maxHp", max?.hp ?: mon.hp.toInt())
      put("status", if (mon.status != 0) de.fiereu.openmmo.common.StatusCondition.code(mon.status) else null)
      put("item", itemName(mon.heldItem))
      put("nature", natureName(mon))
      def?.let {
        val ability =
            if (it.ability2 != de.fiereu.openmmo.common.enums.Ability.NONE && (mon.seed and 1) == 1) it.ability2
            else it.ability1
        put("ability", ability.name.replace('_', ' '))
        put("types", buildJsonArray { it.types.forEach { t -> add(JsonPrimitive(t.name)) } })
      }
      put("moves", buildJsonArray { mon.moves.filter { it.id.toInt() != 0 }.forEach { add(JsonPrimitive(moveName(it.id.toInt()))) } })
      max?.let {
        put("stats", buildJsonArray { listOf(it.hp, it.atk, it.def, it.spAtk, it.spDef, it.spd).forEach { v -> add(JsonPrimitive(v)) } })
      }
    }
  }

  /** Runs one battle of a challenge and waits for it (and for the browser to be back). */
  suspend fun battle(
      run: FrontierRun,
      team: List<Pokemon>,
      opp: FacilityOpponent,
      hooks: FacilityHooks? = null,
      format: Int = 1,
      partner: Pair<FrontierRun, List<Pokemon>>? = null,
      partnerFrom: Int = -1,
  ): FacilityBattleOutcome {
    val setup =
        FacilityBattleSetup(
            team = team,
            opponents = opp.mons,
            trainerName = opp.name,
            trainerPic = opp.pic,
            trainerClass = opp.trainerClass,
            format = format,
            hooks = hooks,
            wild = opp.wild,
            musicKey = if (opp.brain) "elite" else "trainer",
            intro = opp.intro,
            partnerFrom = partnerFrom)
    run.inBattle = true
    run.show(buildJsonObject {
      put("kind", "battle")
      put("text", if (opp.wild) "A wild POKéMON appeared!" else "${opp.name} wants to battle!")
    })
    partner?.first?.inBattle = true
    val outcome =
        try {
          battles
              .get()
              .startFacilityBattle(
                  run.session,
                  setup,
                  partner?.let { (p, t) -> p.session to setup.copy(team = t) })
        } finally {
          run.inBattle = false
          partner?.first?.inBattle = false
        }
    return outcome
  }

  /** BP into the save and onto the screen. */
  suspend fun givePoints(run: FrontierRun, points: Int) {
    save(run.charId).addPoints(points)
    persist(run.charId)
  }

  suspend fun giveItem(run: FrontierRun, decompName: String, qty: Int = 1): Boolean {
    val id = ItemNames.wireId(items, decompName)
    if (id == 0) return false
    if (!characters.addItem(run.charId, id, qty)) return false
    run.session.send(itemStackUpdatePacket(id, characters.getCharacter(run.charId)?.items?.get(id) ?: 0))
    return true
  }

  suspend fun giveDecoration(run: FrontierRun, decompName: String): Boolean {
    val key = decompName.uppercase().replace("_", "").replace(" ", "")
    val decor =
        Gen3Data.decorations.values.firstOrNull {
          it.name.uppercase().replace("_", "").replace(" ", "").replace("É", "E") == key
        } ?: return false
    return characters.commitDurably(run.charId) { s ->
      s.copy(
          storyVars =
              s.storyVars.toMutableMap().apply {
                put(ProgressKeys.decor(decor.id), minOf(99, (this[ProgressKeys.decor(decor.id)] ?: 0) + 1))
              })
    }
  }

  /** Online players at a frontier desk who could be found by name (link multis). */
  fun onlineName(charId: Long): String? = characters.getCharacter(charId)?.info?.name

  fun sessionOf(charId: Long): SessionContext? = sessions.getByCharacterId(charId)

  fun runOf(charId: Long): FrontierRun? = runs[charId]

  internal fun element(v: Any?): JsonElement =
      when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> v
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
      }

  internal fun ints(a: JsonObject, k: String): List<Int> =
      (a[k] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
}
