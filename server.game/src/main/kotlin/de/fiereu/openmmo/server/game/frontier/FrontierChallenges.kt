package de.fiereu.openmmo.server.game.frontier

import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.Ability
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.battle.FacilityHooks
import kotlin.random.Random
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The facility challenges, one coroutine per desk visit. Each facility follows its decomp source;
 * see the KDoc of each function for the file it transcribes.
 */
class FrontierChallenges(private val svc: FrontierService, private val run: FrontierRun) {
  private val rng: Random = run.rng
  private val data = svc.data

  // --- screens ---------------------------------------------------------------------------------

  private fun screen(kind: String, title: String, body: JsonObjectBuilder.() -> Unit = {}) =
      buildJsonObject {
        put("kind", kind)
        put("title", title)
        body()
      }

  private suspend fun menu(
      title: String,
      text: String,
      options: List<Pair<String, String>>
  ): String {
    val a =
        run.ask(
            screen("menu", title) {
              put("text", text)
              put(
                  "options",
                  buildJsonArray {
                    for ((id, label) in options) add(
                        buildJsonObject {
                          put("id", id)
                          put("label", label)
                        })
                  })
            })
    val v = a["v"]?.jsonPrimitive?.contentOrNull ?: "cancel"
    return if (options.any { it.first == v }) v else "cancel"
  }

  private suspend fun message(title: String, text: String) {
    run.ask(screen("message", title) { put("text", text) })
  }

  private fun facilityTitle(f: Facility) = f.title

  // --- desk dispatch ---------------------------------------------------------------------------

  suspend fun runDesk() {
    val save = svc.save(run.charId)
    val d = run.desk
    val parts = d.split(":")
    val rest = save.rest
    if (rest != null && parts[0] !in setOf("card", "exchange")) {
      if (rest.desk != d) {
        return message(
            "BATTLE FRONTIER",
            "Your ${deskTitle(rest.desk)} challenge is on hold. Please continue it there first.")
      }
      // The paused challenge resumes once (Emerald clears frontierChallengePaused on resume).
      save.rest = null
      svc.persist(run.charId)
      message(deskTitle(d), "We've been waiting for you! Let's continue your challenge.")
      when (parts[0]) {
        "tower" -> tower(BattleMode.byKey(parts.getOrNull(1)), rest)
        "palace" -> streakFacility(Facility.PALACE, BattleMode.byKey(parts.getOrNull(1)), rest)
        "arena" -> streakFacility(Facility.ARENA, BattleMode.SINGLES, rest)
        "factory" -> factory(BattleMode.byKey(parts.getOrNull(1)), tent = false, resume = rest)
        "pike" -> pike(rest)
        "pyramid" -> pyramid(rest)
      }
      return
    }
    when (parts[0]) {
      "card" -> card()
      "exchange" -> exchange(parts.getOrElse(1) { "Vitamins" })
      "tower" -> tower(BattleMode.byKey(parts.getOrNull(1)))
      "dome" -> dome(BattleMode.byKey(parts.getOrNull(1)))
      "palace" -> streakFacility(Facility.PALACE, BattleMode.byKey(parts.getOrNull(1)))
      "arena" -> streakFacility(Facility.ARENA, BattleMode.SINGLES)
      "factory" -> factory(BattleMode.byKey(parts.getOrNull(1)), tent = false)
      "pike" -> pike()
      "pyramid" -> pyramid()
      "tent" -> tent(parts.getOrElse(1) { "verdanturf" })
      "hill" -> trainerHill()
      "ttower" -> trainerTower()
      else -> message("BATTLE FRONTIER", "This desk is closed.")
    }
  }

  // --- common pieces ---------------------------------------------------------------------------

  private fun deskTitle(desk: String): String =
      when (desk.substringBefore(":")) {
        "tower" -> "BATTLE TOWER"
        "palace" -> "BATTLE PALACE"
        "arena" -> "BATTLE ARENA"
        "factory" -> "BATTLE FACTORY"
        "pike" -> "BATTLE PIKE"
        "pyramid" -> "BATTLE PYRAMID"
        "dome" -> "BATTLE DOME"
        else -> "BATTLE FRONTIER"
      }

  /**
   * REST: the challenge is saved (streak, the next battle, the team and the facility's state) and
   * ends here; talking to the same desk resumes it.
   */
  private suspend fun rest(
      title: String,
      key: String,
      step: Int,
      team: List<Pokemon>,
      extra: JsonObjectBuilder.() -> Unit = {}
  ) {
    val save = svc.save(run.charId)
    save.rest = FrontierRest(run.desk, key, step, team, buildJsonObject(extra))
    svc.persist(run.charId)
    message(
        title,
        "Your challenge has been saved. Please rest, then talk to me again to continue where you left off.")
  }

  private suspend fun chooseLevel(title: String): LevelMode? =
      when (menu(
          title,
          "Which level do you wish to challenge?",
          listOf("50" to "LV. 50", "open" to "OPEN LEVEL", "cancel" to "CANCEL"))) {
        "50" -> LevelMode.LV50
        "open" -> LevelMode.OPEN
        else -> null
      }

  /** The party as the team screen shows it, with whether each monster may enter. */
  private fun teamScreen(
      title: String,
      text: String,
      count: Int,
      mode: LevelMode,
      pyramid: Boolean,
      party: List<Pokemon>
  ): JsonObject {
    val checks =
        party.map { FrontierRules.EntrantCheck(it.dexId, it.level.toInt(), it.heldItem, it.isEgg) }
    return screen("team", title) {
      put("text", text)
      put("count", count)
      put(
          "party",
          buildJsonArray {
            for ((i, m) in party.withIndex()) {
              val c = checks[i]
              val why =
                  when {
                    c.isEgg -> "EGG"
                    c.species in data.bannedSpecies -> "BANNED"
                    mode == LevelMode.LV50 && c.level > 50 -> "OVER LV. 50"
                    pyramid && c.heldItem != 0 -> "HOLDS AN ITEM"
                    else -> null
                  }
              add(
                  buildJsonObject {
                    for ((k, v) in svc.card(m)) put(k, v)
                    put("slot", i)
                    put("ok", why == null)
                    why?.let { put("why", it) }
                  })
            }
          })
    }
  }

  /**
   * ChoosePartyForBattleFrontier with CheckPartyIneligibility first: null when the party can't
   * enter or the player backs out. The entrants come back healed (the facility heals on entry).
   */
  private suspend fun chooseTeam(
      title: String,
      count: Int,
      mode: LevelMode,
      pyramid: Boolean = false
  ): List<Pokemon>? {
    val party = svc.party(run.charId)
    val checks =
        party.map { FrontierRules.EntrantCheck(it.dexId, it.level.toInt(), it.heldItem, it.isEgg) }
    val eligible = FrontierRules.eligibleSlots(checks, mode, data.bannedSpecies, pyramid)
    if (eligible.size < count) {
      message(
          title,
          "You need $count different POKéMON${if (mode == LevelMode.LV50) " of Lv. 50 or lower" else ""}" +
              " that hold different items${if (pyramid) " (holding nothing in the PYRAMID)" else ""}. " +
              "EGGS, MEW, MEWTWO, LUGIA, HO-OH, CELEBI, KYOGRE, GROUDON, RAYQUAZA, JIRACHI and DEOXYS can't enter.")
      return null
    }
    while (true) {
      val a =
          run.ask(
              teamScreen(
                  title,
                  "Please choose the $count POKéMON that will enter.",
                  count,
                  mode,
                  pyramid,
                  party))
      if (a["v"]?.jsonPrimitive?.contentOrNull == "cancel") return null
      val slots = svc.ints(a, "slots")
      val err = FrontierRules.validEntry(checks, slots, mode, data.bannedSpecies, count, pyramid)
      if (err == null) return slots.map { svc.mons.entrant(party[it]) }
      message(title, err)
    }
  }

  private fun streakKey(f: Facility, mode: BattleMode, lvl: LevelMode) =
      "${f.key}:${mode.key}:${lvl.key}"

  private fun partyMax(team: List<Pokemon>) = team.maxOfOrNull { it.level.toInt() } ?: 50

  /** A frontier trainer's opponent team (FillTrainerParty). */
  private fun trainerOpponent(
      trainerId: Int,
      level: Int,
      count: Int,
      table: FacilitySet = data.frontier,
      ivOverride: Int? = null,
      taken: List<Int> = emptyList()
  ): Pair<FacilityOpponent, List<Int>> {
    val t = table.trainers[trainerId]
    val ids = FrontierRules.fillTrainerParty(t.monSet, table.mons, level, count, rng, taken)
    val iv = ivOverride ?: FrontierRules.fixedIv(trainerId)
    val ms = ids.map { svc.mons.facilityMon(table.mons[it], level, iv, rng) }
    return FacilityOpponent(
        "${t.trainerClass.replace('_', ' ')} ${t.name}",
        t.pic,
        t.trainerClass,
        ms,
        intro = "${t.name}: ${t.speechBefore}") to ids
  }

  private fun brainOpponent(f: Facility, status: BrainStatus, level: Int): FacilityOpponent {
    val b = data.brains.getValue(f)
    val team = (if (status.goldTeam) b.gold else b.silver).map { svc.mons.brainMon(it, level, rng) }
    val title = b.trainerClass.replace('_', ' ')
    return FacilityOpponent(
        "$title ${b.name}",
        b.pic,
        b.trainerClass,
        team,
        intro =
            "$title ${b.name}: " +
                (BrainTexts.intro(f, status.goldTeam, run.playerName) ?: "Show me your power!"),
        brain = true)
  }

  /** Symbols for a beaten brain (GiveFacilitySymbol): silver first, then gold. */
  private suspend fun brainBeaten(f: Facility, status: BrainStatus): String? {
    val s = svc.save(run.charId)
    s.brainsBattled += f.key
    val got = FrontierRules.symbolFor(status, s.symbolsOf(f)) ?: return null
    s.symbols[f.key] = got
    svc.persist(run.charId)
    return if (got == 1) "${f.title.removePrefix("BATTLE ")} SYMBOL (SILVER)"
    else "${f.title.removePrefix("BATTLE ")} SYMBOL (GOLD)"
  }

