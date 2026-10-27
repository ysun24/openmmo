# Web client — contract

Browser (phone + desktop) ⇄ **WebSocket JSON** ⇄ `web.gateway` (Kotlin) ⇄ **TCP** ⇄ OpenMMO login/game servers.

The gateway runs one headless `GameClient` (the existing `client` module: login, overworld, battle,
trade, market) per browser socket and mirrors its state as JSON. The browser renders with Canvas
from pre-exported static assets and sends player intents. Nothing from the official PokeMMO client
is used anywhere.

```
web/
  gateway/          Gradle module :web.gateway  (HTTP static + /ws, Netty, kotlinx.serialization)
  assets-export/    Gradle module :web.assets   (main: exports pret decomp data -> web/static/assets)
  static/           index.html, css/, js/ (vanilla ES modules, no build step), assets/ (generated, gitignored)
```

## 1. Transport

- `GET /` and `GET /<path>` serve `web/static/` (path traversal refused, correct MIME types,
  `Cache-Control: no-cache` for html/js, long cache for `assets/`).
- `GET /ws` upgrades to WebSocket. Text frames, one JSON object per frame, field `t` = type.
- Gateway binds **127.0.0.1:8080** by default (`--host/--port`). It checks `Origin` against its own
  host when present. Max frame 64 KiB. Login attempts rate-limited per socket (5/min).
- Heartbeat: browser sends `{"t":"ping","n":N}` every 5 s, gateway answers `{"t":"pong","n":N}`;
  the browser shows RTT. Socket closed by gateway after 30 s silence; closing the socket
  disconnects the game session.

## 2. Browser → gateway

| `t` | fields | maps to |
|---|---|---|
| `login` | `user`, `pass`, `name` (character name to select/create), `region` (0 Kanto, 1 Hoenn, optional) | LoginClient + GameClient.connect |
| `step` | `dir` (`up`/`down`/`left`/`right`), `run` (bool), `seq` (int, client step counter) | GameClient.step |
| `face` | `dir` | GameClient.face |
| `say` | `text` (a leading `/` is a server command) | GameClient.say / command |
| `a` | – | interact, or advance dialog / confirm choice when a dialog is open |
| `b` | – | cancel dialog / close shop |
| `cursor` | `delta` (±1) | dialog choice / shop cursor |
| `choose` | `index` | dialog choice |
| `shop.buy` / `shop.sell` | `item`, `qty` | OverworldClient.buy/sell |
| `shop.close` | – | |
| `item.use` | `item`, `mon` (party monster `id` string, or null/absent for items without a target, e.g. Repel) | OverworldClient.useItem → FieldItemUsePacket |
| `party.move` | `from`, `to` (party indices 0-5; swaps the two) | OverworldClient.moveMonster → SwapInventorySlotsPacket |
| `pc.deposit` | `slot` (party index) | only where `pc.here` is true |
| `pc.withdraw` | `slot` (PC slot from `pc.mons[].slot`) | only where `pc.here` is true |
| `battle.fight` | `move` (0-3) | BattleClient.fight |
| `battle.switch` | `slot` (party index 0-5) | |
| `battle.run` / `battle.ball` | – | |
| `battle.learn` | `slot` (0-3) or `null` to skip | |
| `battle.close` | – | leaves the result screen of an ENDED battle (`a`, `b` and `step` do too) |
| `duel` | `name`; optional rules `levelCap` (0 = none, 1-100), `party` (1-6, default 6), `timer` (seconds per turn, 0 = none, 5-240) | BattleClient.challenge (rules → `DuelRulesChallengePacket` c2s 0xEE, none → retail 0x5A) |
| `duel.answer` | `accept` (bool) | |
| `trade.request` | `name` | TradeClient |
| `trade.accept` / `trade.decline` / `trade.ready` / `trade.confirm` / `trade.cancel` | – | |
| `trade.mon` | `slot` (toggle) | |
| `trade.item` | `item`, `qty` (0 removes) | |
| `trade.money` | `amount` | |
| `market.search` | `kind` (`mon`/`item`/null), `id`, `min`, `max`, `sort` (`price`/`price-desc`/`new`), `page` | MarketClient |
| `market.mine` / `market.inventory` | – | |
| `market.listMon` | `slot`, `price` | |
| `market.listItem` | `item`, `qty`, `price` | |
| `market.buy` / `market.cancel` | `id` | |
| `ping` | `n` | |

Unknown or malformed messages get `{"t":"error","msg":...}`; they never close the socket.

## 3. Gateway → browser

