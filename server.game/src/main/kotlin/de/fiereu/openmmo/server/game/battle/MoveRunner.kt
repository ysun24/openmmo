package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.common.enums.MoveEffect
import de.fiereu.openmmo.common.enums.MoveFlag
import de.fiereu.openmmo.common.enums.MoveTarget
import de.fiereu.openmmo.common.enums.PokemonType
import de.fiereu.openmmo.moves.MoveDef
import de.fiereu.openmmo.typechart.TypeChart

private const val FLAME_WHEEL = 172
private const val SACRED_FIRE = 221
private const val SNORE = 173
private const val SLEEP_TALK = 214
private const val FUTURE_SIGHT = 248
private const val DOOM_DESIRE = 353

/** The sound moves Soundproof blocks (src/battle_util.c sSoundMovesTable). */
internal val SOUND_MOVES = setOf(45, 46, 47, 48, 103, 173, 195, 215, 253, 304, 319, 320)

/** Moves that only work in the order they were picked, a planned action of one battler. */
private data class Planned(val choice: BattleChoice, val ref: BattlerRef, val mon: BattleMonState)

/** Runs one turn of choices through the Gen 3 action order and the move effects. */
internal class MoveRunner(private val ctx: BattleCtx) {

  private val battle = ctx.battle
  private val rng = ctx.rng

  fun runTurn(choices: List<BattleChoice>) {
    ctx.field.turnCounter += 1
    for (ref in ctx.allRefs()) {
      ctx.mon(ref)?.volatile?.let { v ->
        v.flinched = false
        v.protectedThisTurn = false
        v.enduring = false
        v.helpingHand = false
        v.magicCoat = false
        v.snatching = false
        v.followMe = false
        v.movedThisTurn = false
        v.physicalDamageTaken = 0
        v.specialDamageTaken = 0
        v.damagedBy = 0
      }
    }
    val planned =
        choices.mapNotNull { c ->
          val ref = BattlerRef(c.side, c.pos)
          ctx.mon(ref)?.let { Planned(lockedChoice(c, it), ref, it) }
        }
    for (p in order(planned)) {
      if (ctx.sideFainted(0) || ctx.sideFainted(1)) break
      // The monster that picked this action may have fainted or been forced out.
      val current = ctx.mon(p.ref)
      if (current !== p.mon || current.fainted) continue
      when (val c = p.choice) {
        is BattleChoice.Switch -> switchTo(p.ref, c.partyIndex)
        is BattleChoice.UseMove -> useMove(p.ref, current, c.moveId, c.target, called = false)
        is BattleChoice.Pass -> Unit
      }
      if (ctx.events.any { it is BattleEvent.Fled }) return
    }
    if (ctx.sideFainted(0) || ctx.sideFainted(1)) return
    ctx.endOfTurn()
  }

  /** A charging, recharging, rampaging or Encored monster's pick is overridden. */
  private fun lockedChoice(c: BattleChoice, mon: BattleMonState): BattleChoice {
    if (c !is BattleChoice.UseMove) return c
    val v = mon.volatile
    return when {
      v.chargingMove != 0 -> c.copy(moveId = v.chargingMove)
      v.rampageMove != 0 -> c.copy(moveId = v.rampageMove)
      v.uproarTurns > 0 && v.lastMove != 0 -> c.copy(moveId = v.lastMove)
      v.rolloutTurns > 0 && v.lastMove != 0 -> c.copy(moveId = v.lastMove)
      v.encoreMove != 0 && mon.moves.any { it.id.toInt() == v.encoreMove && it.pp > 0 } ->
          c.copy(moveId = v.encoreMove)
      else -> c
    }
  }

  /** Switches and items first, then moves by priority, Quick Claw, speed and a coin flip. */
  private fun order(planned: List<Planned>): List<Planned> {
    val quick =
        planned.associateWith { p ->
          val e = ctx.heldEffect(p.mon)
          e?.name == "QUICK_CLAW" && rng.percent(e.param)
        }
    val tiebreak = planned.associateWith { rng.random16() }
    return planned.sortedWith(
        compareByDescending<Planned> { if (it.choice is BattleChoice.UseMove) 0 else 1 }
            .thenByDescending { p ->
              (p.choice as? BattleChoice.UseMove)?.let { ctx.move(it.moveId)?.priority } ?: 0
            }
            .thenByDescending { if (quick[it] == true) 1 else 0 }
            .thenByDescending { ctx.speedOf(it.mon) }
            .thenBy { tiebreak[it] })
  }

  // --- switching -------------------------------------------------------------------------------