  /** The screen between two battles: continue or retire. */
  private suspend fun between(
      title: String,
      text: String,
      team: List<Pokemon>,
      preview: FacilityOpponent?,
      extra: JsonObjectBuilder.() -> Unit = {}
  ): Boolean = betweenChoice(title, text, team, preview, false, extra) != "retire"

  /** The between screen with REST offered: "continue", "retire" or "rest". */
  private suspend fun betweenChoice(
      title: String,
      text: String,
      team: List<Pokemon>,
      preview: FacilityOpponent?,
      canRest: Boolean = true,
      extra: JsonObjectBuilder.() -> Unit = {}
  ): String {
    val a =
        run.ask(
            screen("between", title) {
              put("text", text)
              if (canRest) put("rest", true)
              put("team", buildJsonArray { team.forEach { add(svc.card(it)) } })
              preview?.let {
                put("opponent", it.name)
                put("opponentPic", it.pic.lowercase())
                put("brain", it.brain)
              }
              extra()
            })
    return when (val v = a["v"]?.jsonPrimitive?.contentOrNull) {
      "retire",
      "rest" -> if (v == "rest" && !canRest) "continue" else v
      else -> "continue"
    }
  }

  private suspend fun lose(key: String, title: String, text: String) {
    val st = svc.save(run.charId).streak(key)
    st.current = 0
    st.active = false
    svc.persist(run.charId)
    message(title, text)
  }

  private suspend fun winRound(
      f: Facility,
      mode: BattleMode,
      key: String,
      challengeNum: Int,
      brain: Boolean,
      title: String,
      extraText: String = ""
  ) {
    val st = svc.save(run.charId).streak(key)
    st.active = true
    val bp = FrontierRules.battlePoints(f, mode, challengeNum, brain)
    svc.givePoints(run, bp)
    message(
        title,
        "Congratulations! You have won the challenge! $extraText You've earned $bp Battle Point(s)! (Streak: ${st.current})")
  }

  private suspend fun winBattle(key: String) {
    val st = svc.save(run.charId).streak(key)
    st.current = (st.current + 1).coerceAtMost(FrontierRules.MAX_STREAK)
    if (st.current > st.best) st.best = st.current
  }

  // --- Battle Tower ----------------------------------------------------------------------------

  /** battle_tower.c: 7 battles a round, singles / doubles / multis (NPC partner) / link multis. */
  private suspend fun tower(mode: BattleMode, resume: FrontierRest? = null) {
    val title = "BATTLE TOWER"
    if (resume == null) {
      val choice =
          menu(
              title,
              "Welcome to the BATTLE TOWER! ${mode.name} BATTLE ROOM.",
              listOf("challenge" to "CHALLENGE", "info" to "INFO", "cancel" to "CANCEL"))
      if (choice == "info") {
        message(
            title,
            "Battle seven TRAINERS in a row. Lose once and your streak ends. Each set of seven wins earns Battle Points.")
        return
      }
      if (choice != "challenge") return
    }
    val lvl = resume?.let { LevelMode.byKey(it.string("lvl")) } ?: chooseLevel(title) ?: return
    val size = FrontierRules.partySize(Facility.TOWER, mode)
    if (mode == BattleMode.LINK_MULTIS) return linkMultis(lvl)
    var team = resume?.team ?: chooseTeam(title, size, lvl) ?: return
    val key = streakKey(Facility.TOWER, mode, lvl)
    val save = svc.save(run.charId)
    val st = save.streak(key)
    if (resume == null) {
      if (!st.active) st.current = 0
      st.active = false
    }
    svc.persist(run.charId)
    // Multis with an NPC partner: pick one of the tower's trainers
    // (LoadMultiPartnerCandidatesData).
    var partnerTeam: List<Pokemon> = resume?.mons("partner").orEmpty()
    var partnerName = resume?.string("partnerName").orEmpty()
    if (mode == BattleMode.MULTIS && resume == null) {
      val level = FrontierRules.enemyLevel(lvl, partyMax(team))
      val candidates =
          (0 until 4).map { FrontierRules.randomScaledTrainerId(st.current / 7, 0, rng) }.distinct()
      val pick =
          menu(
              title,
              "Please choose your partner.",
              candidates.map {
                it.toString() to
                    "${data.frontier.trainers[it].trainerClass.replace('_', ' ')} ${data.frontier.trainers[it].name}"
              } + ("cancel" to "CANCEL"))
      val pid = pick.toIntOrNull() ?: return
      val (p, _) = trainerOpponent(pid, level, FrontierRules.FRONTIER_MULTI_PARTY_SIZE)
      partnerTeam = p.mons
      partnerName = data.frontier.trainers[pid].name
    }
    val fought = resume?.ints("fought").orEmpty().toMutableList()
    var brainThisRound = resume?.bool("brain") ?: false
    for (battleNum in (resume?.step ?: 0) until FrontierRules.FRONTIER_STAGES_PER_CHALLENGE) {
      val challengeNum = st.current / FrontierRules.FRONTIER_STAGES_PER_CHALLENGE
      val level = FrontierRules.enemyLevel(lvl, partyMax(team))
      val status =
          FrontierRules.brainStatus(
              Facility.TOWER, mode, st.current, save.symbolsOf(Facility.TOWER))
      val opp: FacilityOpponent
      if (status != BrainStatus.NOT_READY) {
        opp = brainOpponent(Facility.TOWER, status, level)
      } else {
        val count =
            when (mode) {
              BattleMode.DOUBLES -> FrontierRules.FRONTIER_DOUBLES_PARTY_SIZE
              BattleMode.MULTIS -> FrontierRules.FRONTIER_MULTI_PARTY_SIZE
              else -> FrontierRules.FRONTIER_PARTY_SIZE
            }
        val id = FrontierRules.nextTrainerId(challengeNum, battleNum, fought, rng)
        fought += id
        if (mode == BattleMode.MULTIS) {
          // Two opposing trainers of two monsters each.
          val id2 = FrontierRules.nextTrainerId(challengeNum, battleNum, fought, rng)
          fought += id2
          val (a, ids) = trainerOpponent(id, level, count)
          val (b, _) = trainerOpponent(id2, level, count, taken = ids)
          opp =
              FacilityOpponent(
                  "${a.name} & ${b.name}", a.pic, a.trainerClass, a.mons + b.mons, intro = a.intro)
        } else {
          opp = trainerOpponent(id, level, count).first
        }
      }
      when (betweenChoice(
          title, "Battle ${battleNum + 1} of 7. Win streak: ${st.current}.", team, opp)) {
        "retire" -> {
          lose(key, title, "You retired. Your streak of ${st.current} has ended.")
          return
        }
        "rest" ->
            return rest(title, key, battleNum, team) {
              put("lvl", lvl.key)
              put("fought", FrontierRest.ints(fought))
              put("brain", brainThisRound)
              put("partner", FrontierMonJson.encodeAll(partnerTeam))
              put("partnerName", partnerName)
            }
      }
      val hooks = if (mode == BattleMode.MULTIS) NpcPartnerHooks(setOf(1)) else null
      val out =
          svc.battle(
              run,
              team + partnerTeam,
              opp,
              hooks = hooks,
              format = if (mode == BattleMode.SINGLES) 1 else 2,
              partnerFrom = if (mode == BattleMode.MULTIS) team.size else -1)
      if (out.result != BattleResult.VICTORY) {
        lose(key, title, "You lost against ${opp.name}. Your streak was ${st.current}.")
        return
      }
      winBattle(key)
      if (opp.brain) {
        brainThisRound = true
        brainBeaten(Facility.TOWER, status)?.let {
          message(title, "${opp.name}: You've earned this. The $it is yours!")
        }
      }
      svc.persist(run.charId)
      // The tower heals the party between battles.
      team = out.team.take(team.size).map(svc.mons::healed)
      partnerTeam = partnerTeam.map(svc.mons::healed)
    }
    winRound(
        Facility.TOWER,
        mode,
        key,
        st.current / 7,
        brainThisRound,
        title,
        if (partnerName.isNotEmpty()) "$partnerName: We did it!" else "")
  }

  /** The partner's monsters at position 1 are the AI's, the player picks for position 0 only. */
  class NpcPartnerHooks(override val aiPositions: Set<Int>) : FacilityHooks

  /**
   * Link multis with another online player (the decomp's link cable, here two players at the Battle
   * Tower): the first player hosts and waits, the second joins from the same desk.
   */
  private suspend fun linkMultis(lvl: LevelMode) {
    val title = "BATTLE TOWER"
    val hosts =
        svc.linkHosts.values.filter {
          it.mode == lvl && it.run.charId != run.charId && it.run.job?.isActive == true
        }
    val opts =
        hosts.map { "join:${it.run.charId}" to "JOIN ${it.run.playerName}" } +
            ("host" to "WAIT FOR A PARTNER") +
            ("cancel" to "CANCEL")
    val pick = menu(title, "Battle with another TRAINER as your partner.", opts)
    if (pick == "cancel") return
    val team = chooseTeam(title, FrontierRules.FRONTIER_MULTI_PARTY_SIZE, lvl) ?: return
    if (pick.startsWith("join:")) {
      val host = svc.linkHosts[pick.removePrefix("join:").toLong()]
      if (host == null || host.partner.isCompleted) {
        message(title, "That TRAINER is no longer waiting.")
        return
      }
      host.partner.complete(run to team)
      // The host's run drives the challenge and mirrors its screens here.
      run.show(screen("wait", title) { put("text", "Linking with ${host.run.playerName}...") })
      host.run.job?.join()
      return
    }
    val host = FrontierService.LinkHost(run, lvl, team)
    svc.linkHosts[run.charId] = host
    run.show(
        screen("wait", title) {
          put(
              "text",
              "Waiting for a partner to join... (another player can talk to this attendant and JOIN)")
        })
    val (partner, partnerTeam0) =
        try {
          withTimeoutOrNull(5 * 60_000L) { host.partner.await() }
              ?: run {
                message(title, "No partner came.")
                return
              }
        } finally {
          svc.linkHosts.remove(run.charId)
        }
    run.mirror = partner
    try {
      linkChallenge(lvl, team, partner, partnerTeam0)
    } finally {
      run.mirror = null
    }
  }

