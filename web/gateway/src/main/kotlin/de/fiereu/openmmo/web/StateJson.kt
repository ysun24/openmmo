package de.fiereu.openmmo.web

import de.fiereu.openmmo.client.net.BattleFx
import de.fiereu.openmmo.client.net.BattleMon
import de.fiereu.openmmo.client.net.BattleNames
import de.fiereu.openmmo.client.net.BattleState
import de.fiereu.openmmo.client.net.MarketClient
import de.fiereu.openmmo.client.net.MarketEntry
import de.fiereu.openmmo.client.net.SocialClient
import de.fiereu.openmmo.client.world.Entity
import de.fiereu.openmmo.client.world.MapKey
import de.fiereu.openmmo.client.world.OverworldState
import de.fiereu.openmmo.client.world.WorldState
import de.fiereu.openmmo.common.MonStatus
import de.fiereu.openmmo.common.Pokemon
import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.net.game.packets.battle.DuelRules
import de.fiereu.openmmo.net.game.packets.field.FieldInfoPacket
import de.fiereu.openmmo.net.game.packets.field.TravelMode
import de.fiereu.openmmo.net.game.packets.progress.ProgressStatePacket
import de.fiereu.openmmo.net.game.packets.trade.TradeOfferView
import de.fiereu.openmmo.net.game.packets.trade.TradePhase
import de.fiereu.openmmo.net.game.packets.trade.TradeStage
import de.fiereu.openmmo.net.game.packets.trade.TradeStatePacket
import de.fiereu.openmmo.progression.Gen3Data
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One read of every client snapshot, taken together so a state message is consistent. */
data class Sources(
    val world: WorldState.Snapshot,
    val battle: BattleState,
    val trade: TradeStatePacket,
    val party: List<Pokemon>,
    val pc: List<Pokemon> = emptyList(),
    /** The bag as the trade client saw it, used when the overworld has none yet. */
    val tradeBag: Map<Int, Int>,
    val tradeMoney: Int?,
    val market: MarketClient.State,
    val ackSeq: Int,
    /** Entities that changed tile in the last step duration. */
    val moving: Set<Long>,
    /** r3-items progression (pokedex, badges, day care…), null before the first push. */
    val progress: ProgressStatePacket? = null,
    /** Play time including the seconds since the last push. */
    val playSeconds: Int = 0,
    /** r3-social: friends, profile, guild, invite, trade history. */
    val social: SocialClient.State = SocialClient.State(),
    /** When (epoch ms) a pending trade request lapses, or 0. */
    val tradeExpiresAt: Long = 0,
    /** r5-contests: the last document per kind. */
    val contests: de.fiereu.openmmo.client.net.ContestClient.State =
        de.fiereu.openmmo.client.net.ContestClient.State(),
    /** r5-minigames: topic -> the server's last JSON body for it (MinigameClient). */
    val minigames: Map<String, String> = emptyMap(),
)

/** The `state` sections of PROTOCOL.md section 3, built from [Sources]. Pure, so it is testable. */
class StateJson(private val names: Names) {
  private val progressJson = ProgressJson(names)
  private val socialJson = SocialJson(names)

  /** Every section, keyed by its name. A section with nothing to show is [JsonNull]. */
  fun build(s: Sources): Map<String, JsonElement> =
      linkedMapOf(
          "world" to world(s),
          "chat" to chat(s.world),
          "ui" to ui(s),
          "party" to party(s.party, s.progress),
          "pc" to pc(s),
          "battle" to battle(s.battle),
          "invite" to invite(s.battle),
          // r3-battle: the evolution scene, which can also play outside a battle.
          "evolution" to evolution(s.battle),
          "trade" to trade(s.trade, s.tradeExpiresAt),
          "market" to market(s),
          "social" to socialJson.social(s.social),
          // r5-contests: {seq, docs: {kind: document}} (PROTOCOL.md "r5-contests")
          "contests" to contests(s.contests),
          // r5-minigames: PROTOCOL.md section "r5-minigames".
          "minigames" to MinigameJson.section(s.minigames),
      ) + progressJson.sections(s)

