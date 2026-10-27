package de.fiereu.openmmo.server.game.minigame.records

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.server.game.minigame.MinigameChannel
import de.fiereu.openmmo.server.game.minigame.MinigameRepository
import de.fiereu.openmmo.server.game.minigame.MinigameRewards
import de.fiereu.openmmo.server.game.minigame.MinigameSave
import de.fiereu.openmmo.server.game.minigame.MinigameTopic
import de.fiereu.openmmo.server.game.minigame.SpecialFn
import de.fiereu.openmmo.server.game.minigame.SpecialTable
import de.fiereu.openmmo.server.game.minigame.action
import de.fiereu.openmmo.server.game.minigame.arr
import de.fiereu.openmmo.server.game.minigame.gamecorner.GameCornerScripts
import de.fiereu.openmmo.server.game.minigame.jsonOf
import de.fiereu.openmmo.server.game.minigame.str
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.SecretBaseRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** An Easy Chat phrase: word ids (EC_WORD: group << 9 | index) and their text as shown. */
data class Phrase(val ids: List<Int>, val text: String)

/**
 * Record mixing for online players (record_mixing.c), Emerald's Easy Chat (easy_chat.c), the
 * Dewford trendy phrase (dewford_trend.c) and TV interviews (tv.c), topics "records" and
 * "easychat".
 * - Mixing records (the Record Corner, or the Link menu) shares this player's record — a trainer
 *   card summary for the Lilycove trainer, the Easy Chat profile, the trendy phrase, TV shows from
 *   interviews — and takes the newest records of other players, and lists their secret bases (read
 *   only from the secret base table).
 * - The Dewford trend is the newest trendy phrase anyone mixed in; its trendiness seeds the
 *   Mauville slot machines (GetSlotMachineId).
 */
@Singleton
class RecordsService
@Inject
constructor(
    private val repo: MinigameRepository,
    private val save: MinigameSave,
    private val characters: CharacterStore,
    private val bases: SecretBaseRepository,
    private val rewards: MinigameRewards,
) : MinigameTopic {
  override val topics = setOf("records", "easychat")
  private val pendingChat = ConcurrentHashMap<Long, CompletableDeferred<Phrase?>>()

  private fun parsePhrase(o: JsonObject): Phrase? {
    val ids = o.arr("words")?.mapNotNull { it.jsonPrimitive.intOrNull } ?: return null
    if (ids.isEmpty() || ids.size > 9 || ids.any { it !in 0..0x2DFF && it != 0xFFFF }) return null
    val text = (o.str("text") ?: "").replace(Regex("[^A-Za-z0-9 '!?.,é…-]"), "").take(120)
    return Phrase(ids, text)
  }

  private fun phraseJson(p: Phrase) = jsonOf("words" to p.ids, "text" to p.text)

  /**
   * ShowEasyChatScreen: opens the word picker for [kind] (profile, trend, interview, greeting) with
   * [count] words and waits for the phrase (null when cancelled).
   */
  suspend fun easyChat(session: SessionContext, charId: Long, kind: String, count: Int): Phrase? {
    val d = CompletableDeferred<Phrase?>()
    pendingChat.put(charId, d)?.complete(null)
    MinigameChannel.send(
        session,
        "easychat",
        jsonOf(
            "kind" to kind, "count" to count, "current" to save.section(charId, "easychat")[kind]))
    try {
      return withTimeoutOrNull(5.minutes) { d.await() }
    } finally {
      pendingChat.remove(charId, d)
      MinigameChannel.close(session, "easychat")
    }
  }

  suspend fun setPhrase(charId: Long, kind: String, p: Phrase) {
    val s = save.section(charId, "easychat")
    save.setSection(charId, "easychat", JsonObject(s + (kind to phraseJson(p))))
  }

  /** The Dewford trend: the newest mixed-in trendy phrase, or the decomp's default. */
  suspend fun trend(charId: Long): JsonObject =
      repo.records("trend", -1, 1).firstOrNull()?.payload
          ?: jsonOf("text" to "SKATE GOOD", "trendiness" to 0, "rand" to 0)

  /** InterviewAfter: the interview becomes a TV show everyone mixing records can watch. */
  suspend fun recordShow(charId: Long, type: Int, p: Phrase) {
    repo.addRecord(
        charId,
        "tvshow",
        jsonOf("type" to type, "author" to name(charId), "words" to p.ids, "text" to p.text))
  }

  private fun name(c: Long) = characters.getCharacter(c)?.info?.name ?: "?"

  /** This player's record for mixing (the Lilycove trainer / trainer card summary). */
  private suspend fun ownRecord(charId: Long): JsonObject {
    val s = characters.getCharacter(charId)
    val ec = save.section(charId, "easychat")
    return jsonOf(
        "name" to name(charId),
        "party" to
            (s?.pokemon?.map { jsonOf("dex" to it.dexId, "level" to it.level) }
                ?: emptyList<Any>()),
        "profile" to ec["profile"],
        "greeting" to ec["greeting"],
    )
  }

  /** TryRecordMixLinkup / RecordMixingPlayerSpotTriggered: share and receive records. */
  suspend fun mix(session: SessionContext, charId: Long): JsonObject {
    repo.addRecord(charId, "trainer", ownRecord(charId))
    save.section(charId, "easychat")["trend"]?.let { t ->
      val o = t as? JsonObject ?: return@let
      repo.addRecord(
          charId,
          "trend",
          JsonObject(
              o +
                  mapOf(
                      "author" to JsonPrimitive(name(charId)),
                      "trendiness" to JsonPrimitive((0..127).random()),
                      "rand" to JsonPrimitive((0..0xFFFF).random()))))
    }
    val trainers =
        repo.records("trainer", charId, 20).distinctBy { it.characterId }.take(5).map { it.payload }
    val shows = repo.records("tvshow", charId, 10).map { it.payload }
    val otherBases =
        bases
            .ofCharacters(
                trainers.size.let {
                  repo.records("trainer", charId, 50).map { it.characterId }.distinct()
                })
            .map {
              jsonOf(
                  "owner" to it.ownerName,
                  "baseId" to it.baseId,
                  "decorations" to it.decorations.size)
            }
    val t = trend(charId)
    // dewfordTrends[0].trendiness + .rand seeds the Mauville slot machines (GetSlotMachineId).
    characters.setStoryVar(
        charId,
        GameCornerScripts.TREND_SEED_VAR,
        (t["trendiness"]?.jsonPrimitive?.intOrNull ?: 0) +
            (t["rand"]?.jsonPrimitive?.intOrNull ?: 0))
    val mixed =
        jsonOf(
            "trainers" to trainers,
            "shows" to shows,
            "bases" to otherBases,
            "trend" to t,
            "at" to System.currentTimeMillis())
    save.setSection(charId, "mixed", mixed)
    rewards.tell(
        session,
        "Records were mixed with ${trainers.size} other TRAINER${if (trainers.size == 1) "" else "S"}.")
    return mixed
  }

  suspend fun state(charId: Long): JsonObject =
      jsonOf(
          "mixed" to save.section(charId, "mixed"),
          "easychat" to save.section(charId, "easychat"),
          "trend" to trend(charId))

  override suspend fun onAction(
      session: SessionContext,
      state: PlayerState,
      topic: String,
      body: JsonObject
  ) {
    val charId = state.characterId ?: return
    if (topic == "easychat") {
      when (body.action()) {
        "done" -> pendingChat.remove(charId)?.complete(parsePhrase(body))
        "cancel" -> pendingChat.remove(charId)?.complete(null)
        // The Link menu's own profile editor (ShowEasyChatProfile without a script).
        "save" -> {
          val kind = body.str("kind")?.takeIf { it in KINDS } ?: return
          val p = parsePhrase(body) ?: return
          setPhrase(charId, kind, p)
          MinigameChannel.send(session, "records", state(charId))
        }
      }
      return
    }
    when (body.action()) {
      "open" -> {}
      "mix" -> mix(session, charId)
      "close" -> return MinigameChannel.close(session, "records")
      else -> return
    }
    MinigameChannel.send(session, "records", state(charId))
  }

  override fun onDisconnect(charId: Long) {
    pendingChat.remove(charId)?.complete(null)
  }

  companion object {
    val KINDS = setOf("profile", "greeting", "trend", "interview")
  }
}

