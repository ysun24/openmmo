package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.common.enums.PokemonType

/*
 * Stat stage changes and the abilities that fire outside the damage formula: on entry
 * (Intimidate, Drizzle, Drought, Sand Stream, Trace), on contact (Static, Flame Body, Poison Point,
 * Effect Spore, Rough Skin, Cute Charm) and the stat-drop guards (Clear Body, Hyper Cutter, Keen
 * Eye, White Smoke, Mist). See src/battle_util.c AbilityBattleEffects.
 */

internal fun BattleCtx.stageValue(mon: BattleMonState, stat: BattleStat): Int =
    when (stat) {
      BattleStat.ACCURACY,
      BattleStat.EVASION -> 0
      else -> mon.effective(stat)
    }

internal fun BattleCtx.raiseStat(
    target: BattleMonState,
    source: BattleMonState,
    stat: BattleStat,
    delta: Int,
    announce: Boolean,
    cause: String? = null,
): Boolean {
  if (target.fainted) return false
  val applied = target.changeStage(stat, delta)
  emit(
      BattleEvent.StageChanged(
          target.entityId, stat, target.stage(stat), stageValue(target, stat), delta, applied == 0))
  if (applied == 0 && announce) {
    say("${name(target)}'s ${statLabel(stat)} won't go higher!", target.entityId)
  }
  return applied != 0
}

/**
 * Lowers [target]'s [stat]. [fromFoe] is false for self-inflicted drops (Superpower, Overheat),
 * which no guard stops.
 */
internal fun BattleCtx.lowerStat(
    target: BattleMonState,
    source: BattleMonState?,
    stat: BattleStat,
    delta: Int,
    announce: Boolean,
    fromFoe: Boolean = true,
): Boolean {
  if (target.fainted) return false
  if (fromFoe && source !== target) {
    val ref = refOf(target)
    if (ref != null && sideState(ref.side).mistTurns > 0) {
      if (announce) say("${name(target)} is protected by MIST!", target.entityId)
      return false
    }
    if (target.volatile.substituteHp > 0 && announce) {
      say("But it failed!", target.entityId)
      return false
    }
    val guard =
        when {
          target.ability in setOf(Ability.CLEAR_BODY, Ability.WHITE_SMOKE) -> target.ability
          target.ability == Ability.HYPER_CUTTER && stat == BattleStat.ATTACK -> target.ability
          target.ability == Ability.KEEN_EYE && stat == BattleStat.ACCURACY -> target.ability
          else -> null
        }
    if (guard != null) {
      if (announce) {
        emit(BattleEvent.AbilityShown(target.entityId, abilityName(guard)))
        say("${name(target)}'s ${abilityName(guard)} prevents stat loss!", target.entityId)
      }
      return false
    }
  }
  val applied = target.changeStage(stat, -delta)
  emit(
      BattleEvent.StageChanged(
          target.entityId, stat, target.stage(stat), stageValue(target, stat), -delta, applied == 0))
  if (applied == 0 && announce) {
    say("${name(target)}'s ${statLabel(stat)} won't go lower!", target.entityId)
  }
  if (applied != 0) whiteHerb(target)
  return applied != 0
}

internal fun statLabel(stat: BattleStat): String =
    when (stat) {
      BattleStat.ATTACK -> "ATTACK"
      BattleStat.DEFENSE -> "DEFENSE"
      BattleStat.SPEED -> "SPEED"
      BattleStat.SP_ATTACK -> "SP. ATK"
      BattleStat.SP_DEFENSE -> "SP. DEF"
      BattleStat.ACCURACY -> "accuracy"
      BattleStat.EVASION -> "evasiveness"
    }

// --- entry ---------------------------------------------------------------------------------------

/** Spikes, then the entry abilities, for a monster that just came out at [ref]. */
internal fun BattleCtx.onEntry(ref: BattlerRef) {
  val mon = live(ref) ?: return
  mon.volatile.actedSinceSwitchIn = false
  val side = sideState(ref.side)
  if (side.spikes > 0 && !mon.hasType(PokemonType.FLYING) && mon.ability != Ability.LEVITATE) {
    val divisor = when (side.spikes) {
      1 -> 8
      2 -> 6
      else -> 4
    }
    say("${name(mon)} is hurt by SPIKES!", mon.entityId)
    hurt(mon, mon.maxHp / divisor, "spikes")
    if (mon.fainted) return
  }
  entryAbility(ref, mon)
}