- `{"t":"login","ok":true,"selfId":"<id>","name":"Ash"}` or `{"t":"login","ok":false,"error":"INVALID_PASSWORD"}`.
  Error codes: the login server's state (`INVALID_PASSWORD`, `INVALID_USERNAME`, ...), or
  `RATE_LIMITED`, `BAD_CREDENTIALS_FORMAT`, `BAD_CHARACTER`, `LOGIN_SERVER_UNREACHABLE`,
  `LOGIN_TIMEOUT`, `GAME_SERVER_UNREACHABLE`, `ENTER_WORLD_TIMEOUT`, `NO_JOINABLE_GAME_SERVER`.
  `name` is the character actually selected (an account's first character if none matches).
- `{"t":"error","msg":"..."}` for refused actions too (e.g. `battle.fight` → `"not your turn"`).
  If the game connection drops, the gateway sends `{"t":"error","msg":"game connection closed"}`
  and closes the socket.
- `{"t":"state","seq":N, ...sections}` — pushed whenever something changed, coalesced to at most
  one message per **33 ms**. Each section is included only if its JSON differs from the last one
  sent on this socket; a section that becomes empty is sent as `null`. The first message after
  login contains every section. Sections:

```jsonc
"world": {
  "map": {"region":0,"bank":3,"map":0,"name":"PalletTown","width":24,"height":20} | null,
  "self": "<entityId string>",
  "inWorld": true,
  "entities": [ {"id":"<string>","kind":"player|npc","name":"Friend","gfx":23,
                 "x":12,"y":8,"facing":"down","moving":false,"map":{"region":0,"bank":3,"map":0}} ],
  "ackSeq": 41          // last `step.seq` the gateway applied (for prediction reconciliation)
},
"chat":   [ {"sender":"*","text":"Welcome to OpenMMO!"} ],     // last 50 lines
"ui": {                 // overworld UI (OverworldState.View)
  "dialog": {"speaker":"PROF. OAK","lines":["Hey! Wait!","Don't go out!"],"lastPage":false,
             "choices":["YES","NO"],"cursor":0} | null,   // speaker: named npcs only, else null
  "shop": {"offers":[{"item":5017,"name":"Potion","price":300,"desc":"..."}],
           "sell":[{"item":5017,"name":"Potion","qty":3,"price":150}],   // what the clerk buys, half price
           "cursor":0,"status":""} | null,
  "money": 3000,
  "bag": [{"item":5017,"name":"Potion","qty":3,
           "pocket":"items|balls|tms|berries|key","desc":"...","use":"mon|field"|null}],
  "locked": false,      // transitioning || scriptLocked || inScript
  "notice": {"seq":3,"text":"BULBASAUR's HP was restored by 16 point(s)."} | null  // last server notice
},
"party": [ {"slot":0,"dex":4,"name":"CHARMANDER","nick":null,"level":15,"hp":39,"maxHp":39,
            "id":"<string>","stats":{"hp":39,"atk":20,"def":18,"spa":22,"spd":19,"spe":24},
            "nature":"HARDY","ability":"BLAZE","types":["FIRE"],
            "moves":[{"name":"SCRATCH","pp":35,"maxPp":35}],"ot":"Ash","xp":1000,"shiny":false} ],
"pc": {"here": true,   // the player is on a map with a Pokemon Center nurse
       "mons": [{"slot":0,"id":"<string>","dex":19,"name":"RATTATA","level":5,"hp":19,"maxHp":19}]},
"battle": {             // null when no battle
  "wild":true,"opponentName":"","phase":"ACTION|FORCED_SWITCH|WAITING|ENDED","turn":3,
  "activeSlot":0,       // index into "party" of the monster that is out
  "me":  {"dex":4,"name":"CHARMANDER","level":15,"hp":30,"maxHp":39,
          "exp":0.42,   // 0..1 towards the next level, omitted when unknown
          "moves":[{"name":"SCRATCH","pp":33,"maxPp":35,"type":"NORMAL","power":40}]},
  "foe": {"dex":19,"name":"RATTATA","level":3,"hp":0,"maxHp":15},
  "party":[{"dex":4,"name":"CHARMANDER","level":15,"hp":30,"maxHp":39}],
  "log":["CHARMANDER used SCRATCH!"],"result":null,
  "rules": {"levelCap":50,"party":3,"timer":30} | null,   // duel rules, null outside a duel
  "timer": {"id":4,"seconds":30,"mustAct":true,"timeouts":1,"rivalTimeouts":0,
            "autoPicked":false,"maxTimeouts":3} | null,   // the running duel turn clock
  "fx": [ {"n":12,"k":"move","side":"me","move":"EMBER","type":"FIRE","power":40,"target":"foe","hits":true} ],
  "learn": {"move":"EMBER","current":["SCRATCH","GROWL"],"mon":"CHARMANDER",
            "offer":{"name":"EMBER","type":"FIRE","power":40,"accuracy":100,"pp":25,"maxPp":25},
            "moves":[{"name":"SCRATCH","type":"NORMAL","power":40,"accuracy":100,"pp":33,"maxPp":35}]} | null
},
"invite": {"from":"Friend","rules":{"levelCap":0,"party":6,"timer":0} | null} | null,  // pending duel invite
"trade": {              // null when no trade
  "phase":"REQUEST_RECEIVED|REQUEST_SENT|OPEN|COMPLETED|CLOSED","partner":"Friend","message":"",
  "mine":  {"stage":"NONE|READY|CONFIRMED","mons":[{"slot":1,"dex":1,"name":"BULBASAUR","level":12}],
            "items":[{"item":5017,"name":"POTION","qty":2}],"money":500},
  "theirs":{ ...same... }
},
"market": {
  "money": 80200,
  "browse": [{"id":"1","kind":"mon|item","dex":25,"item":null,"name":"PIKACHU","level":12,"qty":1,
              "price":1500,"expiresAt":1793000000,"mine":false}],
  "browseTotal": 1, "page": 0,
  "mine": [ ...same shape... ],
  "inventory": {"party":[...party shape...],"bag":[...bag shape...]},
  "log": ["BUY ok: Bought 2x Potion for 300."]
}
```

Gateway notes (implementation in `web/gateway`):
- `ackSeq` is the last `step.seq` the gateway handed to its game client, acknowledged even when
  the step was refused (dialog open, battle) — the position in the same message is then unchanged,
  so the browser reconciles at once. A step the *server* rejects arrives later as a position reset.
- `gfx` is `null` for players. Entities the server hid (scripted `visible=false`) are omitted.
- `moving` is true for ~300 ms after an entity's tile changed.
- `trade` with phase `CLOSED` is sent only when it carries a `message` (why it ended); it stays
  until the next trade starts. `REQUEST_*`/`OPEN`/`COMPLETED` as in the table.
- `battle` stays (phase `ENDED`, `result` set) until `battle.close`/`a`/`b`/`step`.
- `market.money` and `ui.money` are the same balance.
- `battle.turn` counts action prompts in this battle (1 on the first).
- `battle.fx` is the battle's effect stream, oldest first, the newest 60 kept. `n` grows by one per
  effect for the whole session, so the browser plays every effect with `n` above the last one it
  played, in order, to animate the battle in step with the server (state messages are coalesced).
  A fresh battle starts with a `start` effect. Kinds (`side` is `me`/`foe` where it applies):
  `start` {wild, pvp, opponentName, me, foe}, `text` {text}, `move` {move, type, power, target,
  hits}, `hp` {from, hp, maxHp, effect: normal|super|weak|heal}, `miss`, `stat` {stat, delta},
  `faint`, `switch` {mon:{dex,name,level,hp,maxHp}}, `ball` {shakes, caught}, `exp` {from, to}
  (0..1 of the level bar; a level-up is `exp`→1, `level` {level}, `exp` 0→x), `end` {result}.
  Status conditions are not modelled by the server, so there is no status effect yet.
- Duels: `rules` echo what the challenger picked (the server clamps them). Monsters above
  `levelCap` fight at the cap (stats recomputed, hp kept at the same fraction); each side brings
  its first `party` healthy monsters; nothing carries over after the duel. With a `timer`, each
  prompt starts a clock of `timer.seconds` (count it down locally from when a new `timer.id`
  arrives). `mustAct` false means only the rival is choosing. When it runs out the server acts for
  whoever still owes an action (first usable move, or the first healthy monster for a forced
  switch) and counts a timeout; at `maxTimeouts` that player forfeits. Opcodes (ours, free in both
  directions): c2s 0xEE `DuelRulesChallengePacket`, s2c 0xEE `DuelRulesPacket`, s2c 0xEF
  `BattleTurnTimerPacket`.
- Dialog text arrives with `{PLAYER}`, `{RIVAL}` and `{STR_VAR_n}` already filled: the game server
  sends the script's string buffers with every box (dialog arg tag 4 `TextStringArg`, ours).
- `item.use` / `party.move` / `pc.*` results come back as a changed `party` / `pc` / `ui.bag` and a
  `ui.notice` (also in `chat`). Refusals (full HP, no PC here, last monster) are notices too.

Entity ids are strings (they are 64-bit). Names come resolved from the gateway (codegen tables),
so the browser needs no name tables.

## 4. Static assets (`web/static/assets/`, generated by `:web.assets:run`)

```
assets/manifest.json
  {"version":1,
   "maps": {"0:3:0": {"name":"PalletTown","width":24,"height":20,
                      "bottom":"maps/0/pallet_town.bottom.png","top":"maps/0/pallet_town.top.png",
                      "collision":"maps/0/pallet_town.col.bin",
                      "border":"maps/0/pallet_town.border.png",   // borderWidth x borderHeight metatiles, or null
                      "borderWidth":2,"borderHeight":2,
                      "connections":[{"dir":"up","offset":0,"map":"0:3:19"}],
                      "music":"MUS_PALLET"}, ...},
   "objects": {"0:23": {"name":"OBJ_EVENT_GFX_...","sheet":"objects/0/23.png","frameW":16,"frameH":32,"count":9,
                        "frames":{"down":0,"up":1,"left":2,"walkDown":[3,4],"walkUp":[5,6],"walkLeft":[7,8]},
                        "mirrorRight":true}, ...},
   "players": {"0":{"male":"objects/0/0.png","maleGfx":0,"female":"objects/0/7.png","femaleGfx":7},
               "1":{"male":"objects/1/0.png","maleGfx":0,"female":"objects/1/89.png","femaleGfx":89}},
   "pokemon": {"4":{"front":"pokemon/4.front.png","back":"pokemon/4.back.png","icon":"pokemon/4.icon.png"}, ...},
   "ui": {"font":null}}
```

- Map keys are `region:bank:map` exactly as the server sends them: region 0 → pokefirered,
  region 1 → pokeemerald; bank = index in `data/maps/map_groups.json` group_order **plus the
  region's bank offset (0 Kanto, 50 Hoenn)**, map = index inside the group (desktop
  `PretData.mapName`). So Littleroot Town is `1:50:9`. Every map of both games is exported.
