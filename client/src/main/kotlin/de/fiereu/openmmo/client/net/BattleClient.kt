package de.fiereu.openmmo.client.net

import de.fiereu.network.PacketEvent
import de.fiereu.openmmo.common.enums.BattleAction
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.DuelChallengePacket
import de.fiereu.openmmo.net.game.packets.DuelInviteOutcomePacket
import de.fiereu.openmmo.net.game.packets.DuelInvitePacket
import de.fiereu.openmmo.net.game.packets.EntityMovePpPacket
import de.fiereu.openmmo.net.game.packets.InGameChallengeResponsePacket
import de.fiereu.openmmo.net.game.packets.MapLoadedAckPacket
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleActionSelectPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleBulkStatePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleEntityDeltaPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleEntityMoveEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleEventBody
import de.fiereu.openmmo.net.game.packets.battle.BattleFieldStatePacket
import de.fiereu.openmmo.net.game.packets.battle.BattleListEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleQueuedEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSlotEventEnumPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSlotFlagEventPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleStatCountersPacket
import de.fiereu.openmmo.net.game.packets.battle.BattleSwitchInPacket
import de.fiereu.openmmo.net.game.packets.battle.OpposingSide
import de.fiereu.openmmo.net.game.packets.battle.moves.MoveLearnPromptPacket
import de.fiereu.openmmo.net.game.packets.battle.moves.MoveLearnReplyPacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

// Bits of the per-target outcome word, mirroring the server's emitter.
private const val OUTCOME_MISSED = 1
private const val OUTCOME_FAILED = 4
private const val SUPER_EFFECTIVE_BIT = 0x20
private const val NOT_VERY_EFFECTIVE_BIT = 0x10
private const val BATTLE_END_FLAG: Byte = 2
private const val FLED_PHASE: Byte = -1

/** Names and max pp from the codegen tables the server also uses. */
object BattleNames {
  private val moves by lazy { MoveRegistry() }
  private val species by lazy { SpeciesRegistry() }

  fun move(id: Int): String = moves.get(id)?.name ?: "MOVE $id"

  fun maxPp(id: Int): Int = moves.get(id)?.pp ?: 0

  fun species(id: Int): String = species.get(id)?.name ?: "#$id"

  private val trainers by lazy { de.fiereu.openmmo.trainer.TrainerRegistry() }

  fun trainer(regionId: Int, id: Int): String? {
    val region =
        de.fiereu.openmmo.common.enums.Region.entries.firstOrNull {
          it.wireValue.toInt() == regionId
        }
    return region?.let { trainers.get(it, id)?.name?.takeIf(String::isNotBlank) }
  }

  val pokeBall: Short by lazy { ItemRegistry().idOf(Items.POKE_BALL).toShort() }

  private val statNames =
      mapOf(
          1 to "ATTACK",
          2 to "DEFENSE",
          3 to "SPEED",
          4 to "SP. ATK",
          5 to "SP. DEF",
          6 to "accuracy",
          7 to "evasiveness")

  fun stat(id: Int): String = statNames[id] ?: "stat $id"
}

data class BattleMove(val id: Int, val pp: Int, val maxPp: Int) {
  val name: String
    get() = BattleNames.move(id)
}

data class BattleMon(
    val slot: Int,
    val entityId: Long,
    val species: Int,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val moves: List<BattleMove> = emptyList(),
    /** Opposing monsters not shown yet are hidden stubs. */
    val revealed: Boolean = true,
) {
  val name: String
    get() = BattleNames.species(species)

  val fainted: Boolean
    get() = revealed && hp <= 0
}

enum class BattlePhase {
  /** Waiting for the server, between prompts. */
  WAITING,
  /** The server wants a FIGHT/BAG/POKEMON/RUN action. */
  ACTION,
  /** The active monster fainted, a replacement must be picked. */
  FORCED_SWITCH,
  ENDED,
}

