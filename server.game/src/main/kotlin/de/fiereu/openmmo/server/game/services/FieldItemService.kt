package de.fiereu.openmmo.server.game.services

import de.fiereu.network.PacketEvent
import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.net.game.packets.FieldItemUsePacket
import de.fiereu.openmmo.net.game.packets.PokemonContainerPacket
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton

private val log = KotlinLogging.logger {}

/**
 * Bag items used outside a battle ([FieldItemUsePacket]): medicine on a party monster, a repel on
 * the player. The item is taken first, so a failed write never hands out a free heal; every refusal
 * is reported to the player as a notice and costs nothing.
 */
@Singleton
class FieldItemService
@Inject
constructor(
    private val characters: CharacterStore,
    private val items: ItemRegistry,
    private val species: SpeciesRegistry,
    private val moves: MoveRegistry,
    private val battles: BattleService,
    // r3-battle hook: evolution stones start the evolution scene.
    private val evolution: EvolutionService? = null,
) {

  suspend fun onUse(event: PacketEvent<FieldItemUsePacket>) {
    val session = event.session
    val state = session.attributes[PLAYER_STATE] ?: return
    val charId = state.characterId ?: return
    val evo = evolution
    if (evo != null && evo.isEvolutionItem(event.packet.itemId.toInt()) && !battles.inBattle(charId)) {
      evo.useEvolutionItem(session, charId, event.packet.pokemonId, event.packet.itemId.toInt())
          ?.let { session.send(notice(it)) }
      return
    }
    val message = use(charId, event.packet.itemId.toInt(), event.packet.pokemonId, state.inDialog)
    session.send(notice(message.text))
    if (message.changed) syncAfterUse(session, charId, event.packet.itemId.toInt())
  }

  /** The outcome shown to the player, and whether the bag/party changed. */
  data class Outcome(val text: String, val changed: Boolean)

  /** Applies [itemId] to party monster [pokemonId] (or to the player); pure on refusal. */
  suspend fun use(charId: Long, itemId: Int, pokemonId: Long, busy: Boolean = false): Outcome {
    val item = items.get(itemId) ?: return Outcome("That item does not exist.", false)
    if (busy || battles.inBattle(charId)) return Outcome("You can't use that right now.", false)
    val stored = characters.getCharacter(charId) ?: return Outcome("", false)
    if ((stored.items[itemId] ?: 0) <= 0) return Outcome("You don't have any ${item.name}.", false)
    val effect =
        FieldItemEffects.of(item) ?: return Outcome("${item.name} can't be used here.", false)

    if (effect is FieldItemEffect.Repel) {
      if (stored.info.repelLeft > 0) {
        return Outcome("The effects of the last REPEL lingers on.", false)
      }
      if (!characters.addItem(charId, itemId, -1)) return Outcome("", false)
      characters.setRepel(charId, effect.steps, itemId)
      log.info { "char=$charId used ${item.name}: repel for ${effect.steps} steps" }
      return Outcome("You used the ${item.name}. Wild POKéMON will be repelled.", true)
    }

    val mon =
        stored.pokemon.firstOrNull { it.id == pokemonId }
            ?: return Outcome("Choose a POKéMON in your party.", false)
    val name = displayName(mon)
    val updated = apply(effect, mon) ?: return Outcome("It won't have any effect.", false)
    if (!characters.addItem(charId, itemId, -1)) return Outcome("", false)
    characters.updatePokemon(charId, updated)
    log.info { "char=$charId used ${item.name} on ${mon.id}: hp ${mon.hp} -> ${updated.hp}" }
    return Outcome(describe(item, effect, name, mon, updated), true)
  }

  /** The monster after [effect], or null when it would do nothing. */
  internal fun apply(effect: FieldItemEffect, mon: Pokemon): Pokemon? {
    val maxHp = maxHp(mon)
    return when (effect) {
      is FieldItemEffect.Heal -> {
        if (mon.hp <= 0 || mon.hp >= maxHp) return null
        mon.copy(
            hp = (mon.hp + effect.amount.toLong()).coerceAtMost(maxHp.toLong()).toInt().toShort())
      }
      is FieldItemEffect.Revive -> {
        if (mon.hp > 0) return null
        mon.copy(hp = (maxHp * effect.fraction).toInt().coerceIn(1, maxHp).toShort())
      }
      is FieldItemEffect.RestoreAllPp -> {
        val restored =
            mon.moves.map { m ->
              if (m.id.toInt() == 0) return@map m
              val max = moves.get(m.id.toInt())?.pp ?: m.pp.toInt()
              val pp = (m.pp + effect.amount.toLong()).coerceAtMost(max.toLong()).toInt()
              PokemonMove(m.id, pp.toByte())
            }
        if (restored == mon.moves) null else mon.copy(moves = restored)
      }
      // r3-battle: the major status now outlasts a battle (Pokemon.status).
      FieldItemEffect.CureStatus -> if (mon.status == 0 || mon.hp <= 0) null else mon.copy(status = 0)
      is FieldItemEffect.Repel -> null
    }
  }

  private fun describe(
      item: ItemDef,
      effect: FieldItemEffect,
      name: String,
      before: Pokemon,
      after: Pokemon,
  ): String =
      when (effect) {
        is FieldItemEffect.Heal -> "$name's HP was restored by ${after.hp - before.hp} point(s)."
        is FieldItemEffect.Revive -> "$name was revived by the ${item.name}!"
        is FieldItemEffect.RestoreAllPp -> "$name's PP was restored."
        else -> "You used the ${item.name}."
      }

  private fun maxHp(mon: Pokemon): Int =
      species.get(mon.dexId)?.let { StatCalculator.computeAll(it, mon).hp } ?: mon.hp.toInt()

  private fun displayName(mon: Pokemon): String =
      mon.nickname.ifEmpty { species.get(mon.dexId)?.name ?: "POKéMON" }

  private fun syncAfterUse(session: SessionContext, charId: Long, itemId: Int) {
    val after = characters.getCharacter(charId) ?: return
    session.send(
        PokemonContainerPacket(
            container = PokemonContainer.PARTY,
            hasChange = true,
            delete = false,
            pokemon = after.pokemon.sortedBy { it.containerSlot },
        ))
    session.send(itemStackUpdatePacket(itemId, after.items[itemId] ?: 0))
  }
}
