# Player-to-player trading (branch `feat/trade`)

## What was built

**Protocol** (`protocols.game/.../packets/trade/PlayerTradePackets.kt`, registered at the end of `GameProtocol`)
- Reused retail packets: c2s `TradeActionPacket` 0x50 (action byte: 1 accept, 2 decline, 3 ready, 4 confirm, 5 cancel) and c2s `TradeSelectMonPacket` 0x52 (toggle a party slot on or off the table).
- New packets (we own both ends, opcodes were free): c2s `TradeRequestPacket` 0x5C (target name), c2s `TradeOfferItemPacket` 0x5D (kind item/money, id, amount; 0 removes), s2c `TradeStatePacket` 0xEC (the whole window: phase, partner, both offers with mon summaries, both stages, message). The server sends the full state after every change.

**Server** (`server.game/.../services/trade/`)
- `TradeService`: request → accept/decline → open window. Each side offers up to 6 party monsters, bag items and money. Any change to either offer resets both stages. Confirming takes two steps: both sides must be READY before either can CONFIRM, and the swap runs once both have confirmed. Cancel, decline and disconnect close the trade on both sides. Guards run on request and again at commit: target online, not yourself, not already trading, not in a battle, not in a script/dialog (a script rollback would restore the old party). Offers are checked when made (slot exists, item exists and isn't a key item (price 0), quantity and money held, no negatives).
- `TradeSwap`: a pure function that re-validates both offers against the current characters and builds the new aggregates. Ownership moves to the receiver and the OT stays the same. The giver's party slots are compacted. Received monsters go into the party while there's room, the rest go to the PC. A player may not end up with an empty party, so the last party monster can't be traded away. Items and money move, empty stacks are dropped, and counter overflow is refused.
- Atomic commit: `CharacterStore.commitPair` holds both characters' flush locks (in id order), so no purchase, catch or other durable write can interleave. It then calls the new `CharacterRepository.saveTrade`, which writes both characters in **one jOOQ transaction**. Traded rows are moved with an `UPDATE ... WHERE id` (owner, container and slot change). They are not deleted and re-inserted, because the existing `batchMerge` also matches on the (owner, container, slot) key. Memory is only updated after the commit succeeds. Both clients get COMPLETED plus a full party/PC/bag/money resync.
- Dev commands in `services/command/GiveCommands.kt`: `/givemon <dexId> [level]` and `/giveitem <id> [qty]`. Both require the `DEVELOPER` permission (0x100).

**Client**
- `net/TradeClient.kt`: trade actions and state, plus party, PC, bag and money from `PokemonContainerPacket`, the bag stacks packet and `LocalPlayerStatePacket`. `GameNames` gives species and item names from `:codegen`.
- `gfx/TradeView.kt` paints the window using pret front sprites, with palette index 0 made transparent. `gfx/TradeWindow.kt` is the Swing dialog: party toggle buttons, item and money fields, and Request/Accept/Decline/Ready/Confirm/Cancel. It opens automatically on a request, or with the T key. Typing `/...` in the chat box now sends a server chat command.
- `TradeCommands.kt` adds these script commands: `trade NAME`, `trade-accept`, `trade-decline`, `offer-mon SLOT`, `offer-item ID QTY`, `offer-money N`, `trade-ready`, `trade-confirm`, `trade-cancel`, `trade-state`, `party`, `bag`, `wait-trade PHASE [secs]`, `sleep S`, `/any command`. When a trade is open, `--snapshot` draws the trade window on top of the world.

## How to test

Set up per BRIEF, plus a second account `friend`/`bulbasaur123`. Both characters need DEVELOPER for the give commands (dev only):
`update characters set permissions = permissions | 256;` in the game DB.

```
C="client/build/install/client/bin/client --port 2126 --key keys/build/game.public.pem"
$C --user yicheng --pass pikachu123 --name Ash --script "/givemon 1 5; /givemon 4 7; /givemon 7 6; /giveitem 5017 10; party"
$C --user friend --pass bulbasaur123 --name Gary --script "/givemon 25 12; /givemon 133 9; /giveitem 5004 5; party"
# concurrently:
$C ... --name Ash  --script "sleep 1; trade Gary; wait-trade OPEN 20; offer-mon 1; offer-item 5017 3; trade-ready; sleep 3; trade-ready; sleep 3; trade-confirm; wait-trade COMPLETED 20; party; bag"
$C ... --name Gary --script "wait-trade REQUEST_RECEIVED 20; trade-accept; offer-mon 0; sleep 2; offer-money 1000; sleep 1; trade-ready; sleep 4; trade-confirm; wait-trade COMPLETED 20; party; bag"
```

Verified end to end with two concurrent client processes against local servers:
- **Completed trade:** Charmander + 3 Potions for Pikachu + 1000 money. Gary's late money offer reset Ash's READY back to NONE, both sides re-readied, then confirmed. A fresh login of each account and a direct DB query show the result: Ash has Bulbasaur, Squirtle, Pikachu (OT Gary), 7 Potions, 31000 money. Gary has Eevee, Charmander (OT Ash), 3 Potions, 29000 money. Same monster row ids, `owner_id` moved.
- **Guards and cancel:** each of these was refused with a message: invalid slot 9, offering more money or Potions than held, confirming before ready, and Gary offering his whole party for money ("cannot trade away their last party monster"). After the refusal the trade stayed open with stages reset. `trade-cancel` then closed it on both sides with nothing changed.
- **Disconnect:** Ash disconnected after both were READY and Ash had CONFIRMED. Gary got CLOSED "Ash left the game.", and the DB was unchanged.
- **UI:** `docs/trade/trade-ui.png` shows the open window: both offers with sprites, items, money, and the stages "Confirmed" and "Ready".

Tests: `:server.game:test` 270 passed (new: `TradeSwapTest` 8, `TradeServiceTest` 11, `TradeRepositoryIT` 2 against a real Postgres, covering the ownership move and rollback of a failing half). `:protocols.game:test` 80 passed (new `PlayerTradePacketsTest`). `:client:build` passes.

## Not done
- No proximity requirement: any two online players can trade.
- Only party monsters can be offered, not PC ones. There's no PC capacity limit, because the server has none.
- A trade request doesn't expire on its own (cancel/decline/disconnect only).
- The trade window doesn't block other actions (walking, shops). The final commit re-validates everything, so nothing can be duplicated.
- There's no in-game way to grant DEVELOPER; for testing it's set in the DB as above.

## Shared files edited (all additive)
- `protocols.game/.../GameProtocol.kt`: 1 import + 3 registrations at the end (0x5C, 0x5D c2s; 0xEC s2c).
- `server.game/.../handler/GameAppHandler.kt`: `TradeService` injected, 4 handlers, `tradeService.onDisconnect` in `onInactive`.
- `server.game/.../di/ChatCommandModule.kt`: binds `GiveMonCommand` and `GiveItemCommand`.
- `server.game/.../storage/CharacterRepository.kt`: `saveTrade` (interface default + jOOQ impl). `CharacterStore.kt`: `commitPair`.
- `client/build.gradle.kts`: added `:codegen` dependency (names). `client/.../net/GameClient.kt`: `trade` field, `send()`, handlers for `TradeStatePacket`, `PokemonContainerPacket`, `BattleSidePartyPacket`, `LocalPlayerStatePacket`. **Merge note:** if another branch also registers one of the last three, merge them into one handler that calls both, because a typed handler holds one callback per type.
- `client/.../Main.kt` (trade commands fallback, trade overlay in snapshot, `TradeView` passed to the window) and `client/.../gfx/GameWindow.kt` (trade window, T key, `/` commands).
