package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.common.enums.MoveEffect
import de.fiereu.openmmo.common.enums.MoveFlag
import de.fiereu.openmmo.common.enums.PokemonType
import de.fiereu.openmmo.moves.MoveDef
import de.fiereu.openmmo.progression.SpeciesWeights
import de.fiereu.openmmo.typechart.TypeChart

/**
 * The effect half of a move (src/data/battle_scripts_1.s per EFFECT_, with the C helpers in
 * src/battle_script_commands.c). [runSpecial] handles the moves that do not go through the
 * per-target hit loop (field, side and self moves), [apply] one target of everything else.
 */
internal class MoveEffects(private val ctx: BattleCtx, private val runner: MoveRunner) {

  private val rng = ctx.rng
  private val battle = ctx.battle

  private fun failed(user: BattleMonState, move: MoveDef) =
      ctx.emit(BattleEvent.MoveFailed(user.entityId, move.id.toShort()))

  private fun anim(user: BattleMonState, move: MoveDef, target: BattleMonState? = null) =
      ctx.emit(BattleEvent.Animation(user.entityId, move.id, target?.entityId ?: 0))

  // --- field, side and self moves ---------------------------------------------------------------

  /** True when the move was handled here and the hit loop must not run. */
  fun runSpecial(
      userRef: BattlerRef,
      user: BattleMonState,
      move: MoveDef,
      chosen: BattlerRef?,
  ): Boolean {
    val who = ctx.name(user)
    val v = user.volatile
    val mySide = ctx.sideState(userRef.side)
    when (move.effect) {
      MoveEffect.EXPLOSION -> {
        val damp =
            ctx.allRefs().mapNotNull { ctx.live(it) }.firstOrNull { it.ability == Ability.DAMP }
                ?: return false
        ctx.emit(BattleEvent.AbilityShown(damp.entityId, "DAMP"))
        ctx.say(
            "${ctx.name(damp)}'s DAMP prevents ${ctx.name(user)} from using ${move.name}!",
            damp.entityId)
      }
      MoveEffect.SPLASH -> {
        anim(user, move)
        ctx.say("But nothing happened!")
      }
      MoveEffect.HAZE -> {
        anim(user, move)
        for (ref in ctx.allRefs()) ctx.mon(ref)?.clearStages()
        ctx.say("All STAT changes were eliminated!")
      }
      MoveEffect.LIGHT_SCREEN -> {
        if (mySide.lightScreenTurns > 0) return failed(user, move).let { true }
        anim(user, move)
        mySide.lightScreenTurns = 5
        ctx.say("LIGHT SCREEN raised your team's SP. DEF!".teamText(userRef))
      }
      MoveEffect.REFLECT -> {
        if (mySide.reflectTurns > 0) return failed(user, move).let { true }
        anim(user, move)
        mySide.reflectTurns = 5
        ctx.say("REFLECT raised your team's DEFENSE!".teamText(userRef))
      }
      MoveEffect.MIST -> {
        if (mySide.mistTurns > 0) return failed(user, move).let { true }
        anim(user, move)
        mySide.mistTurns = 5
        ctx.say("Your team became shrouded in MIST!".teamText(userRef))
      }
      MoveEffect.SAFEGUARD -> {
        if (mySide.safeguardTurns > 0) return failed(user, move).let { true }
        anim(user, move)
        mySide.safeguardTurns = 5
        ctx.say("Your team became cloaked in a mystical veil!".teamText(userRef))
      }
      MoveEffect.SPIKES -> {
        val foeSide = ctx.sideState(userRef.foeSide)
        if (foeSide.spikes >= 3) return failed(user, move).let { true }
        anim(user, move)
        foeSide.spikes += 1
        ctx.say(
            if (userRef.foeSide == 1) "SPIKES were scattered all around the foe's team!"
            else "SPIKES were scattered all around your team!")
      }
      MoveEffect.RAIN_DANCE -> moveWeather(user, move, Weather.RAIN, "It started to rain!")
      MoveEffect.SUNNY_DAY -> moveWeather(user, move, Weather.SUN, "The sunlight got bright!")
      MoveEffect.SANDSTORM -> moveWeather(user, move, Weather.SAND, "A sandstorm brewed!")
      MoveEffect.HAIL -> moveWeather(user, move, Weather.HAIL, "It started to hail!")
      MoveEffect.MUD_SPORT -> {
        anim(user, move)
        ctx.field.mudSport = true
        ctx.say("Electricity's power was weakened!")
      }
      MoveEffect.WATER_SPORT -> {
        anim(user, move)
        ctx.field.waterSport = true
        ctx.say("Fire's power was weakened!")
      }
      MoveEffect.PROTECT,
      MoveEffect.ENDURE -> {
        // Each successful use in a row halves the chance (Gen 3 sProtectSuccessRates).
        val odds =
            when (v.protectChain) {
              0 -> 0x10000
              1 -> 0x8000
              2 -> 0x4000
              else -> 0x2000
            }
        val last = ctx.events.lastOrNull()
        val movesLeft =
            ctx.allRefs().any { r -> ctx.live(r)?.let { !it.volatile.movedThisTurn } == true }
        if (!movesLeft || rng.random16() >= odds) {
          v.protectChain = 0
          return failed(user, move).let { true }
        }
        if (last == null) Unit
        v.protectChain += 1
        anim(user, move)
        if (move.effect == MoveEffect.PROTECT) {
          v.protectedThisTurn = true
          ctx.say("$who protected itself!", user.entityId)
        } else {
          v.enduring = true
          ctx.say("$who braced itself!", user.entityId)
        }
      }
      MoveEffect.FOCUS_ENERGY -> {
        if (v.focusEnergy) return failed(user, move).let { true }
        anim(user, move)
        v.focusEnergy = true
        ctx.say("$who is getting pumped!", user.entityId)
      }
      MoveEffect.RESTORE_HP,
      MoveEffect.SOFTBOILED -> {
        if (user.currentHp >= user.maxHp) {
          ctx.say("$who's HP is full!", user.entityId)
          return true
        }
        anim(user, move)
        ctx.heal(user, (user.maxHp / 2).coerceAtLeast(1), "move")
        ctx.say("$who regained health!", user.entityId)
      }
      MoveEffect.MORNING_SUN,
      MoveEffect.SYNTHESIS,
      MoveEffect.MOONLIGHT -> {
        if (user.currentHp >= user.maxHp) {
          ctx.say("$who's HP is full!", user.entityId)
          return true
        }
        anim(user, move)
        val amount =
            when (ctx.weather()) {
              Weather.SUN -> user.maxHp * 2 / 3
              Weather.NONE -> user.maxHp / 2
              else -> user.maxHp / 4
            }
        ctx.heal(user, amount.coerceAtLeast(1), "move")
        ctx.say("$who regained health!", user.entityId)
      }
      MoveEffect.REST -> {
        if (user.currentHp >= user.maxHp || user.asleep) return failed(user, move).let { true }
        if (user.ability in setOf(Ability.INSOMNIA, Ability.VITAL_SPIRIT))
            return failed(user, move).let { true }
        anim(user, move)
        user.status = 0
        ctx.trySetStatus(user, REST_SLEEP, null, announceFail = false)
        ctx.setHp(user, user.maxHp, "rest")
      }
      MoveEffect.SUBSTITUTE -> {
        val cost = user.maxHp / 4
        if (v.substituteHp > 0) {
          ctx.say("$who already has a SUBSTITUTE!", user.entityId)
          return true
        }
        if (user.currentHp <= cost || cost == 0) {
          ctx.say("It was too weak to make a SUBSTITUTE!", user.entityId)
          return true
        }
        anim(user, move)
        ctx.hurt(user, cost, "substitute")
        v.substituteHp = cost
        ctx.emit(BattleEvent.VolatileChanged(user.entityId, "substitute", true))
        ctx.say("$who made a SUBSTITUTE!", user.entityId)
      }
      MoveEffect.BELLY_DRUM -> {
        val cost = user.maxHp / 2
        if (user.currentHp <= cost || user.stage(BattleStat.ATTACK) >= StatStages.MAX)
            return failed(user, move).let { true }
        anim(user, move)
        ctx.hurt(user, cost, "bellydrum")
        user.setStage(BattleStat.ATTACK, StatStages.MAX - 1)
        ctx.raiseStat(user, user, BattleStat.ATTACK, 1, announce = false)
        ctx.say("$who cut its own HP and maximized ATTACK!", user.entityId)
      }
      MoveEffect.DEFENSE_CURL -> {
        anim(user, move)
        v.defenseCurl = true
        ctx.raiseStat(user, user, BattleStat.DEFENSE, 1, announce = true)
      }
      MoveEffect.MINIMIZE -> {
        anim(user, move)
        v.minimized = true
        ctx.raiseStat(user, user, BattleStat.EVASION, 1, announce = true)
      }
      MoveEffect.CHARGE -> {
        anim(user, move)
        v.charged = true
        ctx.say("$who began charging power!", user.entityId)
      }
      MoveEffect.INGRAIN -> {
        if (v.ingrained) return failed(user, move).let { true }
        anim(user, move)
        v.ingrained = true
        ctx.say("$who planted its roots!", user.entityId)
      }
      MoveEffect.STOCKPILE -> {
        if (v.stockpile >= 3) {
          ctx.say("$who can't STOCKPILE any more!", user.entityId)
          return true
        }
        anim(user, move)
        v.stockpile += 1
        ctx.say("$who STOCKPILED ${v.stockpile}!", user.entityId)
      }
      MoveEffect.SWALLOW -> {
        if (v.stockpile == 0) {
          ctx.say("But it failed to SWALLOW a thing!", user.entityId)
          return true
        }
        anim(user, move)
        val amount =
            when (v.stockpile) {
              1 -> user.maxHp / 4
              2 -> user.maxHp / 2
              else -> user.maxHp
            }
        v.stockpile = 0
        ctx.heal(user, amount, "move")
        ctx.say("$who regained health!", user.entityId)
      }
      MoveEffect.WISH -> {
        if (mySide.wish.containsKey(userRef.pos)) return failed(user, move).let { true }
        anim(user, move)
        mySide.wish[userRef.pos] = 2 to user.maxHp / 2
        ctx.say("$who made a WISH!", user.entityId)
      }
      MoveEffect.HEAL_BELL -> {
        anim(user, move)
        ctx.say(
            if (move.id == 215) "A bell chimed!" else "A soothing aroma wafted through the area!")
        for (m in ctx.party(userRef.side)) {
          if (m.fainted || !m.hasStatus) continue
          if (move.id == 215 && m.ability == Ability.SOUNDPROOF && ctx.refOf(m) != null) continue
          ctx.cureStatus(m, null)
        }
      }
      MoveEffect.REFRESH -> {
        if (!user.hasStatus || user.frozen || user.asleep) return failed(user, move).let { true }
        anim(user, move)
        ctx.cureStatus(user, "$who's status returned to normal!")
      }
      MoveEffect.PERISH_SONG -> {
        anim(user, move)
        ctx.say("All affected POKéMON will faint in three turns!")
        for (ref in ctx.allRefs()) {
          val m = ctx.live(ref) ?: continue
          if (m.ability == Ability.SOUNDPROOF || m.volatile.perishCount > 0) continue
          m.volatile.perishCount = 4
        }
      }
      MoveEffect.TELEPORT -> {
        if (battle.trainer != null || battle.pvp != null) return failed(user, move).let { true }
        anim(user, move)
        ctx.say("$who fled from the battle!", user.entityId)
        ctx.emit(BattleEvent.Fled(userRef.side))
      }
      MoveEffect.FUTURE_SIGHT -> {
        val target =
            runner.targetsFor(userRef, user, move, chosen).firstOrNull()
                ?: return failed(user, move).let { true }
        val side = ctx.sideState(target.side)
        if (side.futureSight.containsKey(target.pos)) return failed(user, move).let { true }
        anim(user, move)
        val defender = ctx.mon(target) ?: return true
        // Gen 3 Future Sight is typeless at the time it hits: no STAB, no type matchup.
        val hit =
            runner.damage(
                user,
                defender,
                move,
                move.power,
                PokemonType.QUESTIONQUESTIONQUESTION,
                false,
                false)
        side.futureSight[target.pos] = 3 to hit.damage
        ctx.say(
            if (move.id == 353) "$who chose DOOM DESIRE as its destiny!"
            else "$who foresaw an attack!",
            user.entityId)
      }
      MoveEffect.HELPING_HAND -> {
        val ally = ctx.allyOf(userRef)?.let { ctx.live(it) }
        if (ally == null || ally.volatile.movedThisTurn) return failed(user, move).let { true }
        anim(user, move, ally)
        ally.volatile.helpingHand = true
        ctx.say("$who is ready to help ${ctx.name(ally)}!", user.entityId)
      }
      MoveEffect.FOLLOW_ME -> {
        anim(user, move)
        ctx.say("$who became the center of attention!", user.entityId)
      }
      MoveEffect.BATON_PASS -> {
        val party = ctx.party(userRef.side)
        val active = ctx.sideState(userRef.side).actives
        val next = party.indices.firstOrNull { it !in active && !party[it].fainted }
        if (next == null) return failed(user, move).let { true }
        anim(user, move)
        val stages = BattleStat.entries.associateWith { user.stage(it) }
        val keep = user.volatile
        runner.switchTo(userRef, next)
        ctx.mon(userRef)?.let { incoming ->
          stages.forEach { (s, n) -> incoming.setStage(s, n) }
          incoming.volatile.substituteHp = keep.substituteHp
          incoming.volatile.confusionTurns = keep.confusionTurns
          incoming.volatile.focusEnergy = keep.focusEnergy
          incoming.volatile.seededBy = keep.seededBy
          incoming.volatile.cantEscape = keep.cantEscape
          incoming.volatile.ingrained = keep.ingrained
          incoming.volatile.perishCount = keep.perishCount
        }
      }
      MoveEffect.METRONOME -> {
        anim(user, move)
        val pool = ctx.moves.all().filter { it.id in 1..354 && it.id !in METRONOME_BANNED }
        val picked = pool[rng.pick(pool.size)]
        ctx.say("Waggling a finger let it use ${picked.name}!", user.entityId)
        runner.useMove(userRef, user, picked.id, null, called = true)
      }
      MoveEffect.SLEEP_TALK -> {
        if (!user.asleep) return failed(user, move).let { true }
        val known =
            user.moves
                .map { it.id.toInt() }
                .filter { it != 0 && it != move.id && it !in SLEEP_TALK_BANNED }
        if (known.isEmpty()) return failed(user, move).let { true }
        runner.useMove(userRef, user, known[rng.pick(known.size)], null, called = true)
      }
      MoveEffect.ASSIST -> {
        val pool =
            ctx.party(userRef.side)
                .filter { it !== user }
                .flatMap { m -> m.moves.map { it.id.toInt() } }
                .filter { it != 0 && it !in METRONOME_BANNED }
        if (pool.isEmpty()) return failed(user, move).let { true }
        runner.useMove(userRef, user, pool[rng.pick(pool.size)], null, called = true)
      }
      MoveEffect.MIRROR_MOVE -> {
        val target =
            runner.targetsFor(userRef, user, move, chosen).firstOrNull()?.let { ctx.mon(it) }
        val copied =
            target?.volatile?.lastMove?.takeIf {
              it != 0 && ctx.move(it)?.hasFlag(MoveFlag.MIRROR_MOVE_AFFECTED) == true
            }
        if (copied == null) return failed(user, move).let { true }
        runner.useMove(userRef, user, copied, chosen, called = true)
      }
      MoveEffect.NATURE_POWER -> {
        val called =
            when (battle.terrain) {
              BattleTerrain.GRASS,
              BattleTerrain.LONG_GRASS -> 78 // STUN SPORE
              BattleTerrain.SAND -> 89 // EARTHQUAKE
              BattleTerrain.WATER,
              BattleTerrain.POND -> 57 // SURF
              BattleTerrain.UNDERWATER -> 56 // HYDRO PUMP
              BattleTerrain.ROCK,
              BattleTerrain.CAVE -> 157 // ROCK SLIDE (Gen 3: shadow ball in caves)
              else -> 129 // SWIFT
            }
        ctx.say("NATURE POWER turned into ${ctx.move(called)?.name}!")
        runner.useMove(userRef, user, called, chosen, called = true)
      }
      MoveEffect.BIDE -> {
        if (v.bideTurns == 0) {
          anim(user, move)
          v.bideTurns = 2
          v.bideDamage = 0
          ctx.say("$who is storing energy!", user.entityId)
          v.rampageMove = move.id
          v.rampageTurns = 99
        } else if (--v.bideTurns > 0) {
          ctx.say("$who is storing energy!", user.entityId)
        } else {
          v.rampageMove = 0
          v.rampageTurns = 0
          ctx.say("$who unleashed energy!", user.entityId)
          val target =
              ctx.allRefs().mapNotNull { ctx.live(it) }.firstOrNull { it.entityId == v.damagedBy }
                  ?: runner.targetsFor(userRef, user, move, chosen).firstOrNull()?.let {
                    ctx.live(it)
                  }
          if (target == null || v.bideDamage == 0) return failed(user, move).let { true }
          runner.applyDamage(
              user,
              target,
              move,
              v.bideDamage * 2,
              MoveRunner.Hit(v.bideDamage * 2, false, TypeChart.NEUTRAL))
        }
      }
      MoveEffect.CAMOUFLAGE -> {
        anim(user, move)
        val type =
            when (battle.terrain) {
              BattleTerrain.GRASS,
              BattleTerrain.LONG_GRASS -> PokemonType.GRASS
              BattleTerrain.WATER,
              BattleTerrain.POND,
              BattleTerrain.UNDERWATER -> PokemonType.WATER
              BattleTerrain.SAND -> PokemonType.GROUND
              BattleTerrain.ROCK,
              BattleTerrain.CAVE -> PokemonType.ROCK
              else -> PokemonType.NORMAL
            }
        user.types = mutableListOf(type, type)
        ctx.say("$who transformed into the ${type.name} type!", user.entityId)
      }
      MoveEffect.CONVERSION -> {
        val type =
            user.moves
                .mapNotNull { ctx.move(it.id.toInt())?.type }
                .firstOrNull { !user.hasType(it) && it != PokemonType.QUESTIONQUESTIONQUESTION }
                ?: return failed(user, move).let { true }
        anim(user, move)
        user.types = mutableListOf(type, type)
        ctx.say("$who transformed into the ${type.name} type!", user.entityId)
      }
      else -> return false
    }
    return true
  }

