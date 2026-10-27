package de.fiereu.openmmo.server.game.progression

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.EggGroup
import de.fiereu.openmmo.common.enums.GrowthRate
import de.fiereu.openmmo.pokemon.SpeciesDef
import de.fiereu.openmmo.server.game.battle.ExpCurves
import de.fiereu.openmmo.server.game.storage.StoredCharacter

/** The pure Gen 3 day care rules of the decomp's daycare.c. */
object DaycareRules {
  const val SLOTS = 2
  /** Steps per egg cycle, and between two egg rolls (`stepCounter == 255`). */
  const val CYCLE_STEPS = 255
  const val EGG_HATCH_LEVEL = 5

  const val INCOMPATIBLE = 0
  const val LOW = 20
  const val MED = 50
  const val MAX = 70

  private const val MALE_ONLY = 0
  private const val FEMALE_ONLY = 254
  private const val GENDERLESS = 255

  enum class Gender {
    MALE,
    FEMALE,
    NONE,
  }

  /** GetGenderFromSpeciesAndPersonality. */
  fun gender(def: SpeciesDef, personality: Int): Gender =
      when (def.genderRatio) {
        GENDERLESS -> Gender.NONE
        MALE_ONLY -> Gender.MALE
        FEMALE_ONLY -> Gender.FEMALE
        else -> if ((personality and 0xFF) < def.genderRatio) Gender.FEMALE else Gender.MALE
      }

  /** The steps a day care monster walked since it was left (one exp per step). */
  fun steps(stored: StoredCharacter, mon: Pokemon): Int =
      stored.storyVars["${ProgressKeys.DAYCARE_XP_PREFIX}${mon.id}"] ?: 0

  /** GetLevelAfterDaycareSteps minus the level it was left at. */
  fun levelsGained(stored: StoredCharacter, mon: Pokemon, rate: GrowthRate?): Int =
      0.coerceAtLeast(levelAfter(stored, mon, rate) - mon.level)

  /** The exp the monster has once its steps are added, capped at level 100. */
  fun xpAfter(stored: StoredCharacter, mon: Pokemon, rate: GrowthRate): Int =
      (mon.xp + steps(stored, mon)).coerceAtMost(ExpCurves.totalXpFor(rate, ExpCurves.MAX_LEVEL))

  fun levelAfter(stored: StoredCharacter, mon: Pokemon, rate: GrowthRate?): Int {
    if (rate == null) return mon.level.toInt()
    return ExpCurves.levelFor(rate, xpAfter(stored, mon, rate)).coerceAtLeast(mon.level.toInt())
  }

  /** GetDaycareCostForSelectedMon: 100 plus 100 per level gained. */
  fun fee(levelsGained: Int): Int = 100 + 100 * levelsGained

  /** GetDaycareCompatibilityScore; [ot] stands in for the trainer id. */
  fun compatibility(a: Pokemon, aDef: SpeciesDef, b: Pokemon, bDef: SpeciesDef): Int {
    val ga = setOf(aDef.eggGroup1, aDef.eggGroup2)
    val gb = setOf(bDef.eggGroup1, bDef.eggGroup2)
    if (EggGroup.NO_EGGS_DISCOVERED in ga || EggGroup.NO_EGGS_DISCOVERED in gb) return INCOMPATIBLE
    val dittoA = EggGroup.DITTO in ga
    val dittoB = EggGroup.DITTO in gb
    if (dittoA && dittoB) return INCOMPATIBLE
    val sameTrainer = a.ot == b.ot
    if (dittoA || dittoB) return if (sameTrainer) LOW else MED
    val sa = gender(aDef, a.seed)
    val sb = gender(bDef, b.seed)
    if (sa == sb || sa == Gender.NONE || sb == Gender.NONE) return INCOMPATIBLE
    if (ga.intersect(gb).isEmpty()) return INCOMPATIBLE
    return if (a.dexId == b.dexId) {
      if (sameTrainer) MED else MAX
    } else {
      if (sameTrainer) LOW else MED
    }
  }

  /** Which of two compatible parents is the mother (the female, or the non-Ditto one). */
  fun motherIndex(mons: List<Pokemon>, defs: List<SpeciesDef>): Int {
    for (i in mons.indices) {
      if (EggGroup.DITTO in setOf(defs[i].eggGroup1, defs[i].eggGroup2)) return 1 - i
    }
    return if (gender(defs[0], mons[0].seed) == Gender.FEMALE) 0 else 1
  }

  /** The decomp's `HatchEgg` text "Huh?" / "…hatched from the EGG!". */
  fun hatchText(name: String) = "Huh? $name hatched from the EGG!"
}
