package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.common.enums.MoveEffect
import de.fiereu.openmmo.common.enums.MoveTarget
import de.fiereu.openmmo.common.enums.PokemonType
import de.fiereu.openmmo.moves.MoveDef
import de.fiereu.openmmo.typechart.TypeChart

/**
 * The opposing side's picks. Wild monsters pick a random usable move at a random foe, as the GBA's
 * wild AI does. Trainers score their moves the way the Gen 3 AI scripts AI_CheckBadMove,
 * AI_TryToFaint and AI_CheckViability broadly do: no moves the target is immune to, no status on a
 * monster that already has one, no stat moves at the cap, and a strong preference for damage that
 * knocks the target out.
 */
internal object BattleAi {

  /**
   * battle_ai_switch_items.c ShouldSwitch, for trainers: a monster that cannot be trapped switches
   * out when Perish Song is about to take it, when the foe has Wonder Guard and it has no
   * super-effective move, when none of its moves can affect the foe, or (Natural Cure, asleep, at
   * least half HP) half the time. The replacement is GetMostSuitableMonToSwitchInto's pick: the
   * bench monster whose best move hits the foe hardest by type. Null keeps it in.
   */
  fun switchChoice(
      ctx: BattleCtx,
      ref: BattlerRef,
      trapped: (BattleMonState) -> Boolean,
      taken: Set<Int> = emptySet(),
  ): BattleChoice? {
    if (ref.side != 1 || ctx.battle.trainer == null || ctx.battle.pvp != null) return null
    // r5-frontier: Arena and Palace monsters never switch out by choice.
    if (ctx.battle.facility?.allowSwitch == false) return null
    val mon = ctx.live(ref) ?: return null
    if (trapped(mon)) return null
    val foe = ctx.foesOf(ref).mapNotNull { ctx.live(it) }.firstOrNull() ?: return null
    val party = ctx.party(1)
    val actives = ctx.sideState(1).actives
    val bench =
        party.indices.filter {
          it !in actives && it !in taken && !party[it].fainted && !party[it].source.isEgg
        }
    if (bench.isEmpty()) return null
    val mine = bestMultiplier(ctx, mon, foe)
    val reason =
        when {
          mon.volatile.perishCount == 1 -> true
          foe.ability == Ability.WONDER_GUARD && mine <= TypeChart.NEUTRAL -> true
          mine == 0 -> true
          mon.ability == Ability.NATURAL_CURE &&
              mon.asleep &&
              mon.currentHp * 2 >= mon.maxHp &&
              ctx.rng.coinFlip() -> true
          else -> false
        }
    if (!reason) return null
    val pick =
        bench.maxByOrNull { i -> bestMultiplier(ctx, party[i], foe) * 1000 + party[i].currentHp }
            ?: return null
    // With Wonder Guard (or nothing that affects the foe), only a monster that does better helps.
    if ((foe.ability == Ability.WONDER_GUARD || mine == 0) &&
        bestMultiplier(ctx, party[pick], foe) <= mine) {
      return null
    }
    return BattleChoice.Switch(ref.side, ref.pos, pick)
  }

  /** The best type multiplier of [mon]'s damaging moves against [foe] (0 when none affects it). */
  private fun bestMultiplier(ctx: BattleCtx, mon: BattleMonState, foe: BattleMonState): Int {
    var best = 0
    for (m in mon.moves) {
      val def = ctx.move(m.id.toInt()) ?: continue
      if (def.power <= 0 || m.pp <= 0) continue
      var mult = typeMultiplier(ctx, def.type, foe)
      if (foe.ability == Ability.WONDER_GUARD && mult <= TypeChart.NEUTRAL) mult = 0
      if (foe.ability == Ability.LEVITATE && def.type == PokemonType.GROUND) mult = 0
      best = maxOf(best, mult)
    }
    return best
  }

