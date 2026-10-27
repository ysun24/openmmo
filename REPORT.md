# r5-minigames: report

Branch `feat/r5-minigames` (worktree `OpenMMO-r5-minigames`), base own-client 95c5bee.

## Built
All server code lives in `server.game/.../minigame/**`, bound through one Dagger module
(`MinigameModule`) that is added to the component with one line.

- **Channel**: one OpenMMO-only packet pair, c2s 0xDC `MinigameActionPacket` and s2c 0xDD
  `MinigameStatePacket`. Each carries a topic and a JSON object. The headless client keeps the last
  body per topic (`MinigameClient`). The gateway passes it through as the `minigames` state section
  and forwards `{t:'minigame', topic, ...}` back to the server.
- **Coins**: V10_4 `minigame_players` (coins 0..9999 with a CHECK constraint, plus a JSON save
  object). `MinigameSave` caches it and writes through on every change. The coin case counts as
  owned if the bag has COIN CASE or FireRed's `FLAG_GOT_COIN_CASE` is set.
- **FireRed slots** (`FrlgSlotMachine`): ported from slot_machine.c. Includes sReelBiasChances per
  machine, CalcSlotBias with the 5/60 cooldown, StopReel1/2/3 including
  TwoReelBiasCheck/OneReelBiasCheck, and CalcPayout over sRowAttributes with sPayoutTable. The
  machine is picked by GetRandomSlotMachineId (sSlotMachineIndices).
- **Emerald slots** (`EmeraldSlotMachine`): the lucky game and its initial reel positions, and
  DrawMachineBias (sSpecialDrawOdds, sBiasProbabilities_*). Also every DecideStop_Bias_* and
  DecideStop_NoBias_* path, CheckMatch with sSlotPayouts, REPLAY, Pika Power bolts, and the REEL
  TIME draw (sReelTimeProbabilities normal/lucky by bolts) with ReelTimeSpeed. The machine id comes
  from GetSlotMachineId, seeded by the Dewford trend.
- **Roulette** (`Roulette`): six balls a round, and GetMultiplier / RecordHit /
  IsHitInBetSelection. Minimum bets are sTableMinBets including the special rate, and the payout is
  minBet × multiplier.
- **Game Corner scripts**, hand-ported from the decomp onto the specials:
  - Celadon: coin clerk (50 / 500), the three gamblers, slots 0–21, the out-of-order machines, and
    the prize room's mons, TMs and items (FireRed prices and levels).
  - Mauville: coin clerk, slots 0–11, roulette tables 1/2, and the TM and doll prize corners (dolls
    go to the PC decorations).
  - The multichoice boxes are server-driven `menu` lists.
- **Link minigames** (`LinkGames.kt`, `LinkGameService`), run on the server at 60 fps:
  - Lobby: host, join, pick, start. NPC players fill empty slots up to the game's minimum, and a
    solo Pokémon Jump gets 2 NPCs.
  - Berry Crush (berry_crush.c): target from gBerryCrush_BerryData difficulties, sSyncPressBonus,
    big sparkles every 30 frames, silkiness, powder = powder × players × silkiness / 100. Berries
    are used up and powder plus records are saved.
  - Dodrio Berry Picking: shared head columns, MAX_FALL_DIST / EAT_FALL_DIST, sBerryFallDelays by
    sDifficultyThresholds, 10 / 30 / 50 points and −50 per miss, ten misses end the game, and a
    sPrizeBerryIds prize at ≥ 3000.
  - Pokémon Jump: sVineBaseSpeeds + 7 per stage, 10 points a clear, sScoreBonuses for jumping
    together, a trip ends the game, sPokeJumpMons jump types, and sPrizeItems with
    sPrizeQuantityData.
- **Union Room** (`UnionRoomService`): lists everyone who entered it plus everyone in any Pokémon
  Center 2F. Players can offer trade, battle, chat or a minigame. An accepted trade or battle uses
  the existing trade and duel flows. There is a chat log.
- **Pokémon Center 2F attendants**: the Union Room attendant opens the Union Room, the Wireless Club
  attendant opens the minigame lobby, and the Direct Corner offers TRADE CENTER, COLOSSEUM and
  RECORD CORNER (Emerald).
