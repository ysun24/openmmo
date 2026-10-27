package de.fiereu.openmmo.server.game.frontier

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.PokemonMove
import de.fiereu.openmmo.common.enums.EVs
import de.fiereu.openmmo.common.enums.IVs
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.moves.MoveRegistry
import de.fiereu.openmmo.pokemon.SpeciesRegistry
import de.fiereu.openmmo.server.game.battle.BattleChoice
import de.fiereu.openmmo.server.game.battle.BattleEvent
import de.fiereu.openmmo.server.game.battle.BattleInstance
import de.fiereu.openmmo.server.game.battle.BattleMonState
import de.fiereu.openmmo.server.game.battle.BattleRng
import de.fiereu.openmmo.server.game.battle.BattlerRef
import de.fiereu.openmmo.server.game.battle.StatCalculator
import de.fiereu.openmmo.server.game.battle.TurnEngine
import de.fiereu.openmmo.server.game.storage.EntityIdService
import de.fiereu.openmmo.server.game.testsupport.FakeSession
import de.fiereu.openmmo.typechart.TypeChart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDateTime
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put

private val data = FrontierData()
private val speciesRegistry = SpeciesRegistry()
private val moveRegistry = MoveRegistry()
private val factory =
    FrontierMonFactory(speciesRegistry, moveRegistry, ItemRegistry(), EntityIdService())

private fun mon(dex: Int, level: Int, moves: List<Int>, id: Long, seed: Int = 0): Pokemon {
  val m =
      Pokemon(
          id = id,
          ownerId = 0,
          container = PokemonContainer.PARTY,
          containerSlot = 0,
          dexId = dex,
          seed = seed,
          ot = "",
          nickname = "",
          level = level.toByte(),
          hp = 1,
          xp = 0,
          eVs = EVs(),
          iVs = IVs(),
          moves =
              List(4) { i -> moves.getOrNull(i) ?: 0 }
                  .map { PokemonMove(it.toShort(), (moveRegistry.get(it)?.pp ?: 0).toByte()) },
          isShiny = false,
          hasHiddenAbility = false,
          isAlpha = false,
          isSecret = false,
          isFatefulEncounter = false,
          isRaidEncounter = false,
          caughtAt = LocalDateTime.now(),
      )
  return m.copy(hp = StatCalculator.computeAll(speciesRegistry.get(dex)!!, m).hp.toShort())
}

private fun state(m: Pokemon, slot: Int?) =
    BattleMonState(
        m.id,
        speciesRegistry.get(m.dexId)!!,
        slot,
        m,
        StatCalculator.computeAll(speciesRegistry.get(m.dexId)!!, m))

