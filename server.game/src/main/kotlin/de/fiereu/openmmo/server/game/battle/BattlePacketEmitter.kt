package de.fiereu.openmmo.server.game.battle

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.utils.hexToBytes
import de.fiereu.openmmo.net.game.packets.EntityMovePpPacket
import de.fiereu.openmmo.net.game.packets.EntityPresencePacket
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleActionEvent
import de.fiereu.openmmo.net.game.packets.battle.BattleBulkStatePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleEffectTarget
import de.fiereu.openmmo.net.game.packets.battle.BattleEntityDeltaPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleEntityMoveEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleEventBody
import de.fiereu.openmmo.net.game.packets.battle.BattleFieldStatePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleOpponentBlock
import de.fiereu.openmmo.net.game.packets.battle.BattleQueuedEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSidePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSlotEventEnumPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSlotFlagEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleStatCountersPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSwitchInPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleTileMapPacket
import de.fiereu.openmmo.net.game.packets.battle.Experience
import de.fiereu.openmmo.net.game.packets.battle.MoveSlots
import de.fiereu.openmmo.net.game.packets.battle.OpposingSide
import de.fiereu.openmmo.server.game.services.notice
import de.fiereu.openmmo.server.game.world.interest.InterestManager
import de.fiereu.openmmo.typechart.TypeChart
import de.fiereu.openmmo.common.StatusCondition
import de.fiereu.openmmo.net.game.packets.battle.BattleExtPacket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val ACTION_PROMPT: Byte = -128 // 0x80
private const val MOVE_EVENT_KIND: Byte = 1
// Slot-event type shown when the player gets away from a wild battle.
private const val FLED_EVENT: Byte = 0

// The player's overworld entity is hidden while the battle scene is up and shown again when it
// ends.
private const val PRESENCE_IN_BATTLE: Byte = 1
private const val PRESENCE_OVERWORLD: Byte = 0

// The active battle side reported to the client so the bag knows which monster an item targets.
private const val PLAYER_SIDE: Byte = 1

// The side byte a switch-in carries, which is not the same numbering as BattleSidePacket.
private const val OPPONENT_SIDE: Byte = 1

private val CAPTURED_APPEARANCE = "00024c031aac0f00038001a40004".hexToBytes()

// The target's outcome word, which picks the line the client prints for that target. A damaging
// hit is 0x0200, a miss 1, a failure 4, and a status move that only moves a stat carries none of
// them. The events under the target are read either way.
private const val HP_TARGET_MOVE: Short = 0x0200
private const val MISSED_TARGET_MOVE: Short = 1
private const val FAILED_TARGET_MOVE: Short = 4
private const val DEFAULT_TARGET_MOVE: Short = 0
private const val SUPER_EFFECTIVE_BIT = 0x20
private const val NOT_VERY_EFFECTIVE_BIT = 0x10

/**
 * Turns battle state and [BattleEvent]s into packets. Everything battle wide goes through the
 * battle's interest key, so spectators and later trainer opponents receive it too. Packets tied to
 * one viewer (side, bag, party sync) go to that session directly.
 */
@Singleton
class BattlePacketEmitter @Inject constructor(private val interestManager: InterestManager) {

  fun sendStart(battle: BattleInstance, playerName: String, prompt: Boolean = true) {
    battle.session.send(EntityPresencePacket(entityId = battle.charId, status = PRESENCE_IN_BATTLE))
    // Tell the client which side is local so the battle bag knows which monster an item targets.
    // Without it, opening the bag crashes. Opcode 0x40 is left alone here, since re-sending it
    // would wipe the balls out of the battle bag.
    battle.session.send(BattleSidePacket(side = PLAYER_SIDE))
    broadcast(
        battle,
        BattleFieldStatePacket(
            playerName = playerName,
            playerId = battle.charId,
            // TODO Send the player's own appearance and the map's battle backdrop
            //  These are the captured values, so every player appears as the captured character.
            playerAppearance = CAPTURED_APPEARANCE,
            background = 0,
            opposing =
                if (battle.trainer == null && battle.pvp == null) OpposingSide.WILD
                else OpposingSide.TRAINER,
            // TODO Check whether Hoenn needs a region tag, both decomps number trainers from 1
            trainerId = (battle.trainer?.id ?: 0).toShort(),
            playerParty = battle.party.mapIndexed { slot, mon -> mon.toBlock(slot, true) },
            activeSlot = battle.activeSlot,
            opponentParty =
                battle.opponent.mapIndexed { slot, mon ->
                  if (slot in battle.opponentSeen) mon.toOpponentBlock(slot)
                  else BattleOpponentBlock(slot = slot, revealed = false)
                },
            opponentActiveSlot = battle.opponentSlot,
        ),
    )
    sendState(battle)
    if (prompt) sendPrompt(battle)
  }