  private fun String.teamText(ref: BattlerRef): String =
      if (ref.side == 0) this
      else replace("your team", "the foe's team").replace("Your team", "The foe's team")

  private fun moveWeather(user: BattleMonState, move: MoveDef, weather: Weather, text: String) {
    if (ctx.field.weather == weather) {
      failed(user, move)
      return
    }
    anim(user, move)
    ctx.field.weather = weather
    // Gen 3 move weather lasts five turns.
    ctx.field.weatherTurns = 5
    ctx.emit(BattleEvent.WeatherChanged(weather))
    ctx.say(text)
  }

  // --- one target ------------------------------------------------------------------------------

  /** Applies [move] to one target that passed accuracy. True when it connected. */
  fun apply(
      userRef: BattlerRef,
      user: BattleMonState,
      targetRef: BattlerRef,
      target: BattleMonState,
      move: MoveDef,
      spread: Boolean,
  ): Boolean {
    val power = basePower(user, target, move)
    return if (power > 0 || fixedDamage(move.effect)) {
      damaging(userRef, user, targetRef, target, move, power, spread)
    } else statusMove(userRef, user, targetRef, target, move)
  }

  private fun fixedDamage(effect: MoveEffect): Boolean =
      effect in
          setOf(
              MoveEffect.SONICBOOM,
              MoveEffect.DRAGON_RAGE,
              MoveEffect.LEVEL_DAMAGE,
              MoveEffect.PSYWAVE,
              MoveEffect.SUPER_FANG,
              MoveEffect.ENDEAVOR,
              MoveEffect.OHKO,
              MoveEffect.COUNTER,
              MoveEffect.MIRROR_COAT)