  fun switchTo(ref: BattlerRef, slot: Int) {
    val side = ctx.sideState(ref.side)
    val party = ctx.party(ref.side)
    val incoming = party.getOrNull(slot) ?: return
    if (incoming.fainted) return
    val oldSlot = side.actives[ref.pos]
    if (oldSlot == slot) return
    party.getOrNull(oldSlot)?.let { out ->
      if (out.ability == Ability.NATURAL_CURE && out.hasStatus && !out.fainted) {
        ctx.cureStatus(out, null)
      }
      out.resetOnSwitchOut()
      // Whoever this monster had trapped or seeded is free once it leaves.
      for (r in ctx.allRefs()) {
        ctx.mon(r)?.volatile?.let { v ->
          if (v.trappedBy == out.entityId) {
            v.trapTurns = 0
            v.cantEscape = false
          }
          if (v.attractedTo == out.entityId) v.attractedTo = 0
        }
      }
    }
    side.actives[ref.pos] = slot
    if (ref.side == 0) {
      battle.participants += slot
    }
    ctx.emit(BattleEvent.Switched(ref.side, ref.pos, oldSlot, slot, incoming.entityId))
    markFought()
    ctx.onEntry(ref)
  }

  /** Everyone currently out on one side has fought everyone out on the other. */
  private fun markFought() {
    val mine = battle.activeMons().filterNotNull().filter { !it.fainted }
    for (foe in battle.opponentMons().filterNotNull()) {
      foe.foughtBy += mine.map { it.entityId }
    }
  }

  // --- moves -----------------------------------------------------------------------------------

  /** The move a monster with no pp left in any move uses. */
  private fun struggleIfOut(mon: BattleMonState, moveId: Int): Int {
    val usable = mon.moves.filter { it.id.toInt() != 0 && it.pp > 0 }
    if (usable.isEmpty() && mon.moves.any { it.id.toInt() != 0 }) return STRUGGLE_ID
    return moveId
  }

