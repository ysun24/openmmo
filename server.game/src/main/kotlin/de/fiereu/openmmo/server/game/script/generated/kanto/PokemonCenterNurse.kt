package de.fiereu.openmmo.server.game.script.generated.kanto

import de.fiereu.openmmo.dialog.generated.kanto.Misc
import de.fiereu.openmmo.server.game.script.ScriptContext

/**
 * FireRed's EventScript_PkmnCenterNurse (data/scripts/pkmn_center_nurse.inc): the yes/no is the
 * decomp's MULTICHOICE_YES_NO, and the heal animation has no verb, so the party heals between the
 * two boxes. Its text lives in data/event_scripts.s, which has no ROM offset in our table, so the
 * lines come from codegen/dialog/kanto.extra.json.
 */
internal suspend fun healAtKantoPokemonCenter(ctx: ScriptContext) {
  if (!ctx.askYesNo(Misc.Text_WelcomeWantToHealPkmn)) {
    ctx.say(Misc.Text_WeHopeToSeeYouAgain)
    return
  }
  ctx.say(Misc.Text_TakeYourPkmnForFewSeconds)
  ctx.healParty()
  ctx.say(Misc.Text_RestoredPkmnToFullHealth)
  ctx.say(Misc.Text_WeHopeToSeeYouAgain)
}
