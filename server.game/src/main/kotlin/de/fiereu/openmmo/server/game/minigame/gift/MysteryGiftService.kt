package de.fiereu.openmmo.server.game.minigame.gift

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.CharacterPermissions
import de.fiereu.openmmo.common.DynamicWarp
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.dialog.generated.hoenn.LilycoveCity_Harbor as HarborText
import de.fiereu.openmmo.dialog.generated.hoenn.MysteryGift as GiftText
import de.fiereu.openmmo.items.ItemRegistry
import de.fiereu.openmmo.server.game.minigame.MinigameChannel
import de.fiereu.openmmo.server.game.minigame.MinigameMenus
import de.fiereu.openmmo.server.game.minigame.MinigameRepository
import de.fiereu.openmmo.server.game.minigame.MinigameRewards
import de.fiereu.openmmo.server.game.minigame.MinigameSave
import de.fiereu.openmmo.server.game.minigame.MinigameTopic
import de.fiereu.openmmo.server.game.minigame.MysteryGiftRow
import de.fiereu.openmmo.server.game.minigame.SpecialFn
import de.fiereu.openmmo.server.game.minigame.SpecialTable
import de.fiereu.openmmo.server.game.minigame.action
import de.fiereu.openmmo.server.game.minigame.int
import de.fiereu.openmmo.server.game.minigame.jsonOf
import de.fiereu.openmmo.server.game.minigame.str
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.services.StoryService
import de.fiereu.openmmo.server.game.services.command.ChatCommand
import de.fiereu.openmmo.server.game.services.command.CommandContext
import de.fiereu.openmmo.server.game.session.PlayerState
import de.fiereu.openmmo.story.generated.hoenn.HoennFlags
import de.fiereu.openmmo.story.generated.kanto.KantoFlags
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** An event ticket a Mystery Gift can carry, and what it enables (the decomp's mystery events). */
enum class Ticket(val id: String, val title: String, val hoennFlags: List<String>, val kantoFlags: List<String>) {
  EON("eon", "EON TICKET", listOf(HoennFlags.FLAG_ENABLE_SHIP_SOUTHERN_ISLAND), emptyList()),
  MYSTIC("mystic", "MYSTICTICKET",
      listOf(HoennFlags.FLAG_ENABLE_SHIP_NAVEL_ROCK, HoennFlags.FLAG_RECEIVED_MYSTIC_TICKET),
      listOf(KantoFlags.FLAG_ENABLE_SHIP_NAVEL_ROCK)),
  AURORA("aurora", "AURORATICKET",
      listOf(HoennFlags.FLAG_ENABLE_SHIP_BIRTH_ISLAND, HoennFlags.FLAG_RECEIVED_AURORA_TICKET),
      listOf(KantoFlags.FLAG_ENABLE_SHIP_BIRTH_ISLAND)),
  OLD_SEA_MAP("oldseamap", "OLD SEA MAP",
      listOf(HoennFlags.FLAG_ENABLE_SHIP_FARAWAY_ISLAND, HoennFlags.FLAG_RECEIVED_OLD_SEA_MAP), emptyList()),
}

/**
 * MYSTERY GIFT / WONDER CARDS (mystery_gift*.c) for an online game: an admin defines a gift
 * (`/mysterygift add ...`, an item, a Pokémon or an event ticket); every player can take its
 * WONDER CARD from the Link menu (topic "gift"); the Pokémon Center 2F deliveryman
 * (CableClub_EventScript_MysteryGiftMan) hands over what the card holds. Event tickets are kept in
 * save section "tickets" and set the decomp's ship flags; the Lilycove ferry takes a ticket holder
 * to its island.
 */
@Singleton
class MysteryGiftService
@Inject
constructor(
    private val repo: MinigameRepository,
    private val save: MinigameSave,
    private val story: StoryService,
    private val rewards: MinigameRewards,
    private val items: ItemRegistry,
) : MinigameTopic {
  override val topics = setOf("gift")

  private fun regionOk(g: MysteryGiftRow, regionId: Int) = g.region == 0 || g.region == regionId + 1

  suspend fun cards(charId: Long, regionId: Int): JsonObject {
    val claims = repo.claims(charId)
    val gifts = repo.gifts()
    return jsonOf(
        "available" to gifts.filter { it.active && it.id !in claims && regionOk(it, regionId) }.map(::cardJson),
        "cards" to gifts.filter { claims[it.id] == 0 }.map(::cardJson),
        "delivered" to gifts.filter { claims[it.id] == 1 }.map(::cardJson),
        "tickets" to tickets(charId),
    )
  }

  private fun cardJson(g: MysteryGiftRow) =
      jsonOf("id" to g.id, "kind" to g.kind, "title" to g.title, "body" to g.body, "payload" to g.payload)

  suspend fun tickets(charId: Long): List<String> =
      (save.section(charId, "tickets")["owned"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

  private suspend fun addTicket(charId: Long, t: Ticket) {
    val owned = (tickets(charId) + t.id).distinct()
    save.setSection(charId, "tickets", JsonObject(mapOf("owned" to JsonArray(owned.map { JsonPrimitive(it) }))))
    (t.hoennFlags + t.kantoFlags).forEach { story.setFlag(charId, it) }
  }

  suspend fun hasTicket(charId: Long, t: Ticket) = t.id in tickets(charId)

  /** ValidateSavedWonderCard: a card is waiting to be delivered. */
  suspend fun hasPendingCard(charId: Long) = repo.claims(charId).values.any { it == 0 }

  override suspend fun onAction(session: SessionContext, state: PlayerState, topic: String, body: JsonObject) {
    val charId = state.characterId ?: return
    when (body.action()) {
      "open" -> {}
      "receive" -> {
        val id = body.int("id") ?: return
        val g = repo.gifts().firstOrNull { it.id == id && it.active && regionOk(it, state.regionId) } ?: return
        if (repo.claims(charId).containsKey(id)) return
        repo.setClaim(id, charId, 0)
        // The deliveryman appears in every Pokémon Center 2F while a card is waiting.
        story.clearFlag(charId, HoennFlags.FLAG_HIDE_POKEMON_CENTER_2F_MYSTERY_GIFT_MAN)
        rewards.tell(session, "The WONDER CARD \"${g.title}\" was saved. Visit the deliveryman at any POKéMON CENTER.")
      }
      "close" -> return MinigameChannel.close(session, "gift")
      else -> return
    }
    MinigameChannel.send(session, "gift", cards(charId, state.regionId))
  }

  /** The deliveryman: gives what every waiting card holds. Returns what was given. */
  suspend fun deliver(session: SessionContext, charId: Long): List<String> {
    val claims = repo.claims(charId)
    val given = mutableListOf<String>()
    for (g in repo.gifts().filter { claims[it.id] == 0 }) {
      val p = g.payload
      val ok =
          when (g.kind) {
            "item" -> {
              val item = p["item"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
              val qty = p["qty"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
              items.get(item) != null && rewards.addItem(session, charId, item, qty).also {
                if (it) given += "${items.get(item)!!.name.uppercase()} x$qty"
              }
            }
            "mon" -> {
              val dex = p["species"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
              val lvl = p["level"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5
              (rewards.giveMon(session, charId, dex, lvl) != null).also { if (it) given += "a POKéMON (#$dex Lv$lvl)" }
            }
            "ticket" -> {
              val t = Ticket.entries.firstOrNull { it.id == p["ticket"]?.jsonPrimitive?.content }
              if (t != null) {
                addTicket(charId, t)
                given += "the ${t.title}"
              }
              t != null
            }
            else -> false
          }
      if (ok) repo.setClaim(g.id, charId, 1)
    }
    if (!hasPendingCard(charId)) story.setFlag(charId, HoennFlags.FLAG_HIDE_POKEMON_CENTER_2F_MYSTERY_GIFT_MAN)
    return given
  }

  suspend fun addGift(row: MysteryGiftRow) = repo.addGift(row)

  suspend fun list() = repo.gifts()

  suspend fun setActive(id: Int, on: Boolean) = repo.setGiftActive(id, on)
}

/** The deliveryman and the Lilycove ferry's event-ticket routes. */
@Singleton
class MysteryGiftScripts
@Inject
constructor(
    private val gifts: MysteryGiftService,
    private val menus: MinigameMenus,
    private val rewards: MinigameRewards,
) {
  val scripts: Map<String, Script> by lazy {
    mapOf(
        "CableClub_EventScript_MysteryGiftMan" to Script { deliveryman(it) },
        "LilycoveCity_Harbor_EventScript_FerryAttendant" to Script { ferry(it) },
    )
  }

  private suspend fun deliveryman(ctx: ScriptContext) {
    val id = ctx.playerEntityId
    val given = gifts.deliver(ctx.session, id)
    if (given.isEmpty()) return ctx.say(GiftText.TryUsingItAtLilycovePort).let {}
    ctx.say(GiftText.TheresATicketForYou)
    rewards.tell(ctx.session, "${ctx.playerName} received ${given.joinToString(", ")}!")
    if (given.any { it.contains("TICKET") || it.contains("SEA MAP") }) ctx.say(GiftText.TryUsingItAtLilycovePort)
  }

  /**
   * LilycoveCity_Harbor_EventScript_FerryAttendant for ticket holders: the island destinations
   * (GoToSouthernIsland, GoToNavelRock, GoToBirthIsland, GoToFarawayIsland). Without a ticket the
   * generated (story lane) script runs.
   */
  private suspend fun ferry(ctx: ScriptContext) {
    val id = ctx.playerEntityId
    val owned = Ticket.entries.filter { gifts.hasTicket(id, it) }
    if (owned.isEmpty()) {
      return de.fiereu.openmmo.server.game.script.generated.GeneratedScripts.byLabel["LilycoveCity_Harbor_EventScript_FerryAttendant"]?.run(ctx)
          ?: ctx.say(HarborText.NoTicket).let {}
    }
    ctx.say(HarborText.MayISeeYourTicket)
    ctx.say(HarborText.FlashTicketWhereTo)
    val dests = owned.map { DESTINATIONS.getValue(it) }
    val i = menus.choose(ctx.session, id, "WHERE TO?", dests.map { it.first } + "EXIT")
    val d = dests.getOrNull(i) ?: return ctx.say(HarborText.SailAnotherTime).let {}
    ctx.say(HarborText.PleaseBoard)
    ctx.dialog.close(ctx.session, ctx.state)
    val w = d.second
    ctx.warp(1, w[0], w[1], w[2], w[3], Direction.UP)
  }

  companion object {
    /** The decomp's warp targets of LilycoveCity_Harbor (bank, map, x, y). */
    val DESTINATIONS =
        mapOf(
            Ticket.EON to ("SOUTHERN ISLAND" to listOf(26, 9, 13, 22)),
            Ticket.MYSTIC to ("NAVEL ROCK" to listOf(26, 67, 8, 4)),
            Ticket.AURORA to ("BIRTH ISLAND" to listOf(26, 59, 8, 4)),
            Ticket.OLD_SEA_MAP to ("FARAWAY ISLAND" to listOf(26, 56, 13, 38)),
        )
  }
}

/** The Mystery Gift specials. */
@Singleton
class MysteryGiftSpecials @Inject constructor(private val gifts: MysteryGiftService) : SpecialTable() {
  override val table: Map<String, SpecialFn> =
      mapOf(
          "ValidateSavedWonderCard" to { ctx -> if (gifts.hasPendingCard(ctx.playerEntityId)) 1 else 0 },
          // The Eon Ticket comes as an admin-defined gift here, never from the save's flag.
          "ShouldDistributeEonTicket" to { _ -> 0 },
      )
}

/**
 * Admin: `/mysterygift add item <itemId> [qty] <title>`, `add mon <dex> <level> <title>`,
 * `add ticket <eon|mystic|aurora|oldseamap> <title>`, `list`, `on|off <id>`. A trailing
 * `region=kanto|hoenn` limits the gift to one game.
 */
@Singleton
class MysteryGiftCommand @Inject constructor(private val gifts: MysteryGiftService) : ChatCommand {
  override val name = "mysterygift"
  override val usage = "/mysterygift add item|mon|ticket ... | list | on|off <id>"
  override val description = "admin: define MYSTERY GIFTs (WONDER CARDs)"
  override val permission = CharacterPermissions.ADMIN

  override suspend fun run(ctx: CommandContext) {
    val a = ctx.args.toMutableList()
    var region = 0
    a.removeAll { arg ->
      when (arg.lowercase()) {
        "region=kanto" -> true.also { region = 1 }
        "region=hoenn" -> true.also { region = 2 }
        else -> false
      }
    }
    when (a.getOrNull(0)?.lowercase()) {
      "list" -> gifts.list().forEach { ctx.reply("#${it.id} [${if (it.active) "on" else "off"}] ${it.kind} ${it.title} ${it.payload}") }
      "on", "off" -> {
        val id = a.getOrNull(1)?.toIntOrNull() ?: return ctx.reply("Usage: $usage")
        gifts.setActive(id, a[0].lowercase() == "on")
        ctx.reply("Gift #$id is ${a[0].lowercase()}.")
      }
      "add" -> {
        val kind = a.getOrNull(1)?.lowercase() ?: return ctx.reply("Usage: $usage")
        val (payload, rest) =
            when (kind) {
              "item" -> {
                val item = a.getOrNull(2)?.toIntOrNull() ?: return ctx.reply("Usage: /mysterygift add item <itemId> [qty] <title>")
                val qty = a.getOrNull(3)?.toIntOrNull()
                jsonOf("item" to item, "qty" to (qty ?: 1)) to a.drop(if (qty != null) 4 else 3)
              }
              "mon" -> {
                val dex = a.getOrNull(2)?.toIntOrNull() ?: return ctx.reply("Usage: /mysterygift add mon <dex> <level> <title>")
                val lvl = a.getOrNull(3)?.toIntOrNull()?.coerceIn(1, 100) ?: return ctx.reply("Usage: /mysterygift add mon <dex> <level> <title>")
                jsonOf("species" to dex, "level" to lvl) to a.drop(4)
              }
              "ticket" -> {
                val t = Ticket.entries.firstOrNull { it.id == a.getOrNull(2)?.lowercase() }
                    ?: return ctx.reply("Tickets: ${Ticket.entries.joinToString { it.id }}")
                jsonOf("ticket" to t.id) to a.drop(3)
              }
              else -> return ctx.reply("Usage: $usage")
            }
        val title = rest.joinToString(" ").ifBlank { "MYSTERY GIFT" }.take(64)
        val id = gifts.addGift(MysteryGiftRow(0, kind, region, title, "", payload, true, ctx.character.info.name))
        ctx.reply("Defined gift #$id: $kind $title.")
      }
      else -> ctx.reply("Usage: $usage")
    }
  }
}
