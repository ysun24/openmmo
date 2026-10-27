package de.fiereu.openmmo.server.game.storage

import de.fiereu.openmmo.db.game.tables.references.CHARACTERS
import de.fiereu.openmmo.db.game.tables.references.SECRET_BASES
import de.fiereu.openmmo.net.game.packets.field.PlacedDecoration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jooq.DSLContext

/** A character's secret base (SaveBlock1 secretBases[0]). */
data class SecretBaseRow(
    val characterId: Long,
    val ownerName: String,
    /** The decomp SECRET_BASE_* id of the spot. */
    val baseId: Int,
    val decorations: List<PlacedDecoration> = emptyList(),
    val timesEntered: Int = 0,
)

/** Persistence of the secret bases (V9_1__secret_bases). */
interface SecretBaseRepository {
  suspend fun get(charId: Long): SecretBaseRow?

  /** The bases of [charIds] (friends), those that have one. */
  suspend fun ofCharacters(charIds: Collection<Long>): List<SecretBaseRow>

  /** Inserts or replaces [row]. */
  suspend fun save(row: SecretBaseRow, now: LocalDateTime)

  suspend fun delete(charId: Long)

  companion object {
    fun encode(d: List<PlacedDecoration>): String =
        d.joinToString(",") { "${it.decorId}:${it.x}:${it.y}" }

    fun decode(s: String): List<PlacedDecoration> =
        s.split(',')
            .filter { it.isNotBlank() }
            .mapNotNull {
              val p = it.split(':')
              if (p.size != 3) null
              else
                  PlacedDecoration(
                      p[0].toIntOrNull() ?: return@mapNotNull null, p[1].toInt(), p[2].toInt())
            }
  }
}

@Singleton
class JooqSecretBaseRepository
@Inject
constructor(
    private val dsl: DSLContext,
    @param:Named("db") private val dispatcher: CoroutineDispatcher,
) : SecretBaseRepository {
  private suspend fun <T> db(block: (DSLContext) -> T): T = withContext(dispatcher) { block(dsl) }

  private val fields =
      listOf(
          SECRET_BASES.CHARACTER_ID,
          SECRET_BASES.SECRET_BASE_ID,
          SECRET_BASES.DECORATIONS,
          SECRET_BASES.TIMES_ENTERED,
          CHARACTERS.NAME)

  private fun org.jooq.Record.row() =
      SecretBaseRow(
          get(SECRET_BASES.CHARACTER_ID)!!,
          get(CHARACTERS.NAME) ?: "",
          get(SECRET_BASES.SECRET_BASE_ID)!!.toInt(),
          SecretBaseRepository.decode(get(SECRET_BASES.DECORATIONS) ?: ""),
          get(SECRET_BASES.TIMES_ENTERED)?.toInt() ?: 0,
      )

  override suspend fun get(charId: Long): SecretBaseRow? = db { d ->
    d.select(fields)
        .from(SECRET_BASES)
        .join(CHARACTERS)
        .on(CHARACTERS.ID.eq(SECRET_BASES.CHARACTER_ID))
        .where(SECRET_BASES.CHARACTER_ID.eq(charId))
        .fetchOne()
        ?.row()
  }

  override suspend fun ofCharacters(charIds: Collection<Long>): List<SecretBaseRow> =
      if (charIds.isEmpty()) emptyList()
      else
          db { d ->
            d.select(fields)
                .from(SECRET_BASES)
                .join(CHARACTERS)
                .on(CHARACTERS.ID.eq(SECRET_BASES.CHARACTER_ID))
                .where(SECRET_BASES.CHARACTER_ID.`in`(charIds))
                .fetch { it.row() }
          }

  override suspend fun save(row: SecretBaseRow, now: LocalDateTime) {
    db { d ->
      d.insertInto(SECRET_BASES)
          .set(SECRET_BASES.CHARACTER_ID, row.characterId)
          .set(SECRET_BASES.SECRET_BASE_ID, row.baseId.toShort())
          .set(SECRET_BASES.DECORATIONS, SecretBaseRepository.encode(row.decorations))
          .set(SECRET_BASES.TIMES_ENTERED, row.timesEntered.coerceAtMost(255).toShort())
          .set(SECRET_BASES.CREATED_AT, now)
          .onConflict(SECRET_BASES.CHARACTER_ID)
          .doUpdate()
          .set(SECRET_BASES.SECRET_BASE_ID, row.baseId.toShort())
          .set(SECRET_BASES.DECORATIONS, SecretBaseRepository.encode(row.decorations))
          .set(SECRET_BASES.TIMES_ENTERED, row.timesEntered.coerceAtMost(255).toShort())
          .execute()
    }
  }

  override suspend fun delete(charId: Long) {
    db { d -> d.deleteFrom(SECRET_BASES).where(SECRET_BASES.CHARACTER_ID.eq(charId)).execute() }
  }
}

/** For tests and the dev path without a database. */
class InMemorySecretBaseRepository : SecretBaseRepository {
  val rows = ConcurrentHashMap<Long, SecretBaseRow>()

  override suspend fun get(charId: Long): SecretBaseRow? = rows[charId]

  override suspend fun ofCharacters(charIds: Collection<Long>): List<SecretBaseRow> =
      charIds.mapNotNull(rows::get)

  override suspend fun save(row: SecretBaseRow, now: LocalDateTime) {
    rows[row.characterId] = row
  }

  override suspend fun delete(charId: Long) {
    rows.remove(charId)
  }
}