  /** The move's power for this use (Flail, Return, Eruption, Magnitude, Weather Ball...). */
  private fun basePower(user: BattleMonState, target: BattleMonState, move: MoveDef): Int {
    val v = user.volatile
    val tv = target.volatile
    return when (move.effect) {
      MoveEffect.FLAIL -> {
        val ratio = 48 * user.currentHp / user.maxHp
        when {
          ratio <= 1 -> 200
          ratio <= 4 -> 150
          ratio <= 9 -> 100
          ratio <= 16 -> 80
          ratio <= 32 -> 40
          else -> 20
        }
      }
      MoveEffect.RETURN -> (user.source.friendship * 10 / 25).coerceAtLeast(1)
      MoveEffect.FRUSTRATION -> ((255 - user.source.friendship) * 10 / 25).coerceAtLeast(1)
      MoveEffect.ERUPTION -> (move.power * user.currentHp / user.maxHp).coerceAtLeast(1)
      MoveEffect.MAGNITUDE -> {
        val roll = rng.pick(100)
        val (mag, pow) =
            when {
              roll < 5 -> 4 to 10
              roll < 15 -> 5 to 30
              roll < 35 -> 6 to 50
              roll < 65 -> 7 to 70
              roll < 85 -> 8 to 90
              roll < 95 -> 9 to 110
              else -> 10 to 150
            }
        ctx.say("MAGNITUDE $mag!")
        if (tv.semiInvulnerable == "DIG") pow * 2 else pow
      }
      MoveEffect.PRESENT -> {
        val roll = rng.pick(256)
        when {
          roll < 102 -> 40
          roll < 178 -> 80
          roll < 204 -> 120
          else -> -1
        }
      }
      MoveEffect.HIDDEN_POWER -> hiddenPower(user).second
      MoveEffect.WEATHER_BALL -> if (ctx.weather() != Weather.NONE) move.power * 2 else move.power
      MoveEffect.FACADE ->
          if (user.poisoned || user.burned || user.paralyzed) move.power * 2 else move.power
      MoveEffect.SMELLINGSALT -> if (target.paralyzed) move.power * 2 else move.power
      MoveEffect.REVENGE -> if (v.damagedBy == target.entityId) move.power * 2 else move.power
      MoveEffect.ROLLOUT -> {
        val count = 5 - v.rolloutTurns.coerceIn(0, 5)
        var p = move.power
        repeat(if (v.rolloutTurns == 0) 0 else count) { p *= 2 }
        if (v.defenseCurl) p *= 2
        p
      }
      MoveEffect.FURY_CUTTER -> {
        var p = move.power
        repeat(v.furyCutter.coerceAtMost(4)) { p *= 2 }
        p
      }
      MoveEffect.SPIT_UP -> 100 * v.stockpile
      MoveEffect.GUST,
      MoveEffect.TWISTER -> if (tv.semiInvulnerable == "FLY") move.power * 2 else move.power
      MoveEffect.EARTHQUAKE -> if (tv.semiInvulnerable == "DIG") move.power * 2 else move.power
      MoveEffect.FLINCH_MINIMIZE_HIT -> if (tv.minimized) move.power * 2 else move.power
      // Cmd_weightdamagecalculation: the target's pokedex weight against sWeightToDamageTable.
      MoveEffect.LOW_KICK -> lowKickPower(SpeciesWeights.hectograms(target.species.id))
      MoveEffect.TRIPLE_KICK -> move.power
      else ->
          if ((move.id == 57 || move.id == 250) && tv.semiInvulnerable == "DIVE") move.power * 2
          else move.power
    }
  }

