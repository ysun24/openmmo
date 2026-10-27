# r5-contests — Hoenn contests, Berry Blender, Pokéblocks, berry trees, paintings

Branch `feat/r5-contests` (worktree `OpenMMO-r5-contests`), base own-client `95c5bee`.

## Built

**Decomp data** — `codegen/tools/gen_contest_data.py` writes `codegen/src/main/resources/contest/contest_data.json`
from pokeemerald: gContestMoves (354 moves: effect, category, combo starter / finishers),
gContestEffects (48), gComboStarterLookupTable, sContestExcitementTable, gContestOpponents (96, with
rank, category pools, AI flags, stats, postgame filter), the contest AI scripts
(`data/contest_ai_scripts.s` assembled with `asm/macros/contest_ai_script.inc` into 617 instructions),
the contest texts (effect descriptions, sAppealResultTexts, sRoundResultTexts, appeal messages),
gBerries (43), every map's berry tree objects (88) and the new-game trees, Pokéblock flavour
compatibility and names, the blender's NPC tables, the museum captions and gDefaultContestWinners.

**Rules (server.game `contest/`)**, ports with unit tests (`ContestRulesTest`, 16 tests):
- `ContestEngine` — contest.c + contest_effect.c: round-1 (condition) points with scarves, the turn
  order (SortContestants / ApplyNextTurnOrder / RankContestants), CalculateAppealMoveImpact with
  all 48 effect functions (startle / jam, jamSafety, immune, resistant, jamReduction, more easily
  startled, nervousness, condition stars, turn order up / back / scramble, crowd freeze), combos,
  repeat penalty ((repeats+1)×10), crowd excitement and the applause meter (+10 / +60), round
  results (attention levels), final standings (round 1 + 2×appeals, ties by round 1 then random).
  Each round is an ordered event list mirroring Task_DoAppeals' states, with the decomp's texts.
- `ContestAi` — contest_ai.c interpreter over the assembled scripts (every opcode the scripts use);
  per-move score from 100, random among the best (ContestAI_GetActionToUse).
- `BlenderEngine` — berry_blender.c frame simulation: arrow speed and slowdown, hit proximity per
  seat, UpdateSpeedFromHit, progress bar, max RPM, NPC opponents 1-3 and the Blend Master
  (Task_HandleOpponent*), CalculatePokeblock + colour, SetOpponentsBerryData.
- `PokeblockRules` (gain, liked/disliked boost, sheen cap) and `BerryTreeRules` (plant, water,
  growth by stage hours, yield, regrowth ×10, withering).

**Persistence** — Flyway `V10_2__contests.sql`: `pokemon_contest` (cool..sheen, the five rank
ribbons, other ribbon bits; keyed by monster id so it travels with trades), `pokeblocks` (40-slot
case), `berry_trees` (per player, real-time growth), `character_contest` (trees seeded, blender
record), `contest_paintings` (museum per category + hall winners).

**Services** — `ContestService` (entry screen, eligibility, matchmaking: entrants of the same
category + rank within a 12 s join window compete together, up to 4 players, gContestOpponents
NPCs fill the other seats; 30 s move timeout; ribbons, artist ribbon, museum and hall paintings),
`BlenderService` (players at the same blender within 10 s blend together, NPCs fill the seats the
blender's script asks for; 60 fps on the server; a press carries the arrow position the browser
saw, accepted if the arrow was there in the last 45 frames), `PokeblockService`,
`BerryTreeService`, `ContestStatsService`. **Feebas → Milotic**: EvolutionService's EVO_BEAUTY rule
reads the beauty through the `MonBeauty` binding.

**Specials** (`ContestSpecials`, 75 names): the contest* / Contest* / Pokéblock / Blender / berry
tree / Museum / Painting / ribbon / CheckLeadMon* specials of both games' specials.inc
(TryEnterContestMon, GiveMonContestRibbon, ShouldReadyContestArtist, SaveMuseumContestPainting,
ShowContestPainting, DoBerryBlending, ObjectEventInteraction*BerryTree, Bag_ChooseBerry,
OpenPokeblockCaseOnFeeder, the contest lady, OpenMuseumFossilPic (FRLG), …). Checked against the
other r5 worktrees: r5-scripts' `SpecialOwners` assigns these to this lane; `IsEnigmaBerryValid` /
`DoesPartyHaveEnigmaBerry` are left to r5-scripts.

**Lane scripts** (`ContestLaneScripts`, hand ports used until the interpreter runs these labels):
contest receptionist (+ link receptionist) with the hall results, prize, ribbon and contest artist;
BerryTreeScript (plant, water with the Wailmer Pail, pick); the three blenders and the link blender;
the lobby's hall paintings and blender record; the museum 2F frames.

**Web** — `web/static/js/ui/contests.js` + `css/contests.css`: entry screen; contest screen (stage,
judge bubble, applause meter, contestant boxes with hearts / stars / status, move menu with appeal
and jam hearts, description, combo / repeat hints, animated rounds, results); blender (canvas,
marks per seat, extrapolated arrow, tap or A); Pokéblock case (feed, toss, conditions); berry
pocket; paintings. Berry trees are drawn in the world from the decomp sprites
(`ContestAssetsExport`: 43 sheets in their stage palettes, sAnim_BerryTreeStage0-4). A `◆ Blocks`
HUD button opens the case.