  /**
   * Sends a turn's events in order. A move and the target outcomes right behind it become one
   * retail move packet; everything the retail packets cannot carry (status, messages, residual hp,
   * abilities, items, weather, switches by position) goes out on the extension channel, batched
   * between the retail packets so the client sees one ordered stream.
   */
  fun sendEvents(battle: BattleInstance, events: List<BattleEvent>) {
    if (events.isEmpty()) return
    val ext = mutableListOf<JsonObject>()
    fun flush() {
      if (ext.isEmpty()) return
      broadcast(battle, BattleExtPacket(buildJsonObject {
        put("t", "events")
        put("e", JsonArray(ext.toList()))
      }.toString()))
      ext.clear()
    }
    var i = 0
    while (i < events.size) {
      when (val event = events[i]) {
        is BattleEvent.MoveUsed -> {
          flush()
          if (battle.isPlayerSide(event.attackerId)) {
            battle.session.send(
                EntityMovePpPacket(event.attackerId, event.moveSlot.toByte(), event.ppLeft.toByte()))
          }
          val byTarget = LinkedHashMap<Long, Pair<Int, MutableList<BattleActionEvent>>>()
          var j = i + 1
          group@ while (j < events.size) {
            when (val next = events[j]) {
              is BattleEvent.Animation -> Unit
              is BattleEvent.DamageDealt -> {
                val prev = byTarget[next.targetId]
                val outcome = HP_TARGET_MOVE.toInt() or effectivenessBit(next.effectiveness)
                val list = prev?.second ?: mutableListOf()
                list += BattleActionEvent(null, null, BattleEventBody.HpUpdate(next.newHp.toShort()))
                byTarget[next.targetId] = (prev?.first?.let { it or outcome } ?: outcome) to list
              }
              is BattleEvent.StageChanged -> {
                if (!next.failed) {
                  val prev = byTarget[next.targetId]
                  val list = prev?.second ?: mutableListOf()
                  list +=
                      BattleActionEvent(
                          null, null,
                          BattleEventBody.StatChange(statIndex(next.stat), next.delta.toShort()))
                  byTarget[next.targetId] = (prev?.first ?: DEFAULT_TARGET_MOVE.toInt()) to list
                }
              }
              is BattleEvent.MoveWithoutTarget -> {
                val defender = defenderOf(battle, event.attackerId, next)
                val word =
                    if (next is BattleEvent.MoveMissed) MISSED_TARGET_MOVE else FAILED_TARGET_MOVE
                if (defender !in byTarget) byTarget[defender] = word.toInt() to mutableListOf()
              }
              else -> break@group
            }
            j++
          }
          i = j - 1
          val targets =
              byTarget.map { (id, v) -> BattleEffectTarget(id, v.first.toShort(), v.second.toList()) }
          broadcast(
              battle,
              BattleEntityMoveEventPacket(event.attackerId, event.moveId, MOVE_EVENT_KIND, targets))
        }
        is BattleEvent.Switched -> {
          flush()
          sendSwitched(battle, event)
        }
        is BattleEvent.Animation -> Unit
        is BattleEvent.Fainted -> Unit
        else -> extEvent(battle, event)?.let { ext += it }
      }
      i++
    }
    flush()
    sendState(battle)
  }

