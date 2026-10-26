package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.MAX_PARTY_SIZE
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.maps.MapManager
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.net.game.packets.SlotSwap
import de.fiereu.openmmo.net.game.packets.SwapInventorySlotsPacket
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton

private val log = KotlinLogging.logger {}

/**
 * Moves monsters around the party and the PC ([SwapInventorySlotsPacket], list type [PARTY] or
 * [PC]): reorder the party anywhere, deposit and withdraw only in a Pokemon Center, which is any
 * map with a nurse. A swap onto an occupied slot trades the two; onto [ANY_SLOT] (or an empty one)
 * it moves. The party keeps at least one monster that can fight.
 */
@Singleton
class PartyStorageService
@Inject
constructor(
    private val characters: CharacterStore,
    private val maps: MapManager,
    private val battles: BattleService,
) {

  suspend fun onSwap(event: PacketEvent<SwapInventorySlotsPacket>) {
    val session = event.session
    val state = session.attributes[PLAYER_STATE] ?: return
    val charId = state.characterId ?: return
    if (battles.inBattle(charId) || state.inDialog) {
      session.send(notice("You can't do that right now."))
      return
    }
    val stored = characters.getCharacter(charId) ?: return
    val atPc = inPokemonCenter(state)
    var party = stored.pokemon.sortedBy { it.containerSlot }
    var pc = stored.pcStorage.sortedBy { it.containerSlot }
    for (swap in event.packet.swaps) {
      val result = apply(party, pc, swap, atPc)
      if (result is Result.Refused) {
        session.send(notice(result.reason))
        return
      }
      result as Result.Moved
      party = result.party
      pc = result.pc
    }
    if (!characters.rearrangeMonsters(charId, party, pc)) {
      session.send(notice("The PC could not save that. Try again."))
      return
    }
    log.info { "char=$charId moved monsters: party ${party.map { it.id }} pc ${pc.size}" }
    sync(session, party, pc)
  }

  sealed interface Result {
    data class Moved(val party: List<Pokemon>, val pc: List<Pokemon>) : Result

    data class Refused(val reason: String) : Result
  }

  /** One swap over the party (in slot order) and the PC; pure, so it is testable. */
  fun apply(party: List<Pokemon>, pc: List<Pokemon>, swap: SlotSwap, atPc: Boolean): Result {
    val srcType = swap.sourceListType
    val dstType = swap.destinationListType
    if (srcType !in LIST_TYPES || dstType !in LIST_TYPES) return Result.Refused("Unknown list.")
    if ((srcType == PC || dstType == PC) && !atPc) {
      return Result.Refused("The PC is only at a POKéMON CENTER.")
    }
    val src = swap.sourceSlot.toInt()
    val dst = swap.destinationSlot.toInt()
    val partyList = party.toMutableList()
    val pcBySlot = pc.associateBy { it.containerSlot.toInt() }.toMutableMap()

    val moving =
        (if (srcType == PARTY) partyList.getOrNull(src) else pcBySlot[src])
            ?: return Result.Refused("There is no POKéMON there.")

    when {
      srcType == PARTY && dstType == PARTY -> {
        if (dst !in partyList.indices) return Result.Refused("There is no POKéMON there.")
        partyList[src] = partyList[dst].also { partyList[dst] = moving }
      }
      srcType == PARTY && dstType == PC -> {
        val slot = if (dst == ANY_SLOT) firstFree(pcBySlot.keys) else dst
        if (slot < 0) return Result.Refused("There is no POKéMON there.")
        val occupant = pcBySlot[slot]
        if (occupant != null) {
          partyList[src] = occupant
        } else {
          partyList.removeAt(src)
        }
        pcBySlot[slot] = moving
      }
      srcType == PC && dstType == PARTY -> {
        val occupant = if (dst == ANY_SLOT) null else partyList.getOrNull(dst)
        pcBySlot.remove(src)
        if (occupant != null) {
          partyList[dst] = moving
          pcBySlot[src] = occupant
        } else {
          if (partyList.size >= MAX_PARTY_SIZE) return Result.Refused("Your party is full!")
          partyList += moving
        }
      }
      else -> {
        val slot = if (dst == ANY_SLOT) firstFree(pcBySlot.keys) else dst
        if (slot < 0) return Result.Refused("There is no POKéMON there.")
        val occupant = pcBySlot[slot]
        pcBySlot.remove(src)
        if (occupant != null) pcBySlot[src] = occupant
        pcBySlot[slot] = moving
      }
    }
    if (partyList.isEmpty()) return Result.Refused("That's your last POKéMON!")
    if (partyList.none { it.hp > 0 && !it.isEgg }) {
      return Result.Refused("You need a POKéMON that can still battle in your party.")
    }
    return Result.Moved(
        partyList.mapIndexed { i, m ->
          m.copy(container = PokemonContainer.PARTY, containerSlot = i.toShort())
        },
        pcBySlot.entries
            .sortedBy { it.key }
            .map { (slot, m) ->
              m.copy(container = PokemonContainer.PC, containerSlot = slot.toShort())
            },
    )
  }

  private fun firstFree(taken: Set<Int>): Int =
      (0 until MAX_PC_SLOTS).firstOrNull { it !in taken } ?: -1

  /** A Pokemon Center is any map whose npcs include the decomp's nurse script. */
  private fun inPokemonCenter(state: PlayerState): Boolean =
      maps.getMap(state.regionId, state.bankId, state.mapId)?.npcs?.any {
        it.script.endsWith("_EventScript_Nurse")
      } ?: false

  private fun sync(session: SessionContext, party: List<Pokemon>, pc: List<Pokemon>) {
    session.send(PokemonContainerPacket(PokemonContainer.PARTY, true, false, party))
    session.send(PokemonContainerPacket(PokemonContainer.PC, true, false, pc))
  }

  companion object {
    const val PC: Byte = 0
    const val PARTY: Byte = 1
    const val ANY_SLOT = -1
    private val LIST_TYPES = setOf(PC, PARTY)

    /** 14 boxes of 30, the GBA PC. */
    const val MAX_PC_SLOTS = 14 * 30
  }
}
