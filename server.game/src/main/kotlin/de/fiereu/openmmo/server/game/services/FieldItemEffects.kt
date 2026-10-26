package de.fiereu.openmmo.server.game.services

import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.generated.Items

/** What a bag item does when used in the field. */
sealed interface FieldItemEffect {
  /** Restores [amount] HP to a conscious monster; [Int.MAX_VALUE] fills it. */
  data class Heal(val amount: Int) : FieldItemEffect

  /** Brings a fainted monster back with [fraction] of its max HP (1 = full). */
  data class Revive(val fraction: Double) : FieldItemEffect

  /** Restores [amount] PP to every move; [Int.MAX_VALUE] fills them. */
  data class RestoreAllPp(val amount: Int) : FieldItemEffect

  /** Cures a status condition. Status does not outlast a battle here, so it never applies. */
  data object CureStatus : FieldItemEffect

  /** Keeps weaker wild monsters away for [steps] steps. */
  data class Repel(val steps: Int) : FieldItemEffect
}

/**
 * The field effects of the GBA items, from the decomp's `holdEffectParam` (src/data/items.h) and
 * `ItemUseOutOfBattle_*` functions. Items that are not here cannot be used outside a battle.
 */
object FieldItemEffects {
  private const val FULL = Int.MAX_VALUE

  private val table: Map<ItemDef, FieldItemEffect> =
      mapOf(
          Items.POTION to FieldItemEffect.Heal(20),
          Items.SUPER_POTION to FieldItemEffect.Heal(50),
          Items.HYPER_POTION to FieldItemEffect.Heal(200),
          Items.MAX_POTION to FieldItemEffect.Heal(FULL),
          Items.FULL_RESTORE to FieldItemEffect.Heal(FULL),
          Items.FRESH_WATER to FieldItemEffect.Heal(50),
          Items.SODA_POP to FieldItemEffect.Heal(60),
          Items.LEMONADE to FieldItemEffect.Heal(80),
          Items.MOOMOO_MILK to FieldItemEffect.Heal(100),
          Items.ENERGYPOWDER to FieldItemEffect.Heal(50),
          Items.ENERGY_ROOT to FieldItemEffect.Heal(200),
          Items.BERRY_JUICE to FieldItemEffect.Heal(20),
          Items.ORAN_BERRY to FieldItemEffect.Heal(10),
          Items.REVIVE to FieldItemEffect.Revive(0.5),
          Items.MAX_REVIVE to FieldItemEffect.Revive(1.0),
          Items.REVIVAL_HERB to FieldItemEffect.Revive(1.0),
          Items.ELIXIR to FieldItemEffect.RestoreAllPp(10),
          Items.MAX_ELIXIR to FieldItemEffect.RestoreAllPp(FULL),
          Items.ANTIDOTE to FieldItemEffect.CureStatus,
          Items.BURN_HEAL to FieldItemEffect.CureStatus,
          Items.ICE_HEAL to FieldItemEffect.CureStatus,
          Items.AWAKENING to FieldItemEffect.CureStatus,
          Items.PARLYZ_HEAL to FieldItemEffect.CureStatus,
          Items.FULL_HEAL to FieldItemEffect.CureStatus,
          Items.HEAL_POWDER to FieldItemEffect.CureStatus,
          Items.LAVA_COOKIE to FieldItemEffect.CureStatus,
          Items.REPEL to FieldItemEffect.Repel(100),
          Items.SUPER_REPEL to FieldItemEffect.Repel(200),
          Items.MAX_REPEL to FieldItemEffect.Repel(250),
      )

  fun of(item: ItemDef): FieldItemEffect? = table[item]
}
