# r5-frontier: battle facilities

Branch `feat/r5-frontier` (worktree `OpenMMO-r5-frontier`), base own-client 95c5bee.

## What was built

**Decomp data.** `codegen/tools/gen_frontier_data.py` exports
`server.game/src/main/resources/frontier/frontier.json` straight from the decomp sources: the 882
`gBattleFrontierMons` sets, the 300 `gBattleFrontierTrainers` with their mon sets (this includes
the parameterised `FRONTIER_MONS_*_EXTRA(...)` macros), the three Battle Tents (70/45/45 sets, 30
trainers each), the seven Frontier Brains' Silver and Gold teams (`sFrontierBrainsMons`), the Pike
wild mons, the Pyramid wild mons per round, pickup items, pickup slots and floor templates, the
Arena `sMindRatings`, the Factory style move lists, the Exchange Service Corner (prices read from
the clerk scripts), the four Trainer Hill modes, the FRLG Trainer Tower floor sets and prize list,
and `gFrontierBannedSpecies`. Easy-chat speech is turned into text from the easy chat word tables.

**Rules** (`server.game/.../frontier/`), each a transcription of its C source:
- `FrontierRules`: `GetRandomScaledFrontierTrainerId` (normal and hard ranges, no repeat in a
  challenge), `GetFrontierTrainerFixedIvs`, `GetFrontierEnemyMonLevel` (Lv. 50 / Open Level = the
  party's highest, at least 60 / tents at least 30), `FillTrainerParty` (no repeated species or
  held items, no high-tier sets at Lv. 50), `GetFrontierBrainStatus` (`sFrontierBrainStreakAppearances`,
  singles only), `GiveBattlePoints` (`sBattlePointAwards`, +10 for a brain), `GiveFacilitySymbol`,
  `CheckPartyIneligibility` (eggs, banned species, over 50 in Lv. 50 mode, repeated species or
  items, nothing held in the Pyramid), party sizes per mode, `CreateMonWithEVSpreadNatureOTID`.
- `PalaceRules` / `PalaceHooks`: `gBattlePalaceNatureToMoveGroupLikelihood` above and at or below
  half hp, `GetBattlePalaceMoveGroup`, the AI choosing within the group, the fallback with its
  shipped Support-count bug and the 50% "appears incapable of using its power", the nature
  flavor lines at half hp. Both sides' monsters choose for themselves, no switching.
- `ArenaRules` / `ArenaHooks`: one-on-one in party order, no switching, the 3-turn limit, Mind
  (`sMindRatings`), Skill (`BattleArena_AddSkillPoints` / `DeductSkillPoints`), Body (hp left
  against the match-up's start hp), 2/1/0 per category, the referee's lines, the loser (or both
  on a draw) faints; a draw that empties both sides is a loss.
- `FactoryRules`: `sFixedIVTable`, `sInitialRentalMonRanges`, `GetFactoryMonId`, rental rank by
  rent count, `GenerateInitialRentalMons`, `GenerateOpponentMons` (no species shared with the
  rental pool, no Unown), Return → Frustration with friendship 0, Lv. 50 / Lv. 100,
  `GetOpponentBattleStyle` for the pre-battle hint, the swap after each win, Noland's party.
- `PikeRules`: 14 rooms, the hint lady (`SetHintedRoom`, `sRoomTypeHints`), `GetNextRoomType` (the
  other doors never share the hint, no double battle with one healthy monster, no status room
  without a healthy one, healing rooms off while the party is at full health), single / hard
  (heals after) / double battles, wild Pike mons with `levelDelta`, `TryInflictRandomStatus`
  (1/2/3 monsters by room, type and ability immunities, Kirlia or Dusclops), full and partial heal
  rooms, NPC rooms, Lucy with `sNumMonsToHealBeforePikeQueen`, no healing between rooms.
- `PyramidRules`: 7 floors of 4x4 squares, the floor template (`sPyramidFloorTemplateOptions`),
  entrance / exit (`GetPyramidEntranceAndExitSquareIds`), trainers and items per template, pickups
  (`sPickupItemSlots`, per round and level mode), the light radius 32 +4 per win up to 120, wild
  mons from the round's table (±5 levels, Open Level scaled), the Pyramid Bag (starts with a Hyper
  Potion and an Ether, used in battle through the battle bag and on the floor), Brandon at the top.