- File paths are per *layout* (`maps/<region>/<layout>.*`); maps sharing a layout (every Pokemon
  Center...) point at the same files. Always use the paths from the manifest.
- Map images are full-map PNGs at 1 px = 1 GBA pixel (16 px per metatile). `bottom` = backdrop +
  bottom tile layer (+ the top tile layer of COVERED metatiles); `top` = the top tile layer of
  NORMAL and SPLIT metatiles, transparent elsewhere (the GBA puts that layer above sprites; same
  rule as the desktop `WorldRenderer`/`MetatilePainter`). Draw order: bottom → entities sorted by
  y → top.
- `border`: both layers flattened. Outside a map (where no connection is), the block at map tile
  `(x, y)` is border cell `(x mod borderWidth, y mod borderHeight)` (non-negative modulo).
- `col.bin` = width*height bytes, row-major: bit0 = blocked (collision != 0),
  bits 4-7 = elevation. Used only for client-side prediction; the server stays authoritative.
- `connections`: only the four edge directions; `offset` in tiles along the shared edge, as in
  map.json (up/down: x offset of the other map, left/right: y offset).
- Objects are keyed `region:gfx` because gfx ids differ between the two games. `gfx` is the
  `OBJ_EVENT_GFX_*` value the server puts in NPC spawn packets (both read the region decomp's
  `include/constants/event_objects.h`). The sheet is one row of `count` frames, each
  frameW x frameH (16x16, 16x32, 32x32, 64x64...), in pic-table order; `frames` names the indices
  (missing ones fall back to standing). Right = left mirrored. Player sheets (and only those with
  ≥18 frames) also have `runDown/runUp/runLeft` (standing) and `runWalkDown/Up/Left` (two steps).
  Palettes are the game's own palette for the sprite's palette tag; colour 0 is transparent.
  Var gfx ids (`OBJ_EVENT_GFX_VAR_*`, 240+) are resolved by the server, not exported.
- Pokemon: keyed by national dex (1-386, the id the server/battle code uses). front/back 64x64,
  icon 32x32 (first icon frame), transparent background. FireRed art first, Emerald as fallback.

### 4.1 Additions (round 2, all optional / additive)

