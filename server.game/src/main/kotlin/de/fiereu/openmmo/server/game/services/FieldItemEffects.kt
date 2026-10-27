package de.fiereu.openmmo.server.game.services

import de.fiereu.openmmo.common.MonStatus
import de.fiereu.openmmo.items.ItemDef
import de.fiereu.openmmo.items.generated.Items
import de.fiereu.openmmo.progression.Gen3Data

/** Which stat an EV item works on. */
enum class EvStat {
  HP,
  ATK,
  DEF,
  SPEED,
  SPATK,
  SPDEF,
}

/** What a bag item does when used in the field. */
sealed interface FieldItemEffect {
  /** Restores [amount] HP to a conscious monster ([Int.MAX_VALUE] fills it), curing [cures]. */
  data class Heal(val amount: Int, val cures: Int = 0) : FieldItemEffect

  /** Brings a fainted monster back with [fraction] of its max HP (1 = full). */
  data class Revive(val fraction: Double) : FieldItemEffect

  /** Restores [amount] PP to every move; [Int.MAX_VALUE] fills them. */
  data class RestoreAllPp(val amount: Int) : FieldItemEffect

  /** Restores [amount] PP to one chosen move; [Int.MAX_VALUE] fills it. */
  data class RestorePp(val amount: Int) : FieldItemEffect

  /** Cures the status bits in [mask]. */
  data class CureStatus(val mask: Int) : FieldItemEffect

  /** Keeps weaker wild monsters away for [steps] steps. */
  data class Repel(val steps: Int) : FieldItemEffect

  /** One level up, with its moves and a possible evolution. */
  data object RareCandy : FieldItemEffect

  /** Evolves a species that evolves by this item. */
  data object EvolutionStone : FieldItemEffect

  /** +10 EVs in [stat], to at most 100 there and 510 in total. */
  data class Vitamin(val stat: EvStat) : FieldItemEffect

  /** -10 EVs in [stat] (Pomeg & co.). */
  data class EvBerry(val stat: EvStat) : FieldItemEffect

  /** Raises one move's max PP by [steps] fifths of its base, to at most three. */
  data class PpUp(val steps: Int) : FieldItemEffect

  /** Teaches TM/HM number [index] (TM01 = 0, HM01 = 50). */
  data class TeachMachine(val index: Int) : FieldItemEffect

  /** Leads the player out of a cave or dungeon. */
  data object EscapeRope : FieldItemEffect
}

/**
 * The field effects of the GBA items, from the decomp's item effect tables (src/data/pokemon/
 * item_effects.h, src/data/items.h) and `ItemUseOutOfBattle_*`. Items that are not here cannot be
 * used outside a battle.
 */
object FieldItemEffects {
  private const val FULL = Int.MAX_VALUE

