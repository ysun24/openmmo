# feat/world — overworld for our own client

## Built (client only; no server code changed)
- **Map transitions**: door/stair/arrow warps through the server's WarpService flow
  (`MapTransition` → `LoadMap(reloadPlayer, deleteCache)` → `RenderScreen`); seamless map
  connections crossed client-side with the server's MovementService arithmetic
  (`net/MapConnections.kt`), connected maps rendered at the edges, server `GbaEntityMove` with a
  new bank/map relocates the entity.
- **NPCs**: `NpcSpawn` / `NpcUpdate` / `EntityFaceTurn` handled; sprites from decomp
  `object_events` graphics (`gfx/ObjectEventGraphics.kt`) with walk frames; player walk cycle;
  scripted movement (`DialogData` applymovement steps) animated with the server's timings.
- **Interaction**: Z/Space sends `EntityInteract` (npc in front, or across a counter) or
  `TileInteract`; server dialogs (`DialogAction`) resolved from `codegen/dialog/<region>.json`,
  paginated into a GBA text box, advanced, yes/no and pick choices answered with
  `DialogActionResponse` (`world/DialogText.kt`, `gfx/OverworldOverlay.kt`).
- **Mart**: `ShopCatalog` opens a buy window with money box; buy (`ExchangeItemRequest`) and sell
  (`ShopSellRequest`) update bag/money from the server's delta packets. Item ids are the wire ids
  (5000 + pokeblack index).
- **Rendering**: metatile layer priority (top layer above sprites unless COVERED), border blocks
  outside the map, map name label.
- Slash commands typed in chat (`say /story …`) are sent as `ChatMessageSendPacket`, the packet the
  server parses commands from.

## How to test
Servers as in the brief (LOGIN 2146, GAME 7817). Script commands added (`ScriptCommands.kt`):
`interact|z`, `next`, `b`, `choose N`, `up`, `down`, `buy ITEM QTY`, `sell ITEM QTY`, `close`,
`wait MS`, `idle [MS]`, `skip [MS]` (mash A through a cutscene), `go DIR N`, `snap FILE`, `dialog`,
`bag`, `ui`.

```sh
C="client/build/install/client/bin/client --port 2146 --key keys/build/game.public.pem"
# fresh char: 2F -> stairs -> 1F -> door -> Pallet; Oak's cutscene into the lab
$C --user friend --pass bulbasaur123 --name Leaf --script "go d 4; go w 4; go a 1; wait 2000; go s 3; go a 2; go s 3; go a 4; go s 1; wait 2000; snap pallet.png; go d 6; go w 8; skip 150000"
# dev char (permissions |= 0x100 in game db): Oak's parcel, then the Viridian mart
$C --user yicheng --pass pikachu123 --name Red --script "say /story pokedex; wait 3000; interact; skip 90000"
#  (then put Red in 5:3 at (4,3)) "A; interact; wait 1500; next; wait 1500; buy potion 3; wait 1500; bag; sell potion 1; wait 1500; bag"
```
Verified end to end with two concurrent clients (Red and Leaf see each other walk in Pallet Town,
Red talks to the sign lady). Snapshots: `snapshots-world/*.png` (house 2F, 1F, Pallet, Route 1,
Oak cutscene, lab, mart dialog, shop, after buying, two-player views, connection crossings).

## Not done
- Battles are another branch: the rival battle on leaving the lab starts on the server and this
  client cannot finish it (that character stays locked until battle support lands).
- Dialog string variables (`bufferspeciesname` etc.): the server's scripts send no
  `TextPokemonSpeciesArg`, so "received the ___" is blank. Server-side gap.
- Only marts whose clerk script is ported on the server open (Viridian; Pewter etc. are `TODO`).
- Sell has no in-window bag list; it is driven by `sell ITEM QTY` / script only. GUI shop is
  buy-cursor + status line.
- No automated client tests (client module has none); verification is scripted runs + snapshots.

## Shared files edited (small, additive)
- `client/.../Main.kt` — wires `ScriptCommands`, npc lines in status.
- `client/.../net/GameClient.kt` — `overworld` member, step blocked while a box/shop/transition
  owns the player, edge crossing, LoadMap/GbaEntityMove map switches, `say` routes `/commands`,
  unhandled packets go to `OverworldClient`.
- `client/.../world/WorldState.kt` — `graphicsId`/`visible` on `Entity`, `overworld` view,
  `relocate`/`switchMap`/`update`.
- `client/.../gfx/GameWindow.kt` — Z/Space/X/Backspace/menu cursor keys.
- `client/.../gfx/WorldRenderer.kt`, `gfx/PretData.kt` — connections, border, layers, npc sprites.
- Server: none.
