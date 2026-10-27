package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.common.enums.PokemonType

/*
 * Gen 3 major status and confusion rules (src/battle_script_commands.c Cmd_seteffectwithchance /
 * SetMoveEffect, src/battle_util.c DoBattlerEndTurnEffects).
 */

internal fun statusName(status: Int): String =
    when {
      status and StatusCondition.SLEEP_MASK != 0 -> "sleep"
      status and StatusCondition.TOXIC != 0 -> "badly poisoned"
      status and StatusCondition.POISON != 0 -> "poison"
      status and StatusCondition.BURN != 0 -> "burn"
      status and StatusCondition.FREEZE != 0 -> "freeze"
      status and StatusCondition.PARALYSIS != 0 -> "paralysis"
      else -> "none"
    }

/** Why [target] cannot take [status] right now, or null when it can. */
internal fun BattleCtx.statusBlock(
    target: BattleMonState,
    status: Int,
    source: BattleMonState?,
): String? {
  val who = name(target)
  if (target.fainted) return "But it failed!"
  if (target.hasStatus) {
    return when {
      status and StatusCondition.SLEEP_MASK != 0 && target.asleep -> "$who is already asleep!"
      status and (StatusCondition.POISON or StatusCondition.TOXIC) != 0 && target.poisoned ->
          "$who is already poisoned."
      status == StatusCondition.BURN && target.burned -> "$who already has a burn."
      status == StatusCondition.PARALYSIS && target.paralyzed -> "$who is already paralyzed!"
      else -> "But it failed!"
    }
  }
  val ref = refOf(target)
  if (source != null && source !== target && ref != null &&
      sideState(ref.side).safeguardTurns > 0) {
    return "$who is protected by SAFEGUARD!"
  }
  when {
    status and StatusCondition.SLEEP_MASK != 0 -> {
      if (target.ability in setOf(Ability.INSOMNIA, Ability.VITAL_SPIRIT)) {
        return "$who's ${abilityName(target.ability)} prevents sleep!"
      }
      if (allRefs().any { live(it)?.volatile?.uproarTurns ?: 0 > 0 }) {
        return "But the UPROAR kept it awake!"
      }
    }
    status and (StatusCondition.POISON or StatusCondition.TOXIC) != 0 -> {
      if (target.hasType(PokemonType.POISON) || target.hasType(PokemonType.STEEL)) {
        return "It doesn't affect $who..."
      }
      if (target.ability == Ability.IMMUNITY) return "$who's IMMUNITY prevents poisoning!"
    }
    status == StatusCondition.BURN -> {
      if (target.hasType(PokemonType.FIRE)) return "It doesn't affect $who..."
      if (target.ability == Ability.WATER_VEIL) return "$who's WATER VEIL prevents burns!"
    }
    status == StatusCondition.FREEZE -> {
      if (target.hasType(PokemonType.ICE)) return "It doesn't affect $who..."
      if (target.ability == Ability.MAGMA_ARMOR) return "$who's MAGMA ARMOR prevents freezing!"
      if (weather() == Weather.SUN) return "But it failed!"
    }
    status == StatusCondition.PARALYSIS -> {
      if (target.ability == Ability.LIMBER) return "$who's LIMBER prevents paralysis!"
    }
  }
  return null
}

/**
 * Gives [target] [status] (a STATUS1 bit; for sleep any value in the mask is replaced by a 2-5 turn
 * roll). [announceFail] prints why it did not work, as a primary status move does.
 */