Map entries gain:
- `"warps": [x0, y0, x1, y1, ...]` — the map.json `warp_events` tiles. The browser sends a `step`
  against a wall only while standing on / facing one of these (stairs and door mats warp on a push).
- `"anim": "maps/<r>/<layout>.anim.json" | null` — tileset animations of the layout:

```jsonc
{"image": "maps/0/pallet_town.anim.png",   // atlas, `cols` cells per row, cell n at
 "cols": 16,                               // ((n % cols) * 16, floor(n / cols) * 32): bottom layer
                                           // 16x16 on top, top layer 16x16 below it
 "channels": [ {"dest": 416, "size": 48,   // VRAM tile range a decomp callback copies frames into
                "cycle": 640,              // its counter period (CounterMax) in GBA frames (59.7275 Hz)
                "events": [0,7, 1,0, 17,1]} ],  // flat (tick, frame) change points; frame at counter
                                                // t = last event with tick <= t
 "metatiles": [ {"id": 299, "ch": [0],     // channels this metatile shows
                 "top": false,             // top layer animated (the baked top PNG is blank there)
                 "combos": [[f0, cell], ...],   // channel frames (in `ch` order) -> atlas cell
                 "cells": [x, y, ...]} ]}  // map tiles with this metatile
```
  Both counters start with the map; channel tick = `gbaFrame % cycle`. Generated by running the
  decomp's `src/tileset_anims.c` callbacks (the `InitTilesetAnim_*` maxima and `TilesetAnim_*`
  timing), so timing and frame order are the game's.

Top level gains:
- `"music": {"0": "music/0/index.json", "1": "music/1/index.json"}` — per region:
  `{"songs": {"MUS_PALLET": {"mid": "music/0/mus_pallet.mid", "vol": 100, "voices": {...}}},
  "waves": [[32 x 4-bit samples], ...]}`. `mid` is the decomp's own SMF (loop = marker events `[`
  and `]`), `vol` its `midi.cfg -V`. `voices` maps a MIDI program to the voicegroup voice it plays:
  `{"t": "sq"|"wave"|"noise"|"ds", "key", "pan", "duty" (0-3), "wave" (index into waves), "period",
  "name" (DirectSound sample name, no data), "fixed", "env": [a, d, s, r], "cgb": true for the PSG
  channels}`; key splits / drum kits are `{"keys": {"<note>": voice, ...}}` (drum voices carry
  `"drum": true`). A map's `music` constant names the song.
- `"battleMusic": {"0": {"wild": "MUS_VS_WILD", "trainer": ..., "gym": ..., "victoryWild": ...,
  "victoryTrainer": ..., "victoryGym": ..., "caught": "MUS_CAUGHT"}, "1": {...}}`.

`web/static/app.webmanifest` + `icons/` make the client installable (fullscreen). The gateway
serves `.mid` as `audio/midi`.



## 5. Smoothness requirements (browser)

- Render at display refresh with `requestAnimationFrame`, integer-scaled GBA viewport (240×160
  logical, letterboxed, `image-rendering: pixelated`), camera follows the interpolated self position.
- Self movement is **predicted**: on a held direction the browser moves immediately (16 px over
  250 ms walking / 125 ms running, walk frames), checks `col.bin` and NPC tiles before stepping,
  sends `step` with an increasing `seq`, and reconciles against `world.self` position + `ackSeq`
  (snap only if the server disagrees after it has acknowledged that step).
- Other entities interpolate from their previous tile to the new one over one step duration.
- Warps/map changes: fade out/in on `world.map` change; preload the new map's PNGs from the
  manifest (and those of its connections) so crossing a connection never shows black.
- Controls: keyboard (arrows/WASD, Z/Space = A, X/Esc = B, Shift = run, Enter = chat), and on touch
  devices an on-screen D-pad + A/B buttons (multi-touch, no scroll/zoom on the canvas). UI panels
  (battle menu, trade, market, shop, chat) are HTML overlays usable by touch.

## 6. r3-battle additions (all additive / optional)