  fun useMove(
      userRef: BattlerRef,
      user: BattleMonState,
      requestedMoveId: Int,
      chosenTarget: BattlerRef?,
      called: Boolean,
  ) {
    val v = user.volatile
    val who = ctx.name(user)
    markFought()
    var moveId = if (called) requestedMoveId else struggleIfOut(user, requestedMoveId)
    var move = ctx.move(moveId) ?: ctx.move(TACKLE_ID) ?: return

    if (!called) {
      // GRUDGE lasts until the monster moves again.
      v.grudge = false
      // --- attack canceler: the reasons a monster loses its turn, in the Gen 3 order. ---
      if (v.mustRecharge) {
        v.mustRecharge = false
        ctx.say("$who must recharge!", user.entityId)
        return
      }
      if (user.asleep) {
        val step = if (user.ability == Ability.EARLY_BIRD) 2 else 1
        val left = ((user.status and StatusCondition.SLEEP_MASK) - step).coerceAtLeast(0)
        user.status = (user.status and StatusCondition.SLEEP_MASK.inv()) or left
        if (left == 0) {
          ctx.emit(BattleEvent.StatusChanged(user.entityId, 0, null))
          ctx.say("$who woke up!", user.entityId)
          v.nightmare = false
        } else {
          ctx.emit(BattleEvent.StatusChanged(user.entityId, user.status, "slp"))
          if (move.id != SNORE && move.id != SLEEP_TALK) {
            ctx.say("$who is fast asleep.", user.entityId)
            v.rampageMove = 0
            v.chargingMove = 0
            v.semiInvulnerable = null
            return
          }
        }
      } else if (move.id == SNORE || move.id == SLEEP_TALK) {
        deductPp(user, move, userRef)
        ctx.emit(
            BattleEvent.MoveUsed(
                user.entityId, move.id.toShort(), slotOf(user, move), ppOf(user, move)))
        ctx.emit(BattleEvent.MoveFailed(user.entityId, move.id.toShort()))
        return
      }
      if (user.frozen) {
        if (rng.pick(5) == 0 || move.id == FLAME_WHEEL || move.id == SACRED_FIRE) {
          ctx.cureStatus(user, "$who thawed out!")
        } else {
          ctx.say("$who is frozen solid!", user.entityId)
          return
        }
      }
      if (user.ability == Ability.TRUANT && v.truantSkip) {
        v.truantSkip = false
        ctx.emit(BattleEvent.AbilityShown(user.entityId, "TRUANT"))
        ctx.say("$who is loafing around!", user.entityId)
        return
      }
      if (v.flinched) {
        v.flinched = false
        ctx.say("$who flinched!", user.entityId)
        return
      }
      if (v.disabledMove == move.id && move.id != STRUGGLE_ID) {
        ctx.say("$who's ${move.name} is disabled!", user.entityId)
        return
      }
      if (v.tauntTurns > 0 && move.power == 0 && move.effect != MoveEffect.HIT) {
        ctx.say("$who can't use ${move.name} after the TAUNT!", user.entityId)
        return
      }
      // IMPRISON: a foe that imprisons knows this move (CANCELLER_IMPRISONED).
      if (move.id != STRUGGLE_ID && imprisonedBy(userRef, move.id)) {
        ctx.say("$who can't use the sealed ${move.name}!", user.entityId)
        return
      }
      if (v.confusionTurns > 0) {
        v.confusionTurns -= 1
        if (v.confusionTurns == 0) {
          ctx.emit(BattleEvent.VolatileChanged(user.entityId, "confused", false))
          ctx.say("$who snapped out of confusion!", user.entityId)
        } else {
          ctx.say("$who is confused!", user.entityId)
          if (rng.coinFlip()) {
            ctx.say("It hurt itself in its confusion!", user.entityId)
            ctx.hurt(user, confusionDamage(user), "confusion")
            v.rampageMove = 0
            v.chargingMove = 0
            v.semiInvulnerable = null
            return
          }
        }
      }
      if (user.paralyzed && rng.pick(4) == 0) {
        ctx.say("$who is paralyzed! It can't move!", user.entityId)
        v.rampageMove = 0
        v.chargingMove = 0
        v.semiInvulnerable = null
        return
      }
      if (v.attractedTo != 0L) {
        val lover =
            ctx.allRefs().mapNotNull { ctx.live(it) }.firstOrNull { it.entityId == v.attractedTo }
        if (lover == null) v.attractedTo = 0
        else {
          ctx.say("$who is in love with ${ctx.name(lover)}!", user.entityId)
          if (rng.coinFlip()) {
            ctx.say("$who is immobilized by love!", user.entityId)
            return
          }
        }
      }
      // IsMonDisobedient: a traded monster above the badge level may not listen.
      if (disobeys(userRef, user, move, chosenTarget)) return
      // Choice Band locks the holder into its first move.
      if (ctx.heldEffect(user)?.name == "CHOICE_BAND") {
        if (v.choiceMove != 0 &&
            v.choiceMove != move.id &&
            user.moves.any { it.id.toInt() == v.choiceMove && it.pp > 0 }) {
          moveId = v.choiceMove
          move = ctx.move(moveId) ?: move
        }
        v.choiceMove = move.id
      }
    }

    // A two-turn move spends its first turn charging (Solar Beam fires at once in the sun).
    val charging = chargeMessage(move, user)
    if (!called &&
        charging != null &&
        v.chargingMove != move.id &&
        !(move.effect == MoveEffect.SOLAR_BEAM && ctx.weather() == Weather.SUN)) {
      deductPp(user, move, userRef)
      ctx.emit(
          BattleEvent.MoveUsed(
              user.entityId, move.id.toShort(), slotOf(user, move), ppOf(user, move)))
      ctx.emit(BattleEvent.Animation(user.entityId, move.id, 0))
      ctx.say(charging, user.entityId)
      v.chargingMove = move.id
      v.semiInvulnerable =
          when (move.effect) {
            MoveEffect.SEMI_INVULNERABLE ->
                when (move.id) {
                  91 -> "DIG"
                  291 -> "DIVE"
                  else -> "FLY"
                }
            else -> null
          }
      if (move.effect == MoveEffect.SKULL_BASH) {
        ctx.raiseStat(user, user, BattleStat.DEFENSE, 1, announce = true)
      }
      v.movedThisTurn = true
      v.actedSinceSwitchIn = true
      return
    }
    val wasCharging = v.chargingMove == move.id
    v.chargingMove = 0
    v.semiInvulnerable = null

    if (!called && !wasCharging) deductPp(user, move, userRef)
    // Fake Out only works on the first turn out.
    val fakeOutFails = move.effect == MoveEffect.FAKE_OUT && v.actedSinceSwitchIn
    v.movedThisTurn = true
    v.actedSinceSwitchIn = true
    v.lastMove = move.id
    if (move.effect != MoveEffect.FURY_CUTTER) v.furyCutter = 0
    if (move.effect != MoveEffect.PROTECT) v.protectChain = 0
    if (move.effect != MoveEffect.RAGE) v.raging = false

    ctx.emit(
        BattleEvent.MoveUsed(
            user.entityId, move.id.toShort(), slotOf(user, move), ppOf(user, move)))
    if (fakeOutFails) {
      ctx.emit(BattleEvent.MoveFailed(user.entityId, move.id.toShort()))
      return
    }
    val effects = MoveEffects(ctx, this)
    // SNATCH: a waiting snatcher takes a self-targeting status move (Swords Dance, Recover...).
    if (!called && move.hasFlag(MoveFlag.SNATCH_AFFECTED)) {
      val thief =
          ctx.allRefs().firstOrNull { r ->
            r != userRef && ctx.live(r)?.volatile?.snatching == true
          }
      val thiefMon = thief?.let { ctx.live(it) }
      if (thief != null && thiefMon != null) {
        thiefMon.volatile.snatching = false
        ctx.say("${ctx.name(thiefMon)} snatched ${ctx.name(user)}'s move!", thiefMon.entityId)
        useMove(thief, thiefMon, move.id, thief, called = true)
        return
      }
    }
    // MAGIC COAT: a status move aimed at a shrouded monster is bounced back at its user.
    if (!called && move.hasFlag(MoveFlag.MAGIC_COAT_AFFECTED) && move.power == 0) {
      val coated =
          targetsFor(userRef, user, move, chosenTarget).firstOrNull { r ->
            r.side != userRef.side && ctx.live(r)?.volatile?.magicCoat == true
          }
      val coatMon = coated?.let { ctx.live(it) }
      if (coated != null && coatMon != null) {
        coatMon.volatile.magicCoat = false
        ctx.say("${ctx.name(user)}'s ${move.name} was bounced back by MAGIC COAT!", user.entityId)
        useMove(coated, coatMon, move.id, userRef, called = true)
        return
      }
    }
    if (effects.runSpecial(userRef, user, move, chosenTarget)) return
    val targets = targetsFor(userRef, user, move, chosenTarget)
    if (targets.isEmpty()) {
      ctx.emit(BattleEvent.MoveFailed(user.entityId, move.id.toShort()))
      return
    }
    val spread = move.target == MoveTarget.BOTH && targets.size > 1
    var anyHit = false
    for (target in targets) {
      if (user.fainted) break
      val targetMon = ctx.mon(target) ?: continue
      if (targetMon.fainted) continue
      if (hitOne(userRef, user, target, targetMon, move, spread, effects)) anyHit = true
    }
    if (move.effect == MoveEffect.EXPLOSION && !user.fainted) ctx.setHp(user, 0, "explosion")
    if (anyHit) effects.afterAllHits(userRef, user, move, targets)
    else if (move.effect == MoveEffect.RECOIL_IF_MISS) {
      ctx.say("$who kept going and crashed!", user.entityId)
      ctx.hurt(user, user.maxHp / 2, "crash")
    }
    if (user.volatile.charged && move.type == PokemonType.ELECTRIC) user.volatile.charged = false
  }