  private suspend fun linkChallenge(
      lvl: LevelMode,
      team0: List<Pokemon>,
      partner: FrontierRun,
      partnerTeam0: List<Pokemon>
  ) {
    val title = "BATTLE TOWER"
    val mode = BattleMode.LINK_MULTIS
    val key = streakKey(Facility.TOWER, mode, lvl)
    val mine = svc.save(run.charId).streak(key)
    val theirs = svc.save(partner.charId).streak(key)
    if (!mine.active) mine.current = 0
    if (!theirs.active) theirs.current = 0
    // The pair fights at the lower of the two streaks (LoadLinkMultiOpponentsData).
    var team = team0
    var partnerTeam = partnerTeam0
    val fought = mutableListOf<Int>()
    for (battleNum in 0 until 7) {
      val streak = minOf(mine.current, theirs.current)
      val level = FrontierRules.enemyLevel(lvl, maxOf(partyMax(team), partyMax(partnerTeam)))
      val id = FrontierRules.nextTrainerId(streak / 7, battleNum, fought, rng).also { fought += it }
      val id2 =
          FrontierRules.nextTrainerId(streak / 7, battleNum, fought, rng).also { fought += it }
      val (a, ids) = trainerOpponent(id, level, 2)
      val (b, _) = trainerOpponent(id2, level, 2, taken = ids)
      val opp =
          FacilityOpponent(
              "${a.name} & ${b.name}", a.pic, a.trainerClass, a.mons + b.mons, intro = a.intro)
      if (!between(
          title,
          "LINK MULTI battle ${battleNum + 1} of 7 with ${partner.playerName}. Streak: $streak.",
          team + partnerTeam,
          opp)) {
        lose(key, title, "You retired.")
        svc.save(partner.charId).streak(key).apply {
          current = 0
          active = false
        }
        svc.persist(partner.charId)
        return
      }
      val out = svc.battle(run, team, opp, format = 2, partner = partner to partnerTeam)
      if (out.result != BattleResult.VICTORY) {
        svc.save(partner.charId).streak(key).apply {
          current = 0
          active = false
        }
        svc.persist(partner.charId)
        lose(key, title, "You and ${partner.playerName} lost against ${opp.name}.")
        return
      }
      for (s in listOf(mine, theirs)) {
        s.current++
        if (s.current > s.best) s.best = s.current
      }
      svc.persist(run.charId)
      svc.persist(partner.charId)
      val byId = out.team.associateBy { it.id }
      team = team.map { svc.mons.healed(byId[it.id] ?: it) }
      partnerTeam = partnerTeam.map { svc.mons.healed(byId[it.id] ?: it) }
    }
    mine.active = true
    theirs.active = true
    val bp = FrontierRules.battlePoints(Facility.TOWER, mode, mine.current / 7, false)
    svc.givePoints(run, bp)
    svc.save(partner.charId)
        .addPoints(FrontierRules.battlePoints(Facility.TOWER, mode, theirs.current / 7, false))
    svc.persist(partner.charId)
    message(
        title, "You and ${partner.playerName} won all seven battles! You earned $bp Battle Points.")
  }

  // --- Palace / Arena (and their tents) --------------------------------------------------------

  /** battle_palace.c / battle_arena.c: Tower-style 7-battle rounds with their battle rules. */
  private suspend fun streakFacility(f: Facility, mode: BattleMode, resume: FrontierRest? = null) {
    val title = f.title
    val info =
        if (f == Facility.PALACE)
            "In the BATTLE PALACE your POKéMON choose their own moves, by their natures."
        else
            "In the BATTLE ARENA POKéMON battle one on one for three turns. If both remain, the REFEREE judges Mind, Skill and Body."
    if (resume == null) {
      val choice =
          menu(
              title,
              "Welcome to the $title!",
              listOf("challenge" to "CHALLENGE", "info" to "INFO", "cancel" to "CANCEL"))
      if (choice == "info") return message(title, info)
      if (choice != "challenge") return
    }
    val lvl = resume?.let { LevelMode.byKey(it.string("lvl")) } ?: chooseLevel(title) ?: return
    var team = resume?.team ?: chooseTeam(title, FrontierRules.FRONTIER_PARTY_SIZE, lvl) ?: return
    val key = streakKey(f, mode, lvl)
    val save = svc.save(run.charId)
    val st = save.streak(key)
    if (resume == null) {
      if (!st.active) st.current = 0
      st.active = false
    }
    val fought = resume?.ints("fought").orEmpty().toMutableList()
    var brainThisRound = resume?.bool("brain") ?: false
    for (battleNum in (resume?.step ?: 0) until 7) {
      val level = FrontierRules.enemyLevel(lvl, partyMax(team))
      val status = FrontierRules.brainStatus(f, mode, st.current, save.symbolsOf(f))
      val opp =
          if (status != BrainStatus.NOT_READY) brainOpponent(f, status, level)
          else {
            val id =
                FrontierRules.nextTrainerId(st.current / 7, battleNum, fought, rng).also {
                  fought += it
                }
            trainerOpponent(id, level, FrontierRules.FRONTIER_PARTY_SIZE).first
          }
      when (betweenChoice(
          title, "Battle ${battleNum + 1} of 7. Win streak: ${st.current}.", team, opp)) {
        "retire" -> return lose(key, title, "You retired. Your streak of ${st.current} has ended.")
        "rest" ->
            return rest(title, key, battleNum, team) {
              put("lvl", lvl.key)
              put("fought", FrontierRest.ints(fought))
              put("brain", brainThisRound)
            }
      }
      val hooks: FacilityHooks =
          if (f == Facility.PALACE) PalaceHooks(rng)
          else ArenaHooks(data.mindRatings, run.playerName, opp.name)
      val out = svc.battle(run, team, opp, hooks, format = if (mode == BattleMode.DOUBLES) 2 else 1)
      if (out.result != BattleResult.VICTORY)
          return lose(key, title, "You lost against ${opp.name}. Your streak was ${st.current}.")
      winBattle(key)
      if (opp.brain) {
        brainThisRound = true
        brainBeaten(f, status)?.let { message(title, "${opp.name}: The $it is yours!") }
      }
      svc.persist(run.charId)
      team = out.team.map(svc.mons::healed)
    }
    winRound(f, mode, key, st.current / 7, brainThisRound, title)
  }

  // --- Battle Dome -----------------------------------------------------------------------------

  private data class DomeEntrant(
      val trainerId: Int,
      val name: String,
      val pic: String,
      val trainerClass: String,
      val sets: List<FacilityMon>,
      val brain: Boolean = false,
      var out: Boolean = false,
      var outRound: Int = -1,
  )

  private fun domeMultiplier(moveId: Int, target: Int): Int {
    val m = svc.moves.get(moveId) ?: return -1
    if (m.power == 0) return -1
    val def = svc.species.get(target) ?: return -1
    if (def.ability1 == Ability.LEVITATE && m.type.name == "GROUND") return 0
    val e = svc.typeChart.effectiveness(m.type, def.type1, def.type2)
    return when {
      e == 0 -> 0
      e < 5 -> 5
      e < 10 -> 10
      e == 10 -> 20
      e <= 20 -> 40
      else -> 80
    }
  }

  private fun domePoints(sets: List<FacilityMon>, targets: List<Int>, mode: String): Int =
      sets.sumOf { s ->
        s.moves.sumOf { mv ->
          targets.sumOf { t ->
            domeMultiplier(mv, t).let { if (it < 0) 0 else DomeRules.effectivenessPoints(it, mode) }
          }
        }
      }