- `DomeRules`: 16 trainers, the first five harder, seeding by the ranking score, the bracket
  (`sTourneyTreeTrainerIds`), the opponent picking 2 of 3 (`GetDomeTrainerSelectedMons`, good/bad
  points), `DecideRoundWinners` for the other matches (AI-vs-AI points), Tucker taking the other
  half's top seed when ready.
- Battle Tower: singles, doubles (4 mons), multis with an NPC partner chosen from tower trainers
  (the AI plays position 1), and link multis with another online player (one hosts, the other
  joins from the same attendant; both streaks and BP update). Anabel at the 35th / 70th battle.
- Battle Tents: Slateport (Factory rules, Lv. 30 rentals from the tent's sets), Verdanturf (Palace
  rules), Fallarbor (Arena rules); three battles, prizes Full Heal / Nest Ball / Hyper Potion.
- Trainer Hill (Emerald): four modes, four floors of a two-trainer tag match at the party's top
  level (trainer 1 mons 0-2, trainer 2 mons 3-5), the timer, the time-based prize, best times.
- Trainer Tower (FRLG): single / double / knockout / mixed, eight floors with the decomp's mon index
  tables, the timer, the prize of the set, best times.
- Exchange Service Corner (4 clerks, BP prices from the scripts; decorations go to the PC) and the
  Frontier Pass (BP, symbols, every record).

**Battle engine hooks** (`battle/FacilityHooks.kt`, small changes in `BattleService`, `TurnEngine`,
`BattleAi`, `BattleInstance`, `BattleRegistry`, `BattlePacketEmitter`): a facility battle runs a
temporary team (own monsters healed, rentals, NPC partner) and writes nothing back (no EXP, prize,
hp/pp, friendship, evolution or white-out); `autoPlay` (Palace, paced one turn every 2.5 s so the
browser plays each), `allowSwitch` (Arena/Palace: the next monster comes out by itself),
`allowItems` + `bag()` (Pyramid Bag), `aiPositions` (NPC partner), `beforeTurn` / `autoChoice` /
`afterTurn` (Palace choices, Arena judgment), `bothDownIsLoss`. Multi battles with two players:
one battle instance for both sessions, each player picks for their own position only, a fainted
partner monster is replaced by the other one, the battle ends once both clients are back.

**Persistence.** Flyway `V10_3__frontier_records.sql`: `frontier_records(character_id, battle_points,
data, updated_at)`; `data` holds the streaks per facility/mode/level (current, best, active), the
symbols, brains battled, Factory rent counts, Trainer Hill / Trainer Tower best times and prizes.
Leaving mid-challenge (or retiring, or losing) ends the streak like the decomp's challenge without
saving.

**Desks and specials.** Talking to the attendants (`FrontierDesks.BY_SCRIPT`: the 13 frontier
attendants, the 4 exchange clerks, the 3 tent attendants, the Trainer Hill attendant, the Trainer
Tower receptionist) opens the desk. `FrontierSpecials` claims the lane's specials (60 names:
`CallBattleTowerFunc` … `CallTrainerTowerFunc`, the tent functions, `Get/Give/TakeFrontierBattlePoints`,
`GetBattleTowerSinglesStreak`, `CheckPartyBattleTowerBanlist`, the Pyramid/Hill/Tower/Exchange
window specials, ...); a facility function's INIT opens its desk, the BP / streak / symbol ones read
and write the save, window-only ones are no-ops. No name clashes with the other r5 worktrees
(checked). `/frontier <desk>`, `/frontier bp <n>`, `/frontier streak <key> <n>` for development.

**Web UI.** `web/static/js/ui/frontier.js` + `css/frontier.css`: facility menus, party selection
with eligibility reasons, between-battle screen with the next opponent (★ for a brain), Factory
rental and swap screens, Dome bracket with the opponent's team and the two-of-three pick, Pike
doors with the hint, Pyramid floor map with darkness, light, bag and item use, Exchange shop,
Frontier Pass. Mirrored read-only screens for the link-multi partner.

## How it was verified

- `:server.game:test` 496 tests (20 in `FrontierRulesTest`: trainer scaling, IVs, party fill,
  brains, BP, symbols, eligibility, EV spread / nature / Frustration, Palace tables and fallback,
  Palace hooks, Arena judgment and a 3-turn draw through the engine, Factory ranges / rentals /
  opponents, Pike hint and rooms, Pyramid squares / light / pickups, Dome bracket, save JSON),
  `:protocols.game:test`, `:client:check`, `:web.gateway:test`, `:web.assets:test` all green;
  `scripts/check-opcodes.py`: 0 clashes. Assets regenerated.