internal fun BattleCtx.trySetStatus(
    target: BattleMonState,
    status: Int,
    source: BattleMonState?,
    announceFail: Boolean,
): Boolean {
  val block = statusBlock(target, status, source)
  if (block != null) {
    if (announceFail) say(block, target.entityId)
    return false
  }
  val packed =
      if (status and StatusCondition.SLEEP_MASK != 0) {
        // Gen 3 rolls 2-5 sleep turns ((Random() & 3) + 2).
        if (status == REST_SLEEP) REST_SLEEP else rng.range(2, 5)
      } else status
  target.status = packed
  if (status == StatusCondition.TOXIC) target.volatile.toxicCounter = 0
  emit(BattleEvent.StatusChanged(target.entityId, packed, StatusCondition.code(packed)))
  val who = name(target)
  say(
      when {
        packed and StatusCondition.SLEEP_MASK != 0 ->
            if (status == REST_SLEEP) "$who slept and became healthy!" else "$who fell asleep!"
        packed == StatusCondition.TOXIC -> "$who is badly poisoned!"
        packed == StatusCondition.POISON -> "$who was poisoned!"
        packed == StatusCondition.BURN -> "$who was burned!"
        packed == StatusCondition.FREEZE -> "$who was frozen solid!"
        else -> "$who is paralyzed! It may be unable to move!"
      },
      target.entityId)
  // Synchronize passes poison, burn and paralysis back to the monster that caused them.
  if (source != null && source !== target && target.ability == Ability.SYNCHRONIZE &&
      packed in SYNCHRONIZED && !source.hasStatus) {
    emit(BattleEvent.AbilityShown(target.entityId, "SYNCHRONIZE"))
    trySetStatus(source, if (packed == StatusCondition.TOXIC) StatusCondition.POISON else packed,
        null, announceFail = false)
  }
  statusBerry(target)
  return true
}

/** Rest's sleep is always two turns: STATUS1_SLEEP_TURN(3) counts down to wake on the third. */
internal const val REST_SLEEP = 3

private val SYNCHRONIZED =
    setOf(
        StatusCondition.POISON,
        StatusCondition.TOXIC,
        StatusCondition.BURN,
        StatusCondition.PARALYSIS)

internal fun BattleCtx.cureStatus(mon: BattleMonState, message: String?) {
  if (!mon.hasStatus) return
  mon.status = 0
  mon.volatile.toxicCounter = 0
  emit(BattleEvent.StatusChanged(mon.entityId, 0, null))
  if (message != null) say(message, mon.entityId)
}

internal fun BattleCtx.tryConfuse(
    target: BattleMonState,
    source: BattleMonState?,
    announceFail: Boolean,
): Boolean {
  val who = name(target)
  val fail =
      when {
        target.fainted -> "But it failed!"
        target.confused -> "$who is already confused!"
        target.ability == Ability.OWN_TEMPO -> "$who's OWN TEMPO prevents confusion!"
        source != null && source !== target &&
            refOf(target)?.let { sideState(it.side).safeguardTurns > 0 } == true ->
            "$who is protected by SAFEGUARD!"
        else -> null
      }
  if (fail != null) {
    if (announceFail) say(fail, target.entityId)
    return false
  }
  // Gen 3: 2-5 turns ((Random() % 4) + 2).
  target.volatile.confusionTurns = rng.range(2, 5)
  emit(BattleEvent.VolatileChanged(target.entityId, "confused", true))
  say("$who became confused!", target.entityId)
  confusionBerry(target)
  return true
}

internal fun abilityName(ability: Ability): String = ability.name.replace('_', ' ')

// --- end of turn ---------------------------------------------------------------------------------

