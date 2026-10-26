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
| `battle.fight` | `move` (0-3) | BattleClient.fight |
| `battle.switch` | `slot` (party index 0-5) | |
| `battle.run` / `battle.ball` | – | |
| `battle.learn` | `slot` (0-3) or `null` to skip | |
| `duel` | `name` | BattleClient.challenge |
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
  "dialog": {"speaker":"OAK","lines":["Hey! Wait!","Don't go out!"],"lastPage":false,
             "choices":["YES","NO"],"cursor":0} | null,
  "shop": {"offers":[{"item":5017,"name":"POTION","price":300}],"cursor":0,"status":""} | null,
  "money": 3000, "bag": [{"item":5017,"name":"POTION","qty":3}],
  "locked": false       // transitioning || scriptLocked || inScript
},
"party": [ {"slot":0,"dex":4,"name":"CHARMANDER","nick":null,"level":15,"hp":39,"maxHp":39} ],
"battle": {             // null when no battle
  "wild":true,"opponentName":"","phase":"ACTION|FORCED_SWITCH|WAITING|ENDED","turn":3,
  "me":  {"dex":4,"name":"CHARMANDER","level":15,"hp":30,"maxHp":39,
          "moves":[{"name":"SCRATCH","pp":33,"maxPp":35}]},
  "foe": {"dex":19,"name":"RATTATA","level":3,"hp":0,"maxHp":15},
  "party":[{"dex":4,"name":"CHARMANDER","level":15,"hp":30,"maxHp":39}],
  "log":["CHARMANDER used SCRATCH!"],"result":null,
  "learn": {"move":"EMBER","current":["SCRATCH","GROWL"]} | null
},
"invite": {"from":"Friend"} | null,     // pending duel invite
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

Entity ids are strings (they are 64-bit). Names come resolved from the gateway (codegen tables),
so the browser needs no name tables.

## 4. Static assets (`web/static/assets/`, generated by `:web.assets:run`)

```
assets/manifest.json
  {"version":1,
   "maps": {"0:3:0": {"name":"PalletTown","width":24,"height":20,
                      "bottom":"maps/0_3_0.bottom.png","top":"maps/0_3_0.top.png",
                      "collision":"maps/0_3_0.col.bin",
                      "border":"maps/0_3_0.border.png",          // 2x2 (or wxh) metatile tile image, 16px units
                      "connections":[{"dir":"up","offset":0,"map":"0:3:1"}],
                      "music":null}, ...},
   "objects": {"23": {"sheet":"objects/23.png","frameW":16,"frameH":32,
                      "frames":{"down":0,"up":1,"left":2,"walkDown":[3,4],"walkUp":[5,6],"walkLeft":[7,8]},
                      "mirrorRight":true}, ...},
   "players": {"male":"objects/player_male.png","female":"objects/player_female.png"},  // same frame layout
   "pokemon": {"4":{"front":"pokemon/4.front.png","back":"pokemon/4.back.png","icon":"pokemon/4.icon.png"}, ...},
   "ui": {"font":null}}
```

- Map images are full-map PNGs at 1 px = 1 GBA pixel (16 px per metatile). `bottom` = backdrop +
  bottom layer (+ top layer for normal metatiles); `top` = only the tile layer that must cover
  sprites (metatile layer type "covered"/"split"), transparent elsewhere. Draw order:
  bottom → entities sorted by y → top.
- `col.bin` = width*height bytes, row-major: bit0 = blocked (collision != 0),
  bits 4-7 = elevation. Used only for client-side prediction; the server stays authoritative.
- Keys are `region:bank:map` exactly as the server sends them (region 0 → pokefirered,
  region 1 → pokeemerald). Export every map of both games.
- `gfx` ids are the ids the server puts in NPC spawn packets; the exporter resolves them with the
  same table the desktop client uses (`client/.../gfx/ObjectEventGraphics.kt`).

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