  /** battle_dome.c: a 16-trainer tournament, 4 rounds, two of three monsters each match. */
  private suspend fun dome(mode: BattleMode) {
    val title = "BATTLE DOME"
    val choice =
        menu(
            title,
            "Welcome to the BATTLE DOME! Win four matches to take the tournament.",
            listOf("challenge" to "CHALLENGE", "info" to "INFO", "cancel" to "CANCEL"))
    if (choice == "info")
        return message(
            title,
            "Register three POKéMON. Before each match you see your opponent's team and send out two of yours.")
    if (choice != "challenge") return
    val lvl = chooseLevel(title) ?: return
    var team = chooseTeam(title, FrontierRules.FRONTIER_PARTY_SIZE, lvl) ?: return
    val key = streakKey(Facility.DOME, mode, lvl)
    val save = svc.save(run.charId)
    val st = save.streak(key)
    if (!st.active) st.current = 0
    st.active = false
    val level = FrontierRules.enemyLevel(lvl, partyMax(team))
    // InitDomeTrainers: 15 frontier trainers, the first five from a harder range.
    val entrants = mutableListOf<DomeEntrant>()
    entrants += DomeEntrant(-1, run.playerName, "", "", emptyList())
    val used = mutableListOf<Int>()
    for (i in 1 until DomeRules.TRAINERS) {
      val tid =
          FrontierRules.nextTrainerId(st.current + if (i > 5) 0 else 1, 0, used, rng).also {
            used += it
          }
      val t = data.frontier.trainers[tid]
      val ids = FrontierRules.fillTrainerParty(t.monSet, data.frontier.mons, level, 3, rng)
      entrants +=
          DomeEntrant(tid, t.name, t.pic, t.trainerClass, ids.map { data.frontier.mons[it] })
    }
    // Seeding by ranking score: the sum of the six stats of all three, plus types * level / 20.
    fun score(e: DomeEntrant): Int =
        if (e.trainerId < 0)
            team.sumOf { m ->
              svc.species.get(m.dexId)?.let { d ->
                de.fiereu.openmmo.server.game.battle.StatCalculator.computeAll(d, m).let {
                  it.hp + it.atk + it.def + it.spAtk + it.spDef + it.spd
                }
              } ?: 0
            } +
                team.flatMap { svc.species.get(it.dexId)?.types.orEmpty() }.toSet().size * level /
                    20
        else
            e.sets.sumOf { s ->
              val p = svc.mons.facilityMon(s, level, FrontierRules.fixedIv(e.trainerId), rng)
              val c =
                  de.fiereu.openmmo.server.game.battle.StatCalculator.computeAll(
                      svc.species.get(s.species)!!, p)
              c.hp + c.atk + c.def + c.spAtk + c.spDef + c.spd
            } +
                e.sets.flatMap { svc.species.get(it.species)?.types.orEmpty() }.toSet().size *
                    level / 20
    val seeded =
        entrants
            .sortedWith(
                compareByDescending<DomeEntrant> { score(it) }
                    .thenBy { if (it.trainerId < 0) -1 else it.trainerId })
            .toMutableList()
    // A ready Frontier Brain takes the top seed of the other half.
    val status =
        FrontierRules.brainStatus(Facility.DOME, mode, st.current, save.symbolsOf(Facility.DOME))
    val playerSeed0 = seeded.indexOfFirst { it.trainerId < 0 }
    if (status != BrainStatus.NOT_READY) {
      val b = data.brains.getValue(Facility.DOME)
      val brainTeam = if (status.goldTeam) b.gold else b.silver
      val leafOfPlayer = DomeRules.BRACKET.indexOf(playerSeed0)
      val slot = if (leafOfPlayer < 8) 1 else 0
      seeded[slot] =
          DomeEntrant(
              -2,
              "DOME ACE ${b.name}",
              b.pic,
              b.trainerClass,
              brainTeam.map { FacilityMon(it.species, it.moves, it.item, 0, it.nature) },
              brain = true)
    }
    val leaves = DomeRules.BRACKET.map { seeded[it] }
    val playerLeaf = leaves.indexOfFirst { it.trainerId == -1 }
    fun bracket(round: Int) = buildJsonArray {
      for ((i, e) in leaves.withIndex()) add(
          buildJsonObject {
            put("leaf", i)
            put("name", e.name)
            put("seed", seeded.indexOf(e) + 1)
            put("out", e.out)
            put("outRound", e.outRound)
            put("player", e.trainerId == -1)
            put("brain", e.brain)
          })
    }
    for (round in 0 until DomeRules.ROUNDS) {
      val oppLeaf = DomeRules.opponentsLeaves(playerLeaf, round).first { !leaves[it].out }
      val foe = leaves[oppLeaf]
      // The opponent picks two of three against the player's team (GetDomeTrainerSelectedMons).
      val targets = team.map { it.dexId }
      val useGood = rng.nextBoolean()
      val points =
          IntArray(3) { i ->
            domePoints(listOf(foe.sets[i]), targets, if (useGood) "good" else "bad")
          }
      val foePicks = DomeRules.pickTwo(points, rng)
      val a =
          run.ask(
              screen("dome", title) {
                put(
                    "text",
                    "${listOf("ROUND 1", "ROUND 2", "SEMIFINAL", "FINAL")[round]}: ${foe.name}. Choose the two POKéMON to send out.")
                put("round", round)
                put("bracket", bracket(round))
                put("opponent", foe.name)
                put("opponentPic", foe.pic.lowercase())
                put(
                    "opponentMons",
                    buildJsonArray {
                      foe.sets.forEach { s ->
                        add(
                            buildJsonObject {
                              put("dex", s.species)
                              put("name", svc.speciesName(s.species))
                              put("item", svc.itemNameOf(s.item))
                              put(
                                  "moves",
                                  buildJsonArray {
                                    s.moves
                                        .filter { it != 0 }
                                        .forEach { add(JsonPrimitive(svc.moveName(it))) }
                                  })
                            })
                      }
                    })
                put("mine", buildJsonArray { team.forEach { add(svc.card(it)) } })
                put("count", 2)
              })
      if (a["v"]?.jsonPrimitive?.contentOrNull == "retire")
          return lose(key, title, "You forfeited the tournament.")
      val picks =
          svc.ints(a, "picks")
              .distinct()
              .filter { it in team.indices }
              .take(2)
              .ifEmpty { listOf(0, 1) }
      val myTwo = picks.map { team[it] }
      val iv = if (foe.brain) 0 else FrontierRules.fixedIv(foe.trainerId)
      val foeMons =
          if (foe.brain) {
            val b = data.brains.getValue(Facility.DOME)
            val bt = if (status.goldTeam) b.gold else b.silver
            foePicks.map { svc.mons.brainMon(bt[it], level, rng) }
          } else foePicks.map { svc.mons.facilityMon(foe.sets[it], level, iv, rng) }
      val opp =
          FacilityOpponent(
              foe.name,
              foe.pic,
              foe.trainerClass,
              foeMons,
              intro = "${foe.name}: Let's begin!",
              brain = foe.brain)
      val out = svc.battle(run, myTwo, opp, format = if (mode == BattleMode.DOUBLES) 2 else 1)
      if (out.result != BattleResult.VICTORY)
          return lose(
              key,
              title,
              "You lost in ${listOf("the first round", "the second round", "the semifinal", "the final")[round]} against ${foe.name}.")
      foe.out = true
      foe.outRound = round
      if (foe.brain)
          brainBeaten(Facility.DOME, status)?.let {
            message(title, "${foe.name}: The $it is yours!")
          }
      // DecideRoundWinners for every other match of the round.
      val decided = mutableSetOf(oppLeaf, playerLeaf)
      for (leaf in leaves.indices) {
        if (leaf in decided || leaves[leaf].out) continue
        val other =
            DomeRules.opponentsLeaves(leaf, round).firstOrNull { !leaves[it].out } ?: continue
        if (other in decided) continue
        decided += leaf
        decided += other
        val x = leaves[leaf]
        val y = leaves[other]
        val loser =
            when {
              x.brain -> y
              y.brain -> x
              else -> {
                fun pts(p: DomeEntrant, q: DomeEntrant, idx: Int) =
                    domePoints(p.sets, q.sets.map { it.species }, "aivsai") +
                        p.sets.sumOf { s ->
                          svc.species.get(s.species)?.let {
                            (it.baseHp +
                                it.baseAttack +
                                it.baseDefense +
                                it.baseSpeed +
                                it.baseSpAttack +
                                it.baseSpDefense) / 10
                          } ?: 0
                        } +
                        (rng.nextInt() and 0x1F) +
                        idx
                if (pts(x, y, seeded.indexOf(x)) > pts(y, x, seeded.indexOf(y))) y else x
              }
            }
        loser.out = true
        loser.outRound = round
      }
      team = team.map { m -> svc.mons.healed(out.team.firstOrNull { it.id == m.id } ?: m) }
      if (round < 3) message(title, "You advanced past ${foe.name}!")
    }
    winBattle(key)
    svc.persist(run.charId)
    // Dome BP use the number of tournaments won (domeWinStreaks).
    winRound(
        Facility.DOME,
        mode,
        key,
        st.current,
        leaves.any { it.brain && it.out },
        title,
        "You are the BATTLE DOME champion!")
  }

  // --- Battle Factory / Slateport Tent ---------------------------------------------------------