  /** Gen 3 Hidden Power from the IVs: type and 30-70 power. */
  fun hiddenPower(user: BattleMonState): Pair<PokemonType, Int> {
    val iv = user.source.iVs
    val bits = listOf(iv.hp, iv.atk, iv.def, iv.spd, iv.spAtk, iv.spDef)
    val typeBits = bits.mapIndexed { i, x -> (x and 1) shl i }.sum()
    val powerBits = bits.mapIndexed { i, x -> ((x shr 1) and 1) shl i }.sum()
    val types =
        listOf(
            PokemonType.FIGHTING,
            PokemonType.FLYING,
            PokemonType.POISON,
            PokemonType.GROUND,
            PokemonType.ROCK,
            PokemonType.BUG,
            PokemonType.GHOST,
            PokemonType.STEEL,
            PokemonType.FIRE,
            PokemonType.WATER,
            PokemonType.GRASS,
            PokemonType.ELECTRIC,
            PokemonType.PSYCHIC,
            PokemonType.ICE,
            PokemonType.DRAGON,
            PokemonType.DARK)
    return types[typeBits * 15 / 63] to (powerBits * 40 / 63 + 30)
  }

  private fun moveType(user: BattleMonState, move: MoveDef): PokemonType =
      when (move.effect) {
        MoveEffect.HIDDEN_POWER -> hiddenPower(user).first
        MoveEffect.WEATHER_BALL ->
            when (ctx.weather()) {
              Weather.RAIN -> PokemonType.WATER
              Weather.SUN -> PokemonType.FIRE
              Weather.SAND -> PokemonType.ROCK
              Weather.HAIL -> PokemonType.ICE
              Weather.NONE -> PokemonType.NORMAL
            }
        else -> if (move.id == STRUGGLE_ID) PokemonType.QUESTIONQUESTIONQUESTION else move.type
      }

  private fun damaging(
      userRef: BattlerRef,
      user: BattleMonState,
      targetRef: BattlerRef,
      target: BattleMonState,
      move: MoveDef,
      power: Int,
      spread: Boolean,
  ): Boolean {
    val type = moveType(user, move)
    val who = ctx.name(target)
    // Present can heal instead.
    if (power < 0) {
      if (target.currentHp >= target.maxHp) return failed(user, move).let { false }
      ctx.heal(target, target.maxHp / 4, "present")
      ctx.say("$who regained health!", target.entityId)
      return true
    }
    if (move.effect == MoveEffect.DREAM_EATER && !target.asleep) {
      ctx.say("$who wasn't affected!", target.entityId)
      return false
    }
    if (move.effect == MoveEffect.OHKO && target.ability == Ability.STURDY) {
      ctx.emit(BattleEvent.AbilityShown(target.entityId, "STURDY"))
      ctx.say("$who was protected by STURDY!", target.entityId)
      return false
    }
    // Ability immunities, in the order the decomp checks them.
    if (type == PokemonType.GROUND && target.ability == Ability.LEVITATE) {
      ctx.emit(BattleEvent.AbilityShown(target.entityId, "LEVITATE"))
      ctx.say("$who makes GROUND moves miss with LEVITATE!", target.entityId)
      return false
    }
    if (type == PokemonType.ELECTRIC && target.ability == Ability.VOLT_ABSORB ||
        type == PokemonType.WATER && target.ability == Ability.WATER_ABSORB) {
      ctx.emit(BattleEvent.AbilityShown(target.entityId, abilityName(target.ability)))
      if (ctx.heal(target, target.maxHp / 4, "ability") > 0) {
        ctx.say("$who restored HP using its ${abilityName(target.ability)}!", target.entityId)
      } else
          ctx.say(
              "$who's ${abilityName(target.ability)} made ${move.name} useless!", target.entityId)
      return false
    }
    if (type == PokemonType.FIRE && target.ability == Ability.FLASH_FIRE && !target.frozen) {
      ctx.emit(BattleEvent.AbilityShown(target.entityId, "FLASH FIRE"))
      target.volatile.flashFire = true
      ctx.say("$who's FLASH FIRE raised its FIRE power!", target.entityId)
      return false
    }
    val eff = runner.effectiveness(type, target)
    if (eff == 0 && type != PokemonType.QUESTIONQUESTIONQUESTION) {
      ctx.say("It doesn't affect $who...", target.entityId)
      return false
    }
    if (target.ability == Ability.WONDER_GUARD &&
        eff <= TypeChart.NEUTRAL &&
        type != PokemonType.QUESTIONQUESTIONQUESTION &&
        power > 0 &&
        !fixedDamage(move.effect)) {
      ctx.emit(BattleEvent.AbilityShown(target.entityId, "WONDER GUARD"))
      ctx.say("$who avoided damage with WONDER GUARD!", target.entityId)
      return false
    }

    val hits =
        when (move.effect) {
          MoveEffect.MULTI_HIT -> {
            // Gen 3: 2 or 3 hits 3/8 each, 4 or 5 hits 1/8 each.
            val r = rng.pick(8)
            when {
              r < 3 -> 2
              r < 6 -> 3
              r < 7 -> 4
              else -> 5
            }
          }
          MoveEffect.DOUBLE_HIT,
          MoveEffect.TWINEEDLE -> 2
          MoveEffect.TRIPLE_KICK -> 3
          else -> 1
        }
    var dealtTotal = 0
    var landed = 0
    for (i in 0 until hits) {
      if (target.fainted || user.fainted) break
      if (move.effect == MoveEffect.TRIPLE_KICK &&
          i > 0 &&
          !runner.accuracyCheck(user, target, move))
          break
      val pow = if (move.effect == MoveEffect.TRIPLE_KICK) power * (i + 1) else power
      val crit = !fixedDamage(move.effect) && runner.rollCrit(user, target, move)
      val hit =
          fixed(user, target, move)?.let {
            MoveRunner.Hit(it, false, if (eff == 0) TypeChart.NEUTRAL else eff)
          } ?: runner.damage(user, target, move, pow, type, crit, spread)
      if (move.effect == MoveEffect.OHKO) ctx.say("It's a one-hit KO!", target.entityId)
      dealtTotal +=
          runner.applyDamage(
              user, target, move, hit.damage, hit, keepOne = move.effect == MoveEffect.FALSE_SWIPE)
      landed++
      if (move.hasFlag(MoveFlag.MAKES_CONTACT)) ctx.contactAbility(user, target)
      // A fire hit thaws a frozen target.
      if (type == PokemonType.FIRE && target.frozen && !target.fainted)
          ctx.cureStatus(target, "$who thawed out!")
    }
    if (hits > 1 && landed > 0) ctx.say("Hit $landed time(s)!", target.entityId)
    if (landed == 0) return false

    // Drain moves.
    if (move.effect == MoveEffect.ABSORB || move.effect == MoveEffect.DREAM_EATER) {
      val gain = (dealtTotal / 2).coerceAtLeast(1)
      if (target.ability == Ability.LIQUID_OOZE) {
        ctx.say("${ctx.name(user)} sucked up the liquid ooze!", user.entityId)
        ctx.hurt(user, gain, "ooze")
      } else if (ctx.heal(user, gain, "drain") > 0) {
        ctx.say("$who had its energy drained!", target.entityId)
      }
    }
    ctx.shellBell(user, dealtTotal)
    if (move.effect == MoveEffect.SMELLINGSALT && target.paralyzed && !target.fainted) {
      ctx.cureStatus(target, "$who was cured of paralysis.")
    }
    secondary(userRef, user, target, move, dealtTotal)
    if (move.effect == MoveEffect.KNOCK_OFF &&
        target.heldItem != 0 &&
        !target.fainted &&
        target.ability != Ability.STICKY_HOLD) {
      ctx.say(
          "${ctx.name(user)} knocked off $who's ${ctx.held.name(target.heldItem).uppercase()}!",
          target.entityId)
      target.heldItem = 0
    }
    if (move.effect == MoveEffect.THIEF &&
        user.heldItem == 0 &&
        target.heldItem != 0 &&
        target.ability != Ability.STICKY_HOLD &&
        !user.fainted) {
      ctx.say(
          "${ctx.name(user)} stole $who's ${ctx.held.name(target.heldItem).uppercase()}!",
          target.entityId)
      user.heldItem = target.heldItem
      target.heldItem = 0
    }
    if (move.effect == MoveEffect.RAPID_SPIN && !user.fainted) {
      val v = user.volatile
      if (v.trapTurns > 0) {
        v.trapTurns = 0
        ctx.say("${ctx.name(user)} was freed!", user.entityId)
      }
      if (v.seededBy != 0L) {
        v.seededBy = 0
        ctx.say("${ctx.name(user)} was freed from LEECH SEED!", user.entityId)
      }
      val side = ctx.sideState(userRef.side)
      if (side.spikes > 0) {
        side.spikes = 0
        ctx.say("${ctx.name(user)} blew away SPIKES!", user.entityId)
      }
    }
    if (move.effect == MoveEffect.PAY_DAY && ctx.sideOf(user) == 0) {
      battle.payDay += user.level * 5
      ctx.say("Coins scattered everywhere!")
    }
    // Recoil off what was actually dealt.
    val recoil =
        when {
          move.id == STRUGGLE_ID -> dealtTotal / 4
          move.effect == MoveEffect.RECOIL -> dealtTotal / 4
          move.effect == MoveEffect.DOUBLE_EDGE -> dealtTotal / 3
          else -> 0
        }
    if (recoil > 0 && (user.ability != Ability.ROCK_HEAD || move.id == STRUGGLE_ID)) {
      ctx.say("${ctx.name(user)} is hit with recoil!", user.entityId)
      ctx.hurt(user, recoil, "recoil")
    }
    return true
  }