  private fun defenderOf(
      battle: BattleInstance,
      attackerId: Long,
      event: BattleEvent.MoveWithoutTarget,
  ): Long {
    val explicit =
        when (event) {
          is BattleEvent.MoveMissed -> event.targetId
          is BattleEvent.MoveFailed -> event.targetId
        }
    if (explicit != 0L) return explicit
    return if (battle.isPlayerSide(attackerId)) battle.opponentMon().entityId
    else battle.activeMon().entityId
  }

  /** One extension event as JSON, or null when the retail packets already said it. */
  private fun extEvent(battle: BattleInstance, event: BattleEvent): JsonObject? =
      when (event) {
        is BattleEvent.Message -> buildJsonObject {
          put("k", "text")
          put("text", event.text)
          if (event.targetId != 0L) put("id", event.targetId)
        }
        is BattleEvent.HpChanged -> buildJsonObject {
          put("k", "hp")
          put("id", event.targetId)
          put("hp", event.newHp)
          put("reason", event.reason)
        }
        is BattleEvent.DamageDealt -> buildJsonObject {
          put("k", "hp")
          put("id", event.targetId)
          put("hp", event.newHp)
          put("reason", "move")
          put("eff", event.effectiveness)
        }
        is BattleEvent.StatusChanged -> buildJsonObject {
          put("k", "status")
          put("id", event.targetId)
          put("status", event.code)
          put("raw", event.status)
        }
        is BattleEvent.VolatileChanged -> buildJsonObject {
          put("k", "volatile")
          put("id", event.targetId)
          put("kind", event.kind)
          put("on", event.on)
        }
        is BattleEvent.AbilityShown -> buildJsonObject {
          put("k", "ability")
          put("id", event.holderId)
          put("ability", event.ability)
        }
        is BattleEvent.ItemActivated -> buildJsonObject {
          put("k", "item")
          put("id", event.holderId)
          put("item", itemName(event.itemId))
          put("consumed", event.consumed)
        }
        is BattleEvent.WeatherChanged -> buildJsonObject {
          put("k", "weather")
          put("weather", event.weather.code)
        }
        is BattleEvent.StageChanged ->
            if (event.failed) null
            else buildJsonObject {
              put("k", "stat")
              put("id", event.targetId)
              put("stat", statIndex(event.stat).toInt())
              put("delta", event.delta)
            }
        is BattleEvent.MoveMissed -> buildJsonObject {
          put("k", "miss")
          put("id", if (event.targetId != 0L) event.targetId else defenderOf(battle, event.attackerId, event))
        }
        is BattleEvent.MoveFailed -> buildJsonObject {
          put("k", "text")
          put("text", "But it failed!")
        }
        is BattleEvent.Fled -> buildJsonObject {
          put("k", "fled")
          put("side", event.side)
        }
        else -> null
      }

  private fun itemName(itemId: Int): String = itemNames(itemId)

  /** Wire item id to a display name; set by the service that owns the item catalogue. */
  var itemNames: (Int) -> String = { "item" }

  /** A switch the engine made, shown with the viewer's own numbering. */
  private fun sendSwitched(battle: BattleInstance, event: BattleEvent.Switched) {
    val own = battle.party.getOrNull(event.newSlot)?.entityId == event.entityId &&
        battle.isPlayerSide(event.entityId)
    val pos = event.pos
    broadcast(battle, BattleExtPacket(buildJsonObject {
      put("t", "events")
      put("e", JsonArray(listOf(buildJsonObject {
        put("k", "switch")
        put("side", if (own) "me" else "foe")
        put("pos", pos)
        put("slot", event.newSlot)
      })))
    }.toString()))
    if (own) {
      val full = event.newSlot !in battle.seenActive
      battle.seenActive.add(event.newSlot)
      broadcast(
          battle,
          BattleSwitchInPacket(
              newSlot = event.newSlot,
              oldSlot = event.oldSlot.coerceAtLeast(0),
              mon = battle.party[event.newSlot].toBlock(event.newSlot, movesPresent = true),
              fullBlock = full))
    } else {
      val full = event.newSlot !in battle.opponentSeen
      battle.opponentSeen.add(event.newSlot)
      broadcast(
          battle,
          BattleSwitchInPacket(
              newSlot = event.newSlot,
              oldSlot = event.oldSlot.coerceAtLeast(0),
              mon = battle.opponent[event.newSlot].toBlock(event.newSlot, movesPresent = false),
              fullBlock = full,
              side = OPPONENT_SIDE))
    }
  }