  /** battle_factory.c / battle_factory_screen.c: rent three of six, swap one after each win. */
  private suspend fun factory(mode: BattleMode, tent: Boolean, resume: FrontierRest? = null) {
    val title = if (tent) "SLATEPORT BATTLE TENT" else "BATTLE FACTORY"
    if (resume == null) {
      val choice =
          menu(
              title,
              "Welcome to the $title! Here you battle with rental POKéMON.",
              listOf("challenge" to "CHALLENGE", "info" to "INFO", "cancel" to "CANCEL"))
      if (choice == "info")
          return message(
              title,
              "Choose three of six rental POKéMON. After each win you may swap one of yours for one of your opponent's.")
      if (choice != "challenge") return
    }
    val lvl =
        if (tent) LevelMode.TENT
        else resume?.let { LevelMode.byKey(it.string("lvl")) } ?: chooseLevel(title) ?: return
    val table = if (tent) data.tents.getValue("slateport") else data.frontier
    val key = if (tent) "tent:slateport" else streakKey(Facility.FACTORY, mode, lvl)
    val save = svc.save(run.charId)
    val st = save.streak(key)
    if (resume == null) {
      if (!st.active) st.current = 0
      st.active = false
    }
    val stages = if (tent) FrontierRules.TENT_STAGES else 7
    val challengeNum = st.current / stages
    val rentsKey = "${mode.key}:${lvl.key}"
    val rank = FactoryRules.rentalRank(save.factoryRents[rentsKey] ?: 0)
    val level = FactoryRules.level(lvl)
    var team: List<Pair<Int, Pokemon>>
    if (resume != null) {
      team = resume.ints("ids").zip(resume.team)
    } else {
      // GenerateInitialRentalMons (the tent draws from its own 70 sets).
      val rentalIds =
          if (tent) {
            val ids = mutableListOf<Int>()
            while (ids.size < 6) {
              val id = rng.nextInt(table.mons.size)
              val m = table.mons[id]
              if (ids.any {
                table.mons[it].species == m.species ||
                    (m.item != "NONE" && table.mons[it].item == m.item)
              })
                  continue
              ids += id
            }
            ids
          } else FactoryRules.initialRentals(table.mons, lvl, challengeNum, rank, rng)
      val rentals =
          rentalIds.mapIndexed { i, id ->
            val iv =
                if (tent) 0 else FactoryRules.fixedIv(challengeNum + if (i < rank) 1 else 0, false)
            id to svc.mons.facilityMon(table.mons[id], level, iv, rng, avoidReturn = true)
          }
      val a =
          run.ask(
              screen("rental", title) {
                put("text", "Please choose three rental POKéMON.")
                put("count", 3)
                put("mons", buildJsonArray { rentals.forEach { add(svc.card(it.second)) } })
              })
      if (a["v"]?.jsonPrimitive?.contentOrNull == "cancel") return
      val picks = svc.ints(a, "picks").distinct().filter { it in rentals.indices }.take(3)
      if (picks.size != 3) return message(title, "Please choose three POKéMON.")
      save.factoryRents[rentsKey] = (save.factoryRents[rentsKey] ?: 0) + 1
      team = picks.map { rentals[it] }
    }
    var lastOpp: List<Pair<Int, Pokemon>> = emptyList()
    val fought = resume?.ints("fought").orEmpty().toMutableList()
    var brainThisRound = resume?.bool("brain") ?: false
    for (battleNum in (resume?.step ?: 0) until stages) {
      val status =
          if (tent) BrainStatus.NOT_READY
          else
              FrontierRules.brainStatus(
                  Facility.FACTORY, mode, st.current, save.symbolsOf(Facility.FACTORY))
      val pool = team.map { it.first } + lastOpp.map { it.first }
      val last = battleNum == stages - 1
      val oppIds: List<Int>
      val opp: FacilityOpponent
      if (tent) {
        val tid = rng.nextInt(FrontierRules.NUM_BATTLE_TENT_TRAINERS)
        val t = table.trainers[tid]
        val ids =
            FrontierRules.fillTrainerParty(t.monSet, table.mons, level, 3, rng, taken = emptyList())
                .filter { id -> pool.none { table.mons[it].species == table.mons[id].species } }
                .ifEmpty { FrontierRules.fillTrainerParty(t.monSet, table.mons, level, 3, rng) }
        oppIds = ids
        opp =
            FacilityOpponent(
                "${t.trainerClass.replace('_', ' ')} ${t.name}",
                t.pic,
                t.trainerClass,
                ids.map { svc.mons.facilityMon(table.mons[it], level, 0, rng, avoidReturn = true) },
                intro = "${t.name}: ${t.speechBefore}")
      } else if (status != BrainStatus.NOT_READY) {
        // FillFactoryBrainParty: rental-range sets at challenge + 2 IVs.
        oppIds = FactoryRules.opponentMons(table.mons, lvl, challengeNum, pool, rng)
        val b = data.brains.getValue(Facility.FACTORY)
        val iv = FactoryRules.fixedIv(challengeNum + 2, false)
        opp =
            FacilityOpponent(
                "FACTORY HEAD ${b.name}",
                b.pic,
                b.trainerClass,
                oppIds.map {
                  svc.mons.facilityMon(table.mons[it], level, iv, rng, avoidReturn = true)
                },
                intro = "FACTORY HEAD ${b.name}: Let me see what you can do with rentals!",
                brain = true)
      } else {
        val tid =
            FrontierRules.nextTrainerId(st.current / 7, battleNum, fought, rng).also {
              fought += it
            }
        oppIds = FactoryRules.opponentMons(table.mons, lvl, st.current / 7, pool, rng)
        val iv = FactoryRules.fixedIv(st.current / 7, last)
        val t = table.trainers[tid]
        opp =
            FacilityOpponent(
                "${t.trainerClass.replace('_', ' ')} ${t.name}",
                t.pic,
                t.trainerClass,
                oppIds.map {
                  svc.mons.facilityMon(table.mons[it], level, iv, rng, avoidReturn = true)
                },
                intro = "${t.name}: ${t.speechBefore}")
      }
      val style = FactoryRules.battleStyle(oppIds.map { table.mons[it] }, data.factoryStyles)
      when (betweenChoice(
          title,
          "Battle ${battleNum + 1} of $stages. Win streak: ${st.current}.",
          team.map { it.second },
          opp,
          canRest = !tent) {
            put("hint", "Your next opponent's style: ${FactoryRules.STYLE_NAMES[style]}.")
          }) {
        "retire" -> return lose(key, title, "You retired.")
        "rest" ->
            return rest(title, key, battleNum, team.map { it.second }) {
              put("lvl", lvl.key)
              put("ids", FrontierRest.ints(team.map { it.first }))
              put("fought", FrontierRest.ints(fought))
              put("brain", brainThisRound)
            }
      }
      val out =
          svc.battle(
              run, team.map { it.second }, opp, format = if (mode == BattleMode.DOUBLES) 2 else 1)
      if (out.result != BattleResult.VICTORY)
          return lose(key, title, "You lost against ${opp.name}. Your streak was ${st.current}.")
      winBattle(key)
      if (opp.brain) {
        brainThisRound = true
        brainBeaten(Facility.FACTORY, status)?.let {
          message(title, "${opp.name}: The $it is yours!")
        }
      }
      svc.persist(run.charId)
      team =
          team.map { (id, m) -> id to svc.mons.healed(out.team.firstOrNull { it.id == m.id } ?: m) }
      lastOpp = oppIds.zip(opp.mons.map(svc.mons::healed))
      if (battleNum < stages - 1) {
        // The swap screen (SwapRentalMons): trade one of yours for one of theirs, or keep.
        val sw =
            run.ask(
                screen("swap", title) {
                  put(
                      "text",
                      "Would you like to swap one of your POKéMON for one of your opponent's?")
                  put("mine", buildJsonArray { team.forEach { add(svc.card(it.second)) } })
                  put("theirs", buildJsonArray { lastOpp.forEach { add(svc.card(it.second)) } })
                })
        val mi = sw["mine"]?.jsonPrimitive?.intOrNull ?: -1
        val ti = sw["theirs"]?.jsonPrimitive?.intOrNull ?: -1
        if (mi in team.indices && ti in lastOpp.indices) {
          val (oid, om) = lastOpp[ti]
          team = team.toMutableList().also { it[mi] = oid to svc.mons.healed(om) }
          message(title, "${svc.speciesName(om.dexId)} joined your team!")
        }
      }
    }
    if (tent) {
      svc.save(run.charId).streak(key).active = true
      svc.giveItem(run, "FULL_HEAL")
      return message(title, "You won all three battles! Please take this FULL HEAL as your prize.")
    }
    winRound(Facility.FACTORY, mode, key, st.current / 7, brainThisRound, title)
  }

  // --- Battle Tents ----------------------------------------------------------------------------

  /** battle_tent.c: three battles at level 30+ under the matching frontier facility's rules. */
  private suspend fun tent(which: String) {
    if (which == "slateport") return factory(BattleMode.SINGLES, tent = true)
    val title = if (which == "verdanturf") "VERDANTURF BATTLE TENT" else "FALLARBOR BATTLE TENT"
    val palace = which == "verdanturf"
    val choice =
        menu(
            title,
            "Welcome to the $title! ${if (palace) "BATTLE PALACE" else "BATTLE ARENA"} rules apply.",
            listOf("challenge" to "CHALLENGE", "cancel" to "CANCEL"))
    if (choice != "challenge") return
    var team = chooseTeam(title, 3, LevelMode.OPEN) ?: return
    val table = data.tents.getValue(which)
    val key = "tent:$which"
    val level = FrontierRules.enemyLevel(LevelMode.TENT, partyMax(team))
    val used = mutableListOf<Int>()
    for (n in 0 until FrontierRules.TENT_STAGES) {
      var tid: Int
      do tid = rng.nextInt(FrontierRules.NUM_BATTLE_TENT_TRAINERS) while (tid in used)
      used += tid
      val t = table.trainers[tid]
      val ids = FrontierRules.fillTrainerParty(t.monSet, table.mons, level, 3, rng)
      val opp =
          FacilityOpponent(
              "${t.trainerClass.replace('_', ' ')} ${t.name}",
              t.pic,
              t.trainerClass,
              ids.map { svc.mons.facilityMon(table.mons[it], level, 0, rng) },
              intro = "${t.name}: ${t.speechBefore}")
      if (!between(title, "Battle ${n + 1} of 3.", team, opp))
          return lose(key, title, "You retired.")
      val hooks: FacilityHooks =
          if (palace) PalaceHooks(rng) else ArenaHooks(data.mindRatings, run.playerName, opp.name)
      val out = svc.battle(run, team, opp, hooks)
      if (out.result != BattleResult.VICTORY)
          return lose(key, title, "You lost against ${opp.name}.")
      winBattle(key)
      team = out.team.map(svc.mons::healed)
    }
    svc.save(run.charId).streak(key).active = true
    val prize = if (palace) "NEST_BALL" else "HYPER_POTION"
    svc.giveItem(run, prize)
    message(
        title,
        "You won all three battles! Please take this ${svc.itemNameOf(prize)} as your prize.")
  }

  // --- Battle Pike -----------------------------------------------------------------------------

  private fun pikeView(team: List<Pokemon>) =
      team.map { m ->
        val def = svc.species.get(m.dexId)
        val ability =
            def?.let {
              if (it.ability2 != Ability.NONE && (m.seed and 1) == 1) it.ability2 else it.ability1
            }
        PikeRules.PartyView(
            m.hp.toInt(),
            svc.mons.maxHp(m),
            m.status,
            def?.types?.map { it.name }?.toSet().orEmpty(),
            ability?.name ?: "",
            svc.mons.healed(m).moves == m.moves)
      }

