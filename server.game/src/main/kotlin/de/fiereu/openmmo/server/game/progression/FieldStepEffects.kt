package de.fiereu.openmmo.server.game.progression

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.MonStatus
import de.fiereu.openmmo.common.enums.MapType
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.services.notice
import de.fiereu.openmmo.server.game.storage.CharacterStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.random.Random

/**
 * The per-step party effects of field_control_avatar.c: UpdateFriendshipStepCounter (every 128
 * steps each party monster may gain a friendship point, [FriendshipEvent.WALKING]) and
 * UpdatePoisonStepCounter / DoPoisonFieldEffect (every 4 steps, outside secret bases, each poisoned
 * monster loses 1 HP; Gen 3 lets it faint, which costs friendship and cures the poison, and a party
 * with nobody left standing whites out).
 */
@Singleton
class FieldStepEffects
@Inject
constructor(
    private val characters: CharacterStore,
    private val items: ItemRegistry,
    private val species: SpeciesRegistry,
    private val whiteOut: Provider<WhiteOutService>,
    /** IncrementBirthIslandRockStepCount needs to know the Birth Island map. */
    private val maps: de.fiereu.openmmo.maps.MapManager? = null,
) {
  private class Counters {
    var friendship = 0
    var poison = 0
  }

  private val counters = ConcurrentHashMap<Long, Counters>()

  /** Tests seed it. */
  internal var random: Random = Random.Default

  fun onStep(session: SessionContext, charId: Long, map: MapDef) {
    birthIslandStep(charId, map)
    val c = counters.getOrPut(charId) { Counters() }
    c.friendship = (c.friendship + 1) % Friendship.WALK_STEPS
    if (c.friendship == 0) walkFriendship(charId)
    if (map.mapType == MapType.SECRET_BASE) return
    c.poison = (c.poison + 1) % POISON_STEPS
    if (c.poison == 0) poison(session, charId)
  }

  /**
   * IncrementBirthIslandRockStepCount (FRLG field_specials.c): steps on Birth Island since the last
   * touch of the Deoxys triangle, wrapping to 0 past 99.
   */
  private fun birthIslandStep(charId: Long, map: MapDef) {
    val birth = maps?.getMapByName(map.regionId.toInt(), "BirthIsland_Exterior") ?: return
    if (birth.bankId != map.bankId || birth.mapId != map.mapId || birth.regionId != map.regionId)
        return
    val stored = characters.getCharacter(charId) ?: return
    val count = (stored.storyVars[DEOXYS_STEPS] ?: 0) + 1
    characters.setStoryVar(charId, DEOXYS_STEPS, if (count > 99) 0 else count)
  }

  fun forget(charId: Long) {
    counters.remove(charId)
  }

  private fun walkFriendship(charId: Long) {
    val party = party(charId)
    for (mon in party) {
      val next = Friendship.adjust(mon, FriendshipEvent.WALKING, items, random)
      if (next != mon) characters.updatePokemon(charId, next)
    }
  }

  /** DoPoisonFieldEffect; true when the party whited out. */
  internal fun poison(session: SessionContext, charId: Long): Boolean {
    val party = party(charId)
    val poisoned = party.filter { it.status and MonStatus.ANY_POISON != 0 }
    if (poisoned.isEmpty()) return false
    val fainted = mutableListOf<String>()
    for (mon in poisoned) {
      val hp = (mon.hp - 1).coerceAtLeast(0)
      var next = mon.copy(hp = hp.toShort())
      if (hp == 0) {
        // FaintFromFieldPoison: the poison is cured and friendship drops.
        next =
            Friendship.adjust(
                next.copy(status = MonStatus.NONE), FriendshipEvent.FAINT_FIELD_PSN, items)
        fainted += mon.nickname.ifEmpty { species.get(mon.dexId)?.name ?: "POKéMON" }
      }
      characters.updatePokemon(charId, next)
    }
    val after = party(charId)
    val whole =
        characters
            .getCharacter(charId)
            ?.pokemon
            ?.filter { it.container == PokemonContainer.PARTY }
            ?.sortedBy { it.containerSlot } ?: after
    session.send(PokemonContainerPacket(PokemonContainer.PARTY, true, false, whole))
    for (name in fainted) session.send(notice("$name fainted…"))
    if (fainted.isNotEmpty() && after.none { it.hp > 0 }) {
      whiteOut.get().whiteOut(session, charId, againstTrainer = false)
      return true
    }
    return false
  }

  private fun party(charId: Long) =
      characters
          .getCharacter(charId)
          ?.pokemon
          ?.filter { it.container == PokemonContainer.PARTY && !it.isEgg }
          ?.sortedBy { it.containerSlot } ?: emptyList()

  companion object {
    const val POISON_STEPS = 4
    const val DEOXYS_STEPS = "kanto/VAR_DEOXYS_INTERACTION_STEP_COUNTER"
  }
}