  /**
   * battle_util.c IsMonDisobedient for the player's own side (never in a player-vs-player duel, as
   * in a link battle). Only monsters with another trainer as OT ([BattleInstance.trainerName]) and
   * a level above [BattleInstance.obedienceLevel] roll. True when the turn is lost or replaced.
   */
  internal fun disobeys(
      userRef: BattlerRef,
      user: BattleMonState,
      move: MoveDef,
      chosenTarget: BattlerRef?,
  ): Boolean {
    if (battle.pvp != null || userRef.side != 0) return false
    val ownName = battle.trainerName
    if (ownName.isEmpty() || user.source.ot.isEmpty() || user.source.ot.equals(ownName, true)) {
      return false
    }
    val level = user.level
    val obedience = battle.obedienceLevel
    if (obedience >= 100 || level <= obedience) return false
    if ((level + obedience) * rng.pick(256) shr 8 < obedience) return false
    val who = ctx.name(user)
    if (user.asleep && (move.id == SNORE || move.id == SLEEP_TALK)) {
      ctx.say("$who ignored orders while asleep!", user.entityId)
      return true
    }
    if ((level + obedience) * rng.pick(256) shr 8 < obedience) {
      // Another move it can use, picked at random; with none, it loafs around.
      val current = slotOf(user, move)
      val usable =
          user.moves.indices.filter { i ->
            val m = user.moves[i]
            i != current &&
                m.id.toInt() != 0 &&
                m.pp > 0 &&
                m.id.toInt() != user.volatile.disabledMove &&
                !(user.volatile.tauntTurns > 0 && (ctx.move(m.id.toInt())?.power ?: 0) == 0)
          }
      if (usable.isEmpty()) {
        ctx.say(loafText(who), user.entityId)
        return true
      }
      ctx.say("$who ignored orders!", user.entityId)
      val random = user.moves[usable[rng.pick(usable.size)]].id.toInt()
      useMove(userRef, user, random, chosenTarget, called = true)
      return true
    }
    val gap = level - obedience
    val roll = rng.pick(256)
    val awake = ctx.allRefs().none { (ctx.live(it)?.volatile?.uproarTurns ?: 0) > 0 }
    if (roll < gap &&
        !user.hasStatus &&
        user.ability != Ability.VITAL_SPIRIT &&
        user.ability != Ability.INSOMNIA &&
        awake) {
      ctx.say("$who began to nap!", user.entityId)
      user.status = rng.range(2, 5)
      ctx.emit(BattleEvent.StatusChanged(user.entityId, user.status, "slp"))
      return true
    }
    if (roll - gap < gap) {
      ctx.say("$who won't obey!", user.entityId)
      ctx.say("It hurt itself in its confusion!", user.entityId)
      ctx.hurt(user, confusionDamage(user), "confusion")
      return true
    }
    ctx.say(loafText(who), user.entityId)
    return true
  }

  /** gInobedientStringIds, picked at random. */
  private fun loafText(who: String): String =
      when (rng.pick(4)) {
        0 -> "$who is loafing around!"
        1 -> "$who won't obey!"
        2 -> "$who turned away!"
        else -> "$who pretended not to notice!"
      }

