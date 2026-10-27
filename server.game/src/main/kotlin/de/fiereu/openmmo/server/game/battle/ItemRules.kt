package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.enums.PokemonStat

/*
 * Held item triggers, following src/battle_util.c ItemBattleEffects: the hp berries at half hp,
 * the pinch berries at a quarter, the status berries the moment a status lands, Leftovers at the
 * end of the turn, White Herb after a stat drop, Mental Herb against attraction.
 */

private fun BattleCtx.consume(mon: BattleMonState, effect: HoldEffect) {
  emit(BattleEvent.ItemActivated(mon.entityId, mon.heldItem, consumed = true))
  mon.heldItem = 0
}

private fun itemName(effect: HoldEffect): String = effect.item.name.uppercase()

internal fun BattleCtx.endTurnItem(mon: BattleMonState) {
  val effect = heldEffect(mon) ?: return
  if (effect.name == "LEFTOVERS" && mon.currentHp < mon.maxHp && !mon.fainted) {
    emit(BattleEvent.ItemActivated(mon.entityId, mon.heldItem, consumed = false))
    heal(mon, (mon.maxHp / 16).coerceAtLeast(1), "leftovers")
    say("${name(mon)} restored a little HP using its ${itemName(effect)}!", mon.entityId)
  }
  pinchBerry(mon)
}

/** The flavor each nature dislikes is the one that matches the stat it lowers. */
private fun dislikes(mon: BattleMonState, flavor: String): Boolean {
  val nature = mon.source.nature
  if (nature.increasedStat == nature.decreasedStat) return false
  val disliked =
      when (nature.decreasedStat) {
        PokemonStat.ATTACK -> "SPICY"
        PokemonStat.DEFENSE -> "SOUR"
        PokemonStat.SPEED -> "SWEET"
        PokemonStat.SP_ATTACK -> "DRY"
        PokemonStat.SP_DEFENSE -> "BITTER"
        else -> null
      }
  return disliked == flavor
}

/** Hp and pinch berries, checked whenever the holder's hp may have dropped. */
internal fun BattleCtx.pinchBerry(mon: BattleMonState) {
  if (mon.fainted) return
  val effect = heldEffect(mon) ?: return
  val who = name(mon)
  when {
    effect.name == "RESTORE_HP" && mon.currentHp <= mon.maxHp / 2 -> {
      consume(mon, effect)
      heal(mon, effect.param, "berry")
      say("$who's ${itemName(effect)} restored health!", mon.entityId)
    }
    effect.name.startsWith("CONFUSE_") && mon.currentHp <= mon.maxHp / 2 -> {
      consume(mon, effect)
      heal(mon, mon.maxHp / effect.param.coerceAtLeast(1), "berry")
      say("$who's ${itemName(effect)} restored health!", mon.entityId)
      if (dislikes(mon, effect.name.removePrefix("CONFUSE_"))) {
        say("For $who, the ${itemName(effect)} tasted bad!", mon.entityId)
        tryConfuse(mon, null, announceFail = false)
      }
    }
    effect.name in PINCH_STATS && mon.currentHp <= mon.maxHp / effect.param.coerceAtLeast(1) -> {
      val stat = PINCH_STATS.getValue(effect.name)
      if (mon.stage(stat) >= StatStages.MAX) return
      consume(mon, effect)
      raiseStat(mon, mon, stat, 1, announce = true, cause = itemName(effect))
    }
    effect.name == "RANDOM_STAT_UP" && mon.currentHp <= mon.maxHp / effect.param -> {
      val open = PINCH_STATS.values.filter { mon.stage(it) < StatStages.MAX }
      if (open.isEmpty()) return
      consume(mon, effect)
      raiseStat(mon, mon, open[rng.pick(open.size)], 2, announce = true, cause = itemName(effect))
    }
    effect.name == "CRITICAL_UP" &&
        mon.currentHp <= mon.maxHp / effect.param &&
        !mon.volatile.focusEnergy -> {
      consume(mon, effect)
      mon.volatile.focusEnergy = true
      say("$who used ${itemName(effect)} to get pumped!", mon.entityId)
    }
  }
}