  private fun fixed(user: BattleMonState, target: BattleMonState, move: MoveDef): Int? =
      when (move.effect) {
        MoveEffect.SONICBOOM -> 20
        MoveEffect.DRAGON_RAGE -> 40
        MoveEffect.LEVEL_DAMAGE -> user.level
        MoveEffect.PSYWAVE -> (user.level * (rng.range(0, 10) * 10 + 50) / 100).coerceAtLeast(1)
        MoveEffect.SUPER_FANG -> (target.currentHp / 2).coerceAtLeast(1)
        MoveEffect.ENDEAVOR -> (target.currentHp - user.currentHp).coerceAtLeast(0)
        MoveEffect.OHKO -> target.currentHp
        MoveEffect.COUNTER -> (user.volatile.physicalDamageTaken * 2).takeIf { it > 0 } ?: 0
        MoveEffect.MIRROR_COAT -> (user.volatile.specialDamageTaken * 2).takeIf { it > 0 } ?: 0
        else -> null
      }

  /** The secondary effect of a damaging move, rolled against its chance. */
  private fun secondary(
      userRef: BattlerRef,
      user: BattleMonState,
      target: BattleMonState,
      move: MoveDef,
      dealt: Int,
  ) {
    // Fake Out's flinch is certain (setmoveeffect MOVE_EFFECT_FLINCH | MOVE_EFFECT_CERTAIN).
    var chance = if (move.effect == MoveEffect.FAKE_OUT) 100 else move.secondaryEffectChance
    if (user.ability == Ability.SERENE_GRACE) chance *= 2
    val blocked =
        target.ability == Ability.SHIELD_DUST || target.volatile.substituteHp > 0 && dealt == 0
    val roll = { chance >= 100 || rng.percent(chance) }
    val onTarget = !target.fainted && !blocked
    when (move.effect) {
      MoveEffect.POISON_HIT,
      MoveEffect.POISON_TAIL,
      MoveEffect.TWINEEDLE ->
          if (onTarget && roll()) ctx.trySetStatus(target, StatusCondition.POISON, user, false)
      MoveEffect.POISON_FANG ->
          if (onTarget && roll()) ctx.trySetStatus(target, StatusCondition.TOXIC, user, false)
      MoveEffect.BURN_HIT,
      MoveEffect.THAW_HIT,
      MoveEffect.BLAZE_KICK ->
          if (onTarget && roll()) ctx.trySetStatus(target, StatusCondition.BURN, user, false)
      MoveEffect.FREEZE_HIT ->
          if (onTarget && roll()) ctx.trySetStatus(target, StatusCondition.FREEZE, user, false)
      MoveEffect.PARALYZE_HIT,
      MoveEffect.THUNDER ->
          if (onTarget && roll()) ctx.trySetStatus(target, StatusCondition.PARALYSIS, user, false)
      MoveEffect.SECRET_POWER ->
          if (onTarget && roll()) {
            when (battle.terrain) {
              BattleTerrain.GRASS,
              BattleTerrain.LONG_GRASS ->
                  ctx.trySetStatus(target, StatusCondition.SLEEP_MASK, user, false)
              BattleTerrain.SAND -> ctx.lowerStat(target, user, BattleStat.ACCURACY, 1, false)
              BattleTerrain.WATER,
              BattleTerrain.POND -> ctx.lowerStat(target, user, BattleStat.ATTACK, 1, false)
              BattleTerrain.ROCK,
              BattleTerrain.CAVE -> flinch(target)
              else -> ctx.trySetStatus(target, StatusCondition.PARALYSIS, user, false)
            }
          }
      MoveEffect.TRI_ATTACK ->
          if (onTarget && roll()) {
            val s =
                listOf(StatusCondition.BURN, StatusCondition.FREEZE, StatusCondition.PARALYSIS)[
                    rng.pick(3)]
            ctx.trySetStatus(target, s, user, false)
          }
      MoveEffect.FLINCH_HIT,
      MoveEffect.FLINCH_MINIMIZE_HIT,
      MoveEffect.SKY_ATTACK,
      MoveEffect.TWISTER,
      MoveEffect.FAKE_OUT,
      MoveEffect.SNORE -> if (onTarget && roll()) flinch(target)
      MoveEffect.CONFUSE_HIT -> if (onTarget && roll()) ctx.tryConfuse(target, user, false)
      MoveEffect.ATTACK_DOWN_HIT ->
          if (onTarget && roll()) ctx.lowerStat(target, user, BattleStat.ATTACK, 1, false)
      MoveEffect.DEFENSE_DOWN_HIT ->
          if (onTarget && roll()) ctx.lowerStat(target, user, BattleStat.DEFENSE, 1, false)
      MoveEffect.SPEED_DOWN_HIT ->
          if (onTarget && roll()) ctx.lowerStat(target, user, BattleStat.SPEED, 1, false)
      MoveEffect.SPECIAL_ATTACK_DOWN_HIT ->
          if (onTarget && roll()) ctx.lowerStat(target, user, BattleStat.SP_ATTACK, 1, false)
      MoveEffect.SPECIAL_DEFENSE_DOWN_HIT ->
          if (onTarget && roll()) ctx.lowerStat(target, user, BattleStat.SP_DEFENSE, 1, false)
      MoveEffect.ACCURACY_DOWN_HIT ->
          if (onTarget && roll()) ctx.lowerStat(target, user, BattleStat.ACCURACY, 1, false)
      MoveEffect.EVASION_DOWN_HIT ->
          if (onTarget && roll()) ctx.lowerStat(target, user, BattleStat.EVASION, 1, false)
      MoveEffect.ATTACK_UP_HIT ->
          if (!user.fainted && roll()) ctx.raiseStat(user, user, BattleStat.ATTACK, 1, false)
      MoveEffect.DEFENSE_UP_HIT ->
          if (!user.fainted && roll()) ctx.raiseStat(user, user, BattleStat.DEFENSE, 1, false)
      MoveEffect.ALL_STATS_UP_HIT ->
          if (!user.fainted && roll()) {
            for (s in
                listOf(
                    BattleStat.ATTACK,
                    BattleStat.DEFENSE,
                    BattleStat.SPEED,
                    BattleStat.SP_ATTACK,
                    BattleStat.SP_DEFENSE)) {
              ctx.raiseStat(user, user, s, 1, false)
            }
          }
      MoveEffect.SUPERPOWER ->
          if (!user.fainted) {
            ctx.lowerStat(user, user, BattleStat.ATTACK, 1, false, fromFoe = false)
            ctx.lowerStat(user, user, BattleStat.DEFENSE, 1, false, fromFoe = false)
          }
      MoveEffect.OVERHEAT ->
          if (!user.fainted)
              ctx.lowerStat(user, user, BattleStat.SP_ATTACK, 2, false, fromFoe = false)
      MoveEffect.TRAP ->
          if (onTarget && target.volatile.trapTurns == 0) {
            target.volatile.trapTurns = rng.range(2, 5) + 1
            target.volatile.trapMove = move.id
            target.volatile.trappedBy = user.entityId
            ctx.say(trapText(move, user, target), target.entityId)
          }
      MoveEffect.RAGE -> user.volatile.raging = true
      MoveEffect.RAMPAGE ->
          if (user.volatile.rampageMove == 0) {
            user.volatile.rampageMove = move.id
            user.volatile.rampageTurns = rng.range(2, 3)
          }
      MoveEffect.UPROAR ->
          if (user.volatile.uproarTurns == 0) {
            user.volatile.uproarTurns = rng.range(2, 5)
            ctx.say("${ctx.name(user)} caused an UPROAR!", user.entityId)
            for (r in ctx.allRefs()) ctx.live(r)?.let { m ->
              if (m.asleep) ctx.cureStatus(m, "${ctx.name(m)} woke up in the UPROAR!")
            }
          }
      MoveEffect.ROLLOUT -> {
        val v = user.volatile
        v.rolloutTurns = if (v.rolloutTurns == 0) 4 else v.rolloutTurns - 1
      }
      MoveEffect.FURY_CUTTER -> user.volatile.furyCutter += 1
      MoveEffect.RECHARGE -> user.volatile.mustRecharge = true
      MoveEffect.SPIT_UP -> user.volatile.stockpile = 0
      else -> Unit
    }
    // King's Rock adds a flinch to moves that have none of their own.
    if (onTarget &&
        dealt > 0 &&
        move.hasFlag(MoveFlag.KINGS_ROCK_AFFECTED) &&
        move.effect !in FLINCH_EFFECTS) {
      ctx.heldEffect(user)?.let { e ->
        if (e.name == "FLINCH" && rng.percent(e.param)) flinch(target)
      }
    }
  }