  /** battle_pike.c: 14 rooms, three doors each, one hinted by the lady; no healing in between. */
  private suspend fun pike(resume: FrontierRest? = null) {
    val title = "BATTLE PIKE"
    if (resume == null) {
      val choice =
          menu(
              title,
              "Welcome to the BATTLE PIKE! Choose one of three paths through 14 rooms.",
              listOf("challenge" to "CHALLENGE", "info" to "INFO", "cancel" to "CANCEL"))
      if (choice == "info")
          return message(
              title,
              "Behind each door waits a battle, a healer, a wild POKéMON, a status-inflicting POKéMON or someone to talk to. Your POKéMON are not healed between rooms.")
      if (choice != "challenge") return
    }
    val lvl = resume?.let { LevelMode.byKey(it.string("lvl")) } ?: chooseLevel(title) ?: return
    // The Pike keeps HP, status and PP between rooms: the paused team is restored as it was.
    var team = resume?.team ?: chooseTeam(title, 3, lvl) ?: return
    val key = streakKey(Facility.PIKE, BattleMode.SINGLES, lvl)
    val save = svc.save(run.charId)
    val st = save.streak(key)
    if (resume == null) {
      if (!st.active) st.current = 0
      st.active = false
    }
    var healingDisabled = resume?.bool("healingDisabled") ?: false
    val fought = resume?.ints("fought").orEmpty().toMutableList()
    var brainBeatenNow = resume?.bool("brain") ?: false
    for (room in (resume?.step ?: 1)..PikeRules.NUM_PIKE_ROOMS) {
      val queen = PikeRules.queenStatus(st.current, save.symbolsOf(Facility.PIKE), 1)
      val hint =
          PikeRules.hintedRoom(queen != BrainStatus.NOT_READY, healingDisabled, pikeView(team), rng)
      val hintText =
          if (hint.type == PikeRoom.BRAIN) PikeHint.BRAIN.line
          else "${hint.type.hint.line} (the ${listOf("left", "center", "right")[hint.index]} one)"
      val a =
          run.ask(
              screen("doors", title) {
                put("text", "Room $room of ${PikeRules.NUM_PIKE_ROOMS}. Which path will you take?")
                put("hint", hintText)
                put("room", room)
                put("streak", st.current)
                put("team", buildJsonArray { team.forEach { add(svc.card(it)) } })
                put("rest", true)
              })
      if (a["v"]?.jsonPrimitive?.contentOrNull == "retire")
          return lose(key, title, "You retired from the BATTLE PIKE.")
      if (a["v"]?.jsonPrimitive?.contentOrNull == "rest")
          return rest(title, key, room, team) {
            put("lvl", lvl.key)
            put("fought", FrontierRest.ints(fought))
            put("healingDisabled", healingDisabled)
            put("brain", brainBeatenNow)
          }
      val door = (a["door"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 2)
      val type = PikeRules.roomBehind(door, hint, healingDisabled, pikeView(team), rng)
      val level = FrontierRules.enemyLevel(lvl, partyMax(team))
      when (type) {
        PikeRoom.SINGLE_BATTLE,
        PikeRoom.HARD_BATTLE,
        PikeRoom.DOUBLE_BATTLE -> {
          val hard = type == PikeRoom.HARD_BATTLE
          val battleNum = if (hard) 6 else 1
          val id =
              FrontierRules.nextTrainerId(
                      st.current / PikeRules.NUM_PIKE_ROOMS, battleNum, fought, rng)
                  .also { fought += it }
          var (opp, ids) = trainerOpponent(id, level, 3)
          if (type == PikeRoom.DOUBLE_BATTLE) {
            val id2 =
                FrontierRules.nextTrainerId(st.current / PikeRules.NUM_PIKE_ROOMS, 1, fought, rng)
                    .also { fought += it }
            val (b, _) = trainerOpponent(id2, level, 3, taken = ids)
            opp =
                FacilityOpponent(
                    "${opp.name} & ${b.name}",
                    opp.pic,
                    opp.trainerClass,
                    opp.mons.take(2) + b.mons.take(2),
                    intro = opp.intro)
          }
          message(
              title,
              when (type) {
                PikeRoom.HARD_BATTLE ->
                    "You will battle a somewhat tough TRAINER. If you win, your POKéMON will be restored."
                PikeRoom.DOUBLE_BATTLE -> "Two TRAINERS challenge you to a double battle!"
                else -> "A TRAINER challenges you!"
              })
          val out =
              svc.battle(run, team, opp, format = if (type == PikeRoom.DOUBLE_BATTLE) 2 else 1)
          if (out.result != BattleResult.VICTORY)
              return lose(key, title, "You lost in room $room. Rooms cleared: ${st.current}.")
          team = out.team
          if (hard) {
            team = team.map(svc.mons::healed)
            message(title, "I will restore your POKéMON to full health.")
          }
        }
        PikeRoom.HEAL_FULL -> {
          team = team.map(svc.mons::healed)
          message(title, "Welcome! I will restore your POKéMON to full health.")
        }
        PikeRoom.HEAL_PART -> {
          // HealOneOrTwoMons.
          val n = rng.nextInt(2) + 1
          team = healSome(team, n)
          message(title, "I will restore $n of your POKéMON.")
        }
        PikeRoom.STATUS -> {
          val hit = PikeRules.inflict(pikeView(team), room, rng)
          if (hit == null) message(title, "It's empty...")
          else {
            val (status, slots) = hit
            val who = PikeRules.statusMon(status, rng)
            team = team.mapIndexed { i, m -> if (i in slots) m.copy(status = status.bits) else m }
            message(
                title,
                "A wild $who appeared and attacked! ${if (slots.isEmpty()) "But it had no effect!" else "${slots.joinToString { svc.speciesName(team[it].dexId) }} became ${status.label}!"}")
          }
        }
        PikeRoom.WILD_MONS -> {
          val tbl =
              data.pikeWild
                  .getValue(if (lvl == LevelMode.LV50) "lvl50" else "open")[
                      PikeRules.wildHeader(st.current)]
          val w = tbl[rng.nextInt(tbl.size)]
          val lv = PikeRules.wildLevel(lvl, partyMax(team), w.levelDelta)
          val opp =
              FacilityOpponent(
                  "WILD",
                  "",
                  "",
                  listOf(svc.mons.wildMon(w.species, lv, w.moves, -1, rng)),
                  wild = true)
          val out = svc.battle(run, team, opp)
          if (out.result != BattleResult.VICTORY)
              return lose(key, title, "You lost in room $room. Rooms cleared: ${st.current}.")
          team = out.team
        }
        PikeRoom.NPC ->
            message(
                title,
                listOf(
                    "I'm not sure which door is better...",
                    "Keep going! The PIKE QUEEN awaits the strong.",
                    "My POKéMON are tired. I'm resting here.")[rng.nextInt(3)])
        PikeRoom.BRAIN -> {
          val heal = PikeRules.healsBeforeQueen(hint.index, door)
          if (heal > 0) {
            team = healSome(team, heal)
            message(title, "Before you meet our master, I will restore $heal of your POKéMON.")
          }
          val opp = brainOpponent(Facility.PIKE, queen, level)
          val out = svc.battle(run, team, opp)
          if (out.result != BattleResult.VICTORY)
              return lose(key, title, "PIKE QUEEN LUCY defeated you.")
          team = out.team
          brainBeatenNow = true
          brainBeaten(Facility.PIKE, queen)?.let {
            message(title, "PIKE QUEEN LUCY: ...The $it. Take it.")
          }
        }
      }
      // pike_ispartyfullhealth: a fully healthy party disables the healing rooms.
      healingDisabled = PikeRules.fullHealth(pikeView(team))
      winBattle(key)
      svc.persist(run.charId)
    }
    winRound(
        Facility.PIKE,
        BattleMode.SINGLES,
        key,
        st.current / PikeRules.NUM_PIKE_ROOMS,
        brainBeatenNow,
        title,
        "You made it through all 14 rooms!")
  }

  /** TryHealMons: up to [n] monsters that need it, in a shuffled order. */
  private fun healSome(team: List<Pokemon>, n: Int): List<Pokemon> {
    val order = team.indices.shuffled(rng)
    val out = team.toMutableList()
    var left = n
    for (i in order) {
      if (left == 0) break
      val m = out[i]
      val healed = svc.mons.healed(m)
      if (healed != m) {
        out[i] = healed
        left--
      }
    }
    return out
  }

  // --- Battle Pyramid --------------------------------------------------------------------------

  private class PyramidSquare(
      var kind: String,
      var trainer: Int = -1,
      var item: String = "",
      var seen: Boolean = false
  )

  /**
   * battle_pyramid.c: seven dark floors of 4x4 squares, trainers, items, an exit; a bag of its own.
   */
  private suspend fun pyramid(resume: FrontierRest? = null) {
    val title = "BATTLE PYRAMID"
    if (resume == null) {
      val choice =
          menu(
              title,
              "Welcome to the BATTLE PYRAMID! Climb seven floors to the top.",
              listOf("challenge" to "CHALLENGE", "info" to "INFO", "cancel" to "CANCEL"))
      if (choice == "info")
          return message(
              title,
              "The floors are dark; defeating TRAINERS widens your light. Items you find go into the PYRAMID BAG. Your POKéMON may not hold items and are not healed between floors.")
      if (choice != "challenge") return
    }
    val lvl = resume?.let { LevelMode.byKey(it.string("lvl")) } ?: chooseLevel(title) ?: return
    var team = resume?.team ?: chooseTeam(title, 3, lvl, pyramid = true) ?: return
    val key = streakKey(Facility.PYRAMID, BattleMode.SINGLES, lvl)
    val save = svc.save(run.charId)
    val st = save.streak(key)
    if (resume == null) {
      if (!st.active) st.current = 0
      st.active = false
    }
    // InitPyramidBagItems: a HYPER POTION and an ETHER (a paused challenge keeps its bag).
    val bag: LinkedHashMap<String, Int> =
        resume?.extra?.get("bag")?.jsonObject?.let { o ->
          LinkedHashMap(o.mapValues { it.value.jsonPrimitive.intOrNull ?: 0 })
        } ?: linkedMapOf("HYPER_POTION" to 1, "ETHER" to 1)
    var brainBeatenNow = resume?.bool("brain") ?: false
    val fought = resume?.ints("fought").orEmpty().toMutableList()
    // The floor a REST left: its squares, the player's square and the light come back as saved.
    var restored = resume
    for (floor in (resume?.step ?: 0) until 7) {
      val round = (st.current / 7) % PyramidRules.TOTAL_ROUNDS
      val r = restored
      restored = null
      // The top floor (7th) is the brain's when ready; otherwise trainers and items.
      val brain =
          if (floor == 6)
              FrontierRules.brainStatus(
                  Facility.PYRAMID,
                  BattleMode.SINGLES,
                  st.current,
                  save.symbolsOf(Facility.PYRAMID))
          else BrainStatus.NOT_READY
      val grid: List<PyramidSquare>
      var at: Int
      var light: Int
      if (r != null) {
        grid =
            r.extra["grid"]?.jsonArray?.map {
              val o = it.jsonObject
              PyramidSquare(
                  o["kind"]?.jsonPrimitive?.contentOrNull ?: "floor",
                  o["trainer"]?.jsonPrimitive?.intOrNull ?: -1,
                  o["item"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                  o["seen"]?.jsonPrimitive?.contentOrNull == "true")
            } ?: List(PyramidRules.SQUARES) { PyramidSquare("floor") }
        at = r.int("at")
        light = r.int("light", PyramidRules.LIGHT_START)
      } else {
        val tmplId = PyramidRules.floorTemplate(floor, data.pyramidFloorOptions, rng)
        val tmpl =
            data.pyramidFloorTemplates[tmplId.coerceIn(0, data.pyramidFloorTemplates.size - 1)]
        val (entrance, exit) = PyramidRules.entranceAndExit(rng.nextInt(), rng.nextInt())
        grid = List(PyramidRules.SQUARES) { PyramidSquare("floor") }
        grid[exit].kind = "exit"
        val free =
            (0 until PyramidRules.SQUARES)
                .filter { it != entrance && it != exit }
                .shuffled(rng)
                .toMutableList()
        repeat(tmpl.numTrainers.coerceAtMost(free.size)) {
          val sq = free.removeAt(0)
          grid[sq].kind = "trainer"
          grid[sq].trainer =
              FrontierRules.nextTrainerId(
                      st.current / 7 + if (floor == 6) 1 else 0, floor, fought, rng)
                  .also { fought += it }
        }
        val pickups = if (lvl == LevelMode.LV50) data.pyramidPickup50 else data.pyramidPickupOpen
        repeat(tmpl.numItems.coerceAtMost(free.size)) {
          val sq = free.removeAt(0)
          grid[sq].kind = "item"
          grid[sq].item =
              PyramidRules.pickup(floor, rng.nextInt(100), data.pyramidPickupSlots, pickups[round])
        }
        at = entrance
        light = PyramidRules.LIGHT_START
      }
      var note = "Floor ${floor + 1}. Find the exit!"
      while (true) {
        grid.forEachIndexed { i, s -> if (PyramidRules.visible(at, i, light)) s.seen = true }
        val a =
            run.ask(
                screen("pyramid", title) {
                  put("text", note)
                  put("floor", floor + 1)
                  put("at", at)
                  put("light", light)
                  put(
                      "grid",
                      buildJsonArray {
                        grid.forEachIndexed { i, s ->
                          add(
                              buildJsonObject {
                                put("i", i)
                                put("seen", s.seen)
                                put("lit", PyramidRules.visible(at, i, light))
                                put("kind", if (s.seen) s.kind else "dark")
                              })
                        }
                      })
                  put(
                      "bag",
                      buildJsonArray {
                        bag.filter { it.value > 0 }
                            .forEach { (k, v) ->
                              add(
                                  buildJsonObject {
                                    put("item", k)
                                    put("name", svc.itemNameOf(k))
                                    put("count", v)
                                  })
                            }
                      })
                  put("team", buildJsonArray { team.forEach { add(svc.card(it)) } })
                  put("rest", true)
                })
        when (a["v"]?.jsonPrimitive?.contentOrNull) {
          "retire" -> return lose(key, title, "You retired from the BATTLE PYRAMID.")
          // The Pyramid saves anywhere on a floor (its start menu's SAVE).
          "rest" ->
              return rest(title, key, floor, team) {
                put("lvl", lvl.key)
                put("fought", FrontierRest.ints(fought))
                put("brain", brainBeatenNow)
                put("at", at)
                put("light", light)
                put("bag", JsonObject(bag.mapValues { JsonPrimitive(it.value) }))
                put(
                    "grid",
                    buildJsonArray {
                      grid.forEach { sq ->
                        add(
                            buildJsonObject {
                              put("kind", sq.kind)
                              put("trainer", sq.trainer)
                              put("item", sq.item)
                              put("seen", sq.seen.toString())
                            })
                      }
                    })
              }
          "use" -> {
            val item = a["item"]?.jsonPrimitive?.contentOrNull ?: continue
            val target = a["target"]?.jsonPrimitive?.intOrNull ?: 0
            if ((bag[item] ?: 0) <= 0 || target !in team.indices) continue
            val used = useField(item, team[target])
            if (used == null) {
              note = "It won't have any effect."
            } else {
              team = team.toMutableList().also { it[target] = used }
              bag[item] = bag.getValue(item) - 1
              note = "Used ${svc.itemNameOf(item)} on ${svc.speciesName(used.dexId)}."
            }
          }
          "move" -> {
            val to = a["to"]?.jsonPrimitive?.intOrNull ?: continue
            if (!PyramidRules.adjacent(at, to)) continue
            at = to
            val sq = grid[at]
            note = ""
            when (sq.kind) {
              "trainer" -> {
                val level = FrontierRules.enemyLevel(lvl, partyMax(team))
                val opp = trainerOpponent(sq.trainer, level, 3).first
                message(title, "${opp.name} spotted you!")
                val out = svc.battle(run, team, opp, PyramidBagHooks(bag, svc))
                if (out.result != BattleResult.VICTORY)
                    return lose(key, title, "You were defeated on floor ${floor + 1}.")
                team = out.team
                sq.kind = "floor"
                light = (light + PyramidRules.LIGHT_STEP).coerceAtMost(PyramidRules.LIGHT_MAX)
                note = "Defeated ${opp.name}. Your light grew."
              }
              "item" -> {
                if (bag.size >= 10 && sq.item !in bag) note = "The PYRAMID BAG is full."
                else {
                  bag[sq.item] = (bag[sq.item] ?: 0) + 1
                  note = "Found ${svc.itemNameOf(sq.item)}!"
                  sq.kind = "floor"
                }
              }
              "exit" -> {
                if (floor == 6 && brain != BrainStatus.NOT_READY) {
                  val level = FrontierRules.enemyLevel(lvl, partyMax(team))
                  val opp = brainOpponent(Facility.PYRAMID, brain, level)
                  message(title, "PYRAMID KING BRANDON stands at the top!")
                  val out = svc.battle(run, team, opp, PyramidBagHooks(bag, svc))
                  if (out.result != BattleResult.VICTORY)
                      return lose(key, title, "PYRAMID KING BRANDON defeated you.")
                  team = out.team
                  brainBeatenNow = true
                  brainBeaten(Facility.PYRAMID, brain)?.let {
                    message(title, "PYRAMID KING BRANDON: The $it is yours!")
                  }
                }
                break
              }
              else -> {
                // Wild POKéMON roam the dark floors.
                if (rng.nextInt(100) < 25) {
                  val wild =
                      (if (lvl == LevelMode.LV50) data.pyramidWild50 else data.pyramidWildOpen)[
                          round]
                  val w = wild[rng.nextInt(wild.size)]
                  val lv = PyramidRules.wildLevel(lvl, partyMax(team), w.level, rng)
                  val out =
                      svc.battle(
                          run,
                          team,
                          FacilityOpponent(
                              "WILD",
                              "",
                              "",
                              listOf(svc.mons.wildMon(w.species, lv, w.moves, w.ability, rng)),
                              wild = true),
                          PyramidBagHooks(bag, svc))
                  if (out.result != BattleResult.VICTORY)
                      return lose(key, title, "You were defeated on floor ${floor + 1}.")
                  team = out.team
                }
              }
            }
          }
        }
      }
      winBattle(key)
      svc.persist(run.charId)
    }
    winRound(
        Facility.PYRAMID,
        BattleMode.SINGLES,
        key,
        st.current / 7,
        brainBeatenNow,
        title,
        "You reached the top of the BATTLE PYRAMID!")
  }

  /** A Pyramid Bag item used outside battle (potions, revives, ethers, status heals). */
  private fun useField(item: String, mon: Pokemon): Pokemon? {
    val max = svc.mons.maxHp(mon)
    val healed = svc.mons.healed(mon)
    return when (item) {
      "POTION",
      "SUPER_POTION",
      "HYPER_POTION",
      "MAX_POTION",
      "FRESH_WATER",
      "SODA_POP",
      "LEMONADE",
      "MOOMOO_MILK",
      "BERRY_JUICE",
      "ORAN_BERRY",
      "SITRUS_BERRY" -> {
        if (mon.hp <= 0 || mon.hp >= max) null
        else {
          val amount =
              mapOf(
                  "POTION" to 20,
                  "SUPER_POTION" to 50,
                  "HYPER_POTION" to 200,
                  "FRESH_WATER" to 50,
                  "SODA_POP" to 60,
                  "LEMONADE" to 80,
                  "MOOMOO_MILK" to 100,
                  "BERRY_JUICE" to 20,
                  "ORAN_BERRY" to 10,
                  "SITRUS_BERRY" to 30)[item] ?: max
          mon.copy(hp = (mon.hp + amount).coerceAtMost(max).toShort())
        }
      }
      "FULL_RESTORE" ->
          if (mon.hp <= 0 || (mon.hp >= max && mon.status == 0)) null
          else mon.copy(hp = max.toShort(), status = 0)
      "REVIVE",
      "MAX_REVIVE" ->
          if (mon.hp > 0) null
          else
              mon.copy(
                  hp = (if (item == "REVIVE") max / 2 else max).coerceAtLeast(1).toShort(),
                  status = 0)
      "SACRED_ASH" -> if (mon.hp > 0) null else mon.copy(hp = max.toShort(), status = 0)
      "ETHER",
      "MAX_ETHER",
      "ELIXIR",
      "MAX_ELIXIR",
      "LEPPA_BERRY" -> if (healed.moves == mon.moves) null else mon.copy(moves = healed.moves)
      "FULL_HEAL",
      "LUM_BERRY",
      "PECHA_BERRY",
      "RAWST_BERRY",
      "CHESTO_BERRY",
      "CHERI_BERRY",
      "ASPEAR_BERRY" -> if (mon.status == 0 || mon.hp <= 0) null else mon.copy(status = 0)
      else -> null
    }
  }

  /** The Pyramid Bag in battle. */
  class PyramidBagHooks(
      private val bag: MutableMap<String, Int>,
      private val svc: FrontierService
  ) : FacilityHooks {
    override val allowItems: Boolean = true

    override fun bag(): Map<Int, Int> =
        bag.filter { it.value > 0 }
            .mapNotNull { (k, v) ->
              de.fiereu.openmmo.server.game.battle.ItemNames.wireId(svc.items, k)
                  .takeIf { it != 0 }
                  ?.let { it to v }
            }
            .toMap()

    override fun consume(itemId: Int) {
      val name =
          bag.keys.firstOrNull {
            de.fiereu.openmmo.server.game.battle.ItemNames.wireId(svc.items, it) == itemId
          } ?: return
      bag[name] = (bag[name] ?: 1) - 1
    }
  }

  // --- Trainer Hill (Emerald) ------------------------------------------------------------------

  /** trainer_hill.c: four floors of two trainers (a tag match), against the clock. */
  private suspend fun trainerHill() {
    val title = "TRAINER HILL"
    val pick =
        menu(
            title,
            "Welcome to the TRAINER HILL! Which mode?",
            listOf(
                "normal" to "NORMAL",
                "variety" to "VARIETY",
                "unique" to "UNIQUE",
                "expert" to "EXPERT",
                "cancel" to "CANCEL"))
    val floors = data.trainerHill[pick] ?: return
    var team = svc.party(run.charId).filter { !it.isEgg && it.hp > 0 }.map(svc.mons::healed)
    if (team.isEmpty()) return message(title, "You need a POKéMON that can battle.")
    val start = System.currentTimeMillis()
    for ((fi, floor) in floors.withIndex()) {
      val level = team.maxOf { it.level.toInt() }
      // CreateNPCTrainerHillParty: trainer 1 uses mons 0-2, trainer 2 mons 3-5, at the player's top
      // level.
      val t1 = floor.trainers[0]
      val t2 = floor.trainers.getOrNull(1)
      val oppMons =
          t1.mons.take(3).map { svc.mons.towerMon(it, level) } +
              (t2?.mons
                  ?.drop(3)
                  ?.take(3)
                  ?.ifEmpty { t2.mons.take(3) }
                  ?.map { svc.mons.towerMon(it, level) } ?: emptyList())
      val opp =
          FacilityOpponent(
              listOfNotNull(t1.name, t2?.name).joinToString(" & "),
              t1.pic,
              t1.trainerClass,
              oppMons,
              intro = "${t1.name}: ${t1.speechBefore}")
      val elapsed = System.currentTimeMillis() - start
      if (!between(title, "${fi + 1}F. Time: ${fmtTime(elapsed)}", team, opp) {
        put("timer", elapsed)
      })
          return message(title, "You gave up the challenge.")
      val out = svc.battle(run, team, opp, format = if (team.count { it.hp > 0 } >= 2) 2 else 1)
      if (out.result != BattleResult.VICTORY) return message(title, "You lost on ${fi + 1}F.")
      team = out.team
    }
    val time = System.currentTimeMillis() - start
    val save = svc.save(run.charId)
    val best = save.hillBest[pick]
    val record = best == null || time < best
    if (record) save.hillBest[pick] = time
    // GetPrizeItemId: by minutes (under 12 is the list's grand prize).
    val minutes = time / 60_000
    val grand =
        mapOf(
            "normal" to "TM_SUNNY_DAY",
            "variety" to "ELIXIR",
            "unique" to "TM_GIGA_DRAIN",
            "expert" to "TM_BRICK_BREAK")[pick] ?: "ELIXIR"
    val prize =
        when {
          minutes < 12 -> grand
          minutes < 13 -> "ETHER"
          minutes < 14 -> "MAX_POTION"
          minutes < 16 -> "REVIVE"
          minutes < 18 -> "FLUFFY_TAIL"
          else -> "GREAT_BALL"
        }
    val prizeKey = "hill:$pick"
    var prizeText = ""
    if (prizeKey !in save.prizesTaken && svc.giveItem(run, prize)) {
      save.prizesTaken += prizeKey
      prizeText = " Please accept this ${svc.itemNameOf(prize)}!"
    }
    svc.persist(run.charId)
    message(
        title,
        "You reached the roof in ${fmtTime(time)}!${if (record) " That's a new record!" else ""}$prizeText")
  }

  private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%02d".format(s / 60, s % 60, (ms % 1000) / 10)
  }

  // --- FRLG Trainer Tower ----------------------------------------------------------------------

  /** pokefirered trainer_tower.c: eight floors, single / double / knockout / mixed, timed. */
  private suspend fun trainerTower() {
    val title = "TRAINER TOWER"
    val pick =
        menu(
            title,
            "Welcome to the TRAINER TOWER! Which challenge?",
            listOf(
                "single" to "SINGLE",
                "double" to "DOUBLE",
                "knockout" to "KNOCKOUT",
                "mixed" to "MIXED",
                "cancel" to "CANCEL"))
    val floors = data.trainerTower[pick] ?: return
    var team = svc.party(run.charId).filter { !it.isEgg && it.hp > 0 }.map(svc.mons::healed)
    if (team.isEmpty()) return message(title, "You need a POKéMON that can battle.")
    if ((pick == "double" || floors.any { it.type == "double" }) && team.size < 2)
        return message(title, "You need two POKéMON for this challenge.")
    val start = System.currentTimeMillis()
    for ((fi, floor) in floors.withIndex()) {
      val level = team.maxOf { it.level.toInt() }
      val battles: List<Pair<FacilityOpponent, Int>> =
          when (floor.type) {
            // sSingleBattleChallengeMonIdxs / sDoubleBattleChallengeMonIdxs /
            // sKnockoutChallengeMonIdxs.
            "double" -> {
              val idx = DOUBLE_IDX[fi]
              val a = floor.trainers[0]
              val b = floor.trainers.getOrElse(1) { a }
              listOf(
                  FacilityOpponent(
                      "${a.name} & ${b.name}",
                      a.pic,
                      a.trainerClass,
                      listOf(
                          svc.mons.towerMon(a.mons[idx[0]], level),
                          svc.mons.towerMon(b.mons[idx[1]], level)),
                      intro = "${a.name}: ${a.speechBefore}") to 2)
            }
            "knockout" ->
                floor.trainers.mapIndexed { ti, t ->
                  FacilityOpponent(
                      t.name,
                      t.pic,
                      t.trainerClass,
                      listOf(svc.mons.towerMon(t.mons[KNOCKOUT_IDX[fi][ti]], level)),
                      intro = "${t.name}: ${t.speechBefore}") to 1
                }
            else -> {
              val t = floor.trainers[0]
              listOf(
                  FacilityOpponent(
                      t.name,
                      t.pic,
                      t.trainerClass,
                      SINGLE_IDX[fi].map { svc.mons.towerMon(t.mons[it], level) },
                      intro = "${t.name}: ${t.speechBefore}") to 1)
            }
          }
      for ((opp, format) in battles) {
        val elapsed = System.currentTimeMillis() - start
        if (!between(
            title, "${fi + 1}F (${floor.type.uppercase()}). Time: ${fmtTime(elapsed)}", team, opp) {
              put("timer", elapsed)
            })
            return message(title, "You gave up the challenge.")
        val out = svc.battle(run, team, opp, format = format)
        if (out.result != BattleResult.VICTORY) return message(title, "You lost on ${fi + 1}F.")
        team = out.team
      }
    }
    val time = System.currentTimeMillis() - start
    val save = svc.save(run.charId)
    val best = save.towerBest[pick]
    val record = best == null || time < best
    if (record) save.towerBest[pick] = time
    var prizeText = ""
    val prizeKey = "ttower:$pick"
    val prize = data.trainerTowerPrizes.getOrNull(floors[0].prize) ?: "HP_UP"
    if (prizeKey !in save.prizesTaken && svc.giveItem(run, prize)) {
      save.prizesTaken += prizeKey
      prizeText = " The owner gave you a ${svc.itemNameOf(prize)}!"
    }
    svc.persist(run.charId)
    message(
        title,
        "You reached the roof in ${fmtTime(time)}!${if (record) " A new record!" else ""}$prizeText")
  }

  // --- Exchange Service Corner and the Frontier Pass -------------------------------------------

  /** The BP clerks (BattleFrontier_ExchangeServiceCorner scripts). */
  private suspend fun exchange(clerk: String) {
    val title = "EXCHANGE SERVICE CORNER"
    val stock = data.exchange.filter { it.clerk == clerk }
    while (true) {
      val bp = svc.save(run.charId).battlePoints
      val a =
          run.ask(
              screen("shop", title) {
                put("text", "We exchange your Battle Points for prizes. You have $bp BP.")
                put(
                    "items",
                    buildJsonArray {
                      stock.forEachIndexed { i, e ->
                        add(
                            buildJsonObject {
                              put("idx", i)
                              put(
                                  "name",
                                  if (e.kind == "decor") e.id.replace('_', ' ')
                                  else svc.itemNameOf(e.id))
                              put("kind", e.kind)
                              put("cost", e.cost)
                            })
                      }
                    })
              })
      val idx = a["buy"]?.jsonPrimitive?.intOrNull ?: return
      val e = stock.getOrNull(idx) ?: return
      val save = svc.save(run.charId)
      if (save.battlePoints < e.cost) {
        message(title, "I'm sorry, you don't have enough Battle Points.")
        continue
      }
      val ok = if (e.kind == "decor") svc.giveDecoration(run, e.id) else svc.giveItem(run, e.id)
      if (!ok) {
        message(title, "I'm sorry, you can't carry that.")
        continue
      }
      save.battlePoints -= e.cost
      svc.persist(run.charId)
      message(title, "Here you go! ${if (e.kind == "decor") "It was sent to your PC." else ""}")
    }
  }

  /** frontier_pass.c: the symbols card with BP and every record. */
  private suspend fun card() {
    val s = svc.save(run.charId)
    run.ask(
        screen("card", "FRONTIER PASS") {
          put("bp", s.battlePoints)
          put("earned", s.earnedPoints)
          put(
              "symbols",
              JsonObject(Facility.entries.associate { it.key to JsonPrimitive(s.symbolsOf(it)) }))
          put(
              "records",
              JsonArray(
                  s.streaks.entries
                      .sortedBy { it.key }
                      .map { (k, v) ->
                        buildJsonObject {
                          put("key", k)
                          put("current", v.current)
                          put("best", v.best)
                          put("active", v.active)
                        }
                      }))
          put("hill", JsonObject(s.hillBest.mapValues { JsonPrimitive(fmtTime(it.value)) }))
          put("ttower", JsonObject(s.towerBest.mapValues { JsonPrimitive(fmtTime(it.value)) }))
        })
  }

  companion object {
    private val SINGLE_IDX =
        listOf(
            listOf(0, 2),
            listOf(1, 3),
            listOf(2, 4),
            listOf(3, 5),
            listOf(4, 1),
            listOf(5, 2),
            listOf(0, 3),
            listOf(1, 4))
    private val DOUBLE_IDX =
        listOf(
            listOf(0, 1),
            listOf(1, 3),
            listOf(2, 0),
            listOf(3, 4),
            listOf(4, 2),
            listOf(5, 2),
            listOf(0, 3),
            listOf(1, 5))
    private val KNOCKOUT_IDX =
        listOf(
            listOf(0, 2, 4),
            listOf(1, 3, 5),
            listOf(2, 3, 1),
            listOf(3, 4, 0),
            listOf(4, 1, 2),
            listOf(5, 0, 3),
            listOf(0, 5, 2),
            listOf(1, 4, 5))
  }
}