class FrontierRulesTest :
    FunSpec({
      test("r5 merge: brain intros come from the decomp's battle room texts") {
        for (f in Facility.entries) for (gold in listOf(false, true)) {
          val t = BrainTexts.intro(f, gold, "ASH")
          (t != null && t.isNotBlank() && '\\' !in t && '{' !in t) shouldBe true
        }
        BrainTexts.plain("Hi, {PLAYER}!\\nReady?\\pGo$", "ASH") shouldBe "Hi, ASH! Ready? Go"
      }

      test("the decomp tables are all there") {
        data.frontier.mons.size shouldBe 882
        data.frontier.trainers.size shouldBe 300
        data.frontier.trainers[0].name shouldBe "BRADY"
        data.frontier.trainers[299].name shouldBe "GRETEL"
        data.brains.size shouldBe 7
        data.brains.getValue(Facility.TOWER).silver.map { it.species } shouldBe listOf(65, 244, 143)
        data.tents.getValue("slateport").mons.size shouldBe 70
        data.tents.getValue("verdanturf").trainers.size shouldBe 30
        data.bannedSpecies shouldBe setOf(151, 150, 250, 249, 251, 382, 383, 384, 385, 386)
        data.trainerHill.getValue("expert").size shouldBe 4
        data.trainerTower.getValue("knockout").size shouldBe 8
        data.exchange.first { it.id == "LEFTOVERS" }.cost shouldBe 48
        data.exchange.first { it.id == "PROTEIN" }.cost shouldBe 1
        data.pyramidWild50.size shouldBe 20
        data.factoryStyles.size shouldBe 7
        // Every trainer has a mon set, including the macro-built ones (FRONTIER_MONS_*_EXTRA(...)).
        data.frontier.trainers.forEach { it.monSet.size shouldBeGreaterThanOrEqual 12 }
        data.frontier.trainers[48].monSet.take(6) shouldBe listOf(2, 3, 4, 8, 9, 21)
        data.tents.values.forEach { t ->
          t.trainers.forEach { it.monSet.size shouldBeGreaterThanOrEqual 3 }
        }
      }

      test("GetRandomScaledFrontierTrainerId: ranges per challenge, the 7th battle harder") {
        val rng = Random(1)
        repeat(500) {
          FrontierRules.randomScaledTrainerId(0, 0, rng) shouldBeInRange 0..99
          FrontierRules.randomScaledTrainerId(0, 6, rng) shouldBeInRange 100..119
          FrontierRules.randomScaledTrainerId(6, 6, rng) shouldBeInRange 220..239
          FrontierRules.randomScaledTrainerId(12, 3, rng) shouldBeInRange 200..299
        }
        val fought = (0..98).toList()
        FrontierRules.nextTrainerId(0, 0, fought, rng) shouldBe 99
      }

      test("GetFrontierTrainerFixedIvs") {
        FrontierRules.fixedIv(0) shouldBe 3
        FrontierRules.fixedIv(99) shouldBe 3
        FrontierRules.fixedIv(100) shouldBe 6
        FrontierRules.fixedIv(219) shouldBe 21
        FrontierRules.fixedIv(220) shouldBe 31
      }

      test("FillTrainerParty: no repeated species or held item, no high tier at level 50") {
        val rng = Random(7)
        for (tid in listOf(0, 150, 299)) repeat(50) {
          val ids =
              FrontierRules.fillTrainerParty(
                  data.frontier.trainers[tid].monSet, data.frontier.mons, 50, 3, rng)
          ids.size shouldBe 3
          ids.map { data.frontier.mons[it].species }.toSet().size shouldBe 3
          ids.map { data.frontier.mons[it].item }
              .filter { it != "NONE" }
              .let { it.toSet().size shouldBe it.size }
          ids.forEach { it shouldBeLessThanOrEqual FrontierRules.FRONTIER_MONS_HIGH_TIER }
        }
      }

      test("level modes: 50, open = party max but at least 60, tents at least 30") {
        FrontierRules.enemyLevel(LevelMode.LV50, 80) shouldBe 50
        FrontierRules.enemyLevel(LevelMode.OPEN, 45) shouldBe 60
        FrontierRules.enemyLevel(LevelMode.OPEN, 77) shouldBe 77
        FrontierRules.enemyLevel(LevelMode.TENT, 12) shouldBe 30
      }

      test("GetFrontierBrainStatus: Anabel at the 35th and 70th battle, then every 35") {
        FrontierRules.brainStatus(Facility.TOWER, BattleMode.SINGLES, 34, 0) shouldBe
            BrainStatus.SILVER
        FrontierRules.brainStatus(Facility.TOWER, BattleMode.SINGLES, 33, 0) shouldBe
            BrainStatus.NOT_READY
        FrontierRules.brainStatus(Facility.TOWER, BattleMode.SINGLES, 69, 1) shouldBe
            BrainStatus.GOLD
        FrontierRules.brainStatus(Facility.TOWER, BattleMode.SINGLES, 34, 2) shouldBe
            BrainStatus.STREAK
        FrontierRules.brainStatus(Facility.TOWER, BattleMode.SINGLES, 69, 2) shouldBe
            BrainStatus.STREAK_LONG
        FrontierRules.brainStatus(Facility.TOWER, BattleMode.SINGLES, 104, 2) shouldBe
            BrainStatus.STREAK_LONG
        FrontierRules.brainStatus(Facility.TOWER, BattleMode.DOUBLES, 34, 0) shouldBe
            BrainStatus.NOT_READY
        // The Dome counts tournaments with no modifier, the Pyramid floors.
        FrontierRules.brainStatus(Facility.DOME, BattleMode.SINGLES, 4, 0) shouldBe
            BrainStatus.SILVER
        FrontierRules.brainStatus(Facility.PYRAMID, BattleMode.SINGLES, 21, 0) shouldBe
            BrainStatus.SILVER
        FrontierRules.brainStatus(Facility.PALACE, BattleMode.SINGLES, 20, 0) shouldBe
            BrainStatus.SILVER
        FrontierRules.brainStatus(Facility.ARENA, BattleMode.SINGLES, 27, 0) shouldBe
            BrainStatus.SILVER
      }

      test("GiveFacilitySymbol: Silver, then Gold, nothing new on later brain battles") {
        FrontierRules.symbolFor(BrainStatus.SILVER, 0) shouldBe 1
        FrontierRules.symbolFor(BrainStatus.GOLD, 1) shouldBe 2
        FrontierRules.symbolFor(BrainStatus.STREAK, 2) shouldBe null
        FrontierRules.symbolFor(BrainStatus.STREAK_LONG, 2) shouldBe null
      }

      test("GiveBattlePoints: sBattlePointAwards by challenge, +10 for a brain, capped rows") {
        FrontierRules.battlePoints(Facility.TOWER, BattleMode.SINGLES, 1, false) shouldBe 1
        FrontierRules.battlePoints(Facility.TOWER, BattleMode.MULTIS, 1, false) shouldBe 3
        FrontierRules.battlePoints(Facility.TOWER, BattleMode.SINGLES, 5, true) shouldBe 15
        FrontierRules.battlePoints(Facility.PALACE, BattleMode.DOUBLES, 3, false) shouldBe 6
        FrontierRules.battlePoints(Facility.PIKE, BattleMode.SINGLES, 9, false) shouldBe 8
        FrontierRules.battlePoints(Facility.PYRAMID, BattleMode.SINGLES, 100, false) shouldBe 15
        FrontierRules.battlePoints(Facility.ARENA, BattleMode.SINGLES, 11, false) shouldBe 5
      }

      test("CheckPartyIneligibility: eggs, banned species, over 50, repeats are out") {
        val party =
            listOf(
                FrontierRules.EntrantCheck(25, 50, 0, false),
                FrontierRules.EntrantCheck(150, 50, 0, false),
                FrontierRules.EntrantCheck(25, 40, 0, false),
                FrontierRules.EntrantCheck(6, 51, 0, false),
                FrontierRules.EntrantCheck(9, 50, 5, false),
                FrontierRules.EntrantCheck(3, 50, 5, false),
                FrontierRules.EntrantCheck(1, 5, 0, true))
        FrontierRules.eligibleSlots(party, LevelMode.LV50, data.bannedSpecies) shouldBe listOf(0, 4)
        FrontierRules.eligibleSlots(party, LevelMode.OPEN, data.bannedSpecies) shouldBe
            listOf(0, 3, 4)
        FrontierRules.eligibleSlots(
            party, LevelMode.OPEN, data.bannedSpecies, pyramid = true) shouldBe listOf(0, 3)
        FrontierRules.validEntry(
            party, listOf(0, 2, 4), LevelMode.LV50, data.bannedSpecies, 3) shouldBe
            "Two POKéMON of the same kind can't enter."
        FrontierRules.validEntry(
            party, listOf(0, 4, 5), LevelMode.LV50, data.bannedSpecies, 3) shouldBe
            "Two POKéMON can't hold the same item."
        FrontierRules.validEntry(
            party, listOf(0, 1, 4), LevelMode.LV50, data.bannedSpecies, 3) shouldBe
            "That POKéMON is not allowed to enter."
        FrontierRules.validEntry(
            party, listOf(0, 3, 4), LevelMode.LV50, data.bannedSpecies, 3) shouldBe
            "Only POKéMON of Lv. 50 or lower may enter."
        FrontierRules.validEntry(
            party, listOf(0, 3, 4), LevelMode.OPEN, data.bannedSpecies, 3) shouldBe null
        FrontierRules.partySize(Facility.TOWER, BattleMode.DOUBLES) shouldBe 4
        FrontierRules.partySize(Facility.PALACE, BattleMode.DOUBLES) shouldBe 3
        FrontierRules.partySize(Facility.TOWER, BattleMode.MULTIS) shouldBe 2
      }

      test("CreateMonWithEVSpreadNatureOTID: nature, fixed IVs, 510 split over the spread") {
        FrontierRules.evSpread(0b010001).toList() shouldBe listOf(255, 0, 0, 0, 255, 0)
        FrontierRules.evSpread(0b111).toList() shouldBe listOf(170, 170, 170, 0, 0, 0)
        val set = data.frontier.mons[0] // Sunkern: Relaxed, SpAtk|HP, Lax Incense
        val m = factory.facilityMon(set, 50, 9, Random(3))
        m.level.toInt() shouldBe 50
        m.nature.ordinal shouldBe set.nature
        m.iVs.hp shouldBe 9
        m.iVs.spAtk shouldBe 9
        m.eVs.hp shouldBe 255 // Gen 3 per-stat cap
        m.friendship shouldBe 255
        m.heldItem shouldNotBe 0
        // Factory sets lose Return for Frustration and have friendship 0.
        val returner = data.frontier.mons.indexOfFirst { FrontierRules.MOVE_RETURN in it.moves }
        val f =
            factory.facilityMon(data.frontier.mons[returner], 50, 3, Random(1), avoidReturn = true)
        f.moves.map { it.id.toInt() } shouldContain FrontierRules.MOVE_FRUSTRATION
        f.friendship shouldBe 0
      }

      test("Battle Palace: nature tables and move groups") {
        // HARDY 61/7 above half: under 61 attack, under 68 defense, else support.
        PalaceRules.groupFor(0, 60, false) shouldBe PalaceGroup.ATTACK
        PalaceRules.groupFor(0, 61, false) shouldBe PalaceGroup.DEFENSE
        PalaceRules.groupFor(0, 68, false) shouldBe PalaceGroup.SUPPORT
        // LONELY at or below half: 84 attack, 8 defense.
        PalaceRules.groupFor(1, 83, true) shouldBe PalaceGroup.ATTACK
        PalaceRules.groupFor(1, 90, true) shouldBe PalaceGroup.DEFENSE
        PalaceRules.groupFor(1, 92, true) shouldBe PalaceGroup.SUPPORT
        PalaceRules.groupOf(moveRegistry.get(33)!!) shouldBe PalaceGroup.ATTACK // Tackle
        PalaceRules.groupOf(moveRegistry.get(14)!!) shouldBe PalaceGroup.DEFENSE // Swords Dance
        PalaceRules.groupOf(moveRegistry.get(45)!!) shouldBe PalaceGroup.SUPPORT // Growl
        PalaceRules.groupOf(moveRegistry.get(191)!!) shouldBe PalaceGroup.SUPPORT // Spikes
        PalaceRules.flavorLine(0, "X") shouldBe "X is eager for more!"
        PalaceRules.flavorLine(1, "X") shouldBe "A glint appears in X's eyes!"
      }

      test("Battle Palace: no move in the group falls back and fails half the time") {
        val tackleOnly = listOf(moveRegistry.get(33)!! to true)
        var failed = 0
        var picked = 0
        val rng = Random(11)
        repeat(2000) {
          // CAREFUL above half: 42% attack; otherwise defense/support with nothing to use.
          val (group, pick) = PalaceRules.select(23, false, tackleOnly, rng)
          if (pick == null) group.map { it.id } shouldBe listOf(33)
          else if (pick.moveId == null) failed++ else picked++
        }
        (failed + picked) shouldBeGreaterThanOrEqual 1000
        failed shouldBeInRange (picked - 150)..(picked + 150)
        PalaceRules.select(23, false, listOf(moveRegistry.get(33)!! to false), rng).second shouldBe
            PalaceRules.Pick(null, false)
      }

      test("Battle Arena: three categories, 2 for a win, 1 each on a tie") {
        val j = ArenaRules.judge(3 to 1, 2 to 2, 50 to 100, 100 to 100)
        j.mind shouldBe (2 to 0)
        j.skill shouldBe (1 to 1)
        j.body shouldBe (0 to 2)
        j.player shouldBe 3
        j.opponent shouldBe 3
        ArenaRules.skillFor(ArenaRules.MoveOutcome.SUPER_EFFECTIVE) shouldBe 2
        ArenaRules.skillFor(ArenaRules.MoveOutcome.NOT_VERY_EFFECTIVE) shouldBe -1
        ArenaRules.skillFor(ArenaRules.MoveOutcome.NO_EFFECT) shouldBe -2
        ArenaRules.skillFor(ArenaRules.MoveOutcome.ABILITY_BLOCKED) shouldBe -3
        data.mindRatings[33] shouldBe 1 // Tackle
      }

      test("Battle Arena hooks: a three-turn draw faints both monsters") {
        val engine = TurnEngine(moveRegistry, TypeChart())
        val me = state(mon(129, 50, listOf(150), 0x1C001L), 0) // Magikarp, Splash
        val foe = state(mon(129, 50, listOf(150), 0x3C001L), null)
        val battle =
            BattleInstance(1L, 100L, FakeSession(100L), listOf(me), listOf(foe), BattleRng(5))
        val hooks = ArenaHooks(data.mindRatings, "ASH", "FOE")
        battle.facility = hooks
        battle.facilityBattle = true
        var extra = emptyList<BattleEvent>()
        repeat(3) {
          hooks.beforeTurn(engine, battle)
          val ev =
              engine.resolve(
                  battle, listOf(BattleChoice.UseMove(0, 0, 150), BattleChoice.UseMove(1, 0, 150)))
          extra = hooks.afterTurn(engine, battle, ev)
        }
        hooks.lastJudgment!!.player shouldBe 3
        me.fainted shouldBe true
        foe.fainted shouldBe true
        extra.filterIsInstance<BattleEvent.Fainted>().size shouldBe 2
      }

      test("Battle Palace hooks: the player's monster picks its own move") {
        val engine = TurnEngine(moveRegistry, TypeChart())
        val me = state(mon(25, 50, listOf(33, 45), 0x1C002L, seed = 0), 0)
        val foe = state(mon(19, 50, listOf(33), 0x3C002L), null)
        val battle =
            BattleInstance(1L, 100L, FakeSession(100L), listOf(me), listOf(foe), BattleRng(5))
        val hooks = PalaceHooks(Random(2))
        battle.facility = hooks
        hooks.autoPlay shouldBe true
        hooks.allowSwitch shouldBe false
        repeat(50) {
          val (c, line) = hooks.autoChoice(engine, battle, BattlerRef(0, 0))!!
          if (c is BattleChoice.UseMove) listOf(33, 45) shouldContain c.moveId
          else
              line shouldBe
                  "${de.fiereu.openmmo.server.game.battle.BattleNames.token(me)} appears incapable of using its power!"
        }
      }

      test("Battle Factory: rental ranges, fixed IVs, no Unown, distinct species and items") {
        FactoryRules.fixedIv(0, false) shouldBe 3
        FactoryRules.fixedIv(0, true) shouldBe 6
        FactoryRules.fixedIv(5, true) shouldBe 31
        FactoryRules.fixedIv(20, false) shouldBe 31
        FactoryRules.rentalRank(14) shouldBe 0
        FactoryRules.rentalRank(15) shouldBe 1
        FactoryRules.rentalRank(50) shouldBe 5
        FactoryRules.level(LevelMode.OPEN) shouldBe 100
        val rng = Random(9)
        repeat(100) { FactoryRules.monId(LevelMode.LV50, 0, false, rng) shouldBeInRange 110..199 }
        repeat(100) { FactoryRules.monId(LevelMode.LV50, 0, true, rng) shouldBeInRange 162..266 }
        repeat(100) { FactoryRules.monId(LevelMode.OPEN, 9, false, rng) shouldBeInRange 372..881 }
        repeat(30) {
          val r = FactoryRules.initialRentals(data.frontier.mons, LevelMode.LV50, 1, 2, rng)
          r.size shouldBe 6
          r.map { data.frontier.mons[it].species } shouldNotContain 201
          r.map { data.frontier.mons[it].item }
              .filter { it != "NONE" }
              .let { it.toSet().size shouldBe it.size }
          val opp = FactoryRules.opponentMons(data.frontier.mons, LevelMode.LV50, 1, r, rng)
          opp.size shouldBe 3
          opp.forEach { o ->
            r.none { data.frontier.mons[it].species == data.frontier.mons[o].species } shouldBe true
          }
        }
      }

      test("Battle Pike: the hinted door, the other doors' hints, status counts, the queen") {
        val party = List(3) { PikeRules.PartyView(100, 100, 0, setOf("NORMAL"), "", true) }
        val rng = Random(4)
        repeat(200) {
          val hint = PikeRules.hintedRoom(false, false, party, rng)
          hint.index shouldBeInRange 0..2
          PikeRules.roomBehind(hint.index, hint, false, party, rng) shouldBe hint.type
          val other = PikeRules.roomBehind((hint.index + 1) % 3, hint, false, party, rng)
          other.hint shouldNotBe hint.type.hint
          other shouldNotBe PikeRoom.BRAIN
        }
        // Healing rooms are off when the party is fully healthy.
        repeat(100) {
          val hint = PikeRules.hintedRoom(false, true, party, rng)
          listOf(PikeRoom.HEAL_FULL, PikeRoom.HEAL_PART) shouldNotContain hint.type
        }
        PikeRules.inflict(party, 3, rng)!!.second.size shouldBeLessThanOrEqual 1
        PikeRules.inflict(party, 12, rng)!!.second.size shouldBeLessThanOrEqual 3
        PikeRules.queenStatus(26, 0, 1) shouldBe BrainStatus.SILVER
        PikeRules.queenStatus(138, 1, 1) shouldBe BrainStatus.GOLD
        PikeRules.healsBeforeQueen(0, 0) shouldBe 2
        PikeRules.wildLevel(LevelMode.LV50, 50, 4) shouldBe 46
        PikeRules.wildLevel(LevelMode.OPEN, 55, 4) shouldBe 60
      }

      test("Battle Pyramid: entrance and exit differ, light reach, pickups from the round's list") {
        val rng = Random(8)
        repeat(500) {
          val (e, x) = PyramidRules.entranceAndExit(rng.nextInt(), rng.nextInt())
          e shouldNotBe x
        }
        PyramidRules.entranceAndExit(5, 21) shouldBe (6 to 4)
        PyramidRules.visible(0, 1, 32) shouldBe true
        PyramidRules.visible(0, 2, 32) shouldBe false
        PyramidRules.visible(0, 15, 120) shouldBe true
        PyramidRules.adjacent(5, 6) shouldBe true
        PyramidRules.adjacent(3, 4) shouldBe false
        val item = PyramidRules.pickup(0, 0, data.pyramidPickupSlots, data.pyramidPickup50[0])
        item shouldBe data.pyramidPickup50[0][0]
        PyramidRules.floorTemplate(0, data.pyramidFloorOptions, rng) shouldBeInRange 0..3
        repeat(100) { PyramidRules.wildLevel(LevelMode.LV50, 50, 35, rng) shouldBeInRange 30..40 }
      }

      test("Battle Dome: the bracket's opponents per round and the two-of-three pick") {
        DomeRules.opponentsLeaves(0, 0) shouldBe 1..1
        DomeRules.opponentsLeaves(0, 1) shouldBe 2..3
        DomeRules.opponentsLeaves(0, 3) shouldBe 8..15
        DomeRules.opponentsLeaves(13, 1) shouldBe 14..15
        DomeRules.BRACKET.sorted() shouldBe (0..15).toList()
        DomeRules.pickTwo(intArrayOf(5, 1, 9), Random(1)) shouldBe listOf(0, 2)
        DomeRules.effectivenessPoints(40, "aivsai") shouldBe 12
        DomeRules.effectivenessPoints(0, "bad") shouldBe 8
      }

      test("the frontier save survives its JSON") {
        val s = FrontierSave(battlePoints = 12)
        s.symbols["tower"] = 1
        s.streak("tower:singles:50").apply {
          current = 14
          best = 21
          active = true
        }
        s.hillBest["normal"] = 61_000
        val back = FrontierSave.fromJson(12, s.toJson().toString())
        back.symbolsOf(Facility.TOWER) shouldBe 1
        back.streak("tower:singles:50") shouldBe Streak(14, 21, true)
        back.hillBest["normal"] shouldBe 61_000L
        val repo = InMemoryFrontierRepository()
        runBlocking {
          repo.save(7, s)
          repo.load(7).battlePoints shouldBe 12
        }
        s.addPoints(20_000)
        s.battlePoints shouldBe FrontierRules.MAX_BATTLE_FRONTIER_POINTS
      }

      test("a REST keeps the paused challenge: team (hp, status, pp, evs), step and floor state") {
        val mon =
            de.fiereu.openmmo.server.game.services.trade
                .tradeMon(9, 0, 376)
                .copy(hp = 17, status = 0x08, heldItem = 5021, friendship = 200)
        mon.eVs.atk = 255
        val rest =
            FrontierRest(
                "pyramid",
                "pyramid:singles:50",
                3,
                listOf(mon),
                kotlinx.serialization.json.buildJsonObject {
                  put("lvl", "50")
                  put("at", 6)
                  put("fought", FrontierRest.ints(listOf(4, 99)))
                  put("partner", FrontierMonJson.encodeAll(listOf(mon)))
                })
        val s = FrontierSave()
        s.rest = rest
        val back = FrontierSave.fromJson(0, s.toJson().toString()).rest!!
        back.desk shouldBe "pyramid"
        back.key shouldBe "pyramid:singles:50"
        back.step shouldBe 3
        back.team shouldBe listOf(mon)
        back.team.single().eVs.atk shouldBe 255
        back.int("at") shouldBe 6
        back.string("lvl") shouldBe "50"
        back.ints("fought") shouldBe listOf(4, 99)
        back.mons("partner") shouldBe listOf(mon)
        FrontierSave.fromJson(0, FrontierSave().toJson().toString()).rest shouldBe null
      }
    })