/** DoFieldEndTurnEffects then DoBattlerEndTurnEffects, in the Gen 3 order. */
internal fun BattleCtx.endOfTurn() {
  // Screens, Mist and Safeguard count down per side.
  for (side in 0..1) {
    val s = sideState(side)
    val whose = if (side == 0) "Your team's" else "The foe's"
    if (s.reflectTurns > 0 && --s.reflectTurns == 0) say("$whose REFLECT wore off!")
    if (s.lightScreenTurns > 0 && --s.lightScreenTurns == 0) say("$whose LIGHT SCREEN wore off!")
    if (s.mistTurns > 0 && --s.mistTurns == 0) say("$whose MIST wore off!")
    if (s.safeguardTurns > 0 && --s.safeguardTurns == 0) say("$whose SAFEGUARD wore off!")
    for ((pos, wish) in s.wish.toMap()) {
      val (turns, amount) = wish
      if (turns <= 1) {
        s.wish.remove(pos)
        val mon = live(BattlerRef(side, pos)) ?: continue
        say("${name(mon)}'s wish came true!", mon.entityId)
        heal(mon, amount, "wish")
      } else s.wish[pos] = (turns - 1) to amount
    }
  }
  weatherEndOfTurn()
  // Future Sight lands two turns after it was used.
  for (side in 0..1) {
    val s = sideState(side)
    for ((pos, fs) in s.futureSight.toMap()) {
      val (turns, damage) = fs
      if (turns <= 1) {
        s.futureSight.remove(pos)
        val mon = live(BattlerRef(side, pos)) ?: continue
        say("${name(mon)} took the FUTURE SIGHT attack!", mon.entityId)
        hurt(mon, damage, "futuresight")
      } else s.futureSight[pos] = (turns - 1) to damage
    }
  }
  for ((ref, mon) in liveBySpeed()) {
    battlerEndOfTurn(ref, mon)
  }
  // Perish Song counts down after everything else.
  for ((_, mon) in liveBySpeed()) {
    val v = mon.volatile
    if (v.perishCount > 0) {
      v.perishCount -= 1
      say("${name(mon)}'s PERISH count fell to ${v.perishCount}!", mon.entityId)
      if (v.perishCount == 0) setHp(mon, 0, "perish")
    }
  }
}

private fun BattleCtx.weatherEndOfTurn() {
  val w = field.weather
  if (w == Weather.NONE) return
  if (field.weatherTurns > 0 && --field.weatherTurns == 0) {
    say(
        when (w) {
          Weather.RAIN -> "The rain stopped."
          Weather.SUN -> "The sunlight faded."
          Weather.SAND -> "The sandstorm subsided."
          Weather.HAIL -> "The hail stopped."
          Weather.NONE -> ""
        })
    field.weather = Weather.NONE
    emit(BattleEvent.WeatherChanged(Weather.NONE))
    return
  }
  say(
      when (w) {
        Weather.RAIN -> "Rain continues to fall."
        Weather.SUN -> "The sunlight is strong."
        Weather.SAND -> "The sandstorm rages."
        Weather.HAIL -> "Hail continues to fall."
        Weather.NONE -> ""
      })
  emit(BattleEvent.WeatherChanged(w))
  if (weather() == Weather.NONE) return
  for ((_, mon) in liveBySpeed()) {
    val immune =
        when (w) {
          Weather.SAND ->
              mon.hasType(PokemonType.ROCK) || mon.hasType(PokemonType.GROUND) ||
                  mon.hasType(PokemonType.STEEL) || mon.ability == Ability.SAND_VEIL
          Weather.HAIL -> mon.hasType(PokemonType.ICE)
          else -> true
        }
    if (immune || mon.volatile.semiInvulnerable in setOf("DIG", "DIVE")) continue
    say(
        if (w == Weather.SAND) "${name(mon)} is buffeted by the sandstorm!"
        else "${name(mon)} is pelted by HAIL!",
        mon.entityId)
    hurt(mon, mon.maxHp / 16, "weather")
  }
}