  /**
   * The extended battle state: format, which party slot holds each position on both sides, every
   * monster's status/confusion/held item, the weather, the background and music key, and which
   * positions owe an action or a replacement. Sent at the start and after every step.
   */
  fun sendState(battle: BattleInstance) {
    broadcast(battle, BattleExtPacket(stateJson(battle).toString()))
  }

  fun stateJson(battle: BattleInstance): JsonObject = buildJsonObject {
    put("t", "state")
    put("format", battle.format)
    put("terrain", battle.terrain)
    battle.musicKey?.let { put("music", it) }
    put("weather", battle.field.weather.code)
    put("turn", battle.turn)
    battle.trainer?.let { tr ->
      put("trainer", buildJsonObject {
        put("name", tr.name)
        put("pic", tr.pic.lowercase())
        put("class", tr.className)
        put("double", tr.doubleBattle)
      })
    }
    put("me", sideJson(battle, battle.party, battle.own, own = true))
    put("foe", sideJson(battle, battle.opponent, battle.foe, own = false))
    put("owedSwitches", JsonArray(battle.owedSwitches.map { JsonPrimitive(it) }))
    put("chosen", JsonArray(battle.pendingChoices.keys.map { JsonPrimitive(it) }))
  }

  private fun sideJson(
      battle: BattleInstance,
      party: List<BattleMonState>,
      side: BattleSideState,
      own: Boolean,
  ): JsonObject = buildJsonObject {
    put("actives", JsonArray(side.actives.map { JsonPrimitive(it) }))
    put("reflect", side.reflectTurns)
    put("lightScreen", side.lightScreenTurns)
    put("spikes", side.spikes)
    put("mons", JsonArray(party.map { m ->
      buildJsonObject {
        put("id", m.entityId)
        put("status", StatusCondition.code(m.status))
        if (m.asleep) put("sleep", m.status and StatusCondition.SLEEP_MASK)
        put("confused", m.confused)
        put("hp", m.currentHp)
        put("maxHp", m.maxHp)
        put("dex", m.species.id)
        if (own) {
          put("ability", m.ability.name.replace('_', ' '))
          put("item", if (m.heldItem != 0) itemName(m.heldItem) else null)
        }
        if (m.volatile.substituteHp > 0) put("substitute", true)
        if (m.volatile.seededBy != 0L) put("seeded", true)
      }
    }))
  }

  fun sendSwitchIn(battle: BattleInstance, oldSlot: Int, fullBlock: Boolean) {
    broadcast(
        battle,
        BattleSwitchInPacket(
            newSlot = battle.activeSlot,
            oldSlot = oldSlot,
            mon = battle.activeMon().toBlock(slot = battle.activeSlot, movesPresent = true),
            fullBlock = fullBlock,
        ),
    )
  }

  /** The opposing side sends out its next monster. Its moves stay hidden from the player. */
  fun sendOpponentSwitchIn(battle: BattleInstance, oldSlot: Int, fullBlock: Boolean) {
    broadcast(
        battle,
        BattleSwitchInPacket(
            newSlot = battle.opponentSlot,
            oldSlot = oldSlot,
            mon = battle.opponentMon().toBlock(battle.opponentSlot, movesPresent = false),
            fullBlock = fullBlock,
            side = OPPONENT_SIDE,
        ),
    )
  }

  fun sendPrompt(battle: BattleInstance) {
    broadcast(battle, BattleTileMapPacket(groupId = battle.turn.toShort(), slotTiles = null))
    broadcast(battle, BattleQueuedEventPacket(packed = ACTION_PROMPT))
  }

  /** Opens the party switch screen after the active mon faints, in place of the action prompt. */
  fun sendSwitchPrompt(battle: BattleInstance) {
    broadcast(battle, BattleSlotFlagEventPacket(slot = 0, flag = false, immediate = false))
  }