private val PINCH_STATS =
    mapOf(
        "ATTACK_UP" to BattleStat.ATTACK,
        "DEFENSE_UP" to BattleStat.DEFENSE,
        "SPEED_UP" to BattleStat.SPEED,
        "SP_ATTACK_UP" to BattleStat.SP_ATTACK,
        "SP_DEFENSE_UP" to BattleStat.SP_DEFENSE,
    )

/** The status berries and Lum Berry act the moment a status lands. */
internal fun BattleCtx.statusBerry(mon: BattleMonState) {
  val effect = heldEffect(mon) ?: return
  val cures =
      when (effect.name) {
        "CURE_PAR" -> mon.paralyzed
        "CURE_SLP" -> mon.asleep
        "CURE_PSN" -> mon.poisoned
        "CURE_BRN" -> mon.burned
        "CURE_FRZ" -> mon.frozen
        "CURE_STATUS" -> mon.hasStatus
        else -> false
      }
  if (!cures) return
  val was = statusName(mon.status)
  consume(mon, effect)
  cureStatus(mon, "${name(mon)}'s ${itemName(effect)} cured its $was problem!")
}

internal fun BattleCtx.confusionBerry(mon: BattleMonState) {
  val effect = heldEffect(mon) ?: return
  if (effect.name != "CURE_CONFUSION" && effect.name != "CURE_STATUS") return
  if (!mon.confused) return
  consume(mon, effect)
  mon.volatile.confusionTurns = 0
  emit(BattleEvent.VolatileChanged(mon.entityId, "confused", false))
  say("${name(mon)}'s ${itemName(effect)} snapped it out of confusion!", mon.entityId)
}

internal fun BattleCtx.mentalHerb(mon: BattleMonState) {
  val effect = heldEffect(mon) ?: return
  if (effect.name != "CURE_ATTRACT" || mon.volatile.attractedTo == 0L) return
  consume(mon, effect)
  mon.volatile.attractedTo = 0
  say("${name(mon)}'s ${itemName(effect)} cured its infatuation!", mon.entityId)
}

/** White Herb puts every lowered stage back to 0. */
internal fun BattleCtx.whiteHerb(mon: BattleMonState) {
  val effect = heldEffect(mon) ?: return
  if (effect.name != "RESTORE_STATS") return
  val lowered = BattleStat.entries.filter { mon.stage(it) < 0 }
  if (lowered.isEmpty()) return
  consume(mon, effect)
  lowered.forEach { mon.setStage(it, 0) }
  say("${name(mon)}'s ${itemName(effect)} restored its status!", mon.entityId)
}

/** Leppa Berry refills a move whose pp just ran out. */
internal fun BattleCtx.leppaBerry(mon: BattleMonState, slot: Int) {
  val effect = heldEffect(mon) ?: return
  if (effect.name != "RESTORE_PP" || mon.moves.getOrNull(slot)?.pp?.toInt() != 0) return
  val max = move(mon.moves[slot].id.toInt())?.pp ?: return
  consume(mon, effect)
  mon.moves[slot].pp = minOf(max, effect.param).toByte()
  say("${name(mon)}'s ${itemName(effect)} restored ${move(mon.moves[slot].id.toInt())?.name}'s PP!")
}

/** Shell Bell: an eighth of the damage dealt comes back as hp. */
internal fun BattleCtx.shellBell(mon: BattleMonState, dealt: Int) {
  val effect = heldEffect(mon) ?: return
  if (effect.name != "SHELL_BELL" || dealt <= 0 || mon.fainted) return
  if (heal(mon, (dealt / effect.param).coerceAtLeast(1), "shellbell") > 0) {
    emit(BattleEvent.ItemActivated(mon.entityId, mon.heldItem, consumed = false))
    say("${name(mon)} restored a little HP using its ${itemName(effect)}!", mon.entityId)
  }
}

/** Focus Band: a param percent chance to hang on with 1 hp. */
internal fun BattleCtx.focusBandSaves(mon: BattleMonState): Boolean {
  val effect = heldEffect(mon) ?: return false
  return effect.name == "FOCUS_BAND" && rng.percent(effect.param)
}

internal fun BattleCtx.hasHoldEffect(mon: BattleMonState, name: String): Boolean =
    heldEffect(mon)?.name == name
