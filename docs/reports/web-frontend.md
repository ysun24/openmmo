# feat/web-frontend — browser client + mock gateway

## Built
- `web/static/` — vanilla ES-module client, no build step, no frameworks.
  - `index.html`, `css/main.css`
  - `js/main.js`: login flow, layout, main loop (requestAnimationFrame), input routing, fps probe (`window.__fps`, `window.__game`)
  - `js/net.js`: WebSocket JSON transport, 5 s ping/pong RTT, state-section merge, reconnect (re-sends the in-memory login; the password is never persisted)
  - `js/assets.js`: manifest, image cache, map preloading (current map + connections), col.bin, `region:gfx` object lookup, per-region players
  - `js/world.js`: self prediction (col.bin + entity tiles, 250/125 ms walk/run, turn-in-place on a fresh press, seq/ackSeq reconciliation: a step is snapped only once the server has acked it and disagrees), interpolation of other entities, seamless connection crossing (re-bases coordinates into the new map, no fade), fade + preload on warps
  - `js/render.js`: 240×160 logical canvas, integer scale in *device* pixels (crisp at any DPR), letterboxed, pixelated. Order: border pattern (anchored to the map origin, so cell = (x mod w, y mod h)) → bottom of connections + current map → y-sorted entities (walk / runWalk frames, mirrored right) → top layers → fade. Only the visible source rect of each map image is drawn; there is no per-frame allocation besides the reused draw list.
  - `js/input.js`: keyboard (arrows/WASD, Z/Space = A, X/Esc = B, Shift = run, Enter = chat, Tab = menu); touch: D-pad (pointer capture, slide between directions), A/B, RUN toggle, CHAT, MENU, multi-touch, no scroll/zoom
  - `js/ui/*`: chat (log + input, `/` commands), dialog (pages, ▼, choices with server cursor), shop (buy/sell + qty, server cursor), battle (GBA layout: front/back sprites, HP bars, FIGHT moves with PP, POKéMON switch / forced switch, BAG → ball, RUN, log, learn-move prompt, result + Continue), duel invite prompt, player popup (tap a player or press A facing one → Duel / Trade / Chat), trade (request/accept/decline, both offers, mon toggles, items, money, ready/confirm/cancel, completed/closed), market (browse with kind/id/min/max/sort + paging, my listings + cancel, sell from party/bag with price), party & bag view, menu. All panels navigate with arrows/D-pad + A/B, or by touch.
- `web/dev/mock-gateway.js` (Node 18, `ws` 8.18.3 pinned, package-lock committed, node_modules gitignored): serves `web/static` + `/ws` on 127.0.0.1, speaks the PROTOCOL.md §2/§3 contract with a scripted world: Pallet Town, wandering NPCs (woman, fat man), Oak (talk → YES/NO → trainer battle), a clerk (→ shop), "Friend" walking a loop, periodic chat, collision-respecting moves from `col.bin`, connections, ackSeq echo, configurable latency, 33 ms coalesced section diffs, login rate limit, Origin check, 30 s idle close. Dev commands: `/mock battle|trainer|learn|invite|trade|tradereq|market|dialog|shop|warp|home|friend|latency <ms>`, `/testbattle`, `/givemoney n`.
- `web/dev/gen-placeholder-assets.js` (+ `png.js`): placeholder assets in the §4 layout from the pret firered layouts. Use them only when the real assets are absent.
- `docs/web/PROTOCOL.md` §4 replaced with the assets branch's updated §4 (as instructed).

## How to run
```
ln -sfn /local/home/yichengs/projects/OpenMMO-web-assets/web/static/assets web/static/assets   # gitignored
cd web/dev && npm ci && node mock-gateway.js --port 8381 --latency 80
# open http://127.0.0.1:8381/   (any user/pass; pass "wrong" = INVALID_PASSWORD)
# ?ws=ws://host:port/ws to point at another gateway, ?touch=1 forces touch controls, ?fps=1 shows fps
```
With the real gateway the client needs no changes: it connects to `/ws` on the host that served it.

## How it was verified
I scripted runs with `webshot.sh`, both desktop 960×640 and `--mobile` (Pixel 7), plus a 915×412 landscape run with `?touch=1`. I looked at every screenshot and fixed the problems they showed: arrays rendering as text, the chat overlapping the battle and dialog, the learn-move list overflowing, battle text overflowing, and D-pad placement.
- Walking: mid-step shots show sub-tile camera movement. Run (Shift) does 5 steps in 0.7 s.
- Connections: walking north from Pallet into Route1 is seamless (map key 0:3:19, no fade).
- Warp: fade sampled per frame goes 1 → 0 over about 250 ms, once the new map has loaded.
- Prediction: at 300 ms latency, I cleared `col.bin` on the client, then walked into a blocked tile. The server rejected the step and the client snapped back to the server tile (12,10). The `snaps` counter went from 1 to 2.
- Each UI was exercised end to end against the mock: dialog with YES/NO, shop buy, the Oak trainer battle, wild battle, learn prompt, duel invite accept, trade request + offer + ready + confirm → completed, trade request decline, market browse + paging + sell tab, menu, chat.
- On mobile: D-pad taps and hold (synthetic touch pointer events), the CHAT command, battle via the A button, menu and market.
- Frame time (`__fps`): 60 fps, average work 0.2–0.5 ms per frame, max frame 16.8 ms on desktop. On mobile emulation, one 66 ms frame happened during the battle DOM build.

Screenshots are in `$KIROCREW_SCRATCH/shots/fe/` (scratch: deleted when the session ends): `conn-mid.png`, `conn-route1.png`, `warp-done.png`, `r-world.png`, `dialog1/2.png`, `shop.png`, `chat.png`, `r-duel.png`, `learn.png`, `trade1/2/3.png`, `r-tradereq.png`, `r-invite.png`, `menu.png`, `market1/2/3.png`, `rec-snap.png`, `m-world.png`, `m2-world.png`, `m-walk-mid.png`, `m-fight.png`, `m-battle2.png`, `m-market*.png`, `m2-learn.png`, `l-world.png`, `l-learn.png`.

## Known gaps vs the contract / not done
- Not tested against the real Kotlin gateway; that branch is being built in parallel. Field names follow PROTOCOL.md exactly.
- Hoenn (region 1) is only exercised through the asset lookup code. The mock world is Kanto only.
- Elevation bits in col.bin are ignored for prediction (only bit0). Ledges, water and bikes are not predicted; the server corrects these by snapping.
- Players use `objects["region:gfx"]` when that key exists, otherwise `players[region]` (female iff gfx == femaleGfx). How the gateway reports player gfx is not specified in the contract yet.
- Other entities: the `run` state is not in the contract. Interpolation duration is estimated from the time between updates (110–250 ms), and run frames are only chosen for short steps.
- The battle "active mon" in the switch list is guessed by matching name, level and HP, because `battle.party` has no active index.
- There are no player name tags on the canvas. A tapped player gets an arrow marker plus a popup.
- No music, battle animations or a pixel font (ui.font is null; monospace is used).
- No automated JS unit tests: I checked syntax with `node --check` and verified everything else through the screenshot runs.

## Files outside my area touched
- `.gitignore`: added `web/static/assets` (the symlink), `web/dev/node_modules/`, `web/dev/placeholder-assets/`.
- `docs/web/PROTOCOL.md`: §4 synced from the assets branch.