  /** Gen 3 confusion damage: a 40 power typeless physical hit on itself, no crit, no random. */
  private fun confusionDamage(user: BattleMonState): Int {
    val atk = user.effective(BattleStat.ATTACK)
    val def = user.effective(BattleStat.DEFENSE).coerceAtLeast(1)
    var dmg = (2 * user.level / 5 + 2) * 40 * atk / def / 50
    if (user.burned) dmg /= 2
    return dmg + 2
  }

  private fun chargeMessage(move: MoveDef, user: BattleMonState): String? {
    val who = ctx.name(user)
    return when (move.effect) {
      MoveEffect.RAZOR_WIND -> "$who whipped up a whirlwind!"
      MoveEffect.SOLAR_BEAM -> "$who took in sunlight!"
      MoveEffect.SKULL_BASH -> "$who lowered its head!"
      MoveEffect.SKY_ATTACK -> "$who is glowing!"
      MoveEffect.SEMI_INVULNERABLE ->
          when (move.id) {
            91 -> "$who dug a hole!"
            291 -> "$who hid underwater!"
            340 -> "$who sprang up!"
            else -> "$who flew up high!"
          }
      else -> null
    }
  }

  fun slotOf(user: BattleMonState, move: MoveDef): Int =
      user.moves.indexOfFirst { it.id.toInt() == move.id }.coerceAtLeast(0)

  fun ppOf(user: BattleMonState, move: MoveDef): Int =
      user.moves.firstOrNull { it.id.toInt() == move.id }?.pp?.toInt() ?: 0

  /** Pressure on any foe costs one more pp. */
  private fun deductPp(user: BattleMonState, move: MoveDef, userRef: BattlerRef) {
    val slot = user.moves.indexOfFirst { it.id.toInt() == move.id }
    if (slot < 0) return
    var cost = 1
    if (ctx.foesOf(userRef).any { ctx.live(it)?.ability == Ability.PRESSURE }) cost += 1
    user.moves[slot].pp = (user.moves[slot].pp - cost).coerceAtLeast(0).toByte()
    ctx.leppaBerry(user, slot)
  }

  // --- targets ---------------------------------------------------------------------------------

  /** IsImprisoned: a live foe of [userRef] imprisons and knows [moveId]. */
  fun imprisonedBy(userRef: BattlerRef, moveId: Int): Boolean =
      ctx.foesOf(userRef).any { r ->
        val f = ctx.live(r) ?: return@any false
        f.volatile.imprisoning && f.moves.any { it.id.toInt() == moveId }
      }

  fun targetsFor(
      userRef: BattlerRef,
      user: BattleMonState,
      move: MoveDef,
      chosen: BattlerRef?,
  ): List<BattlerRef> {
    val liveFoes = ctx.foesOf(userRef).filter { ctx.live(it) != null }
    return when (move.target) {
      MoveTarget.USER,
      MoveTarget.OPPONENTS_FIELD -> listOf(userRef)
      MoveTarget.DEPENDS -> liveFoes.take(1)
      MoveTarget.BOTH -> liveFoes
      MoveTarget.FOES_AND_ALLY ->
          liveFoes + listOfNotNull(ctx.allyOf(userRef)?.takeIf { ctx.live(it) != null })
      MoveTarget.RANDOM ->
          if (liveFoes.isEmpty()) emptyList() else listOf(liveFoes[rng.pick(liveFoes.size)])
      MoveTarget.USER_OR_SELECTED -> listOf(userRef)
      MoveTarget.SELECTED -> {
        // FOLLOW ME: the foe side's center of attention takes every single-target move.
        liveFoes
            .firstOrNull { ctx.live(it)?.volatile?.followMe == true }
            ?.let {
              return listOf(it)
            }
        if (chosen != null && chosen != userRef && ctx.live(chosen) != null) listOf(chosen)
        else if (liveFoes.isEmpty()) emptyList()
        else if (chosen != null && chosen.side == userRef.foeSide && ctx.live(chosen) == null)
            liveFoes.take(1) // the picked foe fainted, the move moves on to the other one
        else listOf(liveFoes[rng.pick(liveFoes.size)])
      }
    }
  }

  // --- one target --------------------------------------------------------------------------------

  /** True when the move connected with [target]. */
  private fun hitOne(
      userRef: BattlerRef,
      user: BattleMonState,
      targetRef: BattlerRef,
      target: BattleMonState,
      move: MoveDef,
      spread: Boolean,
      effects: MoveEffects,
  ): Boolean {
    val selfTargeted = targetRef == userRef
    val tv = target.volatile
    if (!selfTargeted) {
      if (tv.protectedThisTurn && move.hasFlag(MoveFlag.PROTECT_AFFECTED)) {
        ctx.say("${ctx.name(target)} protected itself!", target.entityId)
        ctx.emit(BattleEvent.MoveFailed(user.entityId, move.id.toShort(), target.entityId))
        return false
      }
      if (move.id in SOUND_MOVES && target.ability == Ability.SOUNDPROOF) {
        ctx.emit(BattleEvent.AbilityShown(target.entityId, "SOUNDPROOF"))
        ctx.say("${ctx.name(target)}'s SOUNDPROOF blocks ${move.name}!", target.entityId)
        return false
      }
      if (!accuracyCheck(user, target, move)) {
        ctx.emit(BattleEvent.MoveMissed(user.entityId, move.id.toShort(), target.entityId))
        return false
      }
    }
    return effects.apply(userRef, user, targetRef, target, move, spread)
  }