  private fun trapText(move: MoveDef, user: BattleMonState, target: BattleMonState): String =
      when (move.id) {
        20 -> "${ctx.name(target)} was squeezed by ${ctx.name(user)}'s BIND!"
        35 -> "${ctx.name(target)} was wrapped by ${ctx.name(user)}!"
        83 -> "${ctx.name(target)} was trapped in the vortex!"
        128 -> "${ctx.name(user)} CLAMPED ${ctx.name(target)}!"
        250 -> "${ctx.name(target)} was trapped in the vortex!"
        328 -> "${ctx.name(target)} was trapped by SAND TOMB!"
        else -> "${ctx.name(target)} was trapped!"
      }

  /** A flinch only matters when the target has not moved yet this turn. */
  private fun flinch(target: BattleMonState) {
    if (target.ability == Ability.INNER_FOCUS || target.volatile.movedThisTurn) return
    target.volatile.flinched = true
  }

  /** After the hit loop: the user's own consequences (Explosion). */
  fun afterAllHits(
      userRef: BattlerRef,
      user: BattleMonState,
      move: MoveDef,
      targets: List<BattlerRef>
  ) {
    if (move.effect == MoveEffect.EXPLOSION && !user.fainted) ctx.setHp(user, 0, "explosion")
  }

  // --- status moves ------------------------------------------------------------------------------

