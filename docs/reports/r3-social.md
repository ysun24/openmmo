# r3-social: social and economy

Branch `feat/r3-social` (worktree `OpenMMO-r3-social`). Every gap listed in `docs/reports/trade.md`,
`market.md` and `web-gateway.md` for this lane is closed. The one exception is the real trade
evolution, which is wired to a hook, see "Not done".

## Built

### Protocol (`protocols.game/.../packets/social/CommunityPackets.kt`)
- c2s 0xFC `SocialRequestPacket`: op, name, text, arg. Covers friends, blocks, profile, chat on a
  channel, every guild action and trade history.
- c2s 0xFD `MarketQueryPacket`: price history, sales history, fee info.
- c2s 0xFE `TradeOfferPcMonPacket`.
- s2c 0xFD `CommunityPacket`: a tagged union of 11 bodies (friends, profile, chat line, guild, guild
  invite, listing seller names, price history, sales, market info, market open, trade history).
- Opcodes: c2s 0xFC–0xFE and s2c 0xFD were free in both directions, also in the other r3 worktrees.
  s2c 0xFC and 0xFE were already taken by retail packets, so 0xFD is the only s2c opcode used.
- `TradeStatePacket` gained `expiresInSeconds`, and `TradeOfferView` gained `pcMons`. Both are
  appended to the codec, with default values.

### Server
- **Persistence**: migration `V8_4__social.sql` adds `friends`, `blocked_players`, `guilds`
  (case-insensitive unique name), `guild_members`, `guild_log`, `trade_history`, plus
  `market_listings.fee` and a sales index. `SocialRepository` (jOOQ) has an in-memory twin for tests.
  The old in-memory `SocialStore` (seeded fake friends) and `GuildStore` are gone.
- **SocialService**: one-way friends with a mutual flag, online state and current map. Friends get
  "X is online/offline" notices, sent once per session rather than on every warp.
- **Blocking**: drops the friendship. Blocked players' chat, whispers, trade requests and guild
  invites are hidden, and a blocker shows offline to the blocked player.
- **Profiles**: party, guild, trainer-since date, trade count. Offline profiles are read from the
  DB without caching them.
- **Chat channels**: local (same map), global, trade, guild, whisper. Every line is cleaned
  (control characters, whitespace), limited to 200 characters, rate limited and passed through the
  filter hook. Retail `ChatMessagePacket` c2s is routed by its type.
- **GuildService**: found (15000, name/tag rules), invite (accept within 60 s, or decline), ranks
  Recruit…Leader, leadership transfer, MOTD, kick, leave, disband, activity log, and members' online
  state and location. Permissions are in `GuildRules`. One mutex serialises all changes. The retail
  guild packets map onto the same actions.
- **Trade**:
  - PC monsters can be offered (`TradeSwap` validates and gives from party ∪ PC).
  - Requests lapse after 60 s.
  - Proximity rule: `trade.proximity` is `map` by default; `none` or a tile radius also work.
  - While the window is open, the server refuses steps (snaps the client back), shop buy/sell,
    NPC/sign interaction and market actions.
  - Blocked players and rate limits apply to requests.
  - Each trade is logged with a summary of what was given.
  - After every completed trade, `TradeEvolutionHook.onTraded(receiver, mon)` is called.