  /** Cmd_accuracycheck, including semi-invulnerable turns, Lock-On, Thunder in the weather. */
  fun accuracyCheck(user: BattleMonState, target: BattleMonState, move: MoveDef): Boolean {
    val tv = target.volatile
    val inv = tv.semiInvulnerable
    if (inv != null) {
      val reaches =
          when (inv) {
            "FLY" ->
                move.effect in
                    setOf(
                        MoveEffect.GUST,
                        MoveEffect.TWISTER,
                        MoveEffect.THUNDER,
                        MoveEffect.SKY_UPPERCUT)
            "DIG" -> move.effect in setOf(MoveEffect.EARTHQUAKE, MoveEffect.MAGNITUDE)
            "DIVE" -> move.id == 57 || move.id == 250
            else -> false
          }
      if (!reaches && user.volatile.lockOnTarget != target.entityId) return false
    }
    if (user.volatile.lockOnTarget == target.entityId) return true
    if (move.effect == MoveEffect.ALWAYS_HIT || move.effect == MoveEffect.VITAL_THROW) return true
    if (move.accuracy == 0) return true
    var accuracy = move.accuracy
    val w = ctx.weather()
    if (move.effect == MoveEffect.THUNDER) {
      if (w == Weather.RAIN) return true
      if (w == Weather.SUN) accuracy = 50
    }
    if (move.effect == MoveEffect.OHKO) {
      if (target.level > user.level) return false
      accuracy = move.accuracy + (user.level - target.level)
      return rng.accuracyRoll() <= accuracy
    }
    val evasionStage =
        if (tv.foresight) target.stage(BattleStat.EVASION).coerceAtMost(0)
        else target.stage(BattleStat.EVASION)
    val stage = user.stage(BattleStat.ACCURACY) - evasionStage
    var calc = StatStages.scaleAccuracy(accuracy, stage)
    if (user.ability == Ability.COMPOUND_EYES) calc = calc * 130 / 100
    if (w == Weather.SAND && target.ability == Ability.SAND_VEIL) calc = calc * 80 / 100
    if (user.ability == Ability.HUSTLE && MoveCategory.isPhysical(move.type)) calc = calc * 80 / 100
    ctx.heldEffect(target)?.let { e ->
      if (e.name == "EVASION_UP") calc = calc * (100 - e.param) / 100
    }
    return rng.accuracyRoll() <= calc
  }

  // --- damage ----------------------------------------------------------------------------------

  /** The damage result for one target, before it is applied. */
  data class Hit(val damage: Int, val crit: Boolean, val effectiveness: Int)

  /** Gen 3 critical stage: high-crit moves +1, Scope Lens +1, Focus Energy +2, species items +2. */
  private fun critStage(user: BattleMonState, move: MoveDef): Int {
    var stage = 0
    if (move.effect in
        setOf(
            MoveEffect.HIGH_CRITICAL,
            MoveEffect.SKY_ATTACK,
            MoveEffect.BLAZE_KICK,
            MoveEffect.POISON_TAIL))
        stage += 1
    if (user.volatile.focusEnergy) stage += 2
    when (ctx.heldEffect(user)?.name) {
      "SCOPE_LENS" -> stage += 1
      "LUCKY_PUNCH" -> if (user.species.id == 113) stage += 2
      "STICK" -> if (user.species.id == 83) stage += 2
    }
    return stage.coerceAtMost(4)
  }

  private val critOdds = intArrayOf(16, 8, 4, 3, 2)

  fun rollCrit(user: BattleMonState, target: BattleMonState, move: MoveDef): Boolean {
    if (target.ability == Ability.BATTLE_ARMOR || target.ability == Ability.SHELL_ARMOR)
        return false
    return rng.critRoll(critOdds[critStage(user, move)])
  }

  /** The type matchup per 10, Foresight letting Normal and Fighting through to Ghost. */
  fun effectiveness(type: PokemonType, target: BattleMonState): Int {
    var result = TypeChart.NEUTRAL
    for ((i, t) in target.types.withIndex()) {
      if (i == 1 && t == target.types[0]) break
      if (t == PokemonType.NONE || t == PokemonType.QUESTIONQUESTIONQUESTION) continue
      var m = ctx.typeChart.multiplier(type, t)
      if (m == 0 &&
          t == PokemonType.GHOST &&
          target.volatile.foresight &&
          (type == PokemonType.NORMAL || type == PokemonType.FIGHTING))
          m = TypeChart.NEUTRAL
      result = result * m / TypeChart.NEUTRAL
    }
    return result
  }