  /** Confirms the forced replacement choice just before its switch-in. */
  fun sendSwitchConfirm(battle: BattleInstance) {
    broadcast(battle, BattleSlotFlagEventPacket(slot = 0, flag = false, immediate = true))
  }

  fun sendFled(battle: BattleInstance) {
    broadcast(battle, BattleSlotEventEnumPacket(slot = 0, eventType = FLED_EVENT))
    broadcast(battle, BattleBulkStatePacket.fled())
    battle.session.send(EntityPresencePacket(entityId = battle.charId, status = PRESENCE_OVERWORLD))
  }

  fun sendBattleEnd(battle: BattleInstance, party: List<Pokemon>, prizeMoney: Int = 0) {
    broadcast(battle, BattleBulkStatePacket.battleEnd(prizeMoney))
    battle.session.send(EntityPresencePacket(entityId = battle.charId, status = PRESENCE_OVERWORLD))
    battle.session.send(
        PokemonContainerPacket(
            container = PokemonContainer.PARTY,
            hasChange = true,
            delete = false,
            pokemon = party,
        ),
    )
  }

  fun sendVictoryDelta(battle: BattleInstance, entityId: Long, reward: RewardResult) {
    broadcast(
        battle,
        BattleEntityDeltaPacket(
            entityId = entityId,
            experience = Experience(reward.newLevel.toByte(), reward.newXp),
        ),
    )
    // The delta above moves the bar, the reward text reads its number from here.
    broadcast(battle, experienceReward(entityId, reward.xpGained))
    if (!reward.leveled) return
    broadcast(
        battle,
        BattleEntityDeltaPacket(
            entityId = entityId,
            statValues = reward.newStats.asWireList(),
            currentHp = reward.newCurrentHp.toShort(),
            evValues = reward.newEvs.asWireList(),
        ),
    )
  }

  // The other six counters are rewards we do not award yet.
  private fun experienceReward(entityId: Long, gained: Int): BattleStatCountersPacket =
      BattleStatCountersPacket(
          entityId = entityId,
          baseCounter = gained,
          counter1 = null,
          counter2 = null,
          counter3 = null,
          counter4 = null,
          counter5 = null,
          counter6 = null,
      )

  fun sendNotice(battle: BattleInstance, message: String) {
    battle.session.send(notice(message))
  }

  /** The effectiveness line rides in the outcome word rather than in an event of its own. */
  private fun effectivenessBit(effectiveness: Int): Int =
      when {
        effectiveness > TypeChart.NEUTRAL -> SUPER_EFFECTIVE_BIT
        effectiveness in 1..<TypeChart.NEUTRAL -> NOT_VERY_EFFECTIVE_BIT
        else -> 0
      }

  fun broadcast(battle: BattleInstance, packet: Any) {
    interestManager.broadcast(battle.key, packet)
  }

  /** The delta that tells the client a monster's moveset changed. */
  fun moveSlotsDelta(
      entityId: Long,
      moveSlots: List<Pair<Short, Byte>>,
      ppUps: Byte,
  ): BattleEntityDeltaPacket =
      BattleEntityDeltaPacket(entityId = entityId, moves = MoveSlots(moveSlots, ppUps))

  // The decomp battle stat order. Not verified against the live client yet.
  private fun statIndex(stat: BattleStat): Byte =
      when (stat) {
        BattleStat.ATTACK -> 1
        BattleStat.DEFENSE -> 2
        BattleStat.SPEED -> 3
        BattleStat.SP_ATTACK -> 4
        BattleStat.SP_DEFENSE -> 5
        BattleStat.ACCURACY -> 6
        BattleStat.EVASION -> 7
      }
}

// The decomp in-game stat order. Not verified against the live client yet.
private fun statOrder(hp: Int, atk: Int, def: Int, spd: Int, spAtk: Int, spDef: Int): List<Short> =
    listOf(
        hp.toShort(), atk.toShort(), def.toShort(), spd.toShort(), spAtk.toShort(), spDef.toShort())

internal fun ComputedStats.asWireList(): List<Short> = statOrder(hp, atk, def, spd, spAtk, spDef)

private fun EVs.asWireList(): List<Short> = statOrder(hp, atk, def, spd, spAtk, spDef)