data class BattleState(
    val active: Boolean = false,
    val wild: Boolean = true,
    val playerName: String = "",
    val opponentName: String = "",
    val party: List<BattleMon> = emptyList(),
    val activeSlot: Int = 0,
    val opponent: List<BattleMon> = emptyList(),
    val opponentSlot: Int = 0,
    val phase: BattlePhase = BattlePhase.WAITING,
    val turn: Int = 0,
    val log: List<String> = emptyList(),
    val result: String? = null,
    val battlesFinished: Int = 0,
    val pendingLearn: Pair<Long, List<Int>>? = null,
    val pendingInviteFrom: String? = null,
) {
  val me: BattleMon?
    get() = party.getOrNull(activeSlot)

  val foe: BattleMon?
    get() = opponent.getOrNull(opponentSlot)
}

/**
 * The client side of a battle: decodes the server's battle packets into a [BattleState] the UI and
 * the script runner read, and sends the player's picks. Also carries the duel challenge flow.
 */
class BattleClient(
    private val send: (Any) -> Unit,
    private val onChange: () -> Unit,
    private val myName: () -> String?,
    private val regionId: () -> Int = { 0 },
) {
  private val lock = Any()
  private var state = BattleState()

  /** The last party the server sent, used for pp the field state leaves out. */
  private var knownParty: Map<Long, List<de.fiereu.openmmo.common.PokemonMove>> = emptyMap()

  fun snapshot(): BattleState = synchronized(lock) { state }

  private fun update(block: (BattleState) -> BattleState) {
    synchronized(lock) { state = block(state) }
    onChange()
  }

  private fun say(line: String) {
    log.info { "[battle] $line" }
    update { it.copy(log = (it.log + line).takeLast(200)) }
  }

  /** True when the packet belonged to the battle or the duel flow. */
  fun handle(event: PacketEvent<*>): Boolean {
    when (val p = event.packet) {
      is BattleFieldStatePacket -> onStart(p)
      is BattleQueuedEventPacket ->
          if (p.flag) {
            update { it.copy(phase = BattlePhase.ACTION) }
            snapshot().me?.let { say("What will ${it.name} do?") }
          }
      is BattleSlotFlagEventPacket ->
          if (!p.immediate) {
            update { it.copy(phase = BattlePhase.FORCED_SWITCH) }
            say("Choose a monster to send out.")
          }
      is BattleEntityMoveEventPacket -> onMove(p)
      is EntityMovePpPacket -> onPp(p)
      is BattleSwitchInPacket -> onSwitchIn(p)
      is BattleEntityDeltaPacket -> onDelta(p)
      is BattleStatCountersPacket -> {
        nameOf(p.entityId)?.let { say("$it gained ${p.baseCounter} EXP. Points!") }
      }
      is BattleSlotEventEnumPacket -> if (p.eventType.toInt() == 0) say("Got away safely!")
      is BattleListEventPacket -> say("You threw a POKé BALL!")
      is BattleBulkStatePacket -> onBulk(p)
      is MoveLearnPromptPacket -> {
        val offered = p.offered.map { it.toInt() }
        update { it.copy(pendingLearn = p.entityId to offered) }
        val who = nameOf(p.entityId) ?: "Your monster"
        say(
            "$who wants to learn ${offered.joinToString { BattleNames.move(it) }}. (learn N / skip)")
      }
      is PokemonContainerPacket -> {
        if (p.container == PokemonContainer.PARTY && !p.delete) {
          synchronized(lock) { knownParty = p.pokemon.associate { it.id to it.moves } }
        }
        return false
      }
      is DuelInvitePacket -> {
        update { it.copy(pendingInviteFrom = p.name) }
        say("${p.name} challenges you to a battle! (accept / decline)")
      }
      is DuelInviteOutcomePacket ->
          when (p.packed.toInt()) {
            0 -> say("Your challenge was declined.")
            1 -> say("Your challenge was accepted!")
            else -> say("That player can't battle right now.")
          }
      else -> return false
    }
    return true
  }

  /** Server notices go to the battle log while a battle is up. */
  fun notice(text: String) {
    // The invite packet already put this line in the log.
    if (text.endsWith("challenges you to a battle!")) return
    if (snapshot().active || snapshot().pendingInviteFrom != null) say(text)
  }

  private fun onStart(p: BattleFieldStatePacket) {
    val known = synchronized(lock) { knownParty }
    val party =
        p.playerParty.map { b ->
          val pp = known[b.entityId]
          BattleMon(
              slot = b.slot,
              entityId = b.entityId,
              species = b.species.toInt(),
              level = b.level.toInt(),
              hp = b.currentHp.toInt(),
              maxHp = b.maxHp.toInt(),
              moves =
                  b.moveIds.mapIndexedNotNull { i, id ->
                    if (id.toInt() == 0) null
                    else {
                      val max = BattleNames.maxPp(id.toInt())
                      val cur = pp?.getOrNull(i)?.takeIf { it.id == id }?.pp?.toInt() ?: max
                      BattleMove(id.toInt(), cur, max)
                    }
                  },
          )
        }
    val opponent =
        p.opponentParty.map { b ->
          BattleMon(
              b.slot,
              b.entityId,
              b.species.toInt(),
              b.level.toInt(),
              b.currentHp.toInt(),
              b.maxHp.toInt(),
              revealed = b.revealed)
        }
    val wild = p.opposing == OpposingSide.WILD
    val invitedBy = snapshot().pendingInviteFrom
    update {
      it.copy(
          active = true,
          wild = wild,
          playerName = p.playerName,
          opponentName =
              when {
                wild -> ""
                p.trainerId.toInt() != 0 ->
                    BattleNames.trainer(regionId(), p.trainerId.toInt()) ?: "Trainer"
                else -> invitedBy ?: it.opponentName
              },
          party = party,
          activeSlot = p.activeSlot,
          opponent = opponent,
          opponentSlot = p.opponentActiveSlot,
          phase = BattlePhase.WAITING,
          turn = 0,
          log = emptyList(),
          result = null,
          pendingInviteFrom = null,
      )
    }
    val foe = snapshot().foe
    if (wild) say("A wild ${foe?.name} (Lv${foe?.level}) appeared!")
    else say("${snapshot().opponentName.ifEmpty { "A trainer" }} sent out ${foe?.name}!")
    say("Go! ${snapshot().me?.name}!")
  }

  private fun nameOf(entityId: Long): String? {
    val s = snapshot()
    s.party
        .firstOrNull { it.entityId == entityId }
        ?.let {
          return it.name
        }
    s.opponent
        .firstOrNull { it.entityId == entityId }
        ?.let {
          return "Foe ${it.name}"
        }
    return null
  }

  private fun mapMon(entityId: Long, f: (BattleMon) -> BattleMon) = update { s ->
    s.copy(
        party = s.party.map { if (it.entityId == entityId) f(it) else it },
        opponent = s.opponent.map { if (it.entityId == entityId) f(it) else it },
    )
  }

  private fun onMove(p: BattleEntityMoveEventPacket) {
    val attacker = nameOf(p.sourceEntity) ?: "?"
    say("$attacker used ${BattleNames.move(p.sourceMove.toInt())}!")
    for (t in p.targets) {
      val outcome = t.targetMove.toInt()
      if (t.subEvents.isEmpty() && outcome == OUTCOME_MISSED) say("$attacker's attack missed!")
      if (t.subEvents.isEmpty() && outcome == OUTCOME_FAILED) say("But it failed!")
      if (outcome and SUPER_EFFECTIVE_BIT != 0) say("It's super effective!")
      if (outcome and NOT_VERY_EFFECTIVE_BIT != 0) say("It's not very effective...")
      val target = nameOf(t.entityId) ?: "?"
      for (e in t.subEvents) {
        when (val body = e.body) {
          is BattleEventBody.HpUpdate -> {
            mapMon(t.entityId) { it.copy(hp = body.currentHp.toInt()) }
            if (body.currentHp <= 0) say("$target fainted!")
          }
          is BattleEventBody.StatChange -> {
            val d = body.stageDelta.toInt()
            val how =
                when {
                  d >= 2 -> "rose sharply"
                  d > 0 -> "rose"
                  d <= -2 -> "harshly fell"
                  else -> "fell"
                }
            say("$target's ${BattleNames.stat(body.stat.toInt())} $how!")
          }
          is BattleEventBody.Faint -> say("$target fainted!")
          is BattleEventBody.MoveFailed -> say("But it failed!")
          BattleEventBody.EffectivenessMessage -> Unit
        }
      }
    }
    update { if (it.phase == BattlePhase.ACTION) it.copy(phase = BattlePhase.WAITING) else it }
  }

  private fun onPp(p: EntityMovePpPacket) =
      mapMon(p.entityId) { mon ->
        mon.copy(
            moves =
                mon.moves.mapIndexed { i, m ->
                  if (i == p.moveSlot.toInt()) m.copy(pp = p.ppValue.toInt()) else m
                })
      }

  private fun onSwitchIn(p: BattleSwitchInPacket) {
    val b = p.mon
    if (p.side.toInt() == 0) {
      update { s ->
        val party =
            s.party.map { m ->
              if (m.slot == p.newSlot && p.fullBlock)
                  m.copy(hp = b.currentHp.toInt(), maxHp = b.maxHp.toInt(), level = b.level.toInt())
              else m
            }
        s.copy(party = party, activeSlot = p.newSlot, phase = BattlePhase.WAITING)
      }
      say("Go! ${snapshot().me?.name}!")
    } else {
      update { s ->
        val existing = s.opponent.firstOrNull { it.slot == p.newSlot }
        val mon =
            if (p.fullBlock || existing == null || !existing.revealed)
                BattleMon(
                    p.newSlot,
                    b.entityId,
                    b.species.toInt(),
                    b.level.toInt(),
                    b.currentHp.toInt(),
                    b.maxHp.toInt())
            else existing
        val list = s.opponent.toMutableList()
        val i = list.indexOfFirst { it.slot == p.newSlot }
        if (i >= 0) list[i] = mon else list += mon
        s.copy(opponent = list, opponentSlot = p.newSlot)
      }
      val who = snapshot().opponentName.ifEmpty { "The foe" }
      say("$who sent out ${snapshot().foe?.name}!")
    }
  }

  private fun onDelta(p: BattleEntityDeltaPacket) {
    val before = snapshot().party.firstOrNull { it.entityId == p.entityId }
    mapMon(p.entityId) { m ->
      var n = m
      p.experience?.let { n = n.copy(level = it.level.toInt()) }
      p.statValues?.let { n = n.copy(maxHp = it[0].toInt()) }
      p.currentHp?.let { n = n.copy(hp = it.toInt()) }
      p.moves?.let { mv ->
        n =
            n.copy(
                moves =
                    mv.slots
                        .filter { it.first.toInt() != 0 }
                        .map { (id, pp) ->
                          BattleMove(id.toInt(), pp.toInt(), BattleNames.maxPp(id.toInt()))
                        })
      }
      n
    }
    val after = snapshot().party.firstOrNull { it.entityId == p.entityId }
    if (before != null && after != null && after.level > before.level) {
      say("${after.name} grew to Lv. ${after.level}!")
    }
  }

  private fun onBulk(p: BattleBulkStatePacket) {
    if (p.flag != BATTLE_END_FLAG) return
    val s = snapshot()
    val result =
        when {
          p.phase == FLED_PHASE -> "FLED"
          s.party.isNotEmpty() && s.party.all { it.fainted } -> "DEFEAT"
          s.opponent.isNotEmpty() && s.opponent.all { it.fainted } -> "VICTORY"
          s.log.any { it.contains("POKé BALL") } -> "CAUGHT"
          s.log.any { it.contains("won the battle") } ->
              if (s.log.last { it.contains("won the battle") }.startsWith("${myName()} ")) "VICTORY"
              else "DEFEAT"
          else -> "ENDED"
        }
    if (p.prizeMoney > 0) say("You got ¥${p.prizeMoney} for winning!")
    when (result) {
      "VICTORY" -> say("You won!")
      "DEFEAT" -> say("You lost...")
      "CAUGHT" -> say("Gotcha! ${s.foe?.name} was caught!")
      else -> Unit
    }
    update {
      it.copy(phase = BattlePhase.ENDED, result = result, battlesFinished = it.battlesFinished + 1)
    }
    log.info { "battle over: $result" }
    // The server holds the result until the client says it is back on the map.
    send(MapLoadedAckPacket())
  }

  /** Leaves the end screen. The overworld is already live on the server side. */
  fun close() = update { it.copy(active = false) }

  // --- actions -----------------------------------------------------------------------------

  fun fight(moveIndex: Int): String? {
    val s = snapshot()
    if (s.phase != BattlePhase.ACTION) return "not your turn"
    val move = s.me?.moves?.getOrNull(moveIndex) ?: return "no move ${moveIndex + 1}"
    if (move.pp <= 0 && s.me!!.moves.any { it.pp > 0 }) return "${move.name} has no PP left"
    update { it.copy(phase = BattlePhase.WAITING) }
    send(BattleActionSelectPacket(0, BattleAction.MOVE, move.id.toShort(), 0, 0))
    return null
  }

  fun switchTo(partyIndex: Int): String? {
    val s = snapshot()
    if (s.phase != BattlePhase.ACTION && s.phase != BattlePhase.FORCED_SWITCH) return "not now"
    val mon = s.party.getOrNull(partyIndex) ?: return "no monster ${partyIndex + 1}"
    if (mon.fainted) return "${mon.name} has fainted"
    if (partyIndex == s.activeSlot) return "${mon.name} is already out"
    update { it.copy(phase = BattlePhase.WAITING) }
    send(BattleActionSelectPacket(0, BattleAction.SWITCH, partyIndex.toShort(), 0, 0))
    return null
  }

  fun run(): String? {
    if (snapshot().phase != BattlePhase.ACTION) return "not your turn"
    update { it.copy(phase = BattlePhase.WAITING) }
    send(BattleActionSelectPacket(0, BattleAction.RUN, 0, 0, 0))
    return null
  }

  /** BAG: the only battle item so far is a Poke Ball thrown at the wild monster. */
  fun throwBall(): String? {
    val s = snapshot()
    if (s.phase != BattlePhase.ACTION) return "not your turn"
    val target = s.foe?.entityId ?: 0
    update { it.copy(phase = BattlePhase.WAITING) }
    send(BattleActionSelectPacket(0, BattleAction.ITEM, BattleNames.pokeBall, target, 0))
    return null
  }

  /** Puts the offered move in [slot], or keeps the moveset when [slot] is null. */
  fun learn(slot: Int?): String? {
    val (entityId, offered) = snapshot().pendingLearn ?: return "nothing to learn"
    val mon = snapshot().party.firstOrNull { it.entityId == entityId }
    val current =
        mon?.moves?.map { it.id.toShort() } ?: knownParty[entityId]?.map { it.id } ?: emptyList()
    val padded = (current + List(4) { 0.toShort() }).take(4).toMutableList()
    if (slot != null) {
      if (slot !in 0..3) return "slot must be 1-4"
      padded[slot] = offered.first().toShort()
    }
    send(MoveLearnReplyPacket(entityId, padded, offered.map { it.toShort() }))
    update { it.copy(pendingLearn = null) }
    return null
  }

  fun challenge(name: String) {
    update { it.copy(opponentName = name) }
    send(
        DuelChallengePacket(
            targetPlayerName = name,
            battleTypeId = 0,
            timed = false,
            battleFormat = 0,
            typeRestriction = 0,
            natureRestriction = 0,
            allowedFormat = 0,
            itemLevelCap = null,
            natureCap = null,
            items = null,
            tier = null,
        ))
  }

  fun answerInvite(accept: Boolean): String? {
    val from = snapshot().pendingInviteFrom ?: return "no challenge to answer"
    if (!accept) update { it.copy(pendingInviteFrom = null) }
    send(InGameChallengeResponsePacket(accept, from))
    return null
  }
}
