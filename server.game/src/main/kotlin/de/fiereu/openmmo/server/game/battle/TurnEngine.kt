package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.typechart.TypeChart
import javax.inject.Inject
import javax.inject.Singleton

internal const val TACKLE_ID = 33
internal const val STRUGGLE_ID = 165

/**
 * The Gen 3 battle engine (src/battle_main.c turn order, src/battle_script_commands.c move effects,
 * src/battle_util.c end of turn). One call resolves a whole turn for every battle position of both
 * sides: switches first, then moves by priority and speed, then the end of turn effects. Single and
 * double battles run through the same code; a position is a [BattlerRef].
 *
 * The battle is always seen from one player's view (side 0 = that player's party). A
 * player-vs-player duel calls it on one of the two linked views with both players' choices.
 */
@Singleton
class TurnEngine
@Inject
constructor(
    private val moves: MoveRegistry,
    private val typeChart: TypeChart,
    private val items: ItemRegistry,
) {
  /** Tests and older callers built the engine from moves and the type chart only. */
  constructor(moves: MoveRegistry, typeChart: TypeChart) : this(moves, typeChart, ItemRegistry())

  private val held = HeldItems(items)

  private fun ctx(battle: BattleInstance) = BattleCtx(battle, moves, typeChart, held)

  /** Resolves one full turn. Missing choices for side 1 are filled in by the AI. */
  fun resolve(battle: BattleInstance, choices: List<BattleChoice>): List<BattleEvent> {
    val ctx = ctx(battle)
    val all = choices.toMutableList()
    for (pos in battle.foe.actives.indices) {
      if (all.none { it.side == 1 && it.pos == pos } && ctx.live(BattlerRef(1, pos)) != null) {
        all += BattleAi.choose(ctx, BattlerRef(1, pos))
      }
    }
    MoveRunner(ctx).runTurn(all)
    return ctx.events
  }

  /** The single-battle turn: the player's move against the AI's (or a human rival's) move. */
  fun resolveTurn(
      battle: BattleInstance,
      playerMoveId: Short,
      opponentMoveId: Short? = null,
  ): List<BattleEvent> =
      resolve(
          battle,
          listOfNotNull(
              BattleChoice.UseMove(0, 0, playerMoveId.toInt()),
              opponentMoveId?.let { BattleChoice.UseMove(1, 0, it.toInt()) }))

  /** The player already switched (or used an item), so only the opposing side acts. */
  fun resolveSwitchTurn(battle: BattleInstance, opponentMoveId: Short? = null): List<BattleEvent> =
      resolve(
          battle,
          listOfNotNull(
              BattleChoice.Pass(0, 0),
              opponentMoveId?.let { BattleChoice.UseMove(1, 0, it.toInt()) }))

  /** What the AI would pick for every opposing position. */
  fun aiChoices(battle: BattleInstance, side: Int = 1): List<BattleChoice> {
    val ctx = ctx(battle)
    return ctx.sideState(side).actives.indices.mapNotNull { pos ->
      val ref = BattlerRef(side, pos)
      ctx.live(ref)?.let { BattleAi.choose(ctx, ref) }
    }
  }

  /** The entry effects of the monsters that start the battle, fastest first. */
  fun startOfBattle(battle: BattleInstance): List<BattleEvent> {
    val ctx = ctx(battle)
    for ((ref, _) in ctx.liveBySpeed()) ctx.onEntry(ref)
    return ctx.events
  }

  /**
   * Brings party slot [slot] out at [pos] on [side] between turns (a replacement for a fainted
   * monster) and runs its entry effects.
   */
  fun switchIn(battle: BattleInstance, side: Int, pos: Int, slot: Int): List<BattleEvent> {
    val ctx = ctx(battle)
    MoveRunner(ctx).switchTo(BattlerRef(side, pos), slot)
    return ctx.events
  }

  /**
   * The Gen 3 escape roll (src/battle_main.c TryRunFromBattle): a faster monster always gets away,
   * otherwise speed ratio plus 30 per earlier attempt out of 256. Smoke Ball and Run Away always
   * work; trapping (Mean Look, Wrap, Shadow Tag, Arena Trap, Magnet Pull) never does.
   */
  fun tryEscape(battle: BattleInstance): Pair<Boolean, List<BattleEvent>> {
    val ctx = ctx(battle)
    val me =
        battle.activeMons().firstOrNull { it != null && !it.fainted } ?: return true to emptyList()
    val foe =
        battle.opponentMons().firstOrNull { it != null && !it.fainted }
            ?: return true to emptyList()
    if (ctx.heldEffect(me)?.name == "CAN_ALWAYS_RUN") {
      ctx.say("Got away safely!")
      return true to ctx.events
    }
    if (me.ability == Ability.RUN_AWAY) return true to ctx.events
    trappedBy(ctx, me)?.let {
      ctx.say(it)
      return false to ctx.events
    }
    val mySpeed = ctx.speedOf(me)
    val foeSpeed = ctx.speedOf(foe).coerceAtLeast(1)
    battle.escapeAttempts += 1
    if (mySpeed > foeSpeed) return true to ctx.events
    val odds = (mySpeed * 128 / foeSpeed + 30 * (battle.escapeAttempts - 1)) and 0xFF
    val escaped = battle.rng.pick(256) < odds
    if (!escaped) ctx.say("Can't escape!")
    return escaped to ctx.events
  }

  /** Why [mon] cannot switch out or flee, or null. */
  fun switchBlock(battle: BattleInstance, mon: BattleMonState): String? =
      trappedBy(ctx(battle), mon)

  private fun trappedBy(ctx: BattleCtx, mon: BattleMonState): String? {
    if (ctx.heldEffect(mon)?.name == "CAN_ALWAYS_RUN") return null
    val v = mon.volatile
    if (v.cantEscape || v.trapTurns > 0 || v.ingrained) return "${ctx.name(mon)} can't escape!"
    val myRef = ctx.refOf(mon) ?: return null
    for (foeRef in ctx.foesOf(myRef)) {
      val foe = ctx.live(foeRef) ?: continue
      when (foe.ability) {
        Ability.SHADOW_TAG ->
            if (mon.ability != Ability.SHADOW_TAG) return "${ctx.name(mon)} can't escape!"
        Ability.ARENA_TRAP ->
            if (!mon.hasType(de.fiereu.openmmo.common.enums.PokemonType.FLYING) &&
                mon.ability != Ability.LEVITATE)
                return "${ctx.name(mon)} can't escape!"
        Ability.MAGNET_PULL ->
            if (mon.hasType(de.fiereu.openmmo.common.enums.PokemonType.STEEL))
                return "${ctx.name(mon)} can't escape!"
        else -> Unit
      }
    }
    return null
  }

  internal fun context(battle: BattleInstance): BattleCtx = ctx(battle)

  /**
   * Uses a bag item from the player's side on [target] (a party monster, or the active one for X
   * items). Returns whether it had an effect, with the events. Balls are not handled here.
   */
  fun useBagItem(
      battle: BattleInstance,
      itemName: String,
      effect: BattleItemEffect,
      target: BattleMonState,
      moveSlot: Int = -1,
      side: Int = 0,
      user: String = "You",
  ): Pair<Boolean, List<BattleEvent>> {
    val ctx = ctx(battle)
    val who = ctx.name(target)
    val upper = itemName.uppercase()
    val worked =
        when (effect) {
          is BattleItemEffect.Heal -> {
            if (target.fainted ||
                target.currentHp >= target.maxHp &&
                    !(effect.cures && (target.hasStatus || target.confused)))
                false
            else {
              ctx.say("$user used $upper!")
              val gained = ctx.heal(target, effect.hp, "item")
              if (effect.cures) {
                ctx.cureStatus(target, null)
                if (target.confused) {
                  target.volatile.confusionTurns = 0
                  ctx.emit(BattleEvent.VolatileChanged(target.entityId, "confused", false))
                }
              }
              if (gained > 0)
                  ctx.say("$who's HP was restored by $gained point(s).", target.entityId)
              true
            }
          }
          is BattleItemEffect.Revive -> {
            if (!target.fainted) false
            else {
              ctx.say("$user used $upper!")
              target.currentHp = 0
              ctx.setHp(
                  target,
                  (target.maxHp * effect.fraction).toInt().coerceIn(1, target.maxHp),
                  "revive")
              ctx.say("$who was revived!", target.entityId)
              true
            }
          }
          is BattleItemEffect.Cure -> {
            val hasBit = target.status and effect.bits != 0
            val conf = effect.confusion && target.confused
            if (target.fainted || !hasBit && !conf) false
            else {
              ctx.say("$user used $upper!")
              if (hasBit)
                  ctx.cureStatus(target, "$who was cured of its ${statusName(target.status)}!")
              if (conf) {
                target.volatile.confusionTurns = 0
                ctx.emit(BattleEvent.VolatileChanged(target.entityId, "confused", false))
                ctx.say("$who snapped out of confusion!", target.entityId)
              }
              true
            }
          }
          is BattleItemEffect.RestorePp -> {
            val slots = if (effect.allMoves) target.moves.indices.toList() else listOf(moveSlot)
            var any = false
            for (s in slots) {
              val m = target.moves.getOrNull(s) ?: continue
              if (m.id.toInt() == 0) continue
              val max = moves.get(m.id.toInt())?.pp ?: continue
              if (m.pp >= max) continue
              m.pp = (m.pp + effect.amount.toLong()).coerceAtMost(max.toLong()).toInt().toByte()
              any = true
            }
            if (any) {
              ctx.say("$user used $upper!")
              ctx.say("$who's PP was restored.", target.entityId)
            }
            any
          }
          is BattleItemEffect.StatUp -> {
            if (target.fainted || target.stage(effect.stat) >= StatStages.MAX) false
            else {
              ctx.say("$user used $upper!")
              ctx.raiseStat(target, target, effect.stat, 1, announce = false)
              true
            }
          }
          BattleItemEffect.DireHit -> {
            if (target.volatile.focusEnergy) false
            else {
              ctx.say("$user used $upper!")
              target.volatile.focusEnergy = true
              ctx.say("$who is getting pumped!", target.entityId)
              true
            }
          }
          BattleItemEffect.GuardSpec -> {
            val s = ctx.sideState(side)
            if (s.mistTurns > 0) false
            else {
              ctx.say("$user used $upper!")
              s.mistTurns = 5
              ctx.say("Your team became shrouded in MIST!")
              true
            }
          }
          BattleItemEffect.Escape,
          BattleItemEffect.Ball -> false
        }
    return worked to ctx.events
  }
}
