# Global market (GTL-style), branch `feat/market`

## What was built

Server (`server.game`)
- `db/migration/V7_4__create_market_listings.sql` adds `market_listings`: id, seller, kind (POKEMON/ITEM),
  pokemon_id or item_id+quantity, denormalised dex_id, price, created/expires, status
  (ACTIVE/SOLD/CANCELLED/EXPIRED), buyer, sold_at, closed_at.
  Versioned 7.4 so it won't collide with another branch's V7.
- Escrow model:
  - Listing a pokemon moves its row to container `GTS`, and the character loader skips GTS rows,
    so the pokemon can't be used, traded or relisted. Party slots get compacted afterwards.
  - You can't list your last party pokemon.
  - Listing an item takes the quantity out of `character_items`; the listing row holds it.
- `storage/MarketRepository.kt`: every action runs as one transaction with `SELECT … FOR UPDATE`,
  locking the listing first and then the characters in id order.
  - Buy checks: still active, not expired, not your own listing, enough money, seller balance won't overflow.
  - Buy debits the buyer, credits the seller straight into `characters.money` (works when the seller
    is offline) and delivers the goods (party if there's room, otherwise PC).
  - Cancel and expiry return the goods to the seller.
  - Search filters by kind, species/item ids and price range, sorts by price asc/desc or newest, and pages 20 at a time.
- `CharacterStore.withExternalWrite(ids) { … }` (new) keeps the in-memory cache consistent:
  1. Locks and flushes the cached characters involved.
  2. Runs the DB transaction.
  3. Re-reads money, monsters and bag into both the live copy and the persisted snapshot, so a later
     flush can't write back the old balance or delete an escrowed monster.

  `getOrLoadCharacter` now loads under the same lock.
- `services/MarketService.kt` handles the packets.
  - It refuses actions while you're in a dialog or a battle.
  - It pushes results, money and inventory to the buyer, and a SOLD notice to the seller if they're online.
  - Expired listings are returned lazily before each search and by a 60 s sweep. The default lifetime
    is 7 days; override it with `-Dmarket.listingSeconds` or `MARKET_LISTING_SECONDS`.
- `services/command/GiveCommands.kt`: `/givemon <dex> [lvl]`, `/giveitem <id> [qty]`, `/givemoney <n>`.
  These need the DEVELOPER permission (`characters.permissions | 256`).

Protocol (`protocols.game`)
- Existing packets reused:
  - `CreateMarketListingPacket` (0x9A): kind 0 = pokemon (entityId = pokemon id), 1 = item.
  - `GtlSearchPageRequestPacket` / `GtlSearchPagePacket` (0x9B):
    - Kind POKEMON, ITEM or OWN_LISTINGS.
    - `categoryId` is the sort (0 price asc, 1 price desc, 2 newest).
    - Ids go in the SPECIES filter (also used for item ids); prices use the MIN_PRICE/MAX_PRICE raw filters.
  - `GtlConfirmPurchasePacket` (0x9C) is buy.
  - `GtlListingCancelPacket` (0xA1) is cancel; the id is sent as a decimal string.
  - `CurrencyBalancePacket` (0xF4) carries money.
- New OpenMMO-only packets in `packets/market/MarketPackets.kt`:
  - `MarketInventoryRequestPacket`, c2s 0x6E.
  - `MarketResultPacket`, s2c 0xCC: the outcome of CREATE/BUY/CANCEL, plus SOLD/EXPIRED pushes.
  - `MarketInventoryPacket`, s2c 0xCD: money, party records, PC count, bag.
- `GtlSearchListing` codec: own-listings rows are now decoded with the board row layout (it used to throw).

Client (`client`)
- `net/MarketClient.kt`: state, actions and reply queues. Species names are read from pret
  `species_names.h`, dex ids 1–251 only.
- `MarketCli.kt` adds these script commands:
  - `money`, `market-inv`
  - `market-list-mon <slot> <price>`: slot is 0-based in the current party.
  - `market-list-item <id> <qty> <price>`
  - `market-search [mon|item] [id] [min] [max] [price|price-desc|new]`
  - `market-buy <id>`, `market-buy-first`, `market-cancel <id>`, `market-mine`
  - `/…` sends a chat command; `wait N` pauses.
- `gfx/MarketWindow.kt`: a Swing market window with Browse/buy, My listings/cancel and Sell
  (party + bag) tabs, showing pret Emerald box icons. Press M in the game window to open it.
  `--market-snapshot out.png` renders all three tabs headless.

## How to test
```
./gradlew :server.game:test --tests '*MarketRepositoryIT*'   # needs docker (testcontainers)
./gradlew :protocols.game:test --tests '*MarketPacketsTest*'
```
`MarketRepositoryIT` covers:
- Escrow and party compaction.
- Refusal to list the last party pokemon.
- Ten concurrent buyers on one listing: exactly one wins, money moves once.
- Refusal for insufficient money and for buying your own listing.
- Delivery to the PC when the buyer's party is full.
- Item escrow, payment, cancel and return.
- Expiry: can't buy after expiry, and the goods are returned.
- Search filters, sorting and paging.
- Cache/DB consistency through `CharacterStore`.

Mutation check: with `FOR UPDATE` removed from the listing lock, the race test fails.

E2E run (two concurrent client processes, local servers):
- A (Alice) lists Pikachu for 1500 → #1. Listing the remaining Bulbasaur is refused (last party pokemon).
- A lists 4x Potion for 300 (#2) and 2x Potion for 50 (#3).
- B (Bob), while A is still online:
  - Searches and finds #1, then buys it: money 30000 → 28500.
  - Buys #3: money → 28450.
  - Buying #3 again is refused ("no longer for sale").
  - Ends with Pikachu in party slot 0 and 2 Potions.
- A gets "Sold PIKACHU for 1500" pushed, and its money goes 35000 → 36550.
- After relogin:
  - A's money is still 36550.
  - A cancels #2: 4 Potions come back and the bag is 8. Cancelling again is refused.
  - B still has Pikachu.
- DB rows: #1 and #3 SOLD with a buyer, #2 CANCELLED, Pikachu owned by Bob in PARTY.
- Snapshots of the market UI were checked: Browse with an icon and "other" seller,
  My listings with "you", and Sell with party and bag.

## Not done / limits
- Seller names aren't shown: the GTL row has no field for it. Monster rows show "you"/"other" from the record's owner id.
- Item names aren't shown on the client: client item ids are PokeMMO-style, not pret.
- The market isn't reachable from an NPC; it opens from the M key / CLI.
- There's no sale history or price-history (`GtlPriceHistoryPacket`) tab.
- Residual race: `CharacterStore.getCharactersByUser` (character-select screen) still loads without the lock.
  A sale committing at exactly that moment could cache a stale balance until relogin.
- Givemon only adds to the party.

## Shared files edited (all small, additive)
- `protocols.game/.../GameProtocol.kt`: three new registrations plus an import.
- `protocols.game/.../packets/gtl/GtlSearchListing.kt`: own-listings rows decoded instead of throwing.
- `server.game/.../handler/GameAppHandler.kt`: MarketService injected, five handlers.
- `server.game/.../di/ChatCommandModule.kt`: three give commands bound.
- `server.game/.../storage/CharacterStore.kt`: `withExternalWrite` added, locked load in `getOrLoadCharacter`.
- `server.game/.../storage/CharacterRepository.kt`: the loader skips `GTS` (escrow) monsters.
- `client/.../net/GameClient.kt`: `market` field, four handlers, `selfId`.
- `client/.../Main.kt`: CLI fallback to market commands, `--market-snapshot`, species names load.
- `client/.../gfx/GameWindow.kt`: the M key.
- `client/.../gfx/WorldRenderer.kt`: `decompRoot` made public.
