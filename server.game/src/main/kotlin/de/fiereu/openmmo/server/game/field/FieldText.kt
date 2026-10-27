package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.common.dialog.DialogLine
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.dialog.generated.hoenn.Misc as HoennMisc
import de.fiereu.openmmo.dialog.generated.kanto.Misc as KantoMisc

/** The field move texts of data/scripts/field_move_scripts.inc, per game. */
class FieldText(
    val wantToCut: DialogLine,
    val cantCut: DialogLine,
    val wantToSmash: DialogLine,
    val cantSmash: DialogLine,
    val wantToStrength: DialogLine,
    val cantStrength: DialogLine,
    /** "{STR_VAR_1} used STRENGTH!..." */
    val usedStrength: DialogLine,
    val strengthActive: DialogLine,
    /** "{STR_VAR_1} used {STR_VAR_2}!" */
    val usedMove: DialogLine,
    val wantToSurf: DialogLine,
    val wantToWaterfall: DialogLine,
    val cantWaterfall: DialogLine,
    val wantToDive: DialogLine?,
    val wantToSurface: DialogLine?,
    /** "{PLAYER} found one {STR_VAR_2}!" */
    val foundItem: DialogLine,
    val bootedPc: DialogLine,
) {
  companion object {
    private val kanto =
        FieldText(
            wantToCut = KantoMisc.Text_CutTreeDown,
            cantCut = KantoMisc.Text_TreeCanBeCutDown,
            wantToSmash = KantoMisc.Text_UseRockSmash,
            cantSmash = KantoMisc.Text_MonMaySmashRock,
            wantToStrength = KantoMisc.Text_UseStrength,
            cantStrength = KantoMisc.Text_MonMayPushBoulder,
            usedStrength = KantoMisc.Text_MonUsedStrengthCanMoveBoulders,
            strengthActive = KantoMisc.Text_StrengthMadeMovingBouldersPossible,
            usedMove = KantoMisc.Text_MonUsedMove,
            wantToSurf = KantoMisc.Text_WantToSurf,
            wantToWaterfall = KantoMisc.Text_UseWaterfall,
            cantWaterfall = KantoMisc.Text_WallOfWaterCrashingDown,
            wantToDive = null,
            wantToSurface = null,
            foundItem = KantoMisc.Text_FoundOneItem,
            bootedPc = KantoMisc.Text_BootedUpPC,
        )
    private val hoenn =
        FieldText(
            wantToCut = HoennMisc.Text_WantToCut,
            cantCut = HoennMisc.Text_CantCut,
            wantToSmash = HoennMisc.Text_WantToSmash,
            cantSmash = HoennMisc.Text_CantSmash,
            wantToStrength = HoennMisc.Text_WantToStrength,
            cantStrength = HoennMisc.Text_CantStrength,
            usedStrength = HoennMisc.Text_MonUsedStrength,
            strengthActive = HoennMisc.Text_StrengthActivated,
            usedMove = HoennMisc.Text_MonUsedFieldMove,
            wantToSurf = HoennMisc.gText_WantToUseSurf,
            wantToWaterfall = HoennMisc.Text_WantToWaterfall,
            cantWaterfall = HoennMisc.Text_CantWaterfall,
            wantToDive = HoennMisc.Text_WantToDive,
            wantToSurface = HoennMisc.Text_WantToSurface,
            foundItem = HoennMisc.gText_PlayerFoundOneItem,
            bootedPc = HoennMisc.Text_BootUpPC,
        )

    fun of(region: Region): FieldText = if (region == Region.HOENN) hoenn else kanto
  }
}

/** A dialog line known only by its id, e.g. a trainer's intro from generated map data. */
data class RawLine(override val textId: Int) : DialogLine