  /**
   * r5-frontier: [allowed] restricts the pick to these move ids (the Battle Palace's nature-chosen
   * move group, BattleAI_SetupAIData(selectedMoves)); [forceSmart] scores moves like a trainer for
   * either side (the Palace runs the AI for the player's monsters too).
   */
  fun choose(
      ctx: BattleCtx,
      ref: BattlerRef,
      allowed: Set<Int>? = null,
      forceSmart: Boolean = false,
  ): BattleChoice {
    val mon = ctx.live(ref) ?: return BattleChoice.Pass(ref.side, ref.pos)
    val usable =
        mon.moves.filter {
          it.id.toInt() != 0 &&
              it.pp > 0 &&
              it.id.toInt() != mon.volatile.disabledMove &&
              (allowed == null || it.id.toInt() in allowed)
        }
    if (usable.isEmpty()) return BattleChoice.UseMove(ref.side, ref.pos, STRUGGLE_ID)
    val foes = ctx.foesOf(ref).filter { ctx.live(it) != null }
    val smart = forceSmart || ref.side == 1 && ctx.battle.trainer != null
    if (!smart) {
      val move = usable[ctx.rng.pick(usable.size)].id.toInt()
      val target = if (foes.isEmpty()) null else foes[ctx.rng.pick(foes.size)]
      return BattleChoice.UseMove(ref.side, ref.pos, move, target)
    }
    var best: Triple<Int, Int, BattlerRef?>? = null
    for (m in usable) {
      val def = ctx.move(m.id.toInt()) ?: continue
      val targets: List<BattlerRef?> =
          if (def.target == MoveTarget.SELECTED && foes.size > 1) foes
          else listOf(foes.firstOrNull())
      for (t in targets) {
        val target = t?.let { ctx.live(it) }
        val score = score(ctx, mon, target, def) + ctx.rng.pick(6)
        if (best == null || score > best.first) best = Triple(score, def.id, t)
      }
    }
    val pick = best ?: return BattleChoice.UseMove(ref.side, ref.pos, usable.first().id.toInt())
    return BattleChoice.UseMove(ref.side, ref.pos, pick.second, pick.third)
  }

  private fun score(
      ctx: BattleCtx,
      user: BattleMonState,
      target: BattleMonState?,
      move: MoveDef
  ): Int {
    var score = 100
    if (target == null) return score
    val damaging = move.power > 0 || move.effect in FIXED
    if (damaging) {
      val eff = typeMultiplier(ctx, move.type, target)
      if (eff == 0) return 0
      if (move.type == PokemonType.GROUND && target.ability == Ability.LEVITATE) return 0
      if (move.type == PokemonType.ELECTRIC && target.ability == Ability.VOLT_ABSORB) return 0
      if (move.type == PokemonType.WATER && target.ability == Ability.WATER_ABSORB) return 0
      if (move.type == PokemonType.FIRE && target.ability == Ability.FLASH_FIRE) return 0
      if (target.ability == Ability.WONDER_GUARD && eff <= TypeChart.NEUTRAL) return 0
      if (move.effect == MoveEffect.DREAM_EATER && !target.asleep) return 0
      val estimate = estimate(user, target, move, eff)
      if (estimate >= target.currentHp) score += 40
      score += (estimate * 40 / target.currentHp.coerceAtLeast(1)).coerceAtMost(30)
      if (eff > TypeChart.NEUTRAL) score += 5
      return score
    }
    // Status and stat moves.
    when (move.effect) {
      MoveEffect.SLEEP,
      MoveEffect.POISON,
      MoveEffect.TOXIC,
      MoveEffect.PARALYZE,
      MoveEffect.WILL_O_WISP ->
          if (target.hasStatus || ctx.statusBlock(target, statusOf(move.effect), user) != null)
              return 0
      MoveEffect.CONFUSE,
      MoveEffect.SWAGGER,
      MoveEffect.FLATTER -> if (target.confused || target.ability == Ability.OWN_TEMPO) return 0
      MoveEffect.LEECH_SEED ->
          if (target.volatile.seededBy != 0L || target.hasType(PokemonType.GRASS)) return 0
      MoveEffect.SUBSTITUTE ->
          if (user.volatile.substituteHp > 0 || user.currentHp <= user.maxHp / 4) return 0
      MoveEffect.REFLECT ->
          if ((ctx.refOf(user)?.let { ctx.sideState(it.side).reflectTurns } ?: 0) > 0) return 0
      MoveEffect.LIGHT_SCREEN ->
          if ((ctx.refOf(user)?.let { ctx.sideState(it.side).lightScreenTurns } ?: 0) > 0) return 0
      MoveEffect.RESTORE_HP,
      MoveEffect.SOFTBOILED,
      MoveEffect.MORNING_SUN,
      MoveEffect.SYNTHESIS,
      MoveEffect.MOONLIGHT -> return if (user.currentHp * 2 < user.maxHp) 130 else 0
      MoveEffect.RAIN_DANCE -> if (ctx.field.weather == Weather.RAIN) return 0
      MoveEffect.SUNNY_DAY -> if (ctx.field.weather == Weather.SUN) return 0
      MoveEffect.SANDSTORM -> if (ctx.field.weather == Weather.SAND) return 0
      MoveEffect.HAIL -> if (ctx.field.weather == Weather.HAIL) return 0
      MoveEffect.SPLASH -> return 1
      else -> Unit
    }
    stageOf(move.effect)?.let { (stat, selfTarget, up) ->
      val who = if (selfTarget) user else target
      if (up && who.stage(stat) >= StatStages.MAX) return 0
      if (!up && who.stage(stat) <= StatStages.MIN) return 0
      if (!up && who.ability in setOf(Ability.CLEAR_BODY, Ability.WHITE_SMOKE)) return 0
    }
    // Set-up moves are worth less once the monster is low.
    if (user.currentHp * 3 < user.maxHp) score -= 10
    return score - 2
  }

