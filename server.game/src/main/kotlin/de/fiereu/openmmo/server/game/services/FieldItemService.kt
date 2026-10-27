package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.MonStatus
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.FieldItemUsePacket
import de.fiereu.openmmo.net.game.packets.progress.LearnPrompt
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.progression.EscapeRopeService
import de.fiereu.openmmo.progression.Gen3Data
import de.fiereu.openmmo.server.game.progression.Growth
import de.fiereu.openmmo.server.game.progression.MonGrowth
import de.fiereu.openmmo.server.game.progression.PlayerProgressService
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.EntityIdService
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton

private val log = KotlinLogging.logger {}

private const val EV_ITEM_RAISE_LIMIT = 100
private const val MAX_TOTAL_EVS = 510
private const val EV_STEP = 10
private const val MAX_PP_UPS = 3
private const val MIN_PP_FOR_PP_UP = 5

/**
 * Bag items used outside a battle, as the decomp's item_use.c and party_menu.c do it: medicine,
 * status cures, PP restorers (all moves or one chosen move), Rare Candy, evolution stones,
 * vitamins and EV berries, PP Up / PP Max, TMs and HMs, Repels and the Escape Rope. The item is
 * taken first, so a failed write never hands out a free effect; every refusal is reported to the
 * player as a notice and costs nothing.
 */
