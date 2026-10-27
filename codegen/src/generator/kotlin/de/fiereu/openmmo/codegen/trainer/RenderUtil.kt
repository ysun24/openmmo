package de.fiereu.openmmo.codegen.trainer

object RenderUtil {

  fun trainer(region: String, t: ParsedTrainer): String {
    val party = t.party.joinToString(", ", "listOf(", ")") { mon(it) }
    return "reg.register(Region.${region.uppercase()}, TrainerDef(${t.id}, \"${escape(t.name)}\", " +
        "${t.trainerClass}, ${t.doubleBattle}, ${t.prizeRate}, $party, " +
        "pic = \"${t.pic}\", className = \"${t.className}\", " +
        "items = listOf(${t.items.joinToString(", ") { "\"$it\"" }}), music = \"${t.music}\"))"
  }

  private fun mon(m: ParsedTrainerMon): String {
    val moves = m.moveIds.joinToString(", ", "listOf(", ")")
    return "TrainerMon(${m.dexId}, ${m.level}, ${m.iv}, ${m.heldItem}, $moves, \"${m.heldItemName}\")"
  }

  private fun escape(name: String) = name.replace("\\", "\\\\").replace("\"", "\\\"")
}