  /** CalculateBaseDamage + Cmd_damagecalc + Cmd_typecalc + the random factor, for one target. */
  fun damage(
      user: BattleMonState,
      target: BattleMonState,
      move: MoveDef,
      power: Int,
      type: PokemonType,
      crit: Boolean,
      spread: Boolean,
  ): Hit {
    val eff = effectiveness(type, target)
    val physical = MoveCategory.isPhysical(type)
    var atkStat = if (physical) BattleStat.ATTACK else BattleStat.SP_ATTACK
    val defStat = if (physical) BattleStat.DEFENSE else BattleStat.SP_DEFENSE
    var attack = user.unstaged(atkStat)
    var defense = target.unstaged(defStat)
    var pow = power
    if (user.ability in setOf(Ability.HUGE_POWER, Ability.PURE_POWER) && physical) attack *= 2
    ctx.heldEffect(user)?.let { e ->
      HeldItems.boostedType(e)?.let { if (it == type) attack = attack * (100 + e.param) / 100 }
      when (e.name) {
        "CHOICE_BAND" -> if (physical) attack = attack * 150 / 100
        "SOUL_DEW" ->
            if (!physical && user.species.id in setOf(380, 381)) attack = attack * 150 / 100
        "DEEP_SEA_TOOTH" -> if (!physical && user.species.id == 366) attack *= 2
        "LIGHT_BALL" -> if (!physical && user.species.id == 25) attack *= 2
        "THICK_CLUB" -> if (physical && user.species.id in setOf(104, 105)) attack *= 2
      }
    }
    ctx.heldEffect(target)?.let { e ->
      when (e.name) {
        "SOUL_DEW" ->
            if (!physical && target.species.id in setOf(380, 381)) defense = defense * 150 / 100
        "DEEP_SEA_SCALE" -> if (!physical && target.species.id == 366) defense *= 2
        "METAL_POWDER" -> if (physical && target.species.id == 132) defense *= 2
      }
    }
    if (target.ability == Ability.THICK_FAT &&
        (type == PokemonType.FIRE || type == PokemonType.ICE))
        attack /= 2
    if (user.ability == Ability.HUSTLE && physical) attack = attack * 150 / 100
    if (user.ability == Ability.GUTS && user.hasStatus && physical) attack = attack * 150 / 100
    if (target.ability == Ability.MARVEL_SCALE && target.hasStatus && physical)
        defense = defense * 150 / 100
    if (user.ability in setOf(Ability.PLUS, Ability.MINUS) && !physical) {
      val partner = ctx.refOf(user)?.let { ctx.allyOf(it) }?.let { ctx.live(it) }
      val other = if (user.ability == Ability.PLUS) Ability.MINUS else Ability.PLUS
      if (partner?.ability == other) attack = attack * 150 / 100
    }
    if (type == PokemonType.ELECTRIC && ctx.field.mudSport) pow /= 2
    if (type == PokemonType.FIRE && ctx.field.waterSport) pow /= 2
    val pinch = user.currentHp <= user.maxHp / 3
    if (pinch) {
      val boosted =
          when (user.ability) {
            Ability.OVERGROW -> PokemonType.GRASS
            Ability.BLAZE -> PokemonType.FIRE
            Ability.TORRENT -> PokemonType.WATER
            Ability.SWARM -> PokemonType.BUG
            else -> null
          }
      if (boosted == type) pow = pow * 150 / 100
    }
    if (move.effect == MoveEffect.EXPLOSION) defense /= 2
    // A crit ignores the attacker's drops and the defender's boosts.
    val atkStage = user.stage(atkStat)
    val defStage = target.stage(defStat)
    attack = if (crit && atkStage < 0) attack else StatStages.scaleStat(attack, atkStage)
    defense = if (crit && defStage > 0) defense else StatStages.scaleStat(defense, defStage)
    defense = defense.coerceAtLeast(1)

    var dmg = attack * pow * (2 * user.level / 5 + 2) / defense / 50
    val targetSide = ctx.refOf(target)?.let { ctx.sideState(it.side) }
    val sideCount =
        ctx.refOf(target)?.let { r ->
          ctx.sideState(r.side).actives.indices.count { ctx.live(BattlerRef(r.side, it)) != null }
        } ?: 1
    if (physical) {
      if (user.burned && user.ability != Ability.GUTS) dmg /= 2
      if (targetSide != null && targetSide.reflectTurns > 0 && !crit) {
        dmg = if (battle.isDouble() && sideCount == 2) dmg * 2 / 3 else dmg / 2
      }
    } else {
      if (targetSide != null && targetSide.lightScreenTurns > 0 && !crit) {
        dmg = if (battle.isDouble() && sideCount == 2) dmg * 2 / 3 else dmg / 2
      }
      when (ctx.weather()) {
        Weather.RAIN ->
            when (type) {
              PokemonType.FIRE -> dmg /= 2
              PokemonType.WATER -> dmg = dmg * 15 / 10
              else -> Unit
            }
        Weather.SUN ->
            when (type) {
              PokemonType.FIRE -> dmg = dmg * 15 / 10
              PokemonType.WATER -> dmg /= 2
              else -> Unit
            }
        else -> Unit
      }
      if (type == PokemonType.FIRE && user.volatile.flashFire) dmg = dmg * 15 / 10
    }
    if (move.effect == MoveEffect.SOLAR_BEAM &&
        ctx.weather() in setOf(Weather.RAIN, Weather.SAND, Weather.HAIL))
        dmg /= 2
    if (spread) dmg /= 2
    if (dmg == 0) dmg = 1
    dmg += 2
    if (crit) dmg *= 2
    if (user.volatile.charged && type == PokemonType.ELECTRIC) dmg *= 2
    if (user.volatile.helpingHand) dmg = dmg * 15 / 10
    if (type != PokemonType.QUESTIONQUESTIONQUESTION && user.hasType(type)) dmg = dmg * 15 / 10
    dmg = dmg * eff / TypeChart.NEUTRAL
    if (dmg > 0) {
      dmg = dmg * rng.damageRoll() / 100
      if (dmg == 0) dmg = 1
    }
    return Hit(dmg, crit, eff)
  }