@Singleton
class FieldItemService
@Inject
constructor(
    private val characters: CharacterStore,
    private val items: ItemRegistry,
    private val species: SpeciesRegistry,
    private val moves: MoveRegistry,
    private val battles: BattleService,
    private val growth: MonGrowth,
    private val progress: PlayerProgressService,
    private val entityIds: EntityIdService,
    private val escapeRope: EscapeRopeService,
) {

  suspend fun onUse(event: PacketEvent<FieldItemUsePacket>) {
    handle(event.session, event.packet.itemId.toInt(), event.packet.pokemonId, -1, -1)
  }

  /** Uses an item, on [moveSlot] of the monster when the item needs a move, and syncs the player. */
  suspend fun handle(
      session: SessionContext,
      itemId: Int,
      pokemonId: Long,
      moveSlot: Int,
      forgetSlot: Int,
  ) {
    val state = session.attributes[PLAYER_STATE] ?: return
    val charId = state.characterId ?: return
    val outcome = use(charId, itemId, pokemonId, state.inDialog, moveSlot, forgetSlot)
    if (outcome.escape) {
      if (!escapeRope.escape(session, state, charId)) {
        // Nothing to escape to after all: give the rope back.
        characters.addItem(charId, itemId, 1)
        session.send(notice("Can't use that here."))
      } else {
        session.send(notice(outcome.text))
      }
      syncAfterUse(session, charId, itemId)
      return
    }
    for (line in outcome.lines()) session.send(notice(line))
    if (outcome.changed) syncAfterUse(session, charId, itemId)
  }

  /** The outcome shown to the player, and whether the bag/party changed. */
  data class Outcome(
      val text: String,
      val changed: Boolean,
      val more: List<String> = emptyList(),
      /** The Escape Rope was spent; the caller warps. */
      val escape: Boolean = false,
  ) {
    fun lines() = listOf(text).filter { it.isNotEmpty() } + more
  }

  /**
   * Applies [itemId] to party monster [pokemonId] (or to the player); pure on refusal. [moveSlot]
   * is the chosen move for PP items; for a TM it is the move to forget when the monster knows four
   * ([forgetSlot] is an alias kept for the action packet, -1 when unused).
   */
  suspend fun use(
      charId: Long,
      itemId: Int,
      pokemonId: Long,
      busy: Boolean = false,
      moveSlot: Int = -1,
      forgetSlot: Int = -1,
  ): Outcome {
    val item = items.get(itemId) ?: return Outcome("That item does not exist.", false)
    if (busy || battles.inBattle(charId)) return Outcome("You can't use that right now.", false)
    val stored = characters.getCharacter(charId) ?: return Outcome("", false)
    if ((stored.items[itemId] ?: 0) <= 0) return Outcome("You don't have any ${item.name}.", false)
    val effect =
        FieldItemEffects.of(item) ?: return Outcome("${item.name} can't be used here.", false)

    if (effect is FieldItemEffect.Repel) {
      if (stored.info.repelLeft > 0) {
        return Outcome("The effects of the last REPEL lingers on.", false)
      }
      if (!characters.addItem(charId, itemId, -1)) return Outcome("", false)
      characters.setRepel(charId, effect.steps, itemId)
      log.info { "char=$charId used ${item.name}: repel for ${effect.steps} steps" }
      return Outcome("You used the ${item.name}. Wild POKéMON will be repelled.", true)
    }
    if (effect is FieldItemEffect.EscapeRope) {
      if (!escapeRope.canEscape(charId)) return Outcome("Can't use that here.", false)
      if (!characters.addItem(charId, itemId, -1)) return Outcome("", false)
      return Outcome("${stored.info.name} used the ESCAPE ROPE.", true, escape = true)
    }

    val mon =
        stored.pokemon.firstOrNull { it.id == pokemonId }
            ?: return Outcome("Choose a POKéMON in your party.", false)
    if (mon.isEgg) return Outcome("It won't have any effect.", false)
    val name = displayName(mon)

    when (effect) {
      FieldItemEffect.RareCandy -> return rareCandy(charId, itemId, mon, name)
      FieldItemEffect.EvolutionStone -> return stone(charId, item, itemId, mon, name)
      is FieldItemEffect.TeachMachine ->
          return teach(charId, item, itemId, mon, name, effect.index,
              if (forgetSlot >= 0) forgetSlot else moveSlot)
      else -> {}
    }

    if (FieldItemEffects.needsMove(effect) && moveSlot !in mon.moves.indices) {
      return Outcome("Choose a move.", false)
    }
    val updated = apply(effect, mon, moveSlot) ?: return Outcome("It won't have any effect.", false)
    if (!characters.addItem(charId, itemId, -1)) return Outcome("", false)
    characters.updatePokemon(charId, updated)
    log.info { "char=$charId used ${item.name} on ${mon.id}" }
    return Outcome(describe(item, effect, name, mon, updated, moveSlot), true)
  }

  /** The monster after [effect], or null when it would do nothing. */
  internal fun apply(effect: FieldItemEffect, mon: Pokemon, moveSlot: Int = -1): Pokemon? {
    val maxHp = maxHp(mon)
    return when (effect) {
      is FieldItemEffect.Heal -> {
        if (mon.hp <= 0) return null
        val cured = mon.status and effect.cures.inv()
        if (mon.hp >= maxHp && cured == mon.status) return null
        mon.copy(
            hp = (mon.hp + effect.amount.toLong()).coerceAtMost(maxHp.toLong()).toInt().toShort(),
            status = cured)
      }
      is FieldItemEffect.Revive -> {
        if (mon.hp > 0) return null
        mon.copy(hp = (maxHp * effect.fraction).toInt().coerceIn(1, maxHp).toShort(), status = 0)
      }
      is FieldItemEffect.RestoreAllPp -> {
        val restored =
            mon.moves.mapIndexed { i, m -> restorePp(mon, m, i, effect.amount) }
        if (restored == mon.moves) null else mon.copy(moves = restored)
      }
      is FieldItemEffect.RestorePp -> {
        val m = mon.moves.getOrNull(moveSlot) ?: return null
        if (m.id.toInt() == 0) return null
        val restored = restorePp(mon, m, moveSlot, effect.amount)
        if (restored == m) null
        else mon.copy(moves = mon.moves.toMutableList().also { it[moveSlot] = restored })
      }
      is FieldItemEffect.CureStatus -> {
        if (mon.hp <= 0 || mon.status and effect.mask == 0) null
        else mon.copy(status = mon.status and effect.mask.inv())
      }
      is FieldItemEffect.Vitamin -> {
        val current = ev(mon.eVs, effect.stat)
        if (mon.eVs.total >= MAX_TOTAL_EVS || current >= EV_ITEM_RAISE_LIMIT) return null
        val add =
            minOf(EV_STEP, EV_ITEM_RAISE_LIMIT - current, MAX_TOTAL_EVS - mon.eVs.total)
        rescaled(mon, mon.copy(eVs = growth.withEv(mon.eVs, effect.stat, current + add)))
      }
      is FieldItemEffect.EvBerry -> {
        val current = ev(mon.eVs, effect.stat)
        // The decomp still raises friendship at 0 EVs; friendship is not kept here, so nothing.
        if (current == 0) return null
        rescaled(mon, mon.copy(eVs = growth.withEv(mon.eVs, effect.stat, (current - EV_STEP).coerceAtLeast(0))))
      }
      is FieldItemEffect.PpUp -> {
        val m = mon.moves.getOrNull(moveSlot) ?: return null
        val base = moves.get(m.id.toInt())?.pp ?: return null
        if (m.id.toInt() == 0 || base < MIN_PP_FOR_PP_UP) return null
        val ups = (mon.ppBonuses shr (moveSlot * 2)) and 3
        if (ups >= MAX_PP_UPS) return null
        val newUps = (ups + effect.steps).coerceAtMost(MAX_PP_UPS)
        val bonuses = (mon.ppBonuses and (3 shl (moveSlot * 2)).inv()) or (newUps shl (moveSlot * 2))
        val gain = MonStatus.maxPp(base, bonuses, moveSlot) - MonStatus.maxPp(base, mon.ppBonuses, moveSlot)
        val moved = PokemonMove(m.id, (m.pp + gain).toByte())
        mon.copy(ppBonuses = bonuses, moves = mon.moves.toMutableList().also { it[moveSlot] = moved })
      }
      else -> null
    }
  }

  private fun restorePp(mon: Pokemon, m: PokemonMove, slot: Int, amount: Int): PokemonMove {
    if (m.id.toInt() == 0) return m
    val base = moves.get(m.id.toInt())?.pp ?: m.pp.toInt()
    val max = MonStatus.maxPp(base, mon.ppBonuses, slot)
    val pp = (m.pp + amount.toLong()).coerceAtMost(max.toLong()).toInt()
    return PokemonMove(m.id, pp.toByte())
  }

  private fun rescaled(before: Pokemon, after: Pokemon): Pokemon =
      if (before.hp <= 0) after else growth.rescaleHp(before, after)

  private suspend fun rareCandy(charId: Long, itemId: Int, mon: Pokemon, name: String): Outcome {
    val grown = growth.levelUp(mon) ?: return Outcome("It won't have any effect.", false)
    if (!characters.addItem(charId, itemId, -1)) return Outcome("", false)
    val lines = mutableListOf("$name grew to Lv. ${grown.mon.level}!")
    lines += grown.learned.map { "$name learned ${it.name}!" }
    var result: Growth = grown
    val evo = growth.levelEvolution(grown.mon)
    if (evo != null) {
      val party = characters.getCharacter(charId)?.pokemon?.size ?: 6
      val evolved = growth.evolve(grown.mon, evo, party, entityIds::newMonsterId)
      result = evolved.copy(offered = grown.offered + evolved.offered, learned = grown.learned + evolved.learned)
      lines += "Congratulations! Your $name evolved into ${speciesName(evolved.mon.dexId)}!"
      lines += evolved.learned.map { "${speciesName(evolved.mon.dexId)} learned ${it.name}!" }
    }
    commitGrowth(charId, mon, result)
    log.info { "char=$charId: Rare Candy took ${mon.id} to ${grown.mon.level}" }
    return Outcome(lines.first(), true, lines.drop(1))
  }

  private suspend fun stone(
      charId: Long,
      item: ItemDef,
      itemId: Int,
      mon: Pokemon,
      name: String,
  ): Outcome {
    val evo = growth.itemEvolution(mon, item) ?: return Outcome("It won't have any effect.", false)
    if (!characters.addItem(charId, itemId, -1)) return Outcome("", false)
    val party = characters.getCharacter(charId)?.pokemon?.size ?: 6
    val evolved = growth.evolve(mon, evo, party, entityIds::newMonsterId)
    commitGrowth(charId, mon, evolved)
    val newName = speciesName(evolved.mon.dexId)
    log.info { "char=$charId: ${item.name} evolved ${mon.id} into $newName" }
    return Outcome(
        "Congratulations! Your $name evolved into $newName!",
        true,
        evolved.learned.map { "$newName learned ${it.name}!" })
  }

  private suspend fun teach(
      charId: Long,
      item: ItemDef,
      itemId: Int,
      mon: Pokemon,
      name: String,
      index: Int,
      forget: Int,
  ): Outcome {
    val moveId = Gen3Data.tmhmMove(index) ?: return Outcome("${item.name} can't be used here.", false)
    val move = moves.get(moveId) ?: return Outcome("${item.name} can't be used here.", false)
    if (!Gen3Data.canLearnTmhm(mon.dexId, index)) {
      return Outcome("$name can't learn ${move.name}.", false)
    }
    if (mon.moves.any { it.id.toInt() == moveId }) {
      return Outcome("$name already knows ${move.name}.", false)
    }
    val full = mon.moves.count { it.id.toInt() != 0 } >= 4
    if (full && forget !in 0..3) {
      return Outcome("$name wants to learn ${move.name}. Choose a move to forget.", false)
    }
    if (full && Gen3Data.isHmMove(mon.moves[forget].id.toInt())) {
      return Outcome("HM moves can't be forgotten now.", false)
    }
    val forgotten = if (full) moves.get(mon.moves[forget].id.toInt())?.name else null
    val taught = growth.teach(mon, moveId, move.pp, if (full) forget else -1)
        ?: return Outcome("It won't have any effect.", false)
    // TMs are used up in Gen 3; HMs are not.
    val isHm = index >= Gen3Data.TM_COUNT
    if (!isHm && !characters.addItem(charId, itemId, -1)) return Outcome("", false)
    characters.updatePokemon(charId, taught)
    log.info { "char=$charId taught ${move.name} to ${mon.id} with ${item.name}" }
    val prefix = forgotten?.let { "1, 2, and… Poof! $name forgot $it. And… " }.orEmpty()
    return Outcome("$prefix$name learned ${move.name}!", true)
  }

  /** Writes a level-up / evolution result, the extra Shedinja, and queues moves to learn. */
  private suspend fun commitGrowth(charId: Long, before: Pokemon, g: Growth) {
    characters.updatePokemon(charId, g.mon)
    g.extra?.let { extra ->
      val stored = characters.getCharacter(charId)
      if (stored != null && stored.pokemon.size < 6) {
        characters.addPokemon(
            charId,
            extra.copy(ownerId = charId, containerSlot = stored.pokemon.size.toShort()))
      }
    }
    progress.queueLearn(
        charId, g.offered.map { LearnPrompt(before.id, it.moveId.toShort()) })
  }

  /** The learn prompt's answer: forget [slot] (0-3) for the offered move, or -1 to give it up. */
  fun answerLearn(charId: Long, slot: Int): String? {
    val prompt = progress.popLearn(charId) ?: return null
    val stored = characters.getCharacter(charId) ?: return null
    val mon = stored.pokemon.firstOrNull { it.id == prompt.monId } ?: return null
    val move = moves.get(prompt.moveId.toInt()) ?: return null
    val name = displayName(mon)
    if (slot !in 0..3) return "$name did not learn ${move.name}."
    if (mon.moves.any { it.id == prompt.moveId }) return null
    val forgotten = moves.get(mon.moves.getOrNull(slot)?.id?.toInt() ?: 0)?.name
    val taught = growth.teach(mon, move.id, move.pp, slot) ?: return null
    characters.updatePokemon(charId, taught)
    val prefix = forgotten?.let { "1, 2, and… Poof! $name forgot $it. And… " }.orEmpty()
    return "$prefix$name learned ${move.name}!"
  }

  private fun describe(
      item: ItemDef,
      effect: FieldItemEffect,
      name: String,
      before: Pokemon,
      after: Pokemon,
      moveSlot: Int,
  ): String =
      when (effect) {
        is FieldItemEffect.Heal ->
            if (after.hp != before.hp) "$name's HP was restored by ${after.hp - before.hp} point(s)."
            else "$name was cured."
        is FieldItemEffect.Revive -> "$name was revived by the ${item.name}!"
        is FieldItemEffect.RestoreAllPp -> "$name's PP was restored."
        is FieldItemEffect.RestorePp ->
            "${moves.get(after.moves[moveSlot].id.toInt())?.name ?: "The move"}'s PP was restored."
        is FieldItemEffect.CureStatus -> cureText(name, before.status and effect.mask)
        is FieldItemEffect.Vitamin -> "$name's ${statName(effect.stat)} rose."
        is FieldItemEffect.EvBerry -> "$name's base ${statName(effect.stat)} fell."
        is FieldItemEffect.PpUp ->
            "${moves.get(after.moves[moveSlot].id.toInt())?.name ?: "The move"}'s PP increased."
        else -> "You used the ${item.name}."
      }

  /** The decomp's cure strings (gText_PkmnCuredOfPoison & co.). */
  private fun cureText(name: String, cured: Int): String =
      when {
        cured and MonStatus.ANY_POISON.inv() == 0 ->
            "$name was cured of its poisoning."
        cured == MonStatus.BURN -> "$name's burn was healed."
        cured == MonStatus.FREEZE -> "$name was thawed out."
        cured and MonStatus.SLEEP.inv() == 0 -> "$name woke up."
        cured == MonStatus.PARALYSIS -> "$name was cured of paralysis."
        else -> "$name became healthy."
      }

  private fun statName(stat: EvStat): String =
      when (stat) {
        EvStat.HP -> "HP"
        EvStat.ATK -> "ATTACK"
        EvStat.DEF -> "DEFENSE"
        EvStat.SPEED -> "SPEED"
        EvStat.SPATK -> "SP. ATK"
        EvStat.SPDEF -> "SP. DEF"
      }

  private fun ev(evs: EVs, stat: EvStat): Int =
      when (stat) {
        EvStat.HP -> evs.hp
        EvStat.ATK -> evs.atk
        EvStat.DEF -> evs.def
        EvStat.SPEED -> evs.spd
        EvStat.SPATK -> evs.spAtk
        EvStat.SPDEF -> evs.spDef
      }

  private fun maxHp(mon: Pokemon): Int = growth.maxHp(mon)

  private fun speciesName(dexId: Int): String = species.get(dexId)?.name ?: "POKéMON"

  private fun displayName(mon: Pokemon): String =
      mon.nickname.ifEmpty { species.get(mon.dexId)?.name ?: "POKéMON" }

  private fun syncAfterUse(session: SessionContext, charId: Long, itemId: Int) {
    val after = characters.getCharacter(charId) ?: return
    progress.pushParty(session, charId)
    session.send(itemStackUpdatePacket(itemId, after.items[itemId] ?: 0))
    progress.push(session, charId)
  }
}