- **Market**:
  - Seller names follow each page as `ListingMetas`.
  - Price history: 30 days of per-unit daily min/max/avg/volume, plus recent sales.
  - Sales history with buyer names and fees.
  - Listing fee: 2 %, at least 1, charged in the listing transaction, not refunded. The active
    listing cap is configurable.
  - A market clerk (the region's mart clerk sprite) stands in **every** Pokemon Center
    (`MarketClerks`, test checks all of them). Talking to the clerk opens the market; the menu
    shortcut stays.
- **Stale balance fix**: `CharacterStore.getCharactersByUser` re-reads each character under its lock,
  which fixes the stale-balance race. Mutation-checked: the test fails on the old code.
- **Anti-abuse**: token buckets per login account (chat 8/10 s, whisper 10/10 s, trade requests 5/60 s,
  market 30/10 s, social 20/10 s, all configurable). Chat length limit. `ChatFilter` hook with a
  whole-word mask list from `chat.filterFile`, empty by default. Settings live in `SocialConfig`;
  the table is in PROTOCOL.md §4.2.
- Dev command `/tp r:b:m x y [facing]` (DEVELOPER). The gateway e2e uses it to reach a mart and a PC.

### Client / gateway
- `SocialClient` (new). `MarketClient` gained sellers, history, sales, info and openSeq.
- `TradeClient` gained `togglePcMon` and `expiresAtMs`, and its `println` is now a log line.
- `GameClient.say` sends local chat; `chat(channel, text, to)`. `ChatLine` gained `channel` and `to`.
- Gateway:
  - New `social` section.
  - Additions to chat, trade and market.
  - 20 new message types (`SocialActions`, see PROTOCOL.md §4.2).
  - No stdout prints left.

### Web
- `ui/chat.js`:
  - Channel tabs (All/Local/Global/Trade/Guild/Whisper) with unread dots.
  - Shortcuts `/w Name`, `@Name`, `/g`, `/t`, `/gu`, `/l`.
  - Click a whisper to reply; click a name to open the player menu.
- `ui/social.js` (new):
  - Friends panel: online dot, location, whisper/profile/trade/remove, a blocked tab, and a trade
    log tab that refreshes every 10 s.
  - Profile popup.
  - Guild window: create, MOTD, members with promote/demote/leader/kick/whisper, invite,
    leave/disband, activity log.
  - Invite prompt with a countdown.
  - Tapped-player context menu: profile, whisper, trade, duel, add/remove friend, guild invite.
  - Stylesheet `css/social.css`, which `social.js` loads itself.
- `ui/market.js`:
  - Seller names, sales tab, SVG price chart (📈 on every row and in Sell), fee preview and limit.
  - Opens itself when a clerk is talked to.
- `ui/trade.js`: PC box chips, PC monsters shown in offers, request countdown, a lock note, and the
  final offers kept in the "complete" view.

## How verified
- **Tests, all passing**:
  - `:server.game:test` 345, including new `SocialServiceTest` (17), `SocialConfigTest`, 10 new
    `TradeServiceTest` rules, `TradeSwapTest` PC cases, `SocialRepositoryIT` and new
    `MarketRepositoryIT` cases against real Postgres, and `CharacterListRaceTest`.
  - `:protocols.game:test` 90 (`CommunityPacketsTest`).
  - `:web.gateway:test` 27 (`SocialJsonTest` and Dispatcher social routes).
  - `:client:check`, `:web.assets:test`, and `spotlessCheck` on the whole project.
- **Gateway e2e**: `python3 web/gateway/test/e2e.py --ws ws://127.0.0.1:8540/ws --a
  yicheng:pikachu123:Ash --b friend:bulbasaur123:Gary` gave **ALL OK** (52 checks) three times in a
  row, so it is re-runnable. It now also covers:
  - chat channels, whisper (including offline), the length limit and a bad channel
  - friends add/remove/mutual, profile, block/unblock and their effects
  - the full guild flow: create, invite, accept, rank, motd, guild chat, kick, decline, disband,
    leave
  - `pc.deposit`/`pc.withdraw`
  - the clerk opening the market
  - `trade.mon` on/off, `trade.pcMon`, request `expiresAt`, a refused step during the trade, trade
    history, decline
  - `market.listMon` with the fee charged, seller names, buy, `market.sales`, `market.history`,
    `market.listItem`/`market.cancel`, `market.info`
  - mart `shop.buy`/`shop.sell`/`shop.close`
  - `battle.learn`/`switch`/`ball`/`run`
  - a chat burst: 8 delivered, the rest refused
- **Real stack, two browsers**: Chromium screenshots, all looked at, in `docs/reports/r3-social/`:
  - friend add + location (`a_friends`), whisper on both sides (`a_whisper`, `b_whisper`)
  - guild invite, guild window with ranks/MOTD/log, guild chat (`b_invite`, `a_guild`,
    `b_guildchat`)
  - clerk in the Viridian PC (`m_clerk`), market opened from the clerk with seller name and price
    chart (`m_chart`), sales tab (`m_sales`)
  - PC-mon trade request countdown, the open window showing "RATTATA Lv9 (PC)", completion
    (`t_b_request`, `t_a_open`, `t_a_done`). Holding Down and sending a raw `step` during the trade
    left the position at (6,6).
  - mobile (Pixel 7): context menu from tapping a player on the map, profile, friends, chat tabs
    (`mob_*`)
  - Some past sales were inserted into the DB to give the chart 30 days of data.

## Not done
- **Real trade evolution**: the hook exists (`TradeEvolutionHook`, bound to a no-op in
  `SocialModule`) and is called for every received monster. The r3-battle worktree now has
  `EvolutionService.onTraded(charId, mon): Int?`, but it is not on this branch. After merging,
  rebind with `@Provides fun tradeEvolution(e: EvolutionService) = TradeEvolutionHook { id, mon -> e.onTraded(id, mon) }`
  in `SocialModule` (a one-line change).
- Friend requests are one-way with a notification; there is no accept step.
- Guild founding cost and rank names are fixed. The retail rank label and permission packets are
  only logged.
- Duel challenges from someone who blocked you are not refused. That code is in the battle lane
  (`PvpBattleService`).
- Map names in friend and guild locations are the pretty-printed decomp map ids.

## Files touched outside my lane (all small, additive)
- `server.game/.../handler/GameAppHandler.kt`: `MarketClerks` injected; the movement/interaction/shop
  handlers go through `tradeService.refuse*`; new social/market-query/pc-mon handlers; friend
  packets on `onSuspend`; chat goes to `socialService.onLegacyChat`; `socialService.onDisconnect`.
- `server.game/.../services/NpcService.kt`: optional `ExtraNpcSource` constructor parameter (default
  none) and one spawn call.
- `server.game/.../di/GameServerComponent.kt`: `SocialModule` added to `modules`.
- `server.game/.../storage/CharacterStore.kt`: the `getCharactersByUser` race fix.
- `client/.../net/GameClient.kt`: `social` field, `CommunityPacket` handler, `say` sends local
  chat, `chat()`.
- `client/.../world/WorldState.kt`: `ChatLine` gained `channel` and `to` (with defaults).
- `web/static/js/main.js`: import `social` and add it to `UIS`.
- `web/static/js/ui/misc.js`: Friends and Guild menu items; `openPlayer` delegates to
  `social.openPlayer`.
- `web/static/js/net.js`: `'social'` added to `SECTIONS`.
- `web/gateway/.../Dispatcher.kt`: `GameActions : SocialActions`, the `say` channel, new routes.
- `docs/web/PROTOCOL.md`: new §4.2 "r3-social" (additive).

## PROTOCOL changes
See `docs/web/PROTOCOL.md` §4.2. Everything is additive except one change in meaning: player chat
now arrives as our own `CommunityPacket`, and `chat[].channel`/`to` are new. Old `say` messages still
work and post to the local channel.