  private fun statusMove(
      userRef: BattlerRef,
      user: BattleMonState,
      targetRef: BattlerRef,
      target: BattleMonState,
      move: MoveDef,
  ): Boolean {
    val who = ctx.name(target)
    val self = targetRef == userRef
    val tv = target.volatile
    if (!self && tv.substituteHp > 0 && move.effect !in SUBSTITUTE_PASSES) {
      return failed(user, move).let { false }
    }
    fun up(stat: BattleStat, n: Int): Boolean {
      anim(user, move, user)
      return ctx.raiseStat(user, user, stat, n, announce = true)
    }
    fun down(stat: BattleStat, n: Int): Boolean {
      anim(user, move, target)
      return ctx.lowerStat(target, user, stat, n, announce = true)
    }
    fun status(bit: Int): Boolean {
      anim(user, move, target)
      // Thunder Wave and Glare respect the type chart (Ground ignores Thunder Wave).
      if (move.effect == MoveEffect.PARALYZE && runner.effectiveness(move.type, target) == 0) {
        ctx.say("It doesn't affect $who...", target.entityId)
        return false
      }
      return ctx.trySetStatus(target, bit, user, announceFail = true)
    }
    return when (move.effect) {
      MoveEffect.ATTACK_UP -> up(BattleStat.ATTACK, 1)
      MoveEffect.DEFENSE_UP -> up(BattleStat.DEFENSE, 1)
      MoveEffect.SPEED_UP -> up(BattleStat.SPEED, 1)
      MoveEffect.SPECIAL_ATTACK_UP -> up(BattleStat.SP_ATTACK, 1)
      MoveEffect.SPECIAL_DEFENSE_UP -> up(BattleStat.SP_DEFENSE, 1)
      MoveEffect.ACCURACY_UP -> up(BattleStat.ACCURACY, 1)
      MoveEffect.EVASION_UP -> up(BattleStat.EVASION, 1)
      MoveEffect.ATTACK_UP_2 -> up(BattleStat.ATTACK, 2)
      MoveEffect.DEFENSE_UP_2 -> up(BattleStat.DEFENSE, 2)
      MoveEffect.SPEED_UP_2 -> up(BattleStat.SPEED, 2)
      MoveEffect.SPECIAL_ATTACK_UP_2 -> up(BattleStat.SP_ATTACK, 2)
      MoveEffect.SPECIAL_DEFENSE_UP_2 -> up(BattleStat.SP_DEFENSE, 2)
      MoveEffect.ACCURACY_UP_2 -> up(BattleStat.ACCURACY, 2)
      MoveEffect.EVASION_UP_2 -> up(BattleStat.EVASION, 2)
      MoveEffect.ATTACK_DOWN -> down(BattleStat.ATTACK, 1)
      MoveEffect.DEFENSE_DOWN -> down(BattleStat.DEFENSE, 1)
      MoveEffect.SPEED_DOWN -> down(BattleStat.SPEED, 1)
      MoveEffect.SPECIAL_ATTACK_DOWN -> down(BattleStat.SP_ATTACK, 1)
      MoveEffect.SPECIAL_DEFENSE_DOWN -> down(BattleStat.SP_DEFENSE, 1)
      MoveEffect.ACCURACY_DOWN -> down(BattleStat.ACCURACY, 1)
      MoveEffect.EVASION_DOWN -> down(BattleStat.EVASION, 1)
      MoveEffect.ATTACK_DOWN_2 -> down(BattleStat.ATTACK, 2)
      MoveEffect.DEFENSE_DOWN_2 -> down(BattleStat.DEFENSE, 2)
      MoveEffect.SPEED_DOWN_2 -> down(BattleStat.SPEED, 2)
      MoveEffect.SPECIAL_ATTACK_DOWN_2 -> down(BattleStat.SP_ATTACK, 2)
      MoveEffect.SPECIAL_DEFENSE_DOWN_2 -> down(BattleStat.SP_DEFENSE, 2)
      MoveEffect.ACCURACY_DOWN_2 -> down(BattleStat.ACCURACY, 2)
      MoveEffect.EVASION_DOWN_2 -> down(BattleStat.EVASION, 2)
      MoveEffect.TICKLE -> {
        anim(user, move, target)
        val a = ctx.lowerStat(target, user, BattleStat.ATTACK, 1, true)
        val d = ctx.lowerStat(target, user, BattleStat.DEFENSE, 1, true)
        a || d
      }
      MoveEffect.COSMIC_POWER ->
          up(BattleStat.DEFENSE, 1).also {
            ctx.raiseStat(user, user, BattleStat.SP_DEFENSE, 1, true)
          }
      MoveEffect.BULK_UP ->
          up(BattleStat.ATTACK, 1).also { ctx.raiseStat(user, user, BattleStat.DEFENSE, 1, true) }
      MoveEffect.CALM_MIND ->
          up(BattleStat.SP_ATTACK, 1).also {
            ctx.raiseStat(user, user, BattleStat.SP_DEFENSE, 1, true)
          }
      MoveEffect.DRAGON_DANCE ->
          up(BattleStat.ATTACK, 1).also { ctx.raiseStat(user, user, BattleStat.SPEED, 1, true) }
      MoveEffect.CURSE -> {
        anim(user, move, target)
        if (user.hasType(PokemonType.GHOST)) {
          val foe =
              ctx.foesOf(userRef).firstNotNullOfOrNull { ctx.live(it) }
                  ?: return failed(user, move).let { false }
          if (foe.volatile.cursed) return failed(user, move).let { false }
          ctx.hurt(user, user.maxHp / 2, "curse")
          foe.volatile.cursed = true
          ctx.say(
              "${ctx.name(user)} cut its own HP and laid a CURSE on ${ctx.name(foe)}!",
              foe.entityId)
          true
        } else {
          ctx.lowerStat(user, user, BattleStat.SPEED, 1, true, fromFoe = false)
          ctx.raiseStat(user, user, BattleStat.ATTACK, 1, true)
          ctx.raiseStat(user, user, BattleStat.DEFENSE, 1, true)
          true
        }
      }
      MoveEffect.SLEEP -> status(StatusCondition.SLEEP_MASK)
      MoveEffect.POISON -> status(StatusCondition.POISON)
      MoveEffect.TOXIC -> status(StatusCondition.TOXIC)
      MoveEffect.PARALYZE -> status(StatusCondition.PARALYSIS)
      MoveEffect.WILL_O_WISP -> status(StatusCondition.BURN)
      MoveEffect.CONFUSE,
      MoveEffect.TEETER_DANCE -> {
        anim(user, move, target)
        ctx.tryConfuse(target, user, announceFail = true)
      }
      MoveEffect.SWAGGER -> {
        anim(user, move, target)
        ctx.raiseStat(target, user, BattleStat.ATTACK, 2, true)
        ctx.tryConfuse(target, user, announceFail = true)
        true
      }
      MoveEffect.FLATTER -> {
        anim(user, move, target)
        ctx.raiseStat(target, user, BattleStat.SP_ATTACK, 1, true)
        ctx.tryConfuse(target, user, announceFail = true)
        true
      }
      MoveEffect.ATTRACT -> {
        anim(user, move, target)
        if (!genderOpposite(user, target) || tv.attractedTo != 0L)
            return failed(user, move).let { false }
        if (target.ability == Ability.OBLIVIOUS) {
          ctx.say("$who's OBLIVIOUS prevents romance!", target.entityId)
          return false
        }
        tv.attractedTo = user.entityId
        ctx.say("$who fell in love!", target.entityId)
        ctx.mentalHerb(target)
        true
      }
      MoveEffect.LEECH_SEED -> {
        anim(user, move, target)
        if (target.hasType(PokemonType.GRASS)) {
          ctx.say("It doesn't affect $who...", target.entityId)
          return false
        }
        if (tv.seededBy != 0L) {
          ctx.say("$who evaded the attack!", target.entityId)
          return false
        }
        tv.seededBy = user.entityId
        ctx.emit(BattleEvent.VolatileChanged(target.entityId, "seeded", true))
        ctx.say("$who was seeded!", target.entityId)
        true
      }
      MoveEffect.DISABLE -> {
        val last = tv.lastMove
        if (last == 0 ||
            tv.disabledMove != 0 ||
            target.moves.none { it.id.toInt() == last && it.pp > 0 }) {
          return failed(user, move).let { false }
        }
        anim(user, move, target)
        tv.disabledMove = last
        tv.disableTurns = rng.range(2, 5)
        ctx.say("$who's ${ctx.move(last)?.name} was disabled!", target.entityId)
        true
      }
      MoveEffect.ENCORE -> {
        val last = tv.lastMove
        if (last == 0 ||
            tv.encoreMove != 0 ||
            last in setOf(move.id, 119, 144, 166) ||
            target.moves.none { it.id.toInt() == last && it.pp > 0 })
            return failed(user, move).let { false }
        anim(user, move, target)
        tv.encoreMove = last
        tv.encoreTurns = rng.range(3, 6)
        ctx.say("$who received an ENCORE!", target.entityId)
        true
      }
      MoveEffect.TAUNT -> {
        if (tv.tauntTurns > 0) return failed(user, move).let { false }
        anim(user, move, target)
        tv.tauntTurns = 2
        ctx.say("$who fell for the TAUNT!", target.entityId)
        true
      }
      MoveEffect.YAWN -> {
        if (tv.yawnTurns > 0 || target.hasStatus) return failed(user, move).let { false }
        if (ctx.statusBlock(target, StatusCondition.SLEEP_MASK, user) != null)
            return failed(user, move).let { false }
        anim(user, move, target)
        tv.yawnTurns = 2
        ctx.say("${ctx.name(user)} made $who drowsy!", target.entityId)
        true
      }
      MoveEffect.FORESIGHT -> {
        anim(user, move, target)
        tv.foresight = true
        target.setStage(BattleStat.EVASION, target.stage(BattleStat.EVASION).coerceAtMost(0))
        ctx.say("${ctx.name(user)} identified $who!", target.entityId)
        true
      }
      MoveEffect.LOCK_ON -> {
        anim(user, move, target)
        user.volatile.lockOnTarget = target.entityId
        user.volatile.lockOnTurns = 2
        ctx.say("${ctx.name(user)} took aim at $who!", target.entityId)
        true
      }
      MoveEffect.MEAN_LOOK -> {
        if (tv.cantEscape) return failed(user, move).let { false }
        anim(user, move, target)
        tv.cantEscape = true
        tv.trappedBy = user.entityId
        ctx.say("$who can't escape now!", target.entityId)
        true
      }
      MoveEffect.NIGHTMARE -> {
        if (!target.asleep || tv.nightmare) return failed(user, move).let { false }
        anim(user, move, target)
        tv.nightmare = true
        ctx.say("$who began having a NIGHTMARE!", target.entityId)
        true
      }
      MoveEffect.PAIN_SPLIT -> {
        anim(user, move, target)
        val avg = (user.currentHp + target.currentHp) / 2
        ctx.setHp(user, avg, "painsplit")
        ctx.setHp(target, avg, "painsplit")
        ctx.say("The battlers shared their pain!")
        true
      }
      MoveEffect.SPITE -> {
        val slot = target.moves.indexOfFirst { it.id.toInt() == tv.lastMove && it.pp > 0 }
        if (slot < 0) return failed(user, move).let { false }
        anim(user, move, target)
        val cut = rng.range(2, 5).coerceAtMost(target.moves[slot].pp.toInt())
        target.moves[slot].pp = (target.moves[slot].pp - cut).toByte()
        ctx.say("Reduced $who's ${ctx.move(tv.lastMove)?.name} by $cut!", target.entityId)
        true
      }
      MoveEffect.MEMENTO -> {
        anim(user, move, target)
        ctx.lowerStat(target, user, BattleStat.ATTACK, 2, true)
        ctx.lowerStat(target, user, BattleStat.SP_ATTACK, 2, true)
        ctx.setHp(user, 0, "memento")
        true
      }
      MoveEffect.PSYCH_UP -> {
        anim(user, move, target)
        BattleStat.entries.forEach { user.setStage(it, target.stage(it)) }
        ctx.say("${ctx.name(user)} copied $who's stat changes!", user.entityId)
        true
      }
      MoveEffect.TRANSFORM -> {
        if (user.volatile.transformed || tv.transformed) return failed(user, move).let { false }
        anim(user, move, target)
        user.volatile.transformed = true
        user.types = target.types.toMutableList()
        BattleStat.entries.forEach { user.setStage(it, target.stage(it)) }
        user.moves.clear()
        user.moves.addAll(target.moves.map { de.fiereu.openmmo.common.PokemonMove(it.id, 5) })
        ctx.say("${ctx.name(user)} transformed into ${target.species.name}!", user.entityId)
        true
      }
      MoveEffect.ROAR -> {
        anim(user, move, target)
        if (target.ability == Ability.SUCTION_CUPS) {
          ctx.say("$who anchors itself with SUCTION CUPS!", target.entityId)
          return false
        }
        if (tv.ingrained) {
          ctx.say("$who anchored itself with its roots!", target.entityId)
          return false
        }
        if (battle.trainer == null && battle.pvp == null) {
          // Against a wild monster Roar and Whirlwind end the battle.
          if (user.level < target.level) return failed(user, move).let { false }
          ctx.emit(BattleEvent.Fled(targetRef.side))
          return true
        }
        val party = ctx.party(targetRef.side)
        val actives = ctx.sideState(targetRef.side).actives
        val bench = party.indices.filter { it !in actives && !party[it].fainted }
        if (bench.isEmpty()) return failed(user, move).let { false }
        runner.switchTo(targetRef, bench[rng.pick(bench.size)])
        ctx.mon(targetRef)?.let { ctx.say("${ctx.name(it)} was dragged out!", it.entityId) }
        true
      }
      MoveEffect.SKILL_SWAP -> {
        anim(user, move, target)
        val a = user.ability
        user.ability = target.ability
        target.ability = a
        ctx.say("${ctx.name(user)} swapped abilities with its target!", user.entityId)
        true
      }
      MoveEffect.ROLE_PLAY -> {
        anim(user, move, target)
        user.ability = target.ability
        ctx.say("${ctx.name(user)} copied $who's ${abilityName(target.ability)}!", user.entityId)
        true
      }
      MoveEffect.TRICK -> {
        if (user.heldItem == 0 && target.heldItem == 0 || target.ability == Ability.STICKY_HOLD) {
          return failed(user, move).let { false }
        }
        anim(user, move, target)
        val a = user.heldItem
        user.heldItem = target.heldItem
        target.heldItem = a
        ctx.say("${ctx.name(user)} switched items with its opponent!", user.entityId)
        true
      }
      MoveEffect.CONVERSION_2 -> {
        val last = ctx.move(tv.lastMove) ?: return failed(user, move).let { false }
        val resist =
            PokemonType.entries.filter {
              it != PokemonType.NONE &&
                  it != PokemonType.QUESTIONQUESTIONQUESTION &&
                  ctx.typeChart.multiplier(last.type, it) < TypeChart.NEUTRAL &&
                  !user.hasType(it)
            }
        if (resist.isEmpty()) return failed(user, move).let { false }
        anim(user, move)
        val t = resist[rng.pick(resist.size)]
        user.types = mutableListOf(t, t)
        ctx.say("${ctx.name(user)} transformed into the ${t.name} type!", user.entityId)
        true
      }
      MoveEffect.SKETCH -> {
        // Cmd_copymovepermanently: the target's last move replaces SKETCH for good.
        val learned = tv.lastMove
        val sketchSlot = user.moves.indexOfFirst { it.id.toInt() == move.id }
        val def = ctx.move(learned)
        if (user.volatile.transformed ||
            learned == 0 ||
            learned == STRUGGLE_ID ||
            learned == move.id ||
            sketchSlot < 0 ||
            def == null ||
            user.moves.any { it.id.toInt() == learned }) {
          return failed(user, move).let { false }
        }
        anim(user, move, target)
        user.moves[sketchSlot] = PokemonMove(learned.toShort(), def.pp.toByte())
        ctx.say("${ctx.name(user)} sketched ${def.name}!", user.entityId)
        true
      }
      MoveEffect.MAGIC_COAT -> {
        anim(user, move)
        user.volatile.magicCoat = true
        ctx.say("${ctx.name(user)} shrouded itself with MAGIC COAT!", user.entityId)
        true
      }
      MoveEffect.SNATCH -> {
        anim(user, move)
        user.volatile.snatching = true
        ctx.say("${ctx.name(user)} waits for a target to make a move!", user.entityId)
        true
      }
      MoveEffect.DESTINY_BOND -> {
        anim(user, move)
        user.volatile.destinyBond = true
        ctx.say("${ctx.name(user)} is trying to take its foe with it!", user.entityId)
        true
      }
      else -> {
        failed(user, move)
        false
      }
    }
  }

