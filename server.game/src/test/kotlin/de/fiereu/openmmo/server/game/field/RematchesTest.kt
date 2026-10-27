package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.trainer.RematchEntry
import de.fiereu.openmmo.trainer.RematchTable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/** vs_seeker.c / battle_setup.c rematch rules. */
class RematchesTest :
    FunSpec({
      // Route 3 Youngster Ben: {BEN, BEN_2, SKIP, BEN_3, BEN_4}.
      val ben = RematchEntry(listOf(89, 101, -1, 498, 499), "MAP_ROUTE3")
      val all = { _: String -> true }

      test("the tables are generated from both decomps") {
        RematchTable.entries(Region.KANTO).shouldNotBeEmpty()
        RematchTable.entries(Region.HOENN).shouldNotBeEmpty()
        RematchTable.byFirst(Region.KANTO, 89) shouldBe ben
        RematchTable.entries(Region.HOENN).count { it.wally } shouldBe 1
        // Gym leaders and the Elite Four are not random rematches.
        RematchTable.entries(Region.HOENN).none { it.map == "MAP_RUSTBORO_CITY" } shouldBe true
      }

      test("GetNextAvailableRematchTrainer: the first unbeaten tier, skipping SKIP") {
        Rematches.frlgNextTier(ben) { false } shouldBe 1
        Rematches.frlgNextTier(ben) { it == 101 } shouldBe 3
        Rematches.frlgNextTier(ben) { it in setOf(101, 498) } shouldBe 4
        Rematches.frlgNextTier(ben) { true } shouldBe 4
        Rematches.frlgNextTier(RematchEntry(listOf(5), "MAP_X")) { false } shouldBe 0
      }

      test("a tier whose story gate is closed drops to the highest tier below it") {
        // Tier 3 needs FLAG_WORLD_MAP_FUCHSIA_CITY; below BEN_3 only tier 1 is not skipped.
        Rematches.frlgGatedTier(ben, 3) { it != "FLAG_WORLD_MAP_FUCHSIA_CITY" } shouldBe 1
        Rematches.frlgGatedTier(ben, 3, all) shouldBe 3
        Rematches.frlgRematchTrainer(ben, { it == 101 }, all) shouldBe 498
        Rematches.frlgRematchTrainer(ben, { it == 101 }) { false } shouldBe 101
      }

      test("Emerald: the first unbeaten rematch party, the last once all are beaten") {
        val rose = RematchEntry(listOf(37, 38, 39, 40, 41), "MAP_ROUTE118")
        Rematches.emeraldRematchTrainer(rose) { it == 37 } shouldBe 38
        Rematches.emeraldRematchTrainer(rose) { it <= 39 } shouldBe 40
        Rematches.emeraldRematchTrainer(rose) { true } shouldBe 41
      }

      test("VS Seeker range is 7 tiles across and 5 up or down") {
        Rematches.inVsSeekerRange(10, 10, 17, 15) shouldBe true
        Rematches.inVsSeekerRange(10, 10, 18, 10) shouldBe false
        Rematches.inVsSeekerRange(10, 10, 10, 4) shouldBe false
      }

      test("map constants match map dir names") {
        Rematches.sameMap("MAP_ROUTE118", "Route118") shouldBe true
        Rematches.sameMap("MAP_ABANDONED_SHIP_ROOMS2_1F", "AbandonedShip_Rooms2_1F") shouldBe true
        Rematches.sameMap("MAP_MT_PYRE_6F", "MtPyre_6F") shouldBe true
        Rematches.sameMap("MAP_ROUTE11", "Route118") shouldBe false
      }
    })
