# web.gateway — branch `feat/web-gateway`

The browser ⇄ WebSocket JSON ⇄ TCP bridge from `docs/web/PROTOCOL.md`: an HTTP static server
plus `/ws` on Netty. Each socket runs one headless `GameClient` from the `:client` module and
mirrors its state as JSON.

## Built

Gradle module `:web.gateway` (`web/gateway/`, registered in `settings.gradle.kts`). Main class is
`de.fiereu.openmmo.web.MainKt`, with the `application` plugin.

| file | what |
|---|---|
| `Main.kt` | Flags: `--host 127.0.0.1 --port 8080 --static web/static --login-host 127.0.0.1 --login-port 2106 --key keys/build/game.public.pem --decomp decomp [--allow-origin URL,...]` |
| `Gateway.kt` | Server bootstrap (bound to `--host`, 127.0.0.1 by default). Shared resources: **one** `gameGroup` event-loop group for every login/game connection, and a virtual-thread executor for blocking client calls. Pipeline: `IdleStateHandler(30s)` → `HttpServerCodec` → `HttpObjectAggregator(64K)` → `OriginGuard` → `WebSocketServerProtocolHandler("/ws", maxFrame 64 KiB, no extensions)` → `WebSocketFrameAggregator` → `WsSession` → `StaticFileHandler`. |
| `StaticFiles.kt` | Path resolution: URL-decoded; refuses `..`, dot files, `\`, NUL and symlinks that leave the root; a directory serves its `index.html`. Also MIME table, GET/HEAD only (405 otherwise), `Cache-Control: no-cache` for html/js/css/json and `public, max-age=604800` under `assets/`, `Last-Modified`/304, `nosniff`. |
| `WsSession.kt` | One per socket. `ping`→`pong`. Login is limited to 5 per minute per socket (`RateLimiter`) and runs `LoginClient` then `GameClient.connect` off the netty loop. Other messages go through a per-socket `SerialExecutor`, which keeps them in order and off the event loop. Unknown or malformed input gets `{"t":"error"}` and the socket stays open. Closes after 30 s of silence. Closing the socket calls `GameClient.close()`; losing the game connection sends an error and closes the socket. |
| `Dispatcher.kt` + `GameSession.kt` | Every message type in PROTOCOL.md §2 is validated (types, ranges, text ≤256, names ≤32) and mapped to `GameClient` / `OverworldClient` / `BattleClient` / `TradeClient` / `MarketClient`. A client API that returns an error string (`"not your turn"`) is forwarded as `error`. |
| `StateJson.kt` | §3 sections, built from one read of `WorldState.Snapshot`, `OverworldState.View`, `BattleState`, `TradeStatePacket`, party, bag and `MarketClient.State`. `SectionDiffer` handles per-section change detection (a section that empties is sent as `null`; the first state after login has every section). `MovementTracker` sets `moving`. |
| `Names.kt` | Names come from the same tables the desktop client uses: `BattleNames` (codegen species and moves), codegen `ItemRegistry` with `item_names.json` as fallback, and `PretData.mapName` for `world.map.name`. Party `maxHp` uses the gen 3 formula over codegen base HP + IV/EV, the same formula the server's `StatCalculator` uses. |
| `web/static/index.html` | Placeholder only. The real frontend is on another branch. |

Push timing: every `onChange` from the clients calls `requestFlush()`. That schedules a flush on
the socket's event loop no earlier than 33 ms after the previous send, so there is at most one
`state` per 33 ms and an idle socket gets an immediate push. A 33 ms ticker re-flushes while
anything is dirty or `moving`. If the socket is not writable, sends are held back until it is,
and they are never queued without limit. `ackSeq` is set after `GameClient.step`.

PROTOCOL.md changes (all additive): new `battle.close`; `a`/`b`/`step` also leave an ENDED
battle; the list of login error codes; the "game connection closed" error; a "Gateway notes"
block covering `ackSeq` semantics, `gfx:null` for players, hidden entities omitted, the `moving`
window, when CLOSED trades are shown, and that `market.money` = `ui.money`.

## How to run

```sh
export JAVA_HOME=~/.jdks/jdk-25.0.5+8
./gradlew --no-daemon -Porg.gradle.java.installations.paths=$JAVA_HOME :keys:generateGame installDist -x test
web/gateway/build/install/web.gateway/bin/web.gateway --port 8080 --login-port 2106 \
    --static web/static --key keys/build/game.public.pem --decomp decomp