private fun BattleCtx.battlerEndOfTurn(ref: BattlerRef, mon: BattleMonState) {
  val v = mon.volatile
  val who = name(mon)
  if (v.ingrained && !mon.fainted) {
    if (heal(mon, mon.maxHp / 16, "ingrain") > 0) say("$who absorbed nutrients with its roots!")
  }
  if (mon.fainted) return
  when (mon.ability) {
    Ability.RAIN_DISH ->
        if (weather() == Weather.RAIN && mon.currentHp < mon.maxHp) {
          emit(BattleEvent.AbilityShown(mon.entityId, "RAIN DISH"))
          heal(mon, mon.maxHp / 16, "ability")
          say("$who's RAIN DISH restored its HP a little!", mon.entityId)
        }
    Ability.SHED_SKIN ->
        if (mon.hasStatus && rng.pick(3) == 0) {
          emit(BattleEvent.AbilityShown(mon.entityId, "SHED SKIN"))
          cureStatus(mon, "$who's SHED SKIN cured its ${statusName(mon.status)} problem!")
        }
    Ability.SPEED_BOOST ->
        if (v.actedSinceSwitchIn && mon.stage(BattleStat.SPEED) < StatStages.MAX) {
          emit(BattleEvent.AbilityShown(mon.entityId, "SPEED BOOST"))
          raiseStat(mon, mon, BattleStat.SPEED, 1, announce = true, cause = "SPEED BOOST")
        }
    else -> Unit
  }
  endTurnItem(mon)
  if (mon.fainted) return
  if (v.seededBy != 0L) {
    val drain = (mon.maxHp / 8).coerceAtLeast(1)
    val receiver = allRefs().mapNotNull { live(it) }.firstOrNull { it.entityId == v.seededBy }
    say("$who's health is sapped by LEECH SEED!", mon.entityId)
    hurt(mon, drain, "leechseed")
    if (receiver != null) {
      if (mon.ability == Ability.LIQUID_OOZE) {
        hurt(receiver, drain, "ooze")
        say("${name(receiver)} sucked up the liquid ooze!")
      } else heal(receiver, drain, "leechseed")
    }
  }
  if (mon.fainted) return
  if (mon.poisoned) {
    val damage =
        if (mon.badlyPoisoned) {
          v.toxicCounter = (v.toxicCounter + 1).coerceAtMost(15)
          mon.maxHp * v.toxicCounter / 16
        } else mon.maxHp / 8
    say("$who is hurt by poison!", mon.entityId)
    hurt(mon, damage, "poison")
  } else if (mon.burned) {
    say("$who is hurt by its burn!", mon.entityId)
    hurt(mon, mon.maxHp / 8, "burn")
  }
  if (mon.fainted) return
  if (v.nightmare) {
    if (mon.asleep) {
      say("$who is locked in a NIGHTMARE!", mon.entityId)
      hurt(mon, mon.maxHp / 4, "nightmare")
    } else v.nightmare = false
  }
  if (mon.fainted) return
  if (v.cursed) {
    say("$who is afflicted by the CURSE!", mon.entityId)
    hurt(mon, mon.maxHp / 4, "curse")
  }
  if (mon.fainted) return
  if (v.trapTurns > 0) {
    v.trapTurns -= 1
    val trapName = move(v.trapMove)?.name ?: "WRAP"
    if (v.trapTurns == 0) {
      say("$who was freed from $trapName!", mon.entityId)
    } else {
      say("$who is hurt by $trapName!", mon.entityId)
      hurt(mon, mon.maxHp / 16, "trap")
    }
  }
  if (mon.fainted) return
  if (v.uproarTurns > 0 && --v.uproarTurns == 0) say("$who calmed down.", mon.entityId)
  if (v.rampageTurns > 0 && --v.rampageTurns == 0) {
    v.rampageMove = 0
    tryConfuse(mon, null, announceFail = false)
  }
  if (v.disableTurns > 0 && --v.disableTurns == 0) {
    v.disabledMove = 0
    say("$who is no longer disabled!", mon.entityId)
  }
  if (v.encoreTurns > 0 && --v.encoreTurns == 0) {
    v.encoreMove = 0
    say("$who's ENCORE ended!", mon.entityId)
  }
  if (v.lockOnTurns > 0 && --v.lockOnTurns == 0) v.lockOnTarget = 0
  if (v.tauntTurns > 0) v.tauntTurns -= 1
  if (v.yawnTurns > 0 && --v.yawnTurns == 0) {
    trySetStatus(mon, StatusCondition.SLEEP_MASK, null, announceFail = false)
  }
}