- **Fame Checker** (`FameCheckerService`): the pick states and flavor-text flags of fame_checker.c,
  stored in the save. Oak starts colored. Handled by SetFlavorTextFlagFromSpecialVars and
  UpdatePickStateFromSpecialVar8005. The gym, Elite Four and Giovanni defeat flags also color their
  person until the map scripts run. The UI uses the decomp's people, quotes, flavor texts, origins
  and objects, exported by `:web.assets`.
- **Mystery Gift** (`MysteryGiftService`, V10_4 `mystery_gifts` / `mystery_gift_claims`):
  - An admin defines a gift with `/mysterygift`: an item, a mon, or an Eon / Mystic / Aurora
    ticket or the Old Sea Map.
  - Players take the WONDER CARD from the Link menu. The deliveryman (both games' flags) hands it
    over.
  - A ticket sets the decomp ship flags. The Lilycove ferry attendant offers the island
    destinations to ticket holders; without a ticket it falls back to the generated script.
- **Records** (`RecordsService`, V10_4 `mixed_records`):
  - Mixing shares the player's trainer summary and Easy Chat profile/greeting, their trendy phrase,
    and TV shows from interviews.
  - It receives the newest trainers and TV shows of other players and lists their secret bases.
    The secret base table is read only and the contests branch is not touched.
  - The Dewford trend seeds GetSlotMachineId.
  - There is a basic Easy Chat word picker over the exported decomp word groups.
- **Web UI**: `web/static/js/ui/minigames.js` plus `ui/mg/*.js` and `css/minigames.css`. A
  "⇄ Link" hub in the top bar opens Union Room, Minigames, Fame Checker, Mystery Gift,
  Records & TV and Easy Chat profile. There is a coin chip, hidden in portrait.

### Specials owned by this lane
GetRandomSlotMachineId, GetSlotMachineId, PlaySlotMachine (stands in for the `playslotmachine`
command, machine id in VAR_RESULT), PlayRoulette, CheckAddCoins, IsWirelessAdapterConnected,
IsPokemonJumpSpeciesInParty, IsDodrioInParty, ChooseMonForWirelessMinigame,
ShowPokemonJumpRecords, ShowDodrioBerryPickingRecords, ShowBerryCrushRankings,
TryBecomeLinkLeader, TryJoinLinkGroup, InitUnionRoom, RunUnionRoom,
ShowWirelessCommunicationScreen, BufferUnionRoomPlayerName, Script_ResetUnionRoomTrade,
SetFlavorTextFlagFromSpecialVars, UpdatePickStateFromSpecialVar8005, ValidateSavedWonderCard,
ShouldDistributeEonTicket, ShowEasyChatScreen, ShowEasyChatProfile, BufferTrendyPhraseString,
IsTrendyPhraseBoring, InterviewBefore, InterviewAfter, TryRecordMixLinkup,
RecordMixingPlayerSpotTriggered.

Specials read VAR_0x8004 and VAR_0x8005 as story vars named `VAR_0x8004`. The interpreter must
store them under that name.

## How it was verified
Real stack (game 7957, login 2286, gateway 8600, Postgres 21811/21821) in a headless Chromium. I
looked at every screenshot. They are in `docs/reports/r5-minigames/`.
- **FireRed slots**: bet 3, stop the 3 reels, no payout (`frlg-slots-*.png`). On mobile, won
  2 coins on cherries (`mobile-slots-win.png`).
- **Emerald slots**: 8 spins including a REPLAY (`emerald-slots.png`).
- **Roulette**: 3 balls bet, grid multipliers and taken squares update (`roulette.png`).
- **Prize exchange**: bought ABRA at the Celadon prize room. Coins went 3000 → 2820 and ABRA joined
  the party (`prize-menu.png`).
- **Berry Crush**: two browsers, Ash and Gary in one group. Both pressed, and the results (46 / 53
  presses, silkiness 95 %, 95 powder) showed in both (`berry-crush-A/B.png`).
- **Pokémon Jump**: solo with NPCs JOEY and LIZ. 19 jumps, 1240 points, ended when JOEY tripped
  (`pokemon-jump*.png`).
- **Dodrio Berry Picking**: solo plus 2 NPCs (`dodrio.png`).
- **Fame Checker**: Brock silhouette with unlocked texts (`fame-checker.png`).
- **Union Room**: Ash sees Gary and offers a trade. Gary says yes and gets the real trade request
  window (`union-room-*.png`).
- **Mystery Gift**: `/mysterygift add ticket eon` → card received from the Link menu → the Mauville
  2F deliveryman gives the EON TICKET → the Lilycove ferry attendant offers SOUTHERN ISLAND → the
  player lands on SouthernIsland_Exterior (`mystery-gift-card.png`, `southern-island.png`).
- **Mobile**: hub, lobby and slots (`mobile-*.png`).
- **Tests**: `MinigameRulesTest` (11 cases: payouts, biases, roulette, the three link games,
  coins) and `MinigameJsonTest`. `:server.game:test`, `:protocols.game:test`, `:client:check`,
  `:web.gateway:test` and `:web.assets:test` all pass. `check-opcodes.py` reports 0 clashes. The
  gateway e2e ends with **ALL OK**. The assets were regenerated (`:web.assets:run`).

## Not done / gaps
- **Script commands outside specials.** In my branch only my hand-ported labels run, because the
  interpreter is the scripts lane's. The `playslotmachine`, `checkcoins` / `addcoins` /
  `removecoins` and `showcoinsbox` commands, and the `famechecker` macro in story scripts, need
  the interpreter to call PlaySlotMachine and `MinigameSave` (coins). When the interpreter lands,
  my overrides in `MinigameScripts` can stay (they implement the same decomp bodies) or be
  dropped.
- **Lati@s encounter.** The island event is reached (Southern Island Exterior). The Lati@s
  encounter itself is a story script (scripts lane). FireRed's Seagallop routes for the
  Mystic/Aurora tickets only get their ship flags set; the Vermilion harbor script is story code.
- **No item ids.** The event tickets and the Fame Checker have no item ids in the item database.
  Tickets are kept in the save (`tickets`), and the Fame Checker opens from the Link menu once
  `FLAG_GOT_FAME_CHECKER` is set.
- **Simplified physics.** Roulette's ball physics (wheel rotation, Shroomish / Taillow bounce)
  becomes a travel distance in sRouletteTables' range plus "roll on to the next free square". The
  rules, multipliers and payouts are exact.
- **Simplified Pokémon Jump.** Vine speed is mapped to frames per swing, and air time per jump type
  is approximated (46/40/34 frames). Jumping together counts as within 6 frames, standing in for
  the decomp's at-peak test.
- **Dodrio difficulty.** Berry type odds by difficulty are an approximation, because the decomp's
  per-column berry choice was not ported table for table. The NPC Dodrio catch too well, so a game
  with NPCs lasts long.
- **No TV broadcast.** TV shows from interviews and record mixing are stored and listed in the
  Records UI, but they are not fed into Emerald's TV broadcast queue. DoTVShow and the rest of the
  TV specials are not this lane's.
- **Basic Easy Chat.** The Pokémon and move word groups are empty, since the decomp builds them from
  species and move names.
- **Server trusts the word text.** The server keeps the phrase text the browser sends and only
  range-checks the word ids, because it has no copy of the word table.
- **sonarlint.** `:server.game:sonarlintMain` reports issues. That is not part of the required test
  set and I did not chase it.

## Files touched outside this lane (small additive hooks)
- `protocols.game/.../GameProtocol.kt`: two registrations (c2s 0xDC, s2c 0xDD).
- `client/.../GameClient.kt`: `val minigames` and one `on<MinigameStatePacket>`.
- `server.game/.../handler/GameAppHandler.kt`: inject `MinigameChannel`, one `onSuspend`, one
  `onDisconnect` call.
- `server.game/.../di/GameServerComponent.kt`: `MinigameModule` in the module list.
- `server.game/.../di/GameServerModule.kt`: `scriptRegistry` merges `MinigameScripts.byLabel` over
  the generated labels.
- `web/gateway/.../StateJson.kt` (`Sources.minigames` plus the `minigames` section),
  `GameSession.kt` (`minigame()`, sources), `Dispatcher.kt` (`minigame` message, default method).
  `StateJsonTest` now expects the new section.
- `web/static/js/main.js`: import, an entry in UIS, and a `top.onKey` hook so a minigame window can
  take the buttons.
- `web/static/js/net.js`: `minigames` added to SECTIONS.
- `web/static/index.html`: one stylesheet link.
- `web/assets-export/.../Main.kt`: `manifest.minigames`.

## PROTOCOL changes
`docs/web/PROTOCOL.md` section 13 "r5-minigames" (additive): the channel, the topics, the assets
and the commands.

Migration: `V10_4__minigames.sql`.