Dev command: `/contestdev kit | stats c b cu s t sheen | ribbon cat rank | block … | grow <h> |
goto lobby|museum|route104 [x y dir]`.

## How verified
- `:server.game:test :protocols.game:test :client:check :web.gateway:test :web.assets:test` green;
  `scripts/check-opcodes.py` 0 clashes; spotless applied.
- Gateway e2e `web/gateway/test/e2e.py` → **ALL OK** on this stack (2266 / 7937 / 8580).
- `web/gateway/test/r5c_e2e.py --only contest,blender,berry,feebas,link` against the real stack, all
  OK: two players + two NPCs in one Normal Rank Cool contest, Ash wins (530 vs 221/166/80), the hall
  confers the ribbon (DB `cool_ribbon = 1`); blending with the Blend Master (BEST 18, 159.6 RPM,
  Gold Pokéblock in the case); two players blending together at the link blender (same block for
  both); Feebas fed 4 Pokéblocks → beauty 240 → Rare Candy → evolution scene FEEBAS→MILOTIC →
  Milotic in the party; Route 104: plant a Cheri, water at 4 stages, `/contestdev grow 3` ×4, pick
  3 berries (the max yield).
- Browser screenshots (looked at) in `docs/reports/r5-contests/`: desktop `d-entry, d-waiting,
  d-intro, d-select, d-appeals, d-results, d-hall, dm-results (Master win, 1037 points), d-painting
  (artist), d-museum-painting (museum caption), d-blender-choose / -play / -result, d-case-fed,
  d-berrytrees`; mobile `m-entry, m-select, m-appeals, m-results, m-blender-play / -result,
  m-case-fed, m-berrytrees, m-museum-painting`.

## Not done / limits
- The contest hall walk-in cutscene (contestants on stage, audience emotes) and move animations are
  not reproduced; the screen shows the sprite, judge symbols and texts. Hall scripts are
  interpreter territory; `DoContestHallWarp` is a no-op.
- contest_painting.c's per-category image effects are approximated with CSS filters.
- AI `if_random_less_than` uses its operand (vanilla reads past `eContestAI.vars`).
- The item catalogue has no POKéBLOCK CASE item: owning it is `FLAG_RECEIVED_POKEBLOCK_CASE` (set by
  the reception, like the decomp). The Wailmer Pail works through BerryTreeScript; using it from
  the bag is not hooked into FieldItemService (`BerryTree_EventScript_ItemUseWailmerPail` is
  registered for it).
- The museum 2F OnLoad metatiles (filled frames) are not changed; the frames show the painting when
  talked to after a Master win.
- Berry trees exist only in Emerald (FireRed has none). Contest lady TV shows are stubs.
- The last contest's per-player view (used by the hall specials) is in memory; a server restart
  between the contest and the hall dialog loses it (the ribbon is already stored).
- Mobile: the first-visit "Touch controls" help card can sit over the top of the overlay.

## Integration notes (for the merge with r5-scripts)
- `SpecialVars` (ContestSpecials.kt) reads/writes VAR_0x8004…, VAR_RESULT, VAR_CONTEST_* via
  `ctx.getVar/setVar("hoenn/VAR_…")`; r5-scripts' ScriptContext has `varGet/varSet(id)`: route these
  two functions there after the merge.
- `GameServerModule.scriptRegistry` adds `ContestLaneScripts.byLabel` over the generated scripts;
  drop that line once the interpreter runs the contest / berry / blender labels.
- Script commands `choosecontestmon`, `startcontest`, `showcontestresults`, `showcontestpainting`
  map to `ContestService.chooseEntry`, `runContest`, `showPainting`.

## Files touched outside the lane (small additive hooks)
- `protocols.game/.../GameProtocol.kt` (2 registrations); `server.game/.../di/GameServerComponent.kt`
  (ContestModule); `di/GameServerModule.kt` (script registry); `handler/GameAppHandler.kt` (handler
  + disconnect); `services/EvolutionService.kt` (MonBeauty param + EVO_BEAUTY rule).
- `client/.../GameClient.kt` (ContestClient + handler); `client/.../OverworldClient.kt`
  ({POKEBLOCK} text macro).
- `web/gateway/.../Dispatcher.kt` (prefix passthrough), `GameSession.kt`, `StateJson.kt` (contests
  section), `test/StateJsonTest.kt` (section list).
- `web/assets-export/.../Main.kt` (ContestAssetsExport); `web/static/js/main.js` (import, UIS, input,
  canMove); `js/render.js` (berry tree hook); `js/net.js` (section list); `index.html` (css).
- `docs/web/PROTOCOL.md`: new section 13 "r5-contests".

## Protocol
c2s 0xD5 `ContestActionPacket`, s2c 0xC6 `ContestStatePacket` (JSON). The reserved 0xD4-0xD7 were
retail in both directions except c2s 0xD5, so s2c uses the free 0xC6. Details in PROTOCOL.md §13.
