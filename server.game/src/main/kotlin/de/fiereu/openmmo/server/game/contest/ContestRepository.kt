package de.fiereu.openmmo.server.game.contest

import de.fiereu.openmmo.db.game.tables.references.BERRY_TREES
import de.fiereu.openmmo.db.game.tables.references.CHARACTER_CONTEST
import de.fiereu.openmmo.db.game.tables.references.CONTEST_PAINTINGS
import de.fiereu.openmmo.db.game.tables.references.POKEBLOCKS
import de.fiereu.openmmo.db.game.tables.references.POKEMON_CONTEST
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jooq.DSLContext

/** A berry tree as stored: the tree and when its countdown was last updated. */
data class StoredBerryTree(val tree: BerryTree, val updatedAt: LocalDateTime)

/** A museum painting (struct ContestWinner, CONTEST_WINNER_MUSEUM_*). */
data class ContestPainting(
    val category: Int,
    val pokemonId: Long,
    val dex: Int,
    val shiny: Boolean,
    val nickname: String,
    val trainer: String,
    val rank: Int,
    val createdAt: LocalDateTime,
)

/** Persistence of r5-contests (V10_2__contests). */
interface ContestRepository {
  suspend fun monContest(ids: Collection<Long>): Map<Long, MonContest>

  suspend fun saveMonContest(id: Long, c: MonContest)

  /** The case's 40 slots, null for an empty one. */
  suspend fun pokeblocks(charId: Long): List<Pokeblock?>

  suspend fun savePokeblocks(charId: Long, slots: List<Pokeblock?>)

  suspend fun berryTrees(charId: Long): Map<Int, StoredBerryTree>

  suspend fun saveBerryTree(charId: Long, treeId: Int, t: StoredBerryTree)

  suspend fun treesInitialized(charId: Long): Boolean

  suspend fun setTreesInitialized(charId: Long)

  suspend fun blenderRecord(charId: Long): Int

  suspend fun setBlenderRecord(charId: Long, rpm: Int)

  suspend fun paintings(charId: Long): List<ContestPainting>

  suspend fun savePainting(charId: Long, p: ContestPainting)
}

/** Memory-only [ContestRepository] for tests. */
class InMemoryContestRepository : ContestRepository {
  val mons = ConcurrentHashMap<Long, MonContest>()
  val blocks = ConcurrentHashMap<Long, List<Pokeblock?>>()
  val trees = ConcurrentHashMap<Long, MutableMap<Int, StoredBerryTree>>()
  private val initialized = ConcurrentHashMap.newKeySet<Long>()
  private val records = ConcurrentHashMap<Long, Int>()
  val paintings = ConcurrentHashMap<Long, MutableMap<Int, ContestPainting>>()

  override suspend fun monContest(ids: Collection<Long>) =
      ids.mapNotNull { id -> mons[id]?.let { id to it } }.toMap()

  override suspend fun saveMonContest(id: Long, c: MonContest) {
    mons[id] = c
  }

  override suspend fun pokeblocks(charId: Long) =
      blocks[charId] ?: List(Pokeblock.CASE_SIZE) { null }

  override suspend fun savePokeblocks(charId: Long, slots: List<Pokeblock?>) {
    blocks[charId] = slots.toList()
  }

  override suspend fun berryTrees(charId: Long): Map<Int, StoredBerryTree> =
      trees[charId]?.toMap() ?: emptyMap()

  override suspend fun saveBerryTree(charId: Long, treeId: Int, t: StoredBerryTree) {
    trees.getOrPut(charId) { ConcurrentHashMap() }[treeId] = t
  }

  override suspend fun treesInitialized(charId: Long) = charId in initialized

  override suspend fun setTreesInitialized(charId: Long) {
    initialized += charId
  }

  override suspend fun blenderRecord(charId: Long) = records[charId] ?: 0

  override suspend fun setBlenderRecord(charId: Long, rpm: Int) {
    records[charId] = rpm
  }

  override suspend fun paintings(charId: Long) = paintings[charId]?.values?.toList() ?: emptyList()

  override suspend fun savePainting(charId: Long, p: ContestPainting) {
    paintings.getOrPut(charId) { ConcurrentHashMap() }[p.category] = p
  }
}