  /** r5-contests: the documents are the server's JSON, passed through. */
  fun contests(c: de.fiereu.openmmo.client.net.ContestClient.State): JsonElement = buildJsonObject {
    put("seq", c.seq)
    putJsonObject("docs") {
      for ((k, json) in c.docs) {
        val doc =
            try {
              kotlinx.serialization.json.Json.parseToJsonElement(json)
            } catch (e: Exception) {
              JsonNull
            }
        put(k, doc)
      }
    }
  }

  fun world(s: Sources): JsonElement {
    val w = s.world
    return buildJsonObject {
      put("map", w.map?.let { mapJson(it, w.mapWidth, w.mapHeight) } ?: JsonNull)
      put("self", w.selfId?.toString())
      // r3-present: own player sprite (male / female sheet of the region)
      put("female", w.characterGender == 1)
      put("inWorld", w.inWorld)
      putJsonArray("entities") {
        w.entities.values
            .filter { it.visible }
            .sortedBy { it.id }
            .forEach { add(entity(it, it.id in s.moving)) }
      }
      put("ackSeq", s.ackSeq)
      w.field?.let { put("field", field(it, w.map)) }
      w.secretBase?.let { put("secretBase", secretBase(it)) }
      // r5-frontier: the battle facility screen, as the server sent it.
      w.frontier?.let { f ->
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(f) }
            .getOrNull()
            ?.let { put("frontier", it) }
      }
      // r4: the Pokénav Match Call list, when asked for (`seq` grows per answer).
      w.matchCall?.let { mc ->
        putJsonObject("matchCall") {
          put("seq", w.matchCallSeq)
          putJsonArray("trainers") {
            mc.entries.forEach { e ->
              add(
                  buildJsonObject {
                    put("id", e.trainerId)
                    put("name", e.name)
                    put("route", e.route)
                    put("ready", e.ready)
                  })
            }
          }
        }
      }
    }
  }

  /** r4: world.secretBase (PROTOCOL.md section 11). */
  fun secretBase(p: de.fiereu.openmmo.net.game.packets.field.SecretBaseStatePacket) =
      buildJsonObject {
        put(
            "inside",
            when (p.inside) {
              1 -> "own"
              2 -> "visiting"
              else -> "none"
            })
        if (p.owner.isNotEmpty()) put("owner", p.owner)
        if (p.baseId != 0) put("id", p.baseId)
        if (p.myBaseId != 0) put("mine", p.myBaseId)
        putJsonArray("decorations") {
          p.decorations.forEach { d ->
            add(
                buildJsonObject {
                  put("id", d.decorId)
                  put("x", d.x)
                  put("y", d.y)
                })
          }
        }
        putJsonArray("open") {
          p.openSpots.forEach { (x, y) ->
            add(
                buildJsonObject {
                  put("x", x)
                  put("y", y)
                })
          }
        }
        if (p.uiKind != 0)
            putJsonObject("ui") {
              put("seq", p.uiSeq)
              put("kind", if (p.uiKind == 1) "pc" else "none")
            }
      }

  private fun mapJson(key: MapKey, width: Int, height: Int) = buildJsonObject {
    put("region", key.regionId)
    put("bank", key.bankId)
    put("map", key.mapId)
    put("name", names.mapName(key))
    put("width", width)
    put("height", height)
  }

  fun entity(e: Entity, moving: Boolean) = buildJsonObject {
    put("id", e.id.toString())
    put("kind", if (e.isPlayer) "player" else "npc")
    put("name", e.name)
    put("gfx", if (e.isPlayer) JsonNull else JsonPrimitive(e.graphicsId))
    put("x", e.x)
    put("y", e.y)
    put("facing", dir(e.facing))
    put("moving", moving)
    putJsonObject("map") {
      put("region", e.map.regionId)
      put("bank", e.map.bankId)
      put("map", e.map.mapId)
    }
    // r3-field, only when not the default: travel mode, running, ledge-hop / trainer "!" counters.
    if (e.travel != TravelMode.WALK) put("mode", e.travel.name.lowercase())
    if (e.running) put("run", true)
    if (e.jumpSeq > 0) put("jump", e.jumpSeq)
    if (e.exclaimSeq > 0) put("exclaim", e.exclaimSeq)
    // r4: the last emote was "!!" (the VS Seeker's rematch answer)
    if (e.exclaimDouble) put("exclaimKind", "double")
    // Integration: another player's gender picks the female player sprite.
    if (e.female) put("female", true)
    // r4: the Acro Bike pose ("wheelie", "hop", "side_jump", "turn_jump").
    if (e.acro != de.fiereu.openmmo.net.game.packets.field.AcroTrick.NONE)
        put("acro", e.acro.name.lowercase())
  }

  /** The player's own field state (r3-field): world.field. */
  fun field(f: FieldInfoPacket, map: MapKey?) = buildJsonObject {
    put("dark", f.dark)
    put("flash", f.flash)
    put("canRun", f.flags and FieldInfoPacket.FLAG_CAN_RUN != 0)
    put("canBike", f.flags and FieldInfoPacket.FLAG_CAN_BIKE != 0)
    put("hasBike", f.flags and FieldInfoPacket.FLAG_HAS_BIKE != 0)
    put("strength", f.flags and FieldInfoPacket.FLAG_STRENGTH != 0)
    put("canEscape", f.flags and FieldInfoPacket.FLAG_CAN_ESCAPE != 0)
    putJsonArray("moves") { f.moves.forEach { add(it.name.lowercase().replace("_", "")) } }
    putJsonArray("fly") {
      val region = map?.regionId ?: 0
      f.fly.forEach {
        addJsonObject {
          put("bank", it.bank)
          put("map", it.map)
          put("name", names.mapName(MapKey(region, it.bank, it.map)))
        }
      }
    }
  }

  fun chat(w: WorldState.Snapshot): JsonElement = buildJsonArray {
    w.chat.takeLast(CHAT_LINES).forEach {
      addJsonObject {
        put("sender", it.sender)
        put("text", it.message)
        // r3-social: local|global|trade|guild|whisper|system, "" for server notices.
        if (it.channel.isNotEmpty()) put("channel", it.channel.lowercase())
        if (it.to.isNotEmpty()) put("to", it.to)
      }
    }
  }

  fun ui(s: Sources): JsonElement {
    val v: OverworldState.View? = s.world.overworld
    return buildJsonObject {
      val d = v?.dialog
      put(
          "dialog",
          if (d == null) JsonNull
          else
              buildJsonObject {
                put("speaker", speakerOf(s.world, d.entityId))
                putJsonArray("lines") { d.pages.getOrNull(d.page).orEmpty().forEach { add(it) } }
                put("lastPage", d.onLastPage)
                putJsonArray("choices") { if (d.onLastPage) d.choices.forEach { add(it) } }
                put("cursor", d.cursor)
              })
      val shop = v?.shop
      put(
          "shop",
          if (shop == null) JsonNull
          else
              buildJsonObject {
                putJsonArray("offers") {
                  shop.offers.forEach {
                    addJsonObject {
                      put("item", it.itemId)
                      put("name", it.name)
                      put("price", it.price)
                      put("desc", names.itemInfo(it.itemId)?.description.orEmpty())
                    }
                  }
                }
                // What the clerk buys back: the bag, at half the item's price (never for 0).
                val bag = v.bag.takeIf { it.isNotEmpty() } ?: s.tradeBag
                putJsonArray("sell") {
                  bag.toSortedMap().forEach { (id, qty) ->
                    val price = (names.itemInfo(id)?.price ?: 0) / 2
                    if (qty > 0 && price > 0)
                        addJsonObject {
                          put("item", id)
                          put("name", names.item(id))
                          put("qty", qty)
                          put("price", price)
                        }
                  }
                }
                put("cursor", shop.cursor)
                put("status", shop.status)
              })
      put("money", v?.money ?: s.tradeMoney ?: s.market.money)
      val bag = v?.bag?.takeIf { it.isNotEmpty() } ?: s.tradeBag
      put("bag", bagJson(bag, s.party))
      put("registered", s.progress?.registeredItem?.toInt()?.takeIf { it != 0 })
      put("locked", v != null && (v.transitioning || v.scriptLocked || v.inScript))
      // r3-field: a UI the server asks to open (the PC tile, the FLY map). `seq` grows per request.
      val f = s.world.field
      if (f != null && f.uiKind != 0)
          putJsonObject("open") {
            put("seq", f.uiSeq)
            put(
                "kind",
                when (f.uiKind) {
                  1 -> "pc"
                  2 -> "pcItems"
                  3 -> "fly"
                  else -> "unknown"
                })
          }
      // r5-scripts: the script interpreter's menu and effects (PROTOCOL.md "r5-scripts").
      putJsonObject("script") {
        val menu = v?.scriptMenu
        put(
            "menu",
            if (menu == null) JsonNull
            else
                buildJsonObject {
                  put("seq", menu.seq)
                  put("kind", menu.kind)
                  put("args", Json.parseToJsonElement(menu.json))
                })
        putJsonArray("fx") {
          v?.scriptFx.orEmpty().forEach {
            addJsonObject {
              put("n", it.n)
              put("kind", it.kind)
              put("args", Json.parseToJsonElement(it.json))
            }
          }
        }
        putJsonArray("metatiles") {
          v?.metatiles.orEmpty().forEach { (xy, t) ->
            addJsonObject {
              put("x", xy.first)
              put("y", xy.second)
              put("tile", t.first)
              put("impassable", t.second)
            }
          }
        }
      }
      val n = v?.notice
      put(
          "notice",
          if (n == null) JsonNull
          else
              buildJsonObject {
                put("seq", n.seq)
                put("text", n.text)
                // presence (join/leave: chat only), system, reward, error or info.
                put("kind", n.kind)
              })
    }
  }

  /** Who is talking: a named character's npc, or nobody (signs, generic npcs). */
  private fun speakerOf(w: WorldState.Snapshot, entityId: Long): String? {
    if (entityId <= 0) return null
    val e = w.entities[entityId] ?: return null
    if (e.isPlayer) return null
    return names.speaker(e.map.regionId, e.graphicsId)
  }

  fun bagJson(bag: Map<Int, Int>, party: List<Pokemon> = emptyList()): JsonArray = buildJsonArray {
    bag.toSortedMap().forEach { (id, qty) ->
      if (qty > 0)
          addJsonObject {
            put("item", id)
            put("name", names.item(id))
            put("qty", qty)
            val info = names.itemInfo(id)
            if (info != null) {
              put("pocket", info.pocket)
              put("desc", info.description)
              put("use", info.use)
              if (info.register) put("register", true)
              val tm = info.tmIndex
              if (tm != null) {
                put("teaches", info.teaches?.let(names::move))
                // Gen 3's party menu marks who is ABLE to learn it.
                putJsonArray("able") {
                  party
                      .filter { m ->
                        !m.isEgg &&
                            Gen3Data.canLearnTmhm(m.dexId, tm) &&
                            m.moves.none { it.id.toInt() == info.teaches }
                      }
                      .forEach { add(it.id.toString()) }
                }
              }
            }
          }
    }
  }

  fun party(party: List<Pokemon>, progress: ProgressStatePacket? = null): JsonElement =
      buildJsonArray {
        party
            .sortedBy { it.containerSlot }
            .map { ProgressJson.withExtras(it, progress) }
            .forEachIndexed { i, mon ->
              addJsonObject {
                put("slot", i)
                put("dex", mon.dexId)
                val species = names.species(mon.dexId)
                put("name", if (mon.isEgg) "EGG" else species)
                put(
                    "nick",
                    mon.nickname.takeIf {
                      it.isNotEmpty() && !it.equals(species, ignoreCase = true)
                    })
                put("level", mon.level.toInt())
                put("hp", mon.hp.toInt())
                put("maxHp", names.maxHp(mon))
                put("id", mon.id.toString())
                names.summary(mon)?.let { sum ->
                  putJsonObject("stats") { sum.stats.forEach { (k, v) -> put(k, v) } }
                  put("nature", sum.nature)
                  put("ability", sum.ability)
                  putJsonArray("types") { sum.types.forEach { add(it) } }
                }
                putJsonArray("moves") {
                  mon.moves.forEachIndexed { slot, it ->
                    if (it.id.toInt() == 0) return@forEachIndexed
                    addJsonObject {
                      put("slot", slot)
                      put("id", it.id.toInt())
                      put("name", names.move(it.id.toInt()))
                      put("pp", it.pp.toInt())
                      put("maxPp", MonStatus.maxPp(names.maxPp(it.id.toInt()), mon.ppBonuses, slot))
                      put("ppUps", (mon.ppBonuses shr (slot * 2)) and 3)
                    }
                  }
                }
                put("status", MonStatus.label(mon.status))
                if (mon.isEgg) put("egg", true)
                put("ot", mon.ot)
                put("xp", mon.xp)
                put("shiny", mon.isShiny)
              }
            }
      }

  /** The PC boxes, and whether the player stands where the PC can be used. */
  fun pc(s: Sources): JsonElement = buildJsonObject {
    put("here", s.world.map?.let(names::hasPc) ?: false)
    putJsonArray("mons") {
      s.pc
          .sortedBy { it.containerSlot }
          .forEach { mon ->
            addJsonObject {
              put("slot", mon.containerSlot.toInt())
              put("id", mon.id.toString())
              put("dex", mon.dexId)
              put("name", names.species(mon.dexId))
              put("level", mon.level.toInt())
              put("hp", mon.hp.toInt())
              put("maxHp", names.maxHp(mon))
            }
          }
    }
  }

  fun battle(b: BattleState): JsonElement {
    if (!b.active) return JsonNull
    return buildJsonObject {
      put("wild", b.wild)
      put("opponentName", b.opponentName)
      put("phase", b.phase.name)
      put("turn", b.turn)
      put("activeSlot", b.activeSlot)
      // r3-battle: format, positions, background, music, weather, trainer sprite, bag.
      put("format", b.format)
      putJsonArray("actives") { b.actives.forEach { add(it) } }
      put("choosingPos", b.choosingPos?.let { JsonPrimitive(it) } ?: JsonNull)
      putJsonArray("owedSwitches") { b.owedSwitches.forEach { add(it) } }
      putJsonArray("mine") {
        b.actives.indices.forEach { pos ->
          add(b.mine(pos)?.let { monJson(it, withMoves = true) } ?: JsonNull)
        }
      }
      putJsonArray("foes") {
        b.foeActives.indices.forEach { pos ->
          add(
              b.foeAt(pos)?.takeIf { it.revealed }?.let { monJson(it, withMoves = false) }
                  ?: JsonNull)
        }
      }
      put("terrain", b.terrain)
      put("music", b.music)
      put("weather", b.weather)
      put(
          "trainer",
          if (b.trainerPic == null) JsonNull
          else
              buildJsonObject {
                put("pic", b.trainerPic)
                put("class", b.trainerClass)
              })
      putJsonArray("bag") {
        b.bag.forEach { i ->
          addJsonObject {
            put("id", i.id)
            put("name", i.name)
            put("count", i.count)
            put("target", i.target)
            put("pocket", i.pocket)
            if (i.perMove) put("perMove", true)
          }
        }
      }
      put("me", b.me?.let { monJson(it, withMoves = true) } ?: JsonNull)
      put("foe", b.foe?.takeIf { it.revealed }?.let { monJson(it, withMoves = false) } ?: JsonNull)
      putJsonArray("party") { b.party.forEach { add(monJson(it, withMoves = false)) } }
      putJsonArray("log") { b.log.takeLast(BATTLE_LOG_LINES).forEach { add(it) } }
      put("result", b.result)
      put("rules", b.rules?.let(::rulesJson) ?: JsonNull)
      put(
          "timer",
          b.timer?.let { t ->
            buildJsonObject {
              put("id", t.id)
              put("seconds", t.seconds)
              put("mustAct", t.mustAct)
              put("timeouts", t.timeouts)
              put("rivalTimeouts", t.rivalTimeouts)
              put("autoPicked", t.autoPicked)
              put("maxTimeouts", DuelRules.MAX_TIMEOUTS)
            }
          } ?: JsonNull)
      putJsonArray("fx") { b.fx.takeLast(BATTLE_FX).forEach { add(fxJson(it)) } }
      val learn = b.pendingLearn
      put(
          "learn",
          if (learn == null) JsonNull
          else
              buildJsonObject {
                put("move", learn.second.firstOrNull()?.let(names::move))
                val mon = b.party.firstOrNull { it.entityId == learn.first }
                putJsonArray("current") { mon?.moves?.forEach { add(names.move(it.id)) } }
                put("mon", mon?.let { names.species(it.species) })
                learn.second.firstOrNull()?.let { id -> put("offer", moveInfo(id, null, null)) }
                putJsonArray("moves") {
                  mon?.moves?.forEach { add(moveInfo(it.id, it.pp, it.maxPp)) }
                }
              })
    }
  }

  /** r3-battle: the evolution scene, or null. */
  fun evolution(b: BattleState): JsonElement =
      b.evolution?.let { e ->
        buildJsonObject {
          put("id", e.id.toString())
          put("seq", e.seq)
          put("phase", e.phase)
          put("fromDex", e.fromDex)
          put("toDex", e.toDex)
          put("name", e.name)
          put("toName", e.toName)
          putJsonArray("learned") { e.learned.forEach { add(it) } }
        }
      } ?: JsonNull

  private fun monJson(m: BattleMon, withMoves: Boolean) = buildJsonObject {
    put("id", m.entityId.toString())
    put("slot", m.slot)
    put("status", m.status)
    put("confused", m.confused)
    m.ability?.let { put("ability", it) }
    m.item?.let { put("item", it) }
    if (m.substitute) put("substitute", true)
    put("dex", m.species)
    put("name", names.species(m.species))
    put("level", m.level)
    put("hp", m.hp.coerceAtLeast(0))
    put("maxHp", m.maxHp)
    put("shiny", m.shiny)
    if (withMoves) {
      BattleNames.expFraction(m.species, m.level, m.xp)?.let {
        put("exp", Math.round(it * 1000) / 1000.0)
      }
      putJsonArray("moves") {
        m.moves.forEach {
          addJsonObject {
            put("name", names.move(it.id))
            put("pp", it.pp)
            put("maxPp", it.maxPp)
            put("type", BattleNames.moveType(it.id))
            put("power", BattleNames.power(it.id))
            put("target", BattleNames.target(it.id))
          }
        }
      }
    }
  }

  fun invite(b: BattleState): JsonElement =
      b.pendingInviteFrom?.let {
        buildJsonObject {
          put("from", it)
          put("rules", b.pendingInviteRules?.let(::rulesJson) ?: JsonNull)
        }
      } ?: JsonNull

  private fun rulesJson(r: DuelRules) = buildJsonObject {
    put("levelCap", r.levelCap)
    put("party", r.maxParty)
    put("timer", r.turnSeconds)
    if (r.doubles) put("doubles", true)
  }

  private fun moveInfo(id: Int, pp: Int?, maxPp: Int?) = buildJsonObject {
    put("name", names.move(id))
    put("type", BattleNames.moveType(id))
    put("power", BattleNames.power(id))
    put("accuracy", BattleNames.accuracy(id))
    put("pp", pp ?: names.maxPp(id))
    put("maxPp", maxPp ?: names.maxPp(id))
  }

  private fun fxJson(f: BattleFx): JsonElement = buildJsonObject {
    put("n", f.seq)
    put("k", f.kind)
    f.side?.let { put("side", it) }
    for ((k, v) in f.data) put(k, anyJson(v))
  }

  private fun anyJson(v: Any?): JsonElement =
      when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        // r3-battle: entity ids exceed 2^53, so a browser would round them; send them as strings.
        is Long -> JsonPrimitive(v.toString())
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Map<*, *> -> buildJsonObject { for ((k, x) in v) put(k.toString(), anyJson(x)) }
        is List<*> -> buildJsonArray { v.forEach { add(anyJson(it)) } }
        else -> JsonPrimitive(v.toString())
      }

  /** A closed trade stays visible once with its reason; a closed trade with no reason is null. */
  fun trade(t: TradeStatePacket, expiresAt: Long = 0): JsonElement {
    if (t.phase == TradePhase.CLOSED && t.message.isEmpty()) return JsonNull
    return buildJsonObject {
      put("phase", t.phase.name)
      put("partner", t.partnerName)
      put("message", t.message)
      if (expiresAt > 0) put("expiresAt", expiresAt)
      put("mine", offer(t.mine, t.myStage))
      put("theirs", offer(t.theirs, t.theirStage))
    }
  }

  private fun offer(o: TradeOfferView, stage: TradeStage) = buildJsonObject {
    put("stage", stage.name)
    putJsonArray("mons") {
      o.mons.forEach {
        addJsonObject {
          put("slot", it.partySlot)
          put("dex", it.dexId)
          put("name", names.species(it.dexId))
          put("level", it.level)
        }
      }
    }
    putJsonArray("items") {
      o.items.forEach {
        addJsonObject {
          put("item", it.itemId)
          put("name", names.item(it.itemId))
          put("qty", it.quantity)
        }
      }
    }
    put("money", o.money)
    // r3-social: monsters offered from the PC, `slot` is the PC slot.
    putJsonArray("pcMons") {
      o.pcMons.forEach {
        addJsonObject {
          put("slot", it.partySlot)
          put("dex", it.dexId)
          put("name", names.species(it.dexId))
          put("level", it.level)
        }
      }
    }
  }

  fun market(s: Sources): JsonElement {
    val m = s.market
    val mineIds = m.mine.map { it.listingId }.toSet()
    val selfId = s.world.selfId
    return buildJsonObject {
      // The overworld's balance follows every money packet (trade, shop, market); the market's
      // own copy only follows market replies, so prefer the former.
      put("money", s.world.overworld?.money ?: m.money)
      putJsonArray("browse") {
        m.browse.forEach {
          add(entry(it, it.listingId in mineIds || it.sellerId == selfId, m.sellers[it.listingId]))
        }
      }
      put("browseTotal", m.browseTotal)
      put("page", m.browsePage)
      putJsonArray("mine") { m.mine.forEach { add(entry(it, true, m.sellers[it.listingId])) } }
      putJsonObject("inventory") {
        put("party", party(m.party))
        put("bag", bagJson(m.items.associate { it.itemId.toInt() to it.quantity }))
      }
      putJsonArray("log") { m.log.forEach { add(it) } }
      // r3-social extras.
      put("info", m.info?.let(socialJson::info) ?: JsonNull)
      put("history", m.priceHistory?.let(socialJson::priceHistory) ?: JsonNull)
      put("sales", m.sales?.let(socialJson::sales) ?: JsonNull)
      put("openSeq", m.openSeq)
    }
  }

  private fun entry(e: MarketEntry, mine: Boolean, seller: String? = null) = buildJsonObject {
    put("id", e.listingId.toString())
    put("kind", if (e.isPokemon) "mon" else "item")
    put("dex", if (e.isPokemon) JsonPrimitive(e.refId) else JsonNull)
    put("item", if (e.isPokemon) JsonNull else JsonPrimitive(e.refId))
    put("name", if (e.isPokemon) e.nickname ?: names.species(e.refId) else names.item(e.refId))
    put("level", e.level)
    put("qty", e.quantity)
    put("price", e.price)
    put("expiresAt", e.expiresAtEpoch)
    put("mine", mine)
    put("seller", seller?.takeIf { it.isNotEmpty() })
  }

  companion object {
    const val CHAT_LINES = 50
    const val BATTLE_LOG_LINES = 50
    const val BATTLE_FX = 60

    fun dir(d: Direction): String =
        when (d) {
          Direction.UP -> "up"
          Direction.DOWN -> "down"
          Direction.LEFT -> "left"
          Direction.RIGHT -> "right"
          else -> d.name.lowercase()
        }

    fun parseDir(s: String?): Direction? =
        when (s) {
          "up" -> Direction.UP
          "down" -> Direction.DOWN
          "left" -> Direction.LEFT
          "right" -> Direction.RIGHT
          else -> null
        }
  }
}