  private val table: Map<ItemDef, FieldItemEffect> =
      mapOf(
          Items.POTION to FieldItemEffect.Heal(20),
          Items.SUPER_POTION to FieldItemEffect.Heal(50),
          Items.HYPER_POTION to FieldItemEffect.Heal(200),
          Items.MAX_POTION to FieldItemEffect.Heal(FULL),
          Items.FULL_RESTORE to FieldItemEffect.Heal(FULL, MonStatus.ANY),
          Items.FRESH_WATER to FieldItemEffect.Heal(50),
          Items.SODA_POP to FieldItemEffect.Heal(60),
          Items.LEMONADE to FieldItemEffect.Heal(80),
          Items.MOOMOO_MILK to FieldItemEffect.Heal(100),
          Items.ENERGYPOWDER to FieldItemEffect.Heal(50),
          Items.ENERGY_ROOT to FieldItemEffect.Heal(200),
          Items.BERRY_JUICE to FieldItemEffect.Heal(20),
          Items.ORAN_BERRY to FieldItemEffect.Heal(10),
          Items.SITRUS_BERRY to FieldItemEffect.Heal(30),
          Items.REVIVE to FieldItemEffect.Revive(0.5),
          Items.MAX_REVIVE to FieldItemEffect.Revive(1.0),
          Items.REVIVAL_HERB to FieldItemEffect.Revive(1.0),
          Items.ETHER to FieldItemEffect.RestorePp(10),
          Items.MAX_ETHER to FieldItemEffect.RestorePp(FULL),
          Items.LEPPA_BERRY to FieldItemEffect.RestorePp(10),
          Items.ELIXIR to FieldItemEffect.RestoreAllPp(10),
          Items.MAX_ELIXIR to FieldItemEffect.RestoreAllPp(FULL),
          Items.ANTIDOTE to FieldItemEffect.CureStatus(MonStatus.ANY_POISON),
          Items.PECHA_BERRY to FieldItemEffect.CureStatus(MonStatus.ANY_POISON),
          Items.BURN_HEAL to FieldItemEffect.CureStatus(MonStatus.BURN),
          Items.RAWST_BERRY to FieldItemEffect.CureStatus(MonStatus.BURN),
          Items.ICE_HEAL to FieldItemEffect.CureStatus(MonStatus.FREEZE),
          Items.ASPEAR_BERRY to FieldItemEffect.CureStatus(MonStatus.FREEZE),
          Items.AWAKENING to FieldItemEffect.CureStatus(MonStatus.SLEEP),
          Items.CHESTO_BERRY to FieldItemEffect.CureStatus(MonStatus.SLEEP),
          Items.PARLYZ_HEAL to FieldItemEffect.CureStatus(MonStatus.PARALYSIS),
          Items.CHERI_BERRY to FieldItemEffect.CureStatus(MonStatus.PARALYSIS),
          Items.FULL_HEAL to FieldItemEffect.CureStatus(MonStatus.ANY),
          Items.HEAL_POWDER to FieldItemEffect.CureStatus(MonStatus.ANY),
          Items.LAVA_COOKIE to FieldItemEffect.CureStatus(MonStatus.ANY),
          Items.LUM_BERRY to FieldItemEffect.CureStatus(MonStatus.ANY),
          Items.REPEL to FieldItemEffect.Repel(100),
          Items.SUPER_REPEL to FieldItemEffect.Repel(200),
          Items.MAX_REPEL to FieldItemEffect.Repel(250),
          Items.RARE_CANDY to FieldItemEffect.RareCandy,
          Items.FIRE_STONE to FieldItemEffect.EvolutionStone,
          Items.WATER_STONE to FieldItemEffect.EvolutionStone,
          Items.THUNDERSTONE to FieldItemEffect.EvolutionStone,
          Items.LEAF_STONE to FieldItemEffect.EvolutionStone,
          Items.MOON_STONE to FieldItemEffect.EvolutionStone,
          Items.SUN_STONE to FieldItemEffect.EvolutionStone,
          Items.HP_UP to FieldItemEffect.Vitamin(EvStat.HP),
          Items.PROTEIN to FieldItemEffect.Vitamin(EvStat.ATK),
          Items.IRON to FieldItemEffect.Vitamin(EvStat.DEF),
          Items.CARBOS to FieldItemEffect.Vitamin(EvStat.SPEED),
          Items.CALCIUM to FieldItemEffect.Vitamin(EvStat.SPATK),
          Items.ZINC to FieldItemEffect.Vitamin(EvStat.SPDEF),
          Items.POMEG_BERRY to FieldItemEffect.EvBerry(EvStat.HP),
          Items.KELPSY_BERRY to FieldItemEffect.EvBerry(EvStat.ATK),
          Items.QUALOT_BERRY to FieldItemEffect.EvBerry(EvStat.DEF),
          Items.HONDEW_BERRY to FieldItemEffect.EvBerry(EvStat.SPATK),
          Items.GREPA_BERRY to FieldItemEffect.EvBerry(EvStat.SPDEF),
          Items.TAMATO_BERRY to FieldItemEffect.EvBerry(EvStat.SPEED),
          Items.PP_UP to FieldItemEffect.PpUp(1),
          Items.PP_MAX to FieldItemEffect.PpUp(3),
          Items.ESCAPE_ROPE to FieldItemEffect.EscapeRope,
      )

  fun of(item: ItemDef): FieldItemEffect? =
      table[item] ?: Gen3Data.tmhmIndex(item)?.let { FieldItemEffect.TeachMachine(it) }

  /** Effects that ask the player which move first (the decomp's move select window). */
  fun needsMove(effect: FieldItemEffect): Boolean =
      effect is FieldItemEffect.RestorePp || effect is FieldItemEffect.PpUp

  /** What the bag shows: "mon" picks a party monster, "move" a monster and then a move,
   * "teach" a monster that can learn it, "self" uses it on the spot, null not usable here. */
  fun useKind(item: ItemDef): String? =
      when (val e = of(item)) {
        null -> null
        is FieldItemEffect.Repel,
        FieldItemEffect.EscapeRope -> "self"
        is FieldItemEffect.TeachMachine -> "teach"
        else -> if (needsMove(e)) "move" else "mon"
      }
}