@Singleton
class JooqContestRepository
@Inject
constructor(
    private val dsl: DSLContext,
    @param:Named("db") private val dispatcher: CoroutineDispatcher,
) : ContestRepository {
  private suspend fun <T> db(block: (DSLContext) -> T): T = withContext(dispatcher) { block(dsl) }

  private fun Short?.i() = this?.toInt() ?: 0

  override suspend fun monContest(ids: Collection<Long>): Map<Long, MonContest> =
      if (ids.isEmpty()) emptyMap()
      else
          db { d ->
            val p = POKEMON_CONTEST
            d.selectFrom(p).where(p.POKEMON_ID.`in`(ids)).fetch().associate { r ->
              r.pokemonId!! to
                  MonContest(
                      r.cool.i(),
                      r.beauty.i(),
                      r.cute.i(),
                      r.smart.i(),
                      r.tough.i(),
                      r.sheen.i(),
                      listOf(
                          r.coolRibbon.i(),
                          r.beautyRibbon.i(),
                          r.cuteRibbon.i(),
                          r.smartRibbon.i(),
                          r.toughRibbon.i()),
                      r.ribbons ?: 0)
            }
          }

  override suspend fun saveMonContest(id: Long, c: MonContest) = db { d ->
    val p = POKEMON_CONTEST
    d.insertInto(p)
        .set(p.POKEMON_ID, id)
        .set(p.COOL, c.cool.toShort())
        .set(p.BEAUTY, c.beauty.toShort())
        .set(p.CUTE, c.cute.toShort())
        .set(p.SMART, c.smart.toShort())
        .set(p.TOUGH, c.tough.toShort())
        .set(p.SHEEN, c.sheen.toShort())
        .set(p.COOL_RIBBON, c.ribbon(0).toShort())
        .set(p.BEAUTY_RIBBON, c.ribbon(1).toShort())
        .set(p.CUTE_RIBBON, c.ribbon(2).toShort())
        .set(p.SMART_RIBBON, c.ribbon(3).toShort())
        .set(p.TOUGH_RIBBON, c.ribbon(4).toShort())
        .set(p.RIBBONS, c.ribbons)
        .onConflict(p.POKEMON_ID)
        .doUpdate()
        .set(p.COOL, c.cool.toShort())
        .set(p.BEAUTY, c.beauty.toShort())
        .set(p.CUTE, c.cute.toShort())
        .set(p.SMART, c.smart.toShort())
        .set(p.TOUGH, c.tough.toShort())
        .set(p.SHEEN, c.sheen.toShort())
        .set(p.COOL_RIBBON, c.ribbon(0).toShort())
        .set(p.BEAUTY_RIBBON, c.ribbon(1).toShort())
        .set(p.CUTE_RIBBON, c.ribbon(2).toShort())
        .set(p.SMART_RIBBON, c.ribbon(3).toShort())
        .set(p.TOUGH_RIBBON, c.ribbon(4).toShort())
        .set(p.RIBBONS, c.ribbons)
        .execute()
    Unit
  }

  override suspend fun pokeblocks(charId: Long): List<Pokeblock?> = db { d ->
    val p = POKEBLOCKS
    val slots = MutableList<Pokeblock?>(Pokeblock.CASE_SIZE) { null }
    d.selectFrom(p).where(p.CHARACTER_ID.eq(charId)).fetch().forEach { r ->
      val s = r.slot.i()
      if (s in slots.indices)
          slots[s] =
              Pokeblock(
                  r.color.i(), r.spicy.i(), r.dry.i(), r.sweet.i(), r.bitter.i(), r.sour.i(), r.feel.i())
    }
    slots
  }

  override suspend fun savePokeblocks(charId: Long, slots: List<Pokeblock?>) = db { d ->
    d.transaction { cfg ->
      val t = org.jooq.impl.DSL.using(cfg)
      val p = POKEBLOCKS
      t.deleteFrom(p).where(p.CHARACTER_ID.eq(charId)).execute()
      slots.forEachIndexed { i, b ->
        if (b != null)
            t.insertInto(p)
                .set(p.CHARACTER_ID, charId)
                .set(p.SLOT, i.toShort())
                .set(p.COLOR, b.color.toShort())
                .set(p.SPICY, b.spicy.toShort())
                .set(p.DRY, b.dry.toShort())
                .set(p.SWEET, b.sweet.toShort())
                .set(p.BITTER, b.bitter.toShort())
                .set(p.SOUR, b.sour.toShort())
                .set(p.FEEL, b.feel.toShort())
                .execute()
      }
    }
  }

  override suspend fun berryTrees(charId: Long): Map<Int, StoredBerryTree> = db { d ->
    val b = BERRY_TREES
    d.selectFrom(b).where(b.CHARACTER_ID.eq(charId)).fetch().associate { r ->
      r.treeId.i() to
          StoredBerryTree(
              BerryTree(
                  r.berry.i(),
                  r.stage.i(),
                  r.minutesUntilNextStage ?: 0,
                  r.berryYield.i(),
                  r.regrowthCount.i(),
                  r.watered.i(),
                  r.stopGrowth ?: false),
              r.updatedAt ?: LocalDateTime.now())
    }
  }

  override suspend fun saveBerryTree(charId: Long, treeId: Int, t: StoredBerryTree) = db { d ->
    val b = BERRY_TREES
    val tr = t.tree
    d.insertInto(b)
        .set(b.CHARACTER_ID, charId)
        .set(b.TREE_ID, treeId.toShort())
        .set(b.BERRY, tr.berry.toShort())
        .set(b.STAGE, tr.stage.toShort())
        .set(b.MINUTES_UNTIL_NEXT_STAGE, tr.minutesUntilNextStage)
        .set(b.BERRY_YIELD, tr.berryYield.toShort())
        .set(b.REGROWTH_COUNT, tr.regrowthCount.toShort())
        .set(b.WATERED, tr.watered.toShort())
        .set(b.STOP_GROWTH, tr.stopGrowth)
        .set(b.UPDATED_AT, t.updatedAt)
        .onConflict(b.CHARACTER_ID, b.TREE_ID)
        .doUpdate()
        .set(b.BERRY, tr.berry.toShort())
        .set(b.STAGE, tr.stage.toShort())
        .set(b.MINUTES_UNTIL_NEXT_STAGE, tr.minutesUntilNextStage)
        .set(b.BERRY_YIELD, tr.berryYield.toShort())
        .set(b.REGROWTH_COUNT, tr.regrowthCount.toShort())
        .set(b.WATERED, tr.watered.toShort())
        .set(b.STOP_GROWTH, tr.stopGrowth)
        .set(b.UPDATED_AT, t.updatedAt)
        .execute()
    Unit
  }

  override suspend fun treesInitialized(charId: Long): Boolean = db { d ->
    val c = CHARACTER_CONTEST
    d.select(c.TREES_INITIALIZED).from(c).where(c.CHARACTER_ID.eq(charId)).fetchOne()?.value1() ==
        true
  }

  private fun ensureRow(d: DSLContext, charId: Long) {
    val c = CHARACTER_CONTEST
    d.insertInto(c).set(c.CHARACTER_ID, charId).onConflictDoNothing().execute()
  }

  override suspend fun setTreesInitialized(charId: Long) = db { d ->
    ensureRow(d, charId)
    val c = CHARACTER_CONTEST
    d.update(c).set(c.TREES_INITIALIZED, true).where(c.CHARACTER_ID.eq(charId)).execute()
    Unit
  }

  override suspend fun blenderRecord(charId: Long): Int = db { d ->
    val c = CHARACTER_CONTEST
    d.select(c.BLENDER_RECORD).from(c).where(c.CHARACTER_ID.eq(charId)).fetchOne()?.value1() ?: 0
  }

  override suspend fun setBlenderRecord(charId: Long, rpm: Int) = db { d ->
    ensureRow(d, charId)
    val c = CHARACTER_CONTEST
    d.update(c).set(c.BLENDER_RECORD, rpm).where(c.CHARACTER_ID.eq(charId)).execute()
    Unit
  }

  override suspend fun paintings(charId: Long): List<ContestPainting> = db { d ->
    val p = CONTEST_PAINTINGS
    d.selectFrom(p).where(p.CHARACTER_ID.eq(charId)).fetch().map { r ->
      ContestPainting(
          r.category.i(),
          r.pokemonId ?: 0,
          r.dex.i(),
          r.shiny ?: false,
          r.nickname.orEmpty(),
          r.trainer.orEmpty(),
          r.contestRank.i(),
          r.createdAt ?: LocalDateTime.now())
    }
  }

  override suspend fun savePainting(charId: Long, p: ContestPainting) = db { d ->
    val t = CONTEST_PAINTINGS
    d.insertInto(t)
        .set(t.CHARACTER_ID, charId)
        .set(t.CATEGORY, p.category.toShort())
        .set(t.POKEMON_ID, p.pokemonId)
        .set(t.DEX, p.dex.toShort())
        .set(t.SHINY, p.shiny)
        .set(t.NICKNAME, p.nickname.take(16))
        .set(t.TRAINER, p.trainer.take(16))
        .set(t.CONTEST_RANK, p.rank.toShort())
        .set(t.CREATED_AT, p.createdAt)
        .onConflict(t.CHARACTER_ID, t.CATEGORY)
        .doUpdate()
        .set(t.POKEMON_ID, p.pokemonId)
        .set(t.DEX, p.dex.toShort())
        .set(t.SHINY, p.shiny)
        .set(t.NICKNAME, p.nickname.take(16))
        .set(t.TRAINER, p.trainer.take(16))
        .set(t.CONTEST_RANK, p.rank.toShort())
        .set(t.CREATED_AT, p.createdAt)
        .execute()
    Unit
  }
}