### 6.1 Browser → gateway
| message | fields | meaning |
|---|---|---|
| `battle.fight` | `move` 0-3, optional `pos` 0-1, optional `target` `{side: "me"|"foe", pos: 0-1}` | a move for a battle position; without `pos`/`target` it is the old single-battle message |
| `battle.item` | `item` (wire id), optional `mon` (party monster id, string), `moveSlot` 0-3 (one-move PP items), `pos` 0-1 | uses a bag item in battle (medicine on any party monster, X items on the position's monster); spends that position's turn |
| `battle.ball` | optional `item` | throws that ball (default Poké Ball) |
| `evolution.answer` | `proceed` bool | `false` = B pressed during the scene ("stopped evolving") |
| `evolution.close` | — | closes a finished / cancelled evolution scene |
| `duel` | optional `doubles` bool | 2v2 double duel (needs two healthy monsters on both sides, else 1v1) |

### 6.2 Gateway → browser
- `battle` gains: `format` (1|2), `actives` (party slot per position, -1 empty), `choosingPos` (position picking next, null when all picked), `owedSwitches`, `mine` / `foes` (monster per position, same shape as `me` / `foe`), `terrain` (background key, see 6.4), `music` (`wild|trainer|gym|elite|champion|rival|team|pvp`), `weather` (`none|rain|sun|sand|hail`), `trainer` `{pic, class}` or null, `bag` `[{id, name, count, target: foe|party|active|none, pocket: medicine|balls|battle, perMove?}]` (only items usable in this battle; no balls against trainers / players).
- Every battle monster (`me`, `foe`, `mine[]`, `foes[]`, `party[]`) gains `id` (string), `slot`, `status` (`slp|psn|tox|brn|frz|par` or null), `confused`, and for own monsters `ability`, `item`, `substitute`. Moves gain `target` (the decomp MoveTarget: `SELECTED`, `BOTH`, `USER`, `FOES_AND_ALLY`, ...).
- `fx` entries carry `id` (string entity id) where they concern one monster (`move` also `targetId`, `targets`), `switch` carries `pos`, `ball` carries `ball` (name), real `shakes` 0-3 and `caught`. New kinds: `status` (`status`), `ability` (`ability`, `name`), `item` (`item`, `consumed`, `name`), `weather` (`weather`), `volatile` (`kind`: confused|substitute|seeded, `on`), `revive`; `hp` gains `reason` (move|poison|burn|leftovers|berry|recoil|drain|weather|item|...).
- New top-level section `evolution`: `{id, seq, phase: start|evolving|stopping|done|cancelled, fromDex, toDex, name, toName, learned[]}` or null. It can appear outside a battle (stones, trades) and after one (level-up); the browser plays it once the battle screen is gone.
- `rules` / `invite.rules` gain `doubles: true` when set.

### 6.3 Game protocol (OpenMMO-only, r3-battle)
- s2c **0xE6** `BattleExtPacket` — UTF-8 JSON (u32 length): `{"t":"state",...}` extended battle state, `{"t":"events","e":[...]}` ordered extension events between the retail packets, `{"t":"evo",...}` evolution scene. (The reserved s2c 0xF0-0xF3 were already used by retail packets.)
- c2s **0xF0** `BattleExtActionPacket` `{position u8, kind u8 (0 move,1 item,2 switch,3 run), id s16, targetSide s8, targetPos s8, targetEntity s64}`.
- c2s **0xF2** `EvolutionReplyPacket` `{entityId s64, proceed u8}`.
- `DuelRulesChallengePacket` / `DuelRulesPacket` (0xEE) gain a trailing flags byte (bit 0 = doubles); senders that leave it out mean 1v1.

### 6.4 Static assets (`manifest.battle`, `manifest.battleMusicExtra`)
- `battle.trainers[<TRAINER_PIC_ suffix lower case>]` → front sprite PNG (64x64).
- `battle.backs[brendan|may|red|leaf]` → `{file, frame, frames}` (throw animation frames stacked vertically).
- `battle.backgrounds[<region>][<key>]` → 240x160 PNG; keys `tall_grass long_grass sand underwater water pond_water rock cave building plain gym leader stadium champion elite_<name> team_aqua team_magma` (FireRed: no team_*, elite_lorelei/bruno/agatha/lance).
- `battle.anims[<TYPE>|hit|sleep|ball|sparkle|stat]` → `{file, frame, frames}` sprite sheets from graphics/battle_anims.
- `battleMusicExtra[<region>]` → `{elite, champion, rival, team, pvp, victoryElite}` song constants (alongside `battleMusic.gym`), picked with `battle.music`.

## 7. r3-items (round 3, additive)

### 7.1 Browser → gateway
| t | fields | effect |
|---|---|---|
| `item.move` | `item`, `mon` (id string), `slot` 0-3 | Ether / Max Ether / Leppa / PP Up / PP Max on move `slot`; for a TM/HM `slot` is the move to forget when the monster knows four |
| `learn.reply` | `slot` 0-3 or `null` | answers `learn`: forget `slot` for the new move, `null` gives it up |
| `item.register` | `item` (0 clears) | registers a key item (GBA SELECT; the browser's key **C** uses it) |
| `item.registered` | — | uses the registered item |
| `daycare.deposit` | `mon` or `null` | answers `daycare.open == "deposit"` (null cancels) |
| `daycare.withdraw` | `index` 0-1 or `null` | answers `daycare.open == "withdraw"` |
| `decor.buy` | `id`, `qty` 1-99 | buys at the open decoration shop |
| `decor.close` | — | closes it ("Please come again!"); `shop.close` now does the same for marts |
| `progress.refresh` | — | asks for a fresh push |

`item.use` keeps working for every item whose bag `use` is `mon` or `field`.

### 7.2 Gateway → browser
- `ui.bag[].use`: `mon`, `move` (pick a monster, then a move), `teach` (TM/HM), `field`, or null. TM/HM rows add
  `teaches` (move name) and `able` (party ids that can learn it, Gen 3 table); key items that register add `register: true`.
  TM descriptions are now "Teaches MOVE. <move description>" with the Gen 3 TM→move mapping.
- `ui.registered`: the registered item id or null. `ui.notice.kind`: `presence` (joins/leaves — chat only, never a
  toast), `system`, `reward`, `error`, `info`.
- `party[]`: `status` (`PSN`/`BRN`/`FRZ`/`PAR`/`SLP` or null), `egg: true` for eggs (name `EGG`), `moves[]` gain
  `slot`, `id`, `ppUps`, and `maxPp` includes PP Ups.
- New sections (null until the first server push):
  - `dex`: `{region, seen, caught, regionalSeen, regionalCaught, regionalSize, nationalSize, regional:[dex…],
    entries:[{dex, name, caught, types, hoenn?, category?, heightM?, weightKg?, text?}]}` — one entry per seen species;
    the page fields only for caught ones.
  - `trainer`: `{name, money, playSeconds, dexSeen, dexCaught, badges:{kanto:[8 bool], hoenn:[8 bool]}, badgeNames,
    region, registered, registeredName, admin, developer}`.
  - `daycare`: `{open: "deposit"|"withdraw"|null, mons:[{index, id, dex, name, level, gained, fee}], egg}` or null.
  - `decor`: `{owned:[{id, name, category, qty}], shop:[{id, name, category, price}]|null}` or null.
  - `learn`: `{mon, name, move:{id,name,type,power,pp,maxPp,desc}, moves:[…]}` or null.

### 7.3 Game protocol (OpenMMO-only)
c2s `0xF8` `ProgressActionPacket(action u8, itemId s16, monId s64, arg1 s32, arg2 s32)` and s2c `0xE7`
`ProgressStatePacket` (see `protocols.game/.../packets/progress/ProgressPackets.kt`; s2c 0xF8-0xFB were taken, so the
state packet uses the free 0xE7).

## 8. r3-social (round 3, additive)

Browser → gateway (new types; the old ones keep their meaning):

| `t` | fields | notes |
|---|---|---|
| `say` | + optional `channel` (`local`/`global`/`trade`/`guild`/`whisper`), `to` (whisper target, required for `whisper`) | no `channel` = local, as before; a leading `/` is still a command |
| `social.refresh` | – | resend friends, blocks, guild |
| `friends.add` / `friends.remove` / `block` / `unblock` / `profile` | `name` | one-way friends; blocking also removes the friendship |
| `guild.create` | `name` (3-24 letters, digits, spaces), `tag` (2-5 letters/digits) | costs `guild.foundCost` (15000) |
| `guild.invite` / `guild.kick` | `name` | officers and above; kick only lower ranks |
| `guild.answer` | `accept` (bool) | invites lapse after 60 s |
| `guild.rank` | `name`, `rank` (0 Recruit .. 5 Leader; 5 hands over the leadership) | |
| `guild.motd` | `text` | officers and above |
| `guild.leave` / `guild.disband` | – | the leader disbands or hands over first |
| `trade.pcMon` | `slot` (PC slot, `pc.mons[].slot`) | toggles a PC monster on the table |
| `trade.history` | – | fills `social.tradeHistory` |
| `market.history` | `kind` (`mon`/`item`), `id` | fills `market.history` |
| `market.sales` / `market.info` | – | fill `market.sales` / `market.info` |

Gateway → browser:
- `chat[]` lines gain `channel` (`local|global|trade|guild|whisper|system`, absent for server notices)
  and `to` (whisper target). The sender of a `system` line is `*`.
- `trade` gains `expiresAt` (epoch ms, pending requests only) and `mine/theirs.pcMons[]`
  (`{slot,dex,name,level}`, slot = PC slot). While a trade is `OPEN` the server refuses steps (the
  position stays), shop, npc and sign actions, and market actions.
- `market.browse[]/mine[]` rows gain `seller` (name, `null` until the page metadata arrived);
  `market` gains `info` `{feePermille,minFee,maxActive,active,listingHours}`, `history`
  `{kind,id,name,days:[{day (epoch day),min,max,avg,volume}],recent:[{at,price,qty}]}` (unit prices,
  last 30 days), `sales` `[{id,kind,dex,item,name,qty,price,fee,buyer,at}]` and `openSeq` (grows when
  a market clerk asked the client to open the market window).
- New section `social`:

```jsonc
"social": {
  "friends": [{"name":"Gary","online":true,"location":{"region":0,"bank":5,"map":4,"name":"ViridianCity_PokemonCenter_1F"} | null,
               "since":1793064112,"mutual":true}],
  "blocked": ["Spammer"],
  "profile": {"name":"Gary","online":true,"location":{...} | null,"guild":"Pallet Pals" | null,"tag":"PAL" | null,
              "party":[{"dex":1,"name":"BULBASAUR","level":15,"shiny":false}],"created":1793063796,
              "friend":true,"blocked":false,"trades":8} | null,
  "profileSeq": 3,               // grows with every profile reply
  "guild": {"inGuild":true,"name":"Pallet Pals","tag":"PAL","motd":"...","myRank":5,"myRankName":"Leader",
            "members":[{"name":"Ash","rank":5,"rankName":"Leader","online":true,"location":{...} | null,"joined":...}],
            "log":[{"at":...,"text":"Ash founded the guild."}]} | {"inGuild":false},
  "invite": {"from":"Ash","guild":"Pallet Pals","tag":"PAL","expiresAt":1793064173000} | null,
  "tradeHistory": [{"at":...,"partner":"Gary","gave":"10 money","got":"RATTATA Lv5"}] | null
}
```

A player who blocked you shows offline with no location, and never receives your chat, whispers,
trade requests or guild invites. Refusals (rate limits, permissions, offline targets) arrive as
`ui.notice` / `system` chat lines, never as errors.

Game protocol (ours, `packets/social/CommunityPackets.kt`): c2s 0xFC `SocialRequestPacket`
(op, name, text, arg), c2s 0xFD `MarketQueryPacket`, c2s 0xFE `TradeOfferPcMonPacket`, s2c 0xFD
`CommunityPacket` (a tagged union: friends, profile, chat line, guild, guild invite, listing seller
names, price history, sales, market info, market open, trade history). All four opcodes were free in
both directions on every r3 worktree. `TradeStatePacket` gained `expiresInSeconds` and
`TradeOfferView.pcMons` (appended to the codec). Player chat is delivered as `CommunityPacket`
chat lines; the retail `ChatMessagePacket` c2s still works (its type picks the channel) and s2c
carries server notices only.

Server settings (system property, then env): `trade.proximity`/`TRADE_PROXIMITY` (`map` default,
`none`, or a tile count), `trade.requestSeconds` (60), `market.feePermille` (20), `market.minFee` (1),
`market.maxActive` (30), `chat.maxLength` (200), `chat.filterFile`/`OPENMMO_CHAT_FILTER_FILE`
(profanity word list, empty by default), `guild.foundCost` (15000), `guild.maxMembers` (50),
`rate.chat|whisper|tradeRequest|market|social` / `RATE_<KIND>` as `events/seconds`
(defaults 8/10, 10/10, 5/60, 30/10, 20/10, per login account). Dev command `/tp r:b:m x y [facing]`.

## 9. r3-field (round 3: field moves, travel modes, field effects; all additive)

Browser → gateway:

| `t` | fields | notes |
|---|---|---|
| `field` | `action` (lower case, no underscores: `cut` `flash` `rocksmash` `strength` `surf` `fly` `dive` `waterfall` `teleport` `dig` `sweetscent` `escaperope` `bike` `dismount` `itemfinder`), `arg` (bike: 0 Bicycle/Mach, 1 Acro), `bank`+`map` (FLY target, the player's region) | c2s 0xF4 `FieldActionPacket`. The server checks badge + party move + place; refusals come back as `ui.notice`. `fly` without a valid visited target makes the server send `ui.open` `fly`. |
| `step` | optional `jump: true` | the browser predicted a ledge hop (two tiles); the headless client predicts the same. The server decides either way and sends the landing tile back. |

Gateway → browser:
- `world.entities[]` gain, only when not default: `mode` (`mach_bike` `acro_bike` `surf` `underwater`; Kanto's Bicycle is `mach_bike`), `run` (true), `jump` (counter, +1 per ledge hop the server reported: animate a 2-tile hop), `exclaim` (counter, +1 when a trainer spots the player: draw "!" for ~1 s). From s2c 0xE8 `FieldAvatarPacket` and `GbaEntityMovePacket.movementMode` 6 (= ledge hop).
- `world.field` (s2c 0xE9 `FieldInfoPacket`, sent on every map entry and change):
  `{"dark","flash","canRun","canBike","hasBike","strength","canEscape","moves":["surf",...],"fly":[{"bank","map","name"}]}`.
  `dark` = the map needs FLASH (header `requires_flash`), `flash` = FLASH lit this visit; `canRun` = running
  shoes (FLAG_SYS_B_DASH) on a map that allows running; `moves` = field actions the party can use now
  (knows the move, has the badge); `fly` = visited FLY towns.
- `ui.open` `{"seq","kind":"pc"|"pcItems"|"fly"}`: the server asks for a UI (the PC tile: Pokémon storage
  in Pokémon Centers, item storage elsewhere; the FLY map). Act once per new `seq`. The PC panel itself
  belongs to the items branch.

Static assets, map entries gain `"behavior": "maps/<r>/<layout>.beh.bin"` (one byte per tile, a
`TileBehavior` ordinal; names in the new top-level `"behaviors": [...]`), `"allowCycling"`,
`"allowRunning"`, `"requiresFlash"`, and `"borderAnim": [slot...]` (per border cell, the `.anim.json`
metatile slot that animates it, -1 = static). Top level gains `"fieldEffects": {"surfBlob": {"sheet",
"frameW","frameH","count"}, "tallGrass", "longGrass", "shadow", "footprints", "deepFootprints",
"tireTracks", "splash", "ripple", "jumpSplash", "jumpGrass", "dust", "ashPuff", "exclamation"}` (Emerald's
`graphics/field_effects` art, one strip each).

Prediction rules (server `FieldRules`, mirrored in `world.js`): `col.bin` bits 4-7 elevation; a step onto a
tile of another elevation is refused unless either is 0 (or the target 15, a bridge); water is 1, land 3,
so land walkers stop at the shore and surfers hop off onto elevation-3 land; elevation after a step = the
new tile's (15 keeps it). Ledges (`JUMP_*` behaviour ahead, matching direction) hop two tiles; directional
walls (`IMPASSABLE_*`); muddy slopes uphill only on the Mach Bike, bumpy slopes / rails only on the Acro
Bike; `NO_RUNNING` tiles and maps without `allowRunning` walk. A STRENGTH boulder (active) is pushed by
stepping into it (the player stays). Step times: walk 250 ms, run/surf 125, acro 100 (r4), Mach 70, Kanto bike 95,
ledge hop 520.

## 10. r3-present (round 3: presentation, sound, session resume; all additive)

WebSocket:
- `login` gains `token` (base64 remember-me token from an earlier reply; replaces `pass`),
  `remember` (bool: ask the login server for a token on a password login; maps to
  `PasswordLogin.stayLoggedIn`) and `gender` (0 boy, 1 girl; only used when the character is
  created). The `login` reply gains `token` (base64) whenever the login server sent one
  (`SentCredentialsPacket`: password login with `remember`, and every token login — sliding
  expiry). A rejected token fails with `INVALID_SAVED_CREDENTIALS`. The browser stores only the
  token (`localStorage openmmo.session`), never the password.
- `world` gains `female` (bool): the own character's gender (`CharacterInfo.rivalSex`), so the
  browser picks the region's female player sheet. Other players' gender is not known yet.
- Battle `me` / `foe` / `party` entries gain `shiny` (bool). It travels in the battle mon blocks
  (byte after the gender, formerly padding, so non-shiny blocks are byte-identical).

Static assets (manifest):
- `pokemon[dex]` gains `"shiny": {"front", "back"}` (same picture, `gMonShinyPaletteTable`
  palette) and `"iconAnim"` (both party-icon frames, 32x64; cycled every ~0.2 s).
- `"sfx": {"<region>": {"select": "SE_SELECT", "door", "exit", "bump", "ledge", "pcOn", "pcLogin",
  "pcOff", "shop", "save", "shiny", "failure", "success", "itemGet", "keyItemGet", "levelUp",
  "heal"}}` — role → song constant. SE_* songs are in the music index like the MUS_* ones.
- Music index: songs gain `rev` (`midi.cfg -R`, m4a reverb amount). Index gains `"samples":
  {"DirectSoundWaveData_x": profile}` and DirectSound voices gain `"sample": "<that key>"`. A
  profile is measured from the decomp's `.wav`: `dur`, `loop`, `loopAt`, `envDur`, `env` (48-point
  amplitude curve), `pitch60` (Hz at MIDI note 60), and either `re`/`im` (24 harmonics, tonal) +
  `gain`, or `bands` (20 log-spaced band energies, noisy) + `level`. No sample data is shipped.
- `ui.fonts`: `{"pokefirered/normal" | "pokeemerald/normal" | "pokeemerald/narrow" |
  "pokeemerald/small": {"json", "image", "ttf", "family", "baseline", "lineHeight"}}`. `ttf` is a
  TrueType of the glyph pixels (64 units per pixel, 16 px em); `image` an atlas (16x16 cells,
  16 per row, red = glyph pixel, green = shadow pixel); `json`: `{"cell", "baseline",
  "lineHeight", "glyphs": {"<code point>": [glyph index, advance px]}}`. Code points come from the
  decomp charmap; every glyph is also at U+E000 + byte (PK = U+E053, MN = U+E054).
- `ui.windows`: `{"<region>": {"dialog": png, "dialogSlice": [t, r, b, l], "frames": [png...],
  "frameSlice": 8, "arrow": png, "arrowSize": "WxH", "arrowSteps": [[x, y]...]}}` — 9-slice
  `border-image` sources composed from the games' frame tiles; `arrowSteps` are the source
  offsets of the bobbing "more" arrow, one step per 8 frames.

## 11. r4-gaps (round 4: remaining gaps; all additive)

### Surf blob (assets)
- `fieldEffects` gains `surfBlobMay` (Emerald's blob in May's palette) and `surfBlobKanto`
  (FireRed's own 6-frame blob in `player.pal`: down 0/1, up 2/3, left 4/5, the two frames swapped
  every 48 GBA frames). `surfBlob` stays Emerald's blob in Brendan's palette (FldEff_SurfBlob draws it
  in palette slot 0, the player's).

### Acro Bike (Emerald)
- Browser → gateway: `field` `action: "acro"`, `arg` = trick (0 none, 1 wheelie, 2 bunny hop,
  3 side jump, 4 turn jump), c2s 0xF4 `FieldAction.ACRO`. Sent whenever the predicted trick state
  changes; a side jump is `acro 3` + `step` (the server keeps the facing and goes back to 0 itself),
  a turn jump is `acro 4` + `face` + `acro 0`. The server checks every step against the state it
  was told (`FieldRules.step`): bumpy slopes only take a bunny hop (a bike on one is always at least
  in a wheelie), isolated rails only a side jump across them, long rails are ridden along and never
  left sideways except by a side jump, side jumps take no ledges or bumpy slopes.
- `world.entities[]` gain `acro` (`"wheelie"`, `"hop"`, `"side_jump"`, `"turn_jump"`; absent = none)
  from s2c 0xE8 `FieldAvatarPacket` flag bits 3-5.
- Behaviour names: `RAIL` became `VERTICAL_RAIL` (same ordinal); `HORIZONTAL_RAIL`,
  `ISOLATED_VERTICAL_RAIL`, `ISOLATED_HORIZONTAL_RAIL` are appended.
- Manifest objects gain `anims` for sheets with special animations: camelCase anim name → facing
  (`down`/`up`/`left`; right mirrors left) → `[[frame, GBA frames], ...]`, e.g. the Acro Bike's
  `bunnyHopBackWheel`, `standingWheelieBackWheel`, `movingWheelie`.
- Timing: riding 6 GBA frames a tile (100 ms), a rolling wheelie 8 (133 ms), every Acro jump 16
  frames (sJumpY_Low for bunny hops, sJumpY_Normal for side and turn jumps). B held 40 frames on a
  standing wheelie starts the bunny hop; B and a new direction within 4 frames is a side jump (or a
  turn jump when it is the opposite direction).

### Secret bases (Emerald)
- Browser → gateway: `secretbase` `{action: "place"|"putaway"|"packup"|"close", decor, x, y}` (c2s 0xF5
  `SecretBaseActionPacket`; only inside the player's own base). `x` is the decoration's left
  column, `y` its bottom row. The server checks `CanPlaceDecoration` and the PC stock.
- `world.secretBase` (s2c 0xED `SecretBaseStatePacket`, every map entry and change):
  `{inside: "none"|"own"|"visiting", owner, id, mine, decorations: [{id, x, y}], open: [{x, y}],
  ui: {seq, kind: "pc"}}`. `open` = spots on this map whose entrance is open for this player (its
  own base, a friend's base). `ui` asks to open the base PC once per new `seq`.
- A at a spot (bg_event `secret_base`) runs SecretBase_EventScript_CheckEntrance (SECRET POWER in
  the party); entering an open spot warps into the base (its MAP_DYNAMIC exit leads back).
- Manifest `secretBases`: `tiles` (secondary-tileset indices of the atlas cells), `tileAttrs`
  (`[MB name without MB_, layer type]` per cell), `bases` (`"r:b:m"` → `{atlas, group, pcX, pcY,
  width, behaviors}`; atlas row 0 bottom layer, row 1 top layer, 16 px cells), `entrances` (atlas of
  open entrance metatiles) + `spots` (`"r:b:m"` → `[{x, y, id, cell}]`), `decorations`
  (`id` → `{name, category, perm, w, h, tiles|gfx}`).
- Dev: `/givedecor <id|all> [qty]`; `/fielddev` in Hoenn also teaches SECRET POWER.

### Palette animations
- `.anim.json` channels may be palette channels: `dest` = 0x10000 + BG palette slot, `size` 0 (the
  Battle Dome's floor lights: BlendAnimPalette_BattleDome_FloorLights copies one of four palettes
  into slot 8 every 4 frames). Their metatiles are pre-rendered per palette in the same atlas and
  `combos`, so the browser animates them like tile channels with no change.

### Match Call and rematches
- Browser → gateway: `matchcall` `{id}`: `id` -1 asks for the list, a trainer id calls them
  (c2s 0xF4 `FieldAction.MATCH_CALL`, `arg` = id). The call is a dialog: the same-route or
  different-route battle request when the trainer wants a rematch, else its personal chat
  (match_call.c SelectMatchCallMessage).
- `world.matchCall`: `{seq, trainers: [{id, name, route, ready}]}` (s2c 0xE0 `MatchCallListPacket`).
- `world.entities[]` gain `exclaimKind: "double"` when the last emote was the VS Seeker's "!!"
  (`FieldAvatarPacket` flag bit 6). Manifest `fieldEffects.emoticonsKanto`: FireRed's emoticons
  sheet, 16x16 cells; "!!" is pictures 3, 4, 5 (4, 4, 52 GBA frames).
- Rematch battles use the trainer's `trainerbattle_rematch` intro / defeat texts and its rematch
  post-battle line.
