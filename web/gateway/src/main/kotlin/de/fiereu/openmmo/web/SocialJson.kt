package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.SocialClient
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.common.enums.GuildRank
import de.fiereu.openmmo.net.game.packets.social.CommunityBody
import de.fiereu.openmmo.net.game.packets.social.MapRef
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The `social` section and the market extras (PROTOCOL.md "r3-social"). Pure, so testable. */
class SocialJson(private val names: Names) {
  fun location(ref: MapRef): JsonElement =
      if (!ref.known) JsonNull
      else
          buildJsonObject {
            put("region", ref.regionId)
            put("bank", ref.bankId)
            put("map", ref.mapId)
            put("name", names.mapName(MapKey(ref.regionId, ref.bankId, ref.mapId)))
          }

  fun rankName(rank: Int): String =
      when (GuildRank.entries.getOrNull(rank)) {
        GuildRank.GRUNT -> "Recruit"
        GuildRank.MEMBER -> "Member"
        GuildRank.OFFICER -> "Officer"
        GuildRank.COMMANDER -> "Commander"
        GuildRank.EXECUTIVE -> "Executive"
        GuildRank.BOSS -> "Leader"
        null -> "?"
      }

  fun social(s: SocialClient.State): JsonElement = buildJsonObject {
    putJsonArray("friends") {
      s.friends.friends.forEach {
        addJsonObject {
          put("name", it.name)
          put("online", it.online)
          put("location", location(it.location))
          put("since", it.sinceEpoch)
          put("mutual", it.mutual)
        }
      }
    }
    putJsonArray("blocked") { s.friends.blocked.forEach { add(it) } }
    // Integration: incoming friend requests waiting for friends.accept / friends.decline.
    putJsonArray("requests") { s.friends.requests.forEach { add(it) } }
    put("profile", s.profile?.let(::profile) ?: JsonNull)
    put("profileSeq", s.profileSeq)
    put("guild", guild(s.guild))
    put(
        "invite",
        s.invite?.let {
          buildJsonObject {
            put("from", it.from)
            put("guild", it.guildName)
            put("tag", it.guildTag)
            put("expiresAt", it.expiresAtMs)
          }
        } ?: JsonNull)
    put(
        "tradeHistory",
        s.tradeHistory?.let { rows ->
          buildJsonArray {
            rows.forEach {
              addJsonObject {
                put("at", it.epoch)
                put("partner", it.partner)
                put("gave", it.gave)
                put("got", it.got)
              }
            }
          }
        } ?: JsonNull)
  }

  private fun profile(p: CommunityBody.Profile) = buildJsonObject {
    put("name", p.name)
    put("online", p.online)
    put("location", location(p.location))
    put("guild", p.guildName.ifEmpty { null })
    put("tag", p.guildTag.ifEmpty { null })
    putJsonArray("party") {
      p.party.forEach {
        addJsonObject {
          put("dex", it.dexId)
          put("name", names.species(it.dexId))
          put("level", it.level)
          put("shiny", it.shiny)
        }
      }
    }
    put("created", p.createdEpoch)
    put("friend", p.friend)
    put("blocked", p.blocked)
    put("trades", p.trades)
  }

  fun guild(g: CommunityBody.Guild): JsonElement = buildJsonObject {
    put("inGuild", g.inGuild)
    if (!g.inGuild) return@buildJsonObject
    put("name", g.name)
    put("tag", g.tag)
    put("motd", g.motd)
    put("myRank", g.myRank)
    put("myRankName", rankName(g.myRank))
    putJsonArray("members") {
      g.members.forEach {
        addJsonObject {
          put("name", it.name)
          put("rank", it.rank)
          put("rankName", rankName(it.rank))
          put("online", it.online)
          put("location", location(it.location))
          put("joined", it.joinedEpoch)
        }
      }
    }
    putJsonArray("log") {
      g.log.forEach {
        addJsonObject {
          put("at", it.epoch)
          put("text", it.text)
        }
      }
    }
  }

  fun priceHistory(h: CommunityBody.PriceHistory): JsonElement = buildJsonObject {
    put("kind", if (h.kind.toInt() == 0) "mon" else "item")
    put("id", h.refId)
    put("name", if (h.kind.toInt() == 0) names.species(h.refId) else names.item(h.refId))
    putJsonArray("days") {
      h.days.forEach {
        addJsonObject {
          put("day", it.dayEpoch)
          put("min", it.min)
          put("max", it.max)
          put("avg", it.avg)
          put("volume", it.volume)
        }
      }
    }
    putJsonArray("recent") {
      h.recent.forEach {
        addJsonObject {
          put("at", it.epoch)
          put("price", it.unitPrice)
          put("qty", it.quantity)
        }
      }
    }
  }

  fun sales(rows: List<de.fiereu.openmmo.net.game.packets.social.SaleRecord>): JsonElement =
      buildJsonArray {
        rows.forEach {
          addJsonObject {
            put("id", it.listingId.toString())
            val mon = it.kind.toInt() == 0
            put("kind", if (mon) "mon" else "item")
            put("dex", if (mon) it.refId else null)
            put("item", if (mon) null else it.refId)
            put("name", if (mon) names.species(it.refId) else names.item(it.refId))
            put("qty", it.quantity)
            put("price", it.price)
            put("fee", it.fee)
            put("buyer", it.buyerName)
            put("at", it.epoch)
          }
        }
      }

  fun info(i: CommunityBody.MarketInfo): JsonElement = buildJsonObject {
    put("feePermille", i.feePermille)
    put("minFee", i.minFee)
    put("maxActive", i.maxActive)
    put("active", i.active)
    put("listingHours", i.listingHours)
  }

  private fun kotlinx.serialization.json.JsonArrayBuilder.add(s: String) =
      add(kotlinx.serialization.json.JsonPrimitive(s))
}