  private val FIXED =
      setOf(
          MoveEffect.SONICBOOM,
          MoveEffect.DRAGON_RAGE,
          MoveEffect.LEVEL_DAMAGE,
          MoveEffect.SUPER_FANG,
          MoveEffect.PSYWAVE)

  private fun statusOf(effect: MoveEffect): Int =
      when (effect) {
        MoveEffect.SLEEP -> StatusCondition.SLEEP_MASK
        MoveEffect.TOXIC -> StatusCondition.TOXIC
        MoveEffect.POISON -> StatusCondition.POISON
        MoveEffect.PARALYZE -> StatusCondition.PARALYSIS
        else -> StatusCondition.BURN
      }

  private fun stageOf(effect: MoveEffect): Triple<BattleStat, Boolean, Boolean>? {
    val name = effect.name
    val stat =
        when {
          name.startsWith("ATTACK_") -> BattleStat.ATTACK
          name.startsWith("DEFENSE_") -> BattleStat.DEFENSE
          name.startsWith("SPEED_") -> BattleStat.SPEED
          name.startsWith("SPECIAL_ATTACK_") -> BattleStat.SP_ATTACK
          name.startsWith("SPECIAL_DEFENSE_") -> BattleStat.SP_DEFENSE
          name.startsWith("ACCURACY_") -> BattleStat.ACCURACY
          name.startsWith("EVASION_") -> BattleStat.EVASION
          else -> return null
        }
    if (name.endsWith("_HIT")) return null
    val up = "_UP" in name
    return Triple(stat, up, up)
  }

  private fun typeMultiplier(ctx: BattleCtx, type: PokemonType, target: BattleMonState): Int {
    var r = TypeChart.NEUTRAL
    for ((i, t) in target.types.withIndex()) {
      if (i == 1 && t == target.types[0]) break
      if (t == PokemonType.NONE) continue
      r = r * ctx.typeChart.multiplier(type, t) / TypeChart.NEUTRAL
    }
    return r
  }

  private fun estimate(user: BattleMonState, target: BattleMonState, move: MoveDef, eff: Int): Int {
    when (move.effect) {
      MoveEffect.SONICBOOM -> return 20
      MoveEffect.DRAGON_RAGE -> return 40
      MoveEffect.LEVEL_DAMAGE,
      MoveEffect.PSYWAVE -> return user.level
      MoveEffect.SUPER_FANG -> return target.currentHp / 2
      else -> Unit
    }
    val physical = MoveCategory.isPhysical(move.type)
    val atk = user.effective(if (physical) BattleStat.ATTACK else BattleStat.SP_ATTACK)
    val def =
        target
            .effective(if (physical) BattleStat.DEFENSE else BattleStat.SP_DEFENSE)
            .coerceAtLeast(1)
    var dmg = (2 * user.level / 5 + 2) * move.power * atk / def / 50 + 2
    if (user.hasType(move.type)) dmg = dmg * 3 / 2
    dmg = dmg * eff / TypeChart.NEUTRAL
    // The average of the 85-100 roll, scaled by accuracy.
    return dmg * 92 / 100 * (if (move.accuracy == 0) 100 else move.accuracy) / 100
  }
}