/**
 * Per-section change detection: remembers the last JSON sent for each section and returns only the
 * ones that differ. The first call returns all of them.
 */
class SectionDiffer {
  private val last = HashMap<String, JsonElement>()

  fun diff(sections: Map<String, JsonElement>): Map<String, JsonElement> {
    val out = linkedMapOf<String, JsonElement>()
    for ((k, v) in sections) {
      if (!last.containsKey(k) || last[k] != v) {
        out[k] = v
        last[k] = v
      }
    }
    return out
  }

  fun reset() = last.clear()
}

/** Marks entities `moving` for one step duration after their tile changes. */
class MovementTracker(private val windowMs: Long = 300) {
  private val lastPos = HashMap<Long, Long>()
  private val movedAt = HashMap<Long, Long>()

  fun update(entities: Collection<Entity>, nowMs: Long): Set<Long> {
    val seen = HashSet<Long>()
    for (e in entities) {
      seen += e.id
      val pos = (e.x.toLong() shl 32) or (e.y.toLong() and 0xFFFFFFFFL)
      val prev = lastPos.put(e.id, pos)
      if (prev != null && prev != pos) movedAt[e.id] = nowMs
    }
    lastPos.keys.retainAll(seen)
    movedAt.keys.retainAll(seen)
    return movedAt.filterValues { nowMs - it < windowMs }.keys
  }

  fun anyMoving(nowMs: Long): Boolean = movedAt.values.any { nowMs - it < windowMs }
}
