package de.fiereu.openmmo.server.game.field

import de.fiereu.openmmo.common.enums.Region

/**
 * A FLY destination: a town's map (decomp name), the flag that marks it visited and where FLY lands
 * (the decomp heal location, src/data/heal_locations.json). FireRed marks towns with
 * FLAG_WORLD_MAP_*, Emerald with FLAG_VISITED_* (region_map.c GetMapsecType).
 */
data class FlyTown(
    val region: Region,
    val displayName: String,
    val mapName: String,
    val flag: String,
    val x: Int,
    val y: Int,
    /** Emerald's Littleroot lands at the player's own house; the female player's is here. */
    val femaleX: Int = x,
    val femaleY: Int = y,
)

object FlyDestinations {
  private fun k(name: String, map: String, flag: String, x: Int, y: Int) =
      FlyTown(Region.KANTO, name, map, "kanto/FLAG_WORLD_MAP_$flag", x, y)

  private fun h(name: String, map: String, flag: String, x: Int, y: Int) =
      FlyTown(Region.HOENN, name, map, "hoenn/FLAG_VISITED_$flag", x, y)

  val all: List<FlyTown> =
      listOf(
          k("PALLET TOWN", "PalletTown", "PALLET_TOWN", 6, 8),
          k("VIRIDIAN CITY", "ViridianCity", "VIRIDIAN_CITY", 26, 27),
          k("PEWTER CITY", "PewterCity", "PEWTER_CITY", 17, 26),
          k("CERULEAN CITY", "CeruleanCity", "CERULEAN_CITY", 22, 20),
          k("LAVENDER TOWN", "LavenderTown", "LAVENDER_TOWN", 6, 6),
          k("VERMILION CITY", "VermilionCity", "VERMILION_CITY", 15, 7),
          k("CELADON CITY", "CeladonCity", "CELADON_CITY", 48, 12),
          k("FUCHSIA CITY", "FuchsiaCity", "FUCHSIA_CITY", 25, 32),
          k("CINNABAR ISLAND", "CinnabarIsland", "CINNABAR_ISLAND", 14, 12),
          k("INDIGO PLATEAU", "IndigoPlateau_Exterior", "INDIGO_PLATEAU_EXTERIOR", 11, 7),
          k("SAFFRON CITY", "SaffronCity", "SAFFRON_CITY", 24, 39),
          k("ONE ISLAND", "OneIsland", "ONE_ISLAND", 14, 6),
          k("TWO ISLAND", "TwoIsland", "TWO_ISLAND", 21, 8),
          k("THREE ISLAND", "ThreeIsland", "THREE_ISLAND", 14, 28),
          k("FOUR ISLAND", "FourIsland", "FOUR_ISLAND", 18, 21),
          k("FIVE ISLAND", "FiveIsland", "FIVE_ISLAND", 18, 7),
          k("SIX ISLAND", "SixIsland", "SIX_ISLAND", 11, 12),
          k("SEVEN ISLAND", "SevenIsland", "SEVEN_ISLAND", 12, 4),
          FlyTown(
              Region.HOENN,
              "LITTLEROOT TOWN",
              "LittlerootTown",
              "hoenn/FLAG_VISITED_LITTLEROOT_TOWN",
              5,
              9,
              femaleX = 14,
              femaleY = 9),
          h("OLDALE TOWN", "OldaleTown", "OLDALE_TOWN", 6, 17),
          h("DEWFORD TOWN", "DewfordTown", "DEWFORD_TOWN", 2, 11),
          h("LAVARIDGE TOWN", "LavaridgeTown", "LAVARIDGE_TOWN", 9, 7),
          h("FALLARBOR TOWN", "FallarborTown", "FALLARBOR_TOWN", 14, 8),
          h("VERDANTURF TOWN", "VerdanturfTown", "VERDANTURF_TOWN", 16, 4),
          h("PACIFIDLOG TOWN", "PacifidlogTown", "PACIFIDLOG_TOWN", 8, 16),
          h("PETALBURG CITY", "PetalburgCity", "PETALBURG_CITY", 20, 17),
          h("SLATEPORT CITY", "SlateportCity", "SLATEPORT_CITY", 19, 20),
          h("MAUVILLE CITY", "MauvilleCity", "MAUVILLE_CITY", 22, 6),
          h("RUSTBORO CITY", "RustboroCity", "RUSTBORO_CITY", 16, 39),
          h("FORTREE CITY", "FortreeCity", "FORTREE_CITY", 5, 7),
          h("LILYCOVE CITY", "LilycoveCity", "LILYCOVE_CITY", 24, 15),
          h("MOSSDEEP CITY", "MossdeepCity", "MOSSDEEP_CITY", 28, 17),
          h("SOOTOPOLIS CITY", "SootopolisCity", "SOOTOPOLIS_CITY", 43, 32),
          h("EVER GRANDE CITY", "EverGrandeCity", "EVER_GRANDE_CITY", 27, 49),
      )

  fun forRegion(region: Region): List<FlyTown> = all.filter { it.region == region }

  /** The town whose own map is [mapName], if it is a FLY destination. */
  fun byMapName(region: Region, mapName: String): FlyTown? =
      all.firstOrNull { it.region == region && it.mapName == mapName }
}
