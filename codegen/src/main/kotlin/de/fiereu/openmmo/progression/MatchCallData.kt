package de.fiereu.openmmo.progression

/** Emerald's Match Call trainers (r4), parsed from [MatchCallTables]. */
object MatchCallData {
  data class Trainer(
      /** The first trainer id of its rematch entry (TRAINER_X_1). */
      val trainerId: Int,
      val name: String,
      /** The route name the trainer is on ({STR_VAR_2}). */
      val route: String,
      /** Dialog ids (0 = none) of the same-route / different-route requests and the chat. */
      val sameRouteText: Int,
      val differentRouteText: Int,
      val generalText: Int,
      val mapsec: String,
  )

  val trainers: List<Trainer> =
      MatchCallTables.TRAINERS.split(';')
          .filter { it.isNotEmpty() }
          .map {
            val p = it.split('/')
            Trainer(p[0].toInt(), p[1], p[2], p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6])
          }

  fun byId(id: Int): Trainer? = trainers.firstOrNull { it.trainerId == id }
}