/** Easy Chat / trendy phrase / interview / record mixing specials. */
@Singleton
class RecordsSpecials @Inject constructor(private val records: RecordsService) : SpecialTable() {
  override val table: Map<String, SpecialFn> =
      mapOf(
          // ShowEasyChatScreen with VAR_0x8004 = EASY_CHAT_TYPE_*: profile (0), trendy phrase
          // (4..),
          // an interview's words otherwise. The phrase is kept for the special that follows.
          "ShowEasyChatScreen" to
              { ctx ->
                val type = ctx.specialVar(0x4)
                val kind =
                    when (type) {
                      0 -> "profile"
                      4 -> "trend"
                      else -> "interview"
                    }
                val p =
                    records.easyChat(
                        ctx.session, ctx.playerEntityId, kind, if (kind == "trend") 2 else 4)
                if (p != null) records.setPhrase(ctx.playerEntityId, kind, p)
                if (p == null) 0 else 1
              },
          "ShowEasyChatProfile" to
              { ctx ->
                val p =
                    records.state(ctx.playerEntityId)["easychat"]?.let {
                      (it as JsonObject)["profile"] as? JsonObject
                    }
                ctx.bufferString(1, p?.get("text")?.jsonPrimitive?.content ?: "")
                0
              },
          "BufferTrendyPhraseString" to
              { ctx ->
                ctx.bufferString(
                    1, records.trend(ctx.playerEntityId)["text"]?.jsonPrimitive?.content ?: "")
                0
              },
          // IsTrendyPhraseBoring: the decomp asks whether the trend has gone stale (trendiness 0).
          "IsTrendyPhraseBoring" to
              { ctx ->
                if ((records.trend(ctx.playerEntityId)["trendiness"]?.jsonPrimitive?.intOrNull
                    ?: 0) == 0)
                    1
                else 0
              },
          "InterviewBefore" to { _ -> 0 },
          "InterviewAfter" to
              { ctx ->
                val ec = records.state(ctx.playerEntityId)["easychat"] as? JsonObject
                val p = ec?.get("interview") as? JsonObject
                if (p != null) {
                  val ids =
                      (p["words"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }
                          ?: emptyList()
                  records.recordShow(
                      ctx.playerEntityId,
                      ctx.specialVar(0x5),
                      Phrase(ids, p["text"]?.jsonPrimitive?.content ?: ""))
                }
                0
              },
          "TryRecordMixLinkup" to
              { ctx ->
                records.mix(ctx.session, ctx.playerEntityId)
                1
              },
          "RecordMixingPlayerSpotTriggered" to
              { ctx ->
                records.mix(ctx.session, ctx.playerEntityId)
                0
              },
      )
}