internal fun BattleCtx.entryAbility(ref: BattlerRef, mon: BattleMonState) {
  when (mon.ability) {
    Ability.INTIMIDATE -> {
      emit(BattleEvent.AbilityShown(mon.entityId, "INTIMIDATE"))
      for (foeRef in foesOf(ref)) {
        val foe = live(foeRef) ?: continue
        if (foe.volatile.substituteHp > 0) continue
        if (foe.ability in setOf(Ability.CLEAR_BODY, Ability.HYPER_CUTTER, Ability.WHITE_SMOKE)) {
          say("${name(foe)}'s ${abilityName(foe.ability)} prevents ATTACK loss!", foe.entityId)
          continue
        }
        say("${name(mon)}'s INTIMIDATE cuts ${name(foe)}'s ATTACK!", foe.entityId)
        lowerStat(foe, mon, BattleStat.ATTACK, 1, announce = false, fromFoe = false)
      }
    }
    Ability.DRIZZLE -> abilityWeather(mon, Weather.RAIN, "DRIZZLE made it rain!")
    Ability.DROUGHT -> abilityWeather(mon, Weather.SUN, "DROUGHT intensified the sun's rays!")
    Ability.SAND_STREAM -> abilityWeather(mon, Weather.SAND, "SAND STREAM whipped up a sandstorm!")
    Ability.PRESSURE -> {
      emit(BattleEvent.AbilityShown(mon.entityId, "PRESSURE"))
      say("${name(mon)} is exerting its PRESSURE!", mon.entityId)
    }
    Ability.TRACE -> {
      val foes = foesOf(ref).mapNotNull { live(it) }.filter { it.ability != Ability.NONE }
      if (foes.isNotEmpty()) {
        val copied = foes[rng.pick(foes.size)]
        mon.ability = copied.ability
        emit(BattleEvent.AbilityShown(mon.entityId, "TRACE"))
        say("${name(mon)} TRACED ${name(copied)}'s ${abilityName(copied.ability)}!", mon.entityId)
        if (mon.ability != Ability.TRACE) entryAbility(ref, mon)
      }
    }
    Ability.CLOUD_NINE,
    Ability.AIR_LOCK ->
        if (field.weather != Weather.NONE) {
          emit(BattleEvent.AbilityShown(mon.entityId, abilityName(mon.ability)))
          say("The effects of weather disappeared.")
        }
    else -> Unit
  }
}

private fun BattleCtx.abilityWeather(mon: BattleMonState, weather: Weather, text: String) {
  if (field.weather == weather && field.weatherTurns == 0) return
  field.weather = weather
  field.weatherTurns = 0
  emit(BattleEvent.AbilityShown(mon.entityId, abilityName(mon.ability)))
  emit(BattleEvent.WeatherChanged(weather))
  say("${name(mon)}'s $text", mon.entityId)
}

// --- contact -------------------------------------------------------------------------------------

/** The defender's contact abilities after a contact hit landed on it. */
internal fun BattleCtx.contactAbility(attacker: BattleMonState, defender: BattleMonState) {
  if (attacker.fainted) return
  val chance30 = rng.pick(3) == 0
  when (defender.ability) {
    Ability.STATIC ->
        if (chance30 && !attacker.hasStatus) {
          emit(BattleEvent.AbilityShown(defender.entityId, "STATIC"))
          trySetStatus(attacker, StatusCondition.PARALYSIS, defender, announceFail = false)
        }
    Ability.FLAME_BODY ->
        if (chance30 && !attacker.hasStatus) {
          emit(BattleEvent.AbilityShown(defender.entityId, "FLAME BODY"))
          trySetStatus(attacker, StatusCondition.BURN, defender, announceFail = false)
        }
    Ability.POISON_POINT ->
        if (chance30 && !attacker.hasStatus) {
          emit(BattleEvent.AbilityShown(defender.entityId, "POISON POINT"))
          trySetStatus(attacker, StatusCondition.POISON, defender, announceFail = false)
        }
    Ability.EFFECT_SPORE ->
        if (rng.pick(10) == 0 && !attacker.hasStatus) {
          val status =
              when (rng.pick(3)) {
                0 -> StatusCondition.POISON
                1 -> StatusCondition.PARALYSIS
                else -> StatusCondition.SLEEP_MASK
              }
          emit(BattleEvent.AbilityShown(defender.entityId, "EFFECT SPORE"))
          trySetStatus(attacker, status, defender, announceFail = false)
        }
    Ability.ROUGH_SKIN -> {
      emit(BattleEvent.AbilityShown(defender.entityId, "ROUGH SKIN"))
      say("${name(attacker)} was hurt by ${name(defender)}'s ROUGH SKIN!", attacker.entityId)
      hurt(attacker, attacker.maxHp / 16, "roughskin")
    }
    Ability.CUTE_CHARM ->
        if (chance30 && attacker.volatile.attractedTo == 0L && genderOpposite(attacker, defender) &&
            attacker.ability != Ability.OBLIVIOUS) {
          attacker.volatile.attractedTo = defender.entityId
          emit(BattleEvent.AbilityShown(defender.entityId, "CUTE CHARM"))
          say("${name(defender)}'s CUTE CHARM infatuated ${name(attacker)}!", attacker.entityId)
          mentalHerb(attacker)
        }
    else -> Unit
  }
}

internal fun genderOpposite(a: BattleMonState, b: BattleMonState): Boolean {
  val ga = genderOf(a)
  val gb = genderOf(b)
  return ga != 0 && gb != 0 && ga != gb
}

/** 1 male, 2 female, 0 genderless, from the species ratio and the personality's low byte. */
internal fun genderOf(mon: BattleMonState): Int {
  val ratio = mon.species.genderRatio
  return when (ratio) {
    255 -> 0
    254 -> 2
    0 -> 1
    else -> if ((mon.source.seed and 0xFF) < ratio) 2 else 1
  }
}
