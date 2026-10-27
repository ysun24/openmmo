package de.fiereu.openmmo.common

/**
 * Gen 3 items the Gen 5 item table (decomp/pokeblack) has no row for, put on its empty "???" slots:
 * HM07 WATERFALL and HM08 DIVE in Gen 3's numbering, Emerald's WAILMER PAIL, the event tickets (EON
 * / MYSTIC / AURORA TICKET, OLD SEA MAP), FRLG's FAME CHECKER and Emerald's POKéBLOCK CASE. The
 * item codegen and the gateway's item catalog both read the table through this overlay, so the wire
 * id is `5000 + index` everywhere.
 */
object ExtraItems {
  val BY_INDEX: Map<Int, String> =
      mapOf(
          426 to "HM07",
          427 to "HM08",
          113 to "Wailmer Pail",
          // r5 merge: the event tickets, the Fame Checker and the Pokéblock Case as real key items.
          114 to "Eon Ticket",
          115 to "Mystic Ticket",
          120 to "Aurora Ticket",
          121 to "Old Sea Map",
          122 to "Fame Checker",
          123 to "Pokeblock Case",
      )

  private val PLACEHOLDERS = setOf("None", "?????", "???", "-", "?", "")

  /** [names] (item_names.json order) with the extra items on their empty slots. */
  fun overlay(names: List<String>): List<String> =
      names.mapIndexed { i, n -> BY_INDEX[i]?.takeIf { n.trim() in PLACEHOLDERS } ?: n }
}