  /**
   * Puts [amount] damage on [target] from [user]'s move: substitute, Endure, Focus Band, False
   * Swipe, then the hp change event and the faint. Returns the hp actually lost.
   */
  fun applyDamage(
      user: BattleMonState,
      target: BattleMonState,
      move: MoveDef,
      amount: Int,
      hit: Hit,
      keepOne: Boolean = false,
  ): Int {
    val tv = target.volatile
    if (tv.substituteHp > 0 && user !== target && move.id !in SOUND_MOVES) {
      val dealt = minOf(amount, tv.substituteHp)
      tv.substituteHp -= dealt
      ctx.emit(
          BattleEvent.DamageDealt(target.entityId, target.currentHp, hit.crit, hit.effectiveness))
      ctx.say("The SUBSTITUTE took damage for ${ctx.name(target)}!", target.entityId)
      if (tv.substituteHp <= 0) {
        tv.substituteHp = 0
        ctx.emit(BattleEvent.VolatileChanged(target.entityId, "substitute", false))
        ctx.say("${ctx.name(target)}'s SUBSTITUTE faded!", target.entityId)
      }
      return 0
    }
    var dmg = amount.coerceAtLeast(0)
    var hungOn: String? = null
    if (dmg >= target.currentHp) {
      when {
        keepOne -> dmg = target.currentHp - 1
        tv.enduring -> {
          dmg = target.currentHp - 1
          hungOn = "${ctx.name(target)} endured the hit!"
        }
        ctx.focusBandSaves(target) -> {
          dmg = target.currentHp - 1
          hungOn = "${ctx.name(target)} hung on using its FOCUS BAND!"
          ctx.emit(BattleEvent.ItemActivated(target.entityId, target.heldItem, consumed = false))
        }
      }
    }
    dmg = dmg.coerceAtLeast(0)
    target.currentHp = (target.currentHp - dmg).coerceAtLeast(0)
    if (MoveCategory.isPhysical(move.type)) tv.physicalDamageTaken += dmg
    else tv.specialDamageTaken += dmg
    tv.damagedBy = user.entityId
    if (tv.bideTurns > 0) tv.bideDamage += dmg
    ctx.emit(
        BattleEvent.DamageDealt(target.entityId, target.currentHp, hit.crit, hit.effectiveness))
    if (hit.crit) ctx.say("A critical hit!", target.entityId)
    hungOn?.let { ctx.say(it, target.entityId) }
    if (target.fainted) {
      ctx.faint(target)
      if (tv.destinyBond && !user.fainted && user !== target) {
        ctx.say("${ctx.name(target)} took ${ctx.name(user)} with it!", user.entityId)
        ctx.setHp(user, 0, "destinybond")
      }
      // GRUDGE: the move that knocked it out loses all its PP.
      if (tv.grudge && user !== target) {
        val slot = user.moves.indexOfFirst { it.id.toInt() == move.id }
        if (slot >= 0) {
          user.moves[slot] = de.fiereu.openmmo.common.PokemonMove(user.moves[slot].id, 0)
          ctx.say(
              "${ctx.name(user)}'s ${move.name} lost all its PP due to the GRUDGE!", user.entityId)
        }
      }
      if (ctx.sideOf(target) == 1 && ctx.sideOf(user) == 0) target.foughtBy += user.entityId
    } else {
      if (tv.raging && dmg > 0) {
        ctx.raiseStat(target, target, BattleStat.ATTACK, 1, announce = false)
        ctx.say("${ctx.name(target)}'s RAGE is building!", target.entityId)
      }
      ctx.pinchBerry(target)
    }
    return dmg
  }
}