# tests
./gradlew ... :web.gateway:test                       # unit
python3 web/gateway/test/e2e.py --ws ws://127.0.0.1:8080/ws \
    --a yicheng:pikachu123:Ash --b friend:bulbasaur123:Gary   # needs running servers + gateway
```

The e2e script needs both characters to have `permissions | 256` (for `/givemon`, `/giveitem`,
`/givemoney` and `/testbattle`). It is re-runnable: it handles leftover state from earlier runs,
such as the character's floor, bag and money.

## How verified

Setup: login 2156, game 7827, gateway 8180, postgres containers `openmmo-wgw-login-db` (20511) and
`openmmo-wgw-game-db` (20521). Everything was bound to 127.0.0.1.

**Unit tests:** `:web.gateway:test`, 20 tests, all passing.
- `DispatcherTest`: every §2 type reaches its action with the right arguments; bad and missing fields, out-of-range values, unknown types and client API errors.
- `StateJsonTest`: exact JSON for the world, chat, ui, dialog paging and choices, party, battle, learn, invite, trade (open and closed) and market sections; differ behaviour; movement window; the envelope.
- `TransportTest`: traversal, encoded traversal, dot files, symlink escape, NUL, bad escapes; MIME types and cache headers; the Origin rule; the 5/min limiter; login error codes.

`:client:build` and the spotless checks pass. The client module has no tests of its own.

**End to end:** `web/gateway/test/e2e.py`, a hand-written RFC 6455 client using only the Python
stdlib, run against the real servers. It printed `ALL OK` on the final run; the full output is
summarised here:

```
== transport
  OK  foreign Origin refused (handshake refused: b'HTTP/1.1 403 Forbidden')
  OK  errors without closing: ['not logged in', 'malformed message', 'not logged in']; pong={'t': 'pong', 'n': 7}
  OK  bad logins: ['INVALID_PASSWORD' x5, 'RATE_LIMITED']
== login
  OK  Ash: login ... first state has every section (map={... 'name': 'PalletTown_PlayersHouse_2F', 'width': 12, 'height': 9}, pos=10,2)
  OK  Gary: login ... first state has every section
  OK  Ash sees Gary as entity: {'id': '2412313315698184192', 'kind': 'player', 'name': 'Gary', 'gfx': None, 'x': 6, 'y': 6, 'facing': 'down', 'moving': False, ...}
== walk   (20 steps with seq; each waits for ackSeq; position, facing and moving are checked)
  OK  Gary sees Ash at (10,3) matching Ash's own position
  OK  burst of 8 run steps acked (ackSeq=20028) in 34.2 ms
   step->ackSeq latency over 20 steps: min 0.38 ms, median 0.55 ms, p90 0.75 ms, max 31.20 ms
== chat
  OK  Gary chat tail: [..., 'Ash: hello from the web gateway']
== party   (via /givemon)
  OK  Ash: party [('CHARMANDER', 15, 38, 38)]     OK  Gary: party [('BULBASAUR', 15, 40, 40)]
== wild battle   (/testbattle 19 3, then battle.fight until result)
  OK  Ash: battle started wild=True me=CHARMANDER moves=[SCRATCH 35/35, GROWL 40/40, EMBER 25/25, SMOKESCREEN 20/20] foe=RATTATA L3 14/14
  OK  Ash: wild battle over, result=VICTORY ... log tail=['Foe RATTATA fainted!', 'CHARMANDER gained 24 EXP. Points!', 'You won!']
