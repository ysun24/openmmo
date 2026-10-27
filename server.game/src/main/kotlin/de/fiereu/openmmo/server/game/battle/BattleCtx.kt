package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.moves.MoveDef
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.typechart.TypeChart

/**
 * Everything one engine call works on: the battle seen from [battle]'s player (side 0 = its party,
 * side 1 = the opposing party) and the events produced so far, in order.
 */
internal class BattleCtx(
    val battle: BattleInstance,
    val moves: MoveRegistry,
    val typeChart: TypeChart,
    val held: HeldItems,
) {
  val events = mutableListOf<BattleEvent>()
  val rng: BattleRng
    get() = battle.rng

  val field: BattleField
    get() = battle.field

  fun emit(event: BattleEvent) {
    events += event
  }

  fun say(text: String, targetId: Long = 0) = emit(BattleEvent.Message(text, targetId))

  fun party(side: Int): List<BattleMonState> = if (side == 0) battle.party else battle.opponent

  fun sideState(side: Int): BattleSideState = if (side == 0) battle.own else battle.foe

  fun mon(ref: BattlerRef): BattleMonState? =
      sideState(ref.side).actives.getOrNull(ref.pos)?.let { party(ref.side).getOrNull(it) }

  /** The active, conscious monster at [ref], or null. */
  fun live(ref: BattlerRef): BattleMonState? = mon(ref)?.takeUnless { it.fainted }

  fun refOf(mon: BattleMonState): BattlerRef? {
    for (side in 0..1) {
      val actives = sideState(side).actives
      for (pos in actives.indices) {
        if (party(side).getOrNull(actives[pos])?.entityId == mon.entityId)
            return BattlerRef(side, pos)
      }
    }
    return null
  }

  fun sideOf(mon: BattleMonState): Int = if (battle.party.any { it === mon }) 0 else 1

  /** Every active position, conscious or not, in position order. */
  fun allRefs(): List<BattlerRef> =
      (0..1).flatMap { side -> sideState(side).actives.indices.map { BattlerRef(side, it) } }

  /** Active conscious monsters, fastest first (the order end-of-turn effects resolve in). */
  fun liveBySpeed(): List<Pair<BattlerRef, BattleMonState>> =
      allRefs()
          .mapNotNull { ref -> live(ref)?.let { ref to it } }
          .sortedByDescending { speedOf(it.second) }

  fun foesOf(ref: BattlerRef): List<BattlerRef> =
      sideState(ref.foeSide).actives.indices.map { BattlerRef(ref.foeSide, it) }

  fun allyOf(ref: BattlerRef): BattlerRef? =
      if (sideState(ref.side).actives.size < 2) null else BattlerRef(ref.side, 1 - ref.pos)

  /**
   * A monster's name in a battle line. Lines are written once and shown to both players of a duel,
   * so the name is a token the emitter turns into "CHARMANDER", "Foe CHARMANDER" or "Wild ..." per
   * viewer (see [BattleNames.localize]).
   */
  fun name(mon: BattleMonState): String = BattleNames.token(mon)

  fun move(id: Int): MoveDef? = moves.get(id)

  // --- abilities that cancel each other ----------------------------------------------------------

  /** Cloud Nine / Air Lock anywhere on the field suppress the weather's effects. */
  fun weather(): Weather =
      if (allRefs().any { live(it)?.ability in setOf(Ability.CLOUD_NINE, Ability.AIR_LOCK) })
          Weather.NONE
      else field.weather

  fun hasAbility(mon: BattleMonState, ability: Ability): Boolean = mon.ability == ability

  fun heldEffect(mon: BattleMonState): HoldEffect? = held.of(mon.heldItem)

  /** Gen 3 speed: stages, paralysis quarter, Swift Swim / Chlorophyll double, Macho Brace half. */
  fun speedOf(mon: BattleMonState): Int {
    var speed = mon.effective(BattleStat.SPEED)
    val w = weather()
    if (mon.ability == Ability.SWIFT_SWIM && w == Weather.RAIN) speed *= 2
    if (mon.ability == Ability.CHLOROPHYLL && w == Weather.SUN) speed *= 2
    when (heldEffect(mon)?.name) {
      "MACHO_BRACE" -> speed /= 2
    }
    if (mon.paralyzed) speed /= 4
    return speed
  }

  fun setHp(mon: BattleMonState, hp: Int, reason: String) {
    val clamped = hp.coerceIn(0, mon.maxHp)
    if (clamped == mon.currentHp && reason != "set") return
    mon.currentHp = clamped
    emit(BattleEvent.HpChanged(mon.entityId, clamped, reason))
    if (mon.fainted) faint(mon)
  }

  fun heal(mon: BattleMonState, amount: Int, reason: String): Int {
    if (mon.fainted || amount <= 0 || mon.currentHp >= mon.maxHp) return 0
    val before = mon.currentHp
    setHp(mon, mon.currentHp + amount, reason)
    return mon.currentHp - before
  }

  /** Residual damage (poison, burn, weather, recoil): at least 1, may faint. */
  fun hurt(mon: BattleMonState, amount: Int, reason: String) {
    if (mon.fainted) return
    setHp(mon, mon.currentHp - amount.coerceAtLeast(1), reason)
  }

  /** The client prints the faint line itself when it sees the hp reach 0. */
  fun faint(mon: BattleMonState) {
    emit(BattleEvent.Fainted(mon.entityId))
    mon.status = 0
    mon.volatile = Volatiles()
  }

  fun sideFainted(side: Int): Boolean = party(side).all { it.fainted }
}