  private companion object {
    val FLINCH_EFFECTS =
        setOf(
            MoveEffect.FLINCH_HIT,
            MoveEffect.FLINCH_MINIMIZE_HIT,
            MoveEffect.SKY_ATTACK,
            MoveEffect.TWISTER,
            MoveEffect.FAKE_OUT,
            MoveEffect.SNORE)
    /** Moves that ignore a substitute in Gen 3. */
    val SUBSTITUTE_PASSES =
        setOf(
            MoveEffect.SKETCH,
            MoveEffect.ROAR,
            MoveEffect.ATTRACT,
            MoveEffect.DISABLE,
            MoveEffect.ENCORE,
            MoveEffect.TAUNT,
            MoveEffect.FORESIGHT,
            MoveEffect.SPITE,
            MoveEffect.PSYCH_UP,
            MoveEffect.ROLE_PLAY,
            MoveEffect.SKILL_SWAP,
            MoveEffect.TRANSFORM)
    val METRONOME_BANNED =
        setOf(
            102,
            118,
            119,
            164,
            165,
            166,
            168,
            182,
            194,
            197,
            203,
            214,
            264,
            266,
            267,
            270,
            271,
            274,
            289,
            343)
    val SLEEP_TALK_BANNED =
        setOf(13, 19, 76, 91, 117, 118, 119, 130, 143, 214, 253, 264, 274, 291, 340)
  }
}

/** sWeightToDamageTable: below 10/25/50/100/200 kg 20/40/60/80/100 power, else 120. */
internal fun lowKickPower(hectograms: Int): Int =
    when {
      hectograms < 100 -> 20
      hectograms < 250 -> 40
      hectograms < 500 -> 60
      hectograms < 1000 -> 80
      hectograms < 2000 -> 100
      else -> 120
    }