- Gateway e2e on the r5-frontier stack (2276/7947/8590): **ALL OK**.
- `web/gateway/test/frontier_e2e.py` (drives every desk through the gateway): Battle Tower round of
  7 won (BP awarded), Factory win + swap, Palace, Arena round of 7 won, Dome tournament won (4
  matches), Pike rooms, Pyramid floors, Trainer Tower (3 floors), Trainer Hill, the three tents,
  exchange (Leftovers for 48 BP), **link multi with two players** (wins and losses on both
  clients): ALL OK.
- Browser screenshots (`docs/reports/r5-frontier/`, looked at): tower menu from the attendant /
  team / round of 7 won (`tower-*.png`); Factory rental, battle, swap, "RATICATE joined your team!"
  (`factory-*.png`); Palace battle with self-chosen moves (`palace-battle*.png`); Arena referee
  judgment (`arena-judge*.png`); Dome bracket (`dome-bracket.png`); Pike doors and a heal room
  (`pike-*.png`); Pyramid floors with darkness and found items (`pyramid-floor*.png`); Trainer
  Tower desk and battle (`ttower-*.png`); exchange (`exchange*.png`); Frontier Pass
  (`frontier-pass.png`); Anabel at streak 34 (`brain-*.png`), Lucy (`queen-battle.png`); two
  browsers in a link multi (`multi-*.png`, desktop host + mobile partner with the mirrored screen);
  mobile: tower menu/team/between, rental, Pike doors, Pyramid floor, Trainer Tower.

## Not done / gaps

- The facility battles run from the lobby: the decomp's walks to the battle rooms, elevators,
  the Pike's three-path room maps and the Pyramid's maze maps are replaced by the screens above
  (the Pyramid is a 4x4 grid of squares; a wild encounter has a 25% chance per square moved, the
  decomp rolls per step on its maze tiles).
- "Rest" (save and quit mid-challenge, resume later) is not kept; leaving ends the streak.
- No Frontier Brain symbol was won in the browser: our learnset-moveset test teams lost to Anabel
  and Lucy (both appear correctly at their streaks); the symbol award is unit-tested.
- Brain / trainer speech before battles uses the easy-chat lines; the decomp's longer brain texts,
  the Dome's info screens and the facility record windows are not reproduced.
- Specials: window/visual ones (BP window, item icon window, curtain, confetti, ranking hall
  scroll) are no-ops; the record-mixing / e-Reader / apprentice trainers are not implemented.
- Gen 3 allows 255 EVs in one stat; our `EVs` model caps 252, so frontier sets with two stats get
  252 instead of 255.
- Pre-existing battle UI (r3-battle) issue seen in screenshots: during fast turns the player's
  trainer back sprite can stay up while the monster's turn plays (not in this lane).

## Files touched outside this lane (small, additive)

- `server.game/.../services/BattleService.kt` (facility battles, multi battles, hooks),
  `battle/BattleInstance.kt`, `battle/BattleRegistry.kt` (`link`), `battle/BattlePacketEmitter.kt`
  (partner start/end, `posOwners`), `battle/TurnEngine.kt` (lead events), `battle/BattleAi.kt`
  (`allowed`, `forceSmart`, no AI switch when the facility forbids it).
- `server.game/.../handler/GameAppHandler.kt` (FrontierActionPacket handler, logout hook),
  `services/InteractionService.kt` (attendant → desk), `di/GameServerComponent.kt` (FrontierModule).
- `protocols.game/.../GameProtocol.kt` (2 registrations).
- `client/.../GameClient.kt`, `client/.../world/WorldState.kt` (frontier JSON),
  `client/.../BattleClient.kt` (`myId`, `notMine`, `myOwedSwitches`).
- `web/gateway/.../Dispatcher.kt`, `GameSession.kt`, `StateJson.kt` (frontier message and state).
- `web/static/js/main.js` (UI module), `web/static/index.html` (stylesheet).

## Protocol changes

`docs/web/PROTOCOL.md` section 13 "r5-frontier": c2s 0xD8 `FrontierActionPacket`, s2c **0xE2**
`FrontierStatePacket` (s2c 0xD8-0xDB of the reservation are retail packets, so 0xE2 was taken, free
in all r5 branches), the `frontier` browser message, `world.frontier` and its screens, `posOwners`
in the battle extension state.
