package de.fiereu.openmmo.server.game.progression

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.MAX_MOVE_SLOTS
import de.fiereu.openmmo.common.MAX_PARTY_SIZE
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket
import de.fiereu.openmmo.net.game.packets.LocalCharacterDeltaPacket
import de.fiereu.openmmo.pokemon.LearnsetRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.progression.Gen3Data
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.ExpCurves
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.battle.WildMonFactory
import de.fiereu.openmmo.server.game.services.notice
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.StoredCharacter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private val log = KotlinLogging.logger {}

/**
 * The Gen 3 day care (daycare.c): two monsters are raised for one exp per step, a compatible pair
 * lays an egg (rolled every 256 steps against the compatibility score), and eggs in the party hatch
 * after their species' egg cycles of 255 steps each. Taking a monster back costs 100 plus 100 per
 * level it gained, and it learns the moves of those levels, pushing out the oldest move.
 */
@Singleton
class DaycareService
@Inject
constructor(
    private val characters: CharacterStore,
    private val species: SpeciesRegistry,
    private val learnsets: LearnsetRegistry,
    private val moves: MoveRegistry,
    private val wildMons: WildMonFactory,
    private val progress: PlayerProgressService,
) {
  /** Replaceable for tests. */
  internal var random: Random = Random.Default

  enum class State {
    EMPTY,
    ONE,
    TWO,
    EGG_WAITING,
  }

  /** GetDaycareState of the breeding day care. */
  fun state(stored: StoredCharacter): State {
    val mons = main(stored)
    return when {
      (stored.storyVars[ProgressKeys.DAYCARE_EGG] ?: 0) != 0 -> State.EGG_WAITING
      mons.size >= DaycareRules.SLOTS -> State.TWO
      mons.size == 1 -> State.ONE
      else -> State.EMPTY
    }
  }

  /** The two-slot breeding day care (Route 117, Four Island), by slot. */
  fun main(stored: StoredCharacter): List<Pokemon> =
      stored.daycare.filter { it.containerSlot < DaycareRules.SLOTS }.sortedBy { it.containerSlot }

  /** FireRed's Route 5 day care man raises one monster and lays no eggs; it keeps slot 2. */
  fun route5(stored: StoredCharacter): Pokemon? =
      stored.daycare.firstOrNull { it.containerSlot.toInt() == ROUTE5_SLOT }

  /** Which monsters a day care ([route5] or the breeding one) holds. */
  fun mons(stored: StoredCharacter, route5: Boolean): List<Pokemon> =
      if (route5) listOfNotNull(route5(stored)) else main(stored)

  /** Leaves party monster [monId] at the day care. Null on success, else what to tell. */
  suspend fun deposit(charId: Long, monId: Long, route5: Boolean = false): String? {
    val stored = characters.getCharacter(charId) ?: return "Not loaded."
    val mon = stored.pokemon.firstOrNull { it.id == monId } ?: return "Choose a POKéMON."
    if (route5 && route5(stored) != null) return "The DAY CARE is full."
    if (!route5 && main(stored).size >= DaycareRules.SLOTS) return "The DAY CARE is full."
    if (mon.isEgg) return "An EGG can't be left here."
    // CountPartyNonEggMons, then CountPartyAliveNonEggMons_IgnoreVar0x8004Slot.
    if (stored.pokemon.count { !it.isEgg } <= 1) return "Oh? You only have one POKéMON with you."
    if (stored.pokemon.none { it.id != monId && !it.isEgg && it.hp > 0 }) {
      return "If you leave me that POKéMON, what will you battle with?"
    }
    val slot =
        if (route5) ROUTE5_SLOT
        else
            (0 until DaycareRules.SLOTS).first { s ->
              stored.daycare.none { it.containerSlot.toInt() == s }
            }
    val ok =
        characters.commitDurably(charId) { s ->
          val party =
              s.pokemon
                  .filter { it.id != monId }
                  .sortedBy { it.containerSlot }
                  .mapIndexed { i, m -> m.copy(containerSlot = i.toShort()) }
          val left = mon.copy(container = PokemonContainer.DAYCARE, containerSlot = slot.toShort())
          s.copy(
              pokemon = party.toMutableList(),
              daycare = (s.daycare + left).toMutableList(),
              storyVars =
                  s.storyVars.toMutableMap().apply {
                    remove("${ProgressKeys.DAYCARE_XP_PREFIX}$monId")
                  },
          )
        }
    if (!ok) return "Could not save."
    log.info { "char=$charId left ${mon.dexId} at the day care" }
    return null
  }

  /** Takes day care monster [index] back. Null on success, else what to tell. */
  suspend fun withdraw(charId: Long, index: Int, route5: Boolean = false): Pair<String?, String?> {
    val stored = characters.getCharacter(charId) ?: return "Not loaded." to null
    val mons = mons(stored, route5)
    val mon = mons.getOrNull(index) ?: return "There is no POKéMON there." to null
    if (stored.pokemon.size >= MAX_PARTY_SIZE) {
      return "You can't take this POKéMON back if you've got no room for it." to null
    }
    val def = species.get(mon.dexId) ?: return "Unknown POKéMON." to null
    val gained = DaycareRules.levelsGained(stored, mon, def.growthRate)
    val fee = DaycareRules.fee(gained)
    if (stored.info.money < fee) return "You don't have enough money." to null
    val grown = grow(stored, mon)
    val ok =
        characters.commitDurably(charId) { s ->
          if (s.info.money < fee) return@commitDurably null
          val back =
              grown.copy(
                  container = PokemonContainer.PARTY, containerSlot = s.pokemon.size.toShort())
          s.copy(
              info = s.info.copy(money = s.info.money - fee),
              pokemon = (s.pokemon + back).toMutableList(),
              daycare = s.daycare.filter { it.id != mon.id }.toMutableList(),
              storyVars =
                  s.storyVars.toMutableMap().apply {
                    remove("${ProgressKeys.DAYCARE_XP_PREFIX}${mon.id}")
                  },
          )
        }
    if (!ok) return "Could not save." to null
    val name = mon.nickname.ifEmpty { def.name }
    val grewText = if (gained > 0) "Your $name has grown by $gained level(s). " else ""
    return null to "${grewText}Paid ¥$fee. Got $name back from the DAY CARE."
  }

  /** The monster with its day care steps turned into exp, levels and level-up moves. */
  internal fun grow(stored: StoredCharacter, mon: Pokemon): Pokemon {
    val def = species.get(mon.dexId) ?: return mon
    val xp = DaycareRules.xpAfter(stored, mon, def.growthRate)
    val level = ExpCurves.levelFor(def.growthRate, xp).coerceAtLeast(mon.level.toInt())
    var known = mon.moves.toMutableList()
    for (lv in mon.level + 1..level) {
      for (moveId in learnsets.movesAt(mon.dexId, lv)) {
        if (known.any { it.id.toInt() == moveId }) continue
        val pp = moves.get(moveId)?.pp ?: continue
        known = giveOrPushOut(known, moveId, pp)
      }
    }
    val grown = mon.copy(level = level.toByte(), xp = xp, moves = known)
    val oldMax = stats(mon)
    val newMax = stats(grown)
    return grown.copy(hp = (mon.hp + newMax - oldMax).coerceIn(0, newMax).toShort())
  }

  private fun stats(mon: Pokemon): Int =
      species.get(mon.dexId)?.let { StatCalculator.computeAll(it, mon).hp } ?: mon.hp.toInt()

  /** GiveMoveToMon, or DeleteFirstMoveAndGiveMoveToMon when all four slots are used. */
  private fun giveOrPushOut(
      known: MutableList<PokemonMove>,
      moveId: Int,
      pp: Int
  ): MutableList<PokemonMove> {
    val move = PokemonMove(moveId.toShort(), pp.toByte())
    val free = known.indexOfFirst { it.id.toInt() == 0 }
    if (free >= 0) {
      known[free] = move
      return known
    }
    if (known.size < MAX_MOVE_SLOTS) return (known + move).toMutableList()
    return (known.drop(1) + move).toMutableList()
  }

  /**
   * One player step: day care exp, the egg roll, and egg hatching. Returns the notices to show (an
   * egg was laid is not told, like the games; a hatch is).
   */
  fun onStep(charId: Long): List<String> {
    val stored = characters.getCharacter(charId) ?: return emptyList()
    val mons = main(stored)
    if (stored.daycare.isEmpty() && stored.pokemon.none { it.isEgg }) return emptyList()
    val vars = HashMap<String, Int>()
    for (m in stored.daycare) {
      val key = "${ProgressKeys.DAYCARE_XP_PREFIX}${m.id}"
      vars[key] = (stored.storyVars[key] ?: 0) + 1
    }
    // TryProduceOrHatchEgg: an egg roll whenever the second monster's step count ends in 0xFF.
    if (mons.size == DaycareRules.SLOTS && (stored.storyVars[ProgressKeys.DAYCARE_EGG] ?: 0) == 0) {
      val steps = vars["${ProgressKeys.DAYCARE_XP_PREFIX}${mons[1].id}"] ?: 0
      if (steps and 0xFF == 0xFF) {
        val compat = compatibility(mons)
        if (compat > random.nextInt(100)) {
          vars[ProgressKeys.DAYCARE_EGG] = 1
          log.info { "char=$charId: the day care found an egg (compatibility $compat)" }
        }
      }
    }
    // Every 255 steps each egg in the party loses a cycle; one at zero hatches.
    val counter = (stored.storyVars[ProgressKeys.DAYCARE_STEPS] ?: 0) + 1
    var hatched: Pokemon? = null
    if (counter >= DaycareRules.CYCLE_STEPS) {
      vars[ProgressKeys.DAYCARE_STEPS] = 0
      for ((eggId, left) in cycleEggs(stored)) {
        val key = "${ProgressKeys.EGG_STEPS_PREFIX}$eggId"
        if (left < 0 && hatched == null) {
          hatched = stored.pokemon.first { it.id == eggId }
          vars[key] = 0
        } else {
          vars[key] = left.coerceAtLeast(1)
        }
      }
    } else {
      vars[ProgressKeys.DAYCARE_STEPS] = counter
    }
    characters.setStoryVars(charId, vars)
    val egg = hatched ?: return emptyList()
    val mon = hatch(egg)
    characters.updatePokemon(charId, mon)
    log.info { "char=$charId: egg ${egg.id} hatched into ${mon.dexId}" }
    return listOf(DaycareRules.hatchText(species.get(mon.dexId)?.name ?: "POKéMON"))
  }

  /**
   * The stored value after one more cycle; -1 marks an egg that hatches now. The var holds the
   * cycles left plus one, so an egg at zero cycles is not mistaken for a var that was never set.
   */
  private fun cycleEggs(stored: StoredCharacter): Map<Long, Int> =
      stored.pokemon
          .filter { it.isEgg }
          .associate { egg ->
            val key = "${ProgressKeys.EGG_STEPS_PREFIX}${egg.id}"
            val cycles = (stored.storyVars[key] ?: (eggCycles(egg.dexId) + 1)) - 1
            egg.id to if (cycles <= 0) -1 else cycles
          }

  private fun eggCycles(dexId: Int) = species.get(dexId)?.eggCycles ?: DEFAULT_EGG_CYCLES

  fun compatibility(mons: List<Pokemon>): Int {
    if (mons.size < 2) return 0
    val a = species.get(mons[0].dexId) ?: return 0
    val b = species.get(mons[1].dexId) ?: return 0
    return DaycareRules.compatibility(mons[0], a, mons[1], b)
  }

  /** Hands the waiting egg over (the day care man's YES). Null on success. */
  suspend fun takeEgg(session: SessionContext, charId: Long): String? {
    val stored = characters.getCharacter(charId) ?: return "Not loaded."
    if ((stored.storyVars[ProgressKeys.DAYCARE_EGG] ?: 0) == 0) return "There is no EGG."
    if (stored.pokemon.size >= MAX_PARTY_SIZE) return "You have no room for it."
    val egg = createEgg(stored) ?: return "The EGG could not be made."
    val placed =
        egg.copy(
            ownerId = charId, containerSlot = stored.pokemon.size.toShort(), ot = stored.info.name)
    val cycles = eggCycles(egg.dexId) + 1
    val ok =
        characters.commitDurably(charId) { s ->
          s.copy(
              pokemon = (s.pokemon + placed).toMutableList(),
              storyVars =
                  s.storyVars.toMutableMap().apply {
                    remove(ProgressKeys.DAYCARE_EGG)
                    put("${ProgressKeys.EGG_STEPS_PREFIX}${placed.id}", cycles)
                  },
          )
        }
    if (!ok) return "Could not save."
    progress.pushParty(session, charId)
    return null
  }

  /** The day care man's NO: the egg is let go. */
  fun discardEgg(charId: Long) {
    characters.setStoryVars(charId, mapOf(ProgressKeys.DAYCARE_EGG to 0))
  }

  /**
   * CreateEgg + BuildEggMoveset + InheritIVs: the mother's (or non-Ditto parent's) species at its
   * bottom form, three IVs passed down, the father's egg and TM moves and the moves both parents
   * know from the baby's level-up learnset.
   */
  internal fun createEgg(stored: StoredCharacter): Pokemon? {
    val parents = main(stored)
    if (parents.size < 2) return null
    val defs = parents.map { species.get(it.dexId) ?: return null }
    val motherIdx = DaycareRules.motherIndex(parents, defs)
    val mother = parents[motherIdx]
    val father = parents[1 - motherIdx]
    var baby = Gen3Data.babyOf(mother.dexId)
    // GetEggSpecies' two exceptions: Nidoran♀ and Illumise lay either line.
    if (baby == NIDORAN_F && random.nextBoolean()) baby = NIDORAN_M
    if (baby == ILLUMISE && random.nextBoolean()) baby = VOLBEAT
    val rng = BattleRng()
    val base = wildMons.create(baby, DaycareRules.EGG_HATCH_LEVEL, rng) ?: return null
    // InheritIVs: three different stats, each from a random parent.
    val ivs = copyIvs(base.iVs)
    val stats = (0 until 6).shuffled(random).take(3)
    for (s in stats) setIv(ivs, s, getIv((if (random.nextBoolean()) mother else father).iVs, s))
    val moveset = eggMoveset(baby, base.moves, mother, father)
    return base.copy(
        container = PokemonContainer.PARTY,
        iVs = ivs,
        moves = moveset,
        isEgg = true,
        nickname = "",
        hp = 0,
        caughtAt = LocalDateTime.now(),
    )
  }

  internal fun eggMoveset(
      baby: Int,
      start: List<PokemonMove>,
      mother: Pokemon,
      father: Pokemon,
  ): List<PokemonMove> {
    var known = start.toMutableList()
    fun give(moveId: Int) {
      if (moveId == 0 || known.any { it.id.toInt() == moveId }) return
      val pp = moves.get(moveId)?.pp ?: return
      known = giveOrPushOut(known, moveId, pp)
    }
    val fatherMoves = father.moves.map { it.id.toInt() }.filter { it != 0 }
    val motherMoves = mother.moves.map { it.id.toInt() }.filter { it != 0 }
    val eggMoves = Gen3Data.eggMoves(baby)
    for (m in fatherMoves) if (m in eggMoves) give(m)
    for (m in fatherMoves) {
      val tm = Gen3Data.tmhmMoves.indexOf(m)
      if (tm >= 0 && Gen3Data.canLearnTmhm(baby, tm)) give(m)
    }
    val levelUp = learnsets.get(baby).map { it.moveId }.toSet()
    for (m in fatherMoves) if (m in motherMoves && m in levelUp) give(m)
    return known
  }

  internal fun hatch(egg: Pokemon): Pokemon {
    val def = species.get(egg.dexId)
    val mon =
        egg.copy(
            isEgg = false,
            caughtAt = LocalDateTime.now(),
            level = DaycareRules.EGG_HATCH_LEVEL.toByte())
    val hp = def?.let { StatCalculator.computeAll(it, mon).hp } ?: 1
    return mon.copy(hp = hp.toShort())
  }

  private fun copyIvs(src: IVs) = IVs().also { for (i in 0 until 6) setIv(it, i, getIv(src, i)) }

  private fun getIv(v: IVs, i: Int) =
      when (i) {
        0 -> v.hp
        1 -> v.atk
        2 -> v.def
        3 -> v.spd
        4 -> v.spAtk
        else -> v.spDef
      }

  private fun setIv(v: IVs, i: Int, value: Int) {
    when (i) {
      0 -> v.hp = value
      1 -> v.atk = value
      2 -> v.def = value
      3 -> v.spd = value
      4 -> v.spAtk = value
      else -> v.spDef = value
    }
  }

  fun speciesName(dexId: Int): String = species.get(dexId)?.name ?: "POKéMON"

  fun levelsGained(stored: StoredCharacter, mon: Pokemon): Int =
      DaycareRules.levelsGained(stored, mon, species.get(mon.dexId)?.growthRate)

  /** The balance after a fee, for the money box. */
  fun sendMoney(session: SessionContext, charId: Long) {
    val money = characters.getCharacter(charId)?.info?.money ?: return
    session.send(CurrencyBalancePacket(money, 0))
    session.send(LocalCharacterDeltaPacket(money = money))
  }

  fun notifyHatch(session: SessionContext, charId: Long, lines: List<String>) {
    if (lines.isEmpty()) return
    for (l in lines) session.send(notice(l))
    progress.pushParty(session, charId)
    progress.push(session, charId)
  }

  companion object {
    const val ROUTE5_SLOT = 2
    private const val DEFAULT_EGG_CYCLES = 20
    private const val NIDORAN_F = 29
    private const val NIDORAN_M = 32
    private const val ILLUMISE = 314
    private const val VOLBEAT = 313
  }
}