== duel   (duel -> invite -> duel.answer accept -> both fight)
  OK  Gary invite: {'from': 'Ash'}
  OK  Ash: duel over, result=VICTORY      OK  Gary: duel over, result=DEFEAT
== trade   (request/accept, 700 money vs 2x Potion, ready/confirm on both)
  OK  trade done: {'phase': 'COMPLETED', 'message': 'Trade complete.', ...}; Ash money 56944 -> 56244, Ash got +2 Potion
== market   (Ash listItem Potion @111 -> Gary search item 5017 -> buy)
  OK  Gary bought: log='BUY ok: Bought 1x Potion for 111.' money 33756 -> 33645; Ash log=['SOLD ok: Sold 1x Potion for 111.']
== warp + npcs   (walk into the house stairs both ways)
  OK  Ash warped PalletTown_PlayersHouse_2F -> PalletTown_PlayersHouse_1F
  OK  Ash on PalletTown_PlayersHouse_1F; npcs: [(88, 8, 4, 'left')]      # Mom, with her gfx id
  OK  Ash back on PalletTown_PlayersHouse_2F
== summary
   min gap between world updates: 33.0 ms (cap is 33 ms)
ALL OK
```

Manual checks:
- An idle socket is closed by the gateway after exactly 30.0 s.
- A text frame over 64 KiB closes the socket.
- `POST /` returns 405.
- `/../x` and `/%2e%2e/%2e%2e/etc/passwd` return 404.
- `index.html` is served with `no-cache` and `text/html; charset=utf-8`.
- After about 15 sessions had connected and closed, the gateway JVM had no `overworld-anim` threads left (the new `GameClient.close()` takes care of them), and the game server logged a disconnect for every closed socket.

### Latency

Step round trip, measured as browser `step` sent → `state` carrying `world.ackSeq ≥ seq` received
(localhost, Python client):
- Median **0.55 ms**, p90 0.75 ms, min 0.38 ms over 20 walking steps spaced 270 ms apart. An earlier run gave a median of 1.2 ms.
- The maximum, ~31–33 ms, is the first step after a push: the 33 ms coalescing cap holds it until the frame boundary. A burst of 8 run steps sent back to back was acked in a single coalesced message after 34 ms.

`ackSeq` acknowledges that the gateway's `GameClient` applied (predicted) the step. The game
server's verdict comes separately. When the server rejects a step (walls, stairs in the tiny
house rooms), the position reset shows up one server round trip later; in the walk log above that
is the "before" position of the next step. The browser should reconcile against both, as
PROTOCOL.md §5 describes.

## Client-module changes (additive)

- `OverworldClient.shutdown()` stops its per-instance `overworld-anim` timer thread.
- `GameClient.close()` does `disconnect()` + `overworld.shutdown()`. Without it, every browser session would leak one thread.

Nothing else in `:client` changed. Everything the gateway needed was already public.

## Not done / notes

- `ui.dialog.speaker` is always `null`. The client's `DialogView` has no speaker.
- `battle.turn` is whatever `BattleState.turn` holds; `BattleClient` never increments it.
- The e2e test does not cover these, although they are wired and unit-tested at the dispatch level:
  - shop buy/sell (needs a mart scene)
  - `trade.mon`, `market.listMon` / `market.cancel`
  - `battle.switch` / `run` / `ball` / `learn`
- `TradeClient.onState` prints each trade state to stdout, so it shows up in the gateway log.
- No authentication beyond the game login itself. The gateway is meant to sit on 127.0.0.1, or behind a TLS reverse proxy listed in `--allow-origin`. It does not terminate TLS itself.

## Files outside `web/` touched

- `settings.gradle.kts`: include `:web.gateway` with its project dir set to `web/gateway`.
- `client/.../net/GameClient.kt` and `client/.../net/OverworldClient.kt`: the two additive methods above.
- `docs/web/PROTOCOL.md`: the additions listed above.
