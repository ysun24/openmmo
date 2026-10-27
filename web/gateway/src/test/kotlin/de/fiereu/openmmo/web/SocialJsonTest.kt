package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.SocialClient
import de.fiereu.openmmo.client.world.ChatLine
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.client.world.WorldState
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.net.game.packets.social.CommunityBody
import de.fiereu.openmmo.net.game.packets.social.FriendView
import de.fiereu.openmmo.net.game.packets.social.GuildLogView
import de.fiereu.openmmo.net.game.packets.social.GuildMemberView
import de.fiereu.openmmo.net.game.packets.social.MapRef
import de.fiereu.openmmo.net.game.packets.social.PricePoint
import de.fiereu.openmmo.net.game.packets.social.ProfileMon
import de.fiereu.openmmo.net.game.packets.social.RecentSale
import de.fiereu.openmmo.net.game.packets.social.SaleRecord
import de.fiereu.openmmo.net.game.packets.social.TradeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

private object SocialNames : Names {
  override fun species(dex: Int) = if (dex == 25) "PIKACHU" else "#$dex"

  override fun move(id: Int) = "M$id"

  override fun maxPp(id: Int) = 0

  override fun item(id: Int) = if (id == 5017) "Potion" else "item $id"

  override fun mapName(key: MapKey) = if (key == MapKey(1, 50, 9)) "LittlerootTown" else null

  override fun maxHp(mon: Pokemon) = 1
}

class SocialJsonTest {
  private val json = SocialJson(SocialNames)
  private val here = MapRef(1, 50, 9)

  private fun parse(s: String) = Json.parseToJsonElement(s)

  @Test
  fun `social section`() {
    val state =
        SocialClient.State(
            friends =
                CommunityBody.Friends(
                    listOf(
                        FriendView("Gary", true, here, 10, true),
                        FriendView("Misty", false, MapRef.UNKNOWN, 11, false)),
                    listOf("Spammer")),
            profile =
                CommunityBody.Profile(
                    "Gary",
                    true,
                    here,
                    "Rockets",
                    "RKT",
                    listOf(ProfileMon(25, 12, true)),
                    5,
                    true,
                    false,
                    2),
            profileSeq = 3,
            guild =
                CommunityBody.Guild(
                    true,
                    "Rockets",
                    "RKT",
                    "hi",
                    5,
                    listOf(
                        GuildMemberView("Ash", 5, true, here, 1),
                        GuildMemberView("Gary", 0, false, MapRef.UNKNOWN, 2)),
                    listOf(GuildLogView(9, "Ash founded the guild."))),
            invite = SocialClient.Invite("Ash", "Rockets", "RKT", 1234),
            tradeHistory = listOf(TradeRecord(7, "Gary", "PIKACHU Lv5", "500 money")),
        )
    assertEquals(
        parse(
            """{"friends":[{"name":"Gary","online":true,
                 "location":{"region":1,"bank":50,"map":9,"name":"LittlerootTown"},"since":10,"mutual":true},
               {"name":"Misty","online":false,"location":null,"since":11,"mutual":false}],
               "blocked":["Spammer"],
               "profile":{"name":"Gary","online":true,
                 "location":{"region":1,"bank":50,"map":9,"name":"LittlerootTown"},"guild":"Rockets","tag":"RKT",
                 "party":[{"dex":25,"name":"PIKACHU","level":12,"shiny":true}],"created":5,"friend":true,
                 "blocked":false,"trades":2},
               "profileSeq":3,
               "guild":{"inGuild":true,"name":"Rockets","tag":"RKT","motd":"hi","myRank":5,"myRankName":"Leader",
                 "members":[{"name":"Ash","rank":5,"rankName":"Leader","online":true,
                   "location":{"region":1,"bank":50,"map":9,"name":"LittlerootTown"},"joined":1},
                   {"name":"Gary","rank":0,"rankName":"Recruit","online":false,"location":null,"joined":2}],
                 "log":[{"at":9,"text":"Ash founded the guild."}]},
               "invite":{"from":"Ash","guild":"Rockets","tag":"RKT","expiresAt":1234},
               "tradeHistory":[{"at":7,"partner":"Gary","gave":"PIKACHU Lv5","got":"500 money"}]}"""),
        json.social(state))
    assertEquals(
        parse(
            """{"friends":[],"blocked":[],"profile":null,"profileSeq":0,"guild":{"inGuild":false},
               "invite":null,"tradeHistory":null}"""),
        json.social(SocialClient.State()))
  }

  @Test
  fun `market extras`() {
    assertEquals(
        parse(
            """{"kind":"mon","id":25,"name":"PIKACHU","days":[{"day":20000,"min":100,"max":900,"avg":450,
               "volume":4}],"recent":[{"at":1,"price":450,"qty":1}]}"""),
        json.priceHistory(
            CommunityBody.PriceHistory(
                0, 25, listOf(PricePoint(20000, 100, 900, 450, 4)), listOf(RecentSale(1, 450, 1)))))
    assertEquals(
        parse(
            """[{"id":"9","kind":"item","dex":null,"item":5017,"name":"Potion","qty":3,"price":300,
               "fee":6,"buyer":"Gary","at":77}]"""),
        json.sales(listOf(SaleRecord(9, 1, 5017, 3, 300, 6, "Gary", 77))))
    assertEquals(
        parse("""{"feePermille":20,"minFee":1,"maxActive":30,"active":2,"listingHours":168}"""),
        json.info(CommunityBody.MarketInfo(20, 1, 30, 2, 168)))
  }

  @Test
  fun `chat lines carry their channel and whisper target`() {
    val w = WorldState()
    w.addChat(ChatLine("*", "Welcome"))
    w.addChat(ChatLine("Ash", "psst", "WHISPER", "Gary"))
    w.addChat(ChatLine("Ash", "hi", "GLOBAL"))
    assertEquals(
        parse(
            """[{"sender":"*","text":"Welcome"},
               {"sender":"Ash","text":"psst","channel":"whisper","to":"Gary"},
               {"sender":"Ash","text":"hi","channel":"global"}]"""),
        StateJson(SocialNames).chat(w.snapshot()))
  }
}
