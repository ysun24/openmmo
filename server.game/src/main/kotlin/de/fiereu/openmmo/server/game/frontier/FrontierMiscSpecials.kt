package de.fiereu.openmmo.server.game.frontier

import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.script.vm.SpecialTable
import de.fiereu.openmmo.server.game.script.vm.vmMoveName
import javax.inject.Inject
import javax.inject.Singleton

/**
 * r5 merge: the frontier-lane specials (SpecialOwners) the lane did not implement and the scripts
 * reach: the Battle Frontier move tutors (field_specials.c), and the e-Reader / Apprentice / record
 * windows, which have no counterpart here (no e-Reader cards, no Apprentice trainers).
 */
@Singleton
class FrontierMiscSpecials @Inject constructor() : SpecialTable() {
  init {
    // BufferBattleFrontierTutorMoveName: gStringVar1 = the tutor's move (0x8005: which tutor).
    on("BufferBattleFrontierTutorMoveName") { ctx ->
      ctx.bufferString(1, ctx.vmMoveName(tutorMove(ctx, ctx.specialVar(5) != 0, ctx.specialVar(4))))
      0
    }
    // GetBattleFrontierTutorMoveIndex: 0x8005 = the gTutorMoves index of the selected move.
    on("GetBattleFrontierTutorMoveIndex") { ctx ->
      val tutor = ctx.varGet("VAR_TEMP_FRONTIER_TUTOR_ID")
      val sel = ctx.varGet("VAR_TEMP_FRONTIER_TUTOR_SELECTION")
      val move = tutorMove(ctx, tutor != 0, sel)
      val idx = TUTOR_MOVES.indexOfFirst { ctx.constant(it) == move }
      ctx.setSpecialVar(5, idx.coerceAtLeast(0))
      0
    }
    noop("CloseBattleFrontierTutorWindow")
    // No e-Reader cards: the Battle Tower's e-Reader trainer is never valid.
    on("ValidateEReaderTrainer") { ctx ->
      ctx.result = 1
      1
    }
    noop(
        "BufferEReaderTrainerName",
        "BufferEReaderTrainerGreeting",
        "CopyEReaderTrainerGreeting",
        "SetEReaderTrainerGfxId",
        "CallApprenticeFunction",
        "ShowBattleRecords",
        "ReducePlayerPartyToSelectedMons")
    // The cable club's half-party picker: link battles go through the Union Room instead.
    on("ChooseHalfPartyForBattle") { ctx ->
      ctx.result = 0
      0
    }
  }

  private fun tutorMove(ctx: ScriptContext, second: Boolean, i: Int): Int {
    val list = if (second) TUTOR2 else TUTOR1
    return ctx.constant(list.getOrElse(i) { list[0] }) ?: 0
  }

  private companion object {
    val TUTOR1 =
        listOf(
            "MOVE_SOFT_BOILED",
            "MOVE_SEISMIC_TOSS",
            "MOVE_DREAM_EATER",
            "MOVE_MEGA_PUNCH",
            "MOVE_MEGA_KICK",
            "MOVE_BODY_SLAM",
            "MOVE_ROCK_SLIDE",
            "MOVE_COUNTER",
            "MOVE_THUNDER_WAVE",
            "MOVE_SWORDS_DANCE")
    val TUTOR2 =
        listOf(
            "MOVE_DEFENSE_CURL",
            "MOVE_SNORE",
            "MOVE_MUD_SLAP",
            "MOVE_SWIFT",
            "MOVE_ICY_WIND",
            "MOVE_ENDURE",
            "MOVE_PSYCH_UP",
            "MOVE_ICE_PUNCH",
            "MOVE_THUNDER_PUNCH",
            "MOVE_FIRE_PUNCH")
    // gTutorMoves (src/data/pokemon/tutor_learnsets.h), in TUTOR_MOVE_* order.
    val TUTOR_MOVES =
        listOf(
            "MOVE_MEGA_PUNCH",
            "MOVE_SWORDS_DANCE",
            "MOVE_MEGA_KICK",
            "MOVE_BODY_SLAM",
            "MOVE_DOUBLE_EDGE",
            "MOVE_COUNTER",
            "MOVE_SEISMIC_TOSS",
            "MOVE_MIMIC",
            "MOVE_METRONOME",
            "MOVE_SOFT_BOILED",
            "MOVE_DREAM_EATER",
            "MOVE_THUNDER_WAVE",
            "MOVE_EXPLOSION",
            "MOVE_ROCK_SLIDE",
            "MOVE_SUBSTITUTE",
            "MOVE_DYNAMIC_PUNCH",
            "MOVE_ROLLOUT",
            "MOVE_PSYCH_UP",
            "MOVE_SNORE",
            "MOVE_ICY_WIND",
            "MOVE_ENDURE",
            "MOVE_MUD_SLAP",
            "MOVE_ICE_PUNCH",
            "MOVE_SWAGGER",
            "MOVE_SLEEP_TALK",
            "MOVE_SWIFT",
            "MOVE_DEFENSE_CURL",
            "MOVE_THUNDER_PUNCH",
            "MOVE_FIRE_PUNCH",
            "MOVE_FURY_CUTTER")
  }
}
