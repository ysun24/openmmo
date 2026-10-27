# Round 5 integration (branch `own-client`)

Backup tag before the merge: `pre-r5`. Lane reports: `docs/reports/r5-{scripts,contests,frontier,minigames}.md`
(r5-scripts left no REPORT.md; its summary was written from its commits). Screenshots:
`docs/reports/r5-integration/`.

## Close-out (after `24a0202`)
One commit per item, all on `own-client`:
- **Live stack detached** (`ea04855`): `scripts/live-stack.sh` starts each server with `setsid nohup … </dev/null`
  (own session and process group; the pid file is the java pid, which is also the pgid), `status` checks that
  the recorded pid is alive and is this checkout's java, `stop` kills exactly those pids.
- **Dev command replies stay private** (`c72c044`): every ChatCommand reply already went to the caller's session
  only (`CommandContext.reply` → a `GAME_NOTIFICATIONS` notice, shown as a ★ system line); audited all commands
  (`/grant` additionally notices the target, by design). The leak was the other way in: a slash line sent on a
  social channel (`CommunityBody.Chat`) or the retail chat packet was broadcast as chat. `SocialService.chat` now
  hands any `/` line to `ChatCommandService`, so neither the command nor its reply reaches anyone else (unit test).
- **Fame Checker from the bag** (`b76d76c`): `FameCheckerFromBag` (`BagItemScript`, FieldUseFunc_FameChecker)
  opens the Fame Checker; the catalog gives it `use: field` and `register: true` (items.json registrability 1),
  so USE in the bag and SELECT on the registered item both open it (unit test, driver check, shots
  `d-fame-bag` / `d-fame-open`).
- **FRLG Mystic / Aurora Ticket** (`5377124`): story scenes `mystic` (Vermilion sailor → MULTICHOICE_SEVII_NAVEL →
  Seagallop 10 → Navel Rock; the summit's coord trigger → Ho-Oh, the base → Lugia) and `aurora` (→ Seagallop 12 →
  Birth Island; the Deoxys triangle). New: `DoDeoxysTriangleInteraction` per field_specials.c (sDeoxysCoords /
  sDeoxysStepCaps, the triangle moves with `setobjectxy`, too many steps send it home, the 11th touch awakens
  DEOXYS) and `IncrementBirthIslandRockStepCount` in FieldStepEffects. Bug found on the way: `GetQuestLogState`
  did not write VAR_RESULT, so `goto_if_questlog` after a menu answer of 2 (BIRTH ISLAND) ended the script.
- **Battle Frontier REST** (`6ce4599`): `FrontierSave.rest` (`FrontierRest`: desk, streak key, next
  battle/room/floor, the team with HP/status/PP as every field of the Pokémon, facility extras) is persisted in
  `frontier_records.data`; REST on the between screen (Tower singles/doubles/multis with the NPC partner, Palace,
  Arena, Factory with its rental ids), on the Pike's door screen (room, healing flag) and anywhere on a Pyramid
  floor (the 16 squares, position, light, the Pyramid bag). The same desk resumes it once ("We've been waiting
  for you!"); other challenge desks refuse while one is on hold. Unit test (JSON round trip) and
  `frontier_e2e.py --only rest`: REST → the row in the database → resume at the same battle / room / square.
- **Contest hall cutscene** (`dfbcb4a`, `6b76556`): the reception stays the multiplayer hand port (entry screen,
  matchmaking); once the contestants are set every player is warped into ContestHall with
  VAR_CONTEST_HALL_STATE 1 and the interpreter runs `ContestHall_EventScript_Contest`: MC and judge walk on, each
  contestant walks to the centre with their entry text, audience hearts and vote, `startcontest` (new special:
  waits on stage for every online entrant, then the live appeal screen), the judging lines,
  `showcontestresults` (results screen), the winner and prize (ribbon), the exit warp, and the lobby's contest
  artist. Contestant and audience sprites come from VAR_OBJ_GFX_ID_* (`SetContestTrainerGfxIds`, NpcService now
  resolves OBJ_EVENT_GFX_VAR_*). Appeals play the move's type sprite from the exported battle anims
  (`moveType` on appeal events). Fixed on the way: `GenerateContestRand` did not set VAR_RESULT (the hall's
  heart loop spun 200k commands), an `InterestManager.members()` race (`toSet()` → NoSuchElementException on a
  concurrent leave), and leaving mid-contest now puts the player back at the lobby counter.
  Shots `d-c-hall-walkin` / `d-c-hall-appeal`.
- **Starter pick in the browser**: `d-k3-pick` / `m-k3-pick` (the Bulbasaur ball's "So, … you want to go with
  the GRASS POKéMON BULBASAUR?") and `d-k4-received` / `m-k4-received` (the ball gone, GREEN takes Charmander;
  the party gained BULBASAUR).

### Final verification (close-out)
- Full build + tests (`spotlessCheck` and every module's tests, `installDist`): BUILD SUCCESSFUL, no failures,
  no skips. server.game 540, protocols.game 105, server.login 54, web.gateway 42, codegen 38, protocols.login 12,
  web.assets 8. `check-opcodes.py`: 405 registrations, 0 clashes. Assets regenerated.
- Live stack (detached, game 7777 / login 2106 / gateway 8080): `e2e.py` **ALL OK** (52 checks);
  `story.py` **ALL OK** in one run of both regions (19 scenes: Kanto smoke, bill, mtmoon, ssanne, hideout,
  tower, silph, trade, sevii, mystic, aurora, gamecorner; Hoenn woods, devon, chimney, weather, regirock,
  sootopolis, eon; the long-lived test character's party was trimmed to three offline first, since a full party
  sends `/givemon` to the PC); `r5c_e2e.py --only contest,blender,berry` **ALL OK** (two players + two NPCs through the hall
  cutscene), `--only feebas,link` **ALL OK**; `frontier_e2e.py` every section **ALL OK** over three runs on a
  fresh character (tower…pike; pyramid, ttower, hill, tents, exchange; rest, multi — the first run's Pyramid
  battle hung on a refused move, fixed in the driver, `0774607`).
- Home bundle: `deploy/home/dist/openmmo-home-20261027-df93e9d9.zip` (248 MB, image `openmmo:20261027-3bc89dfb`)
  built with `build-bundle.sh --skip-gradle` from `3bc89df` (the last code commit). Smoke test: unzip,
  `sha256sum -c` OK, `docker load`, compose project `omfinal` with `TUNNEL=off` (+ `TUNNEL_DRY_RUN=1`) on
  127.0.0.1:8090, all services healthy, the tunnel container only printed "no public URL"; invites from
  `login invite create`, `stack_check.py` **ALL OK** (registration with the invite, used-up invite refused,
  login), `e2e.py` **ALL OK**; then `compose down -v` of `omfinal` only (the live `openmmo-*-db` untouched).
  (Compose: this host has no compose plugin; a pinned docker-compose v2.29.7 binary, sha256 checked against the
  release, was used from the session scratch dir.)

## Merged
`feat/r5-scripts` → `feat/r5-contests` → `feat/r5-frontier` → `feat/r5-minigames`, each `--no-ff`, with the
full build + test set and `check-opcodes.py` after every merge (all green, 0 opcode clashes). Conflicts were
additive (both sides kept): GameProtocol registrations, the component's module list, GameAppHandler handlers,
the gateway's StateJson / GameSession / Dispatcher, `main.js` UIS, `net.js` sections, `index.html` styles,
`:web.assets` manifest entries. PROTOCOL.md sections renumbered: 13 r5-contests, 14 r5-frontier,
15 r5-minigames (r5-scripts documents 0xCE in its own section). After the scripts merge the lane's WIP needed
`spotlessApply` and a StateJsonTest update for the new `ui.script` field.

## Reconciliations
- **One handler per decomp special.** `ScriptSpecials` now reports every clash at once and is built at
  start-up (`GameServerComponent.scriptSpecials()`, logged "637 decomp specials have a handler"). The five
  clashes were r5-scripts no-ops on r5-minigames' names (`IsWirelessAdapterConnected`, `TryBecomeLinkLeader`,
  `TryJoinLinkGroup`, `SetFlavorTextFlagFromSpecialVars`, `UpdatePickStateFromSpecialVar8005`); the stubs
  were removed, the owner lane wins.
- **Coverage walk counts the real handlers.** `ScriptCoverageTest` builds the real Dagger component and
  checks every special both decomps reach against the bound handlers (no more "owned by another lane"
  pass by regex). That exposed 32 specials the regex gave to a lane that never implemented them; they are
  now implemented in the owning lane: `FrontierMiscSpecials` (Battle Frontier move tutors from
  `sBattleFrontier_TutorMoves*` / `gTutorMoves`, e-Reader trainer never valid, Apprentice / records /
  cable-club half-party as no-ops) and `MinigameMiscSpecials` (berry powder vendor on Berry Crush's
  powder, interviews / easy chat message / mixing language / Wonder News). Result: 0 unimplemented
  commands, 0 unclaimed specials, kanto 222 + hoenn 494 specials reached.
- **One var accessor.** `ScriptContext.varGet/varSet(id)` and new `varGet/varSet(name)` (by the image's
  var names: `VAR_0x8004`, `VAR_RESULT`, `VAR_ITEM_ID`, `VAR_CONTEST_RANK`...) are the only path to the
  temp / special vars: inside the interpreter they are the VM's gSpecialVar slots, a hand port without the
  VM keeps them in the context. Contests' `SpecialVars.get/set`, minigames' specials (their private
  `specialVar` extension was dropped; it is now the member, so `specialVar(0x8004)` call sites became
  `specialVar(4)`) and frontier's `VAR_0x8004` / `VAR_FRONTIER_FACILITY` reads use it. Unit test in
  `ScriptVmTest`. The bug this fixed was visible: Mr. Fuji's `famechecker` macro rolled the Pokémon Tower
  script back.
- **One coin store.** `script/CoinStore` (bound to r5-minigames' `minigame_players.coins` via
  `MinigameCoinStore`): the interpreter's `checkcoins` / `addcoins` / `removecoins` / `*coinsbox` and the
  Game Corner share the coin case (and push the coin chip).
- **Hand ports vs the interpreter** (decision per label):
  - retired, the interpreter runs the decomp script with the lane's specials: berry trees
    (`BerryTreeScript`, `BerryTree_EventScript_ItemUseWailmerPail`), the three blenders + the link
    blender, the blender records, the hall winner paintings (`showcontestpainting`), the museum 2F frames,
    the whole Celadon / Mauville Game Corner (clerks, gamblers, slot machines via `playslotmachine`,
    roulette, prize corners), the Lilycove ferry attendant. `Bag_ChooseBerry` now gives the interpreter the
    decomp item number (the script continues with `removeitem VAR_ITEM_ID`).
  - kept: the link blender (two online players blend together; the decomp one needs a link cable) and
    the contest receptionists (entry screen, matchmaking with other online players and the live
    contest screen are UI flows the decomp hall cutscene + `startcontest` cannot express); the PC 2F Union
    Room / Wireless Club / Direct Corner attendants and the Mystery Gift deliveryman (they replace
    link-cable features); the Battle Frontier attendants open the frontier desks (their battle-room walks
    are replaced by the desk screens); the other lobby NPCs run in the interpreter.
  - GameServerModule's script registry now merges only these kept labels (`ContestLaneScripts.byLabel`,
    `MinigameScripts.byLabel`); the retired ports are kept as `replaced` maps for reference.
- **Items.** EON / MYSTIC / AURORA TICKET, OLD SEA MAP, FAME CHECKER and POKéBLOCK CASE are real key items
  (ExtraItems slots 114, 115, 120-123). The Mystery Gift ticket also gives the item; the decomp harbor
  scripts' `checkitem` finds it.
- **Wailmer Pail from the bag**: `services/BagItemScript` (multibound); contests' `WailmerPailFromBag`
  runs `BerryTree_EventScript_ItemUseWailmerPail` on the tree in front.
- **Frontier**: EVs are capped at Gen 3's 255 per stat in the model (`EVs`), so frontier sets keep their
  255 (battle EV gain keeps its own 252 cap); Frontier Brain intros are the decomp's battle room texts
  (`BrainTexts`, Silver / Gold per facility); battle.js no longer brings the trainer / player intro sprites
  back when the screen is rebuilt mid-battle (`introGone`).

## Hooks through the interpreter (verified)
- `playslotmachine` / coin commands → r5-minigames (story `gamecorner` scene + browser slots).
- Fame Checker: the `famechecker` macro → `UpdatePickStateFromSpecialVar8005` (Pokémon Tower scene).
- Eon Ticket → Lilycove ferry (first-time sailor route) → Southern Island → the Lati@s event
  (camera pan, `BattleSetup_StartLatiBattle`) (story `eon`).
- FRLG Mystic / Aurora Ticket: Vermilion's decomp script `checkitem ITEM_MYSTIC_TICKET` now finds the key
  item (not driven by a scene).
- Contest hall / museum scripts, berry trees, blenders: interpreter + contests specials (r5c_e2e).
- Battle Frontier: attendants → desks (frontier_e2e).


## Story playthroughs (`web/gateway/test/story.py`, real stack, gateway WebSocket)
- Kanto (one run, ALL OK): smoke (interpreted sign), Bill / S.S. TICKET, Mt Moon (Miguel + fossil),
  S.S. Anne (captain, HM01), Rocket Hideout (poster switch, Giovanni, SILPH SCOPE), Pokémon Tower (Marowak
  ghost, Mr. Fuji, POKé FLUTE), Silph Co (elevator ListMenu, CARD KEY door, Giovanni, MASTER BALL), Route 2
  in-game trade, Seagallop to One Island, Celadon Game Corner (coins + `playslotmachine`).
- Hoenn: Petalburg Woods (Aqua grunt), Devon Corp 3F (Mr. Stone ON_FRAME cutscene, POKéNAV, LETTER), Mt
  Chimney (Maxie), Weather Institute (Shelly, CASTFORM), Regirock (braille-sealed chamber, legendary
  battle), Sootopolis Gym (Juan, RAIN BADGE, TM WATER PULSE), Eon Ticket → Lilycove ferry → Southern
  Island → Lati@s battle. Each passed on the live stack; see "Final verification" for the last full run.
- Driver fixes: replies read after the capped chat window's tail (long runs read stale replies before),
  waits for the scripted legendary battles to start (cutscenes have seconds without a message), the live
  db container name.

## Not done (with reasons)
All the items of the previous list are closed (see "Close-out" above). What remains, and why:
- **REST in the Battle Dome and the link multis**: the Dome's state is a 16-entrant bracket with per-round
  seeding, and a link multi needs the other player to come back at the same moment; both still end the
  challenge when left. (Battle Tents and Trainer Hill / Trainer Tower have no REST in the games either.)
- **Per-category hall maps**: the five `ContestHall<Category>` maps only borrow ContestHall's objects and scripts
  (`shared_events_map`), which the map codegen does not follow, so every category uses the shared ContestHall
  itself (same stage, without the category's floor decoration).
- **The contestants' Pokémon picture during the walk-in** (`ShowContestEntryMonPic`) is not drawn on the field;
  the entry text names the monster and the appeal screen shows it.
- **Navel Rock / Birth Island on foot**: the story scenes sail there through the real Vermilion script and
  Seagallop, then teleport to the summit / base (Navel Rock's 20 maze floors are not walked by the driver).
- The lanes' own "Not done" lists stand otherwise (docs/reports/r5-*.md, docs/reports/r3-*.md).


## Final verification (round 5)
- `spotlessCheck :protocols.game:test :protocols.login:test :server.game:test :server.login:test :codegen:test
  :client:check :web.assets:test :web.gateway:test installDist :web.gateway:installDist`: BUILD SUCCESSFUL,
  no failures, no skips; `scripts/check-opcodes.py`: 405 registrations, 0 clashes.

  | Module | Tests |
  |---|---|
  | server.game | 536 |
  | protocols.game | 105 |
  | server.login | 54 |
  | codegen | 38 |
  | web.gateway | 42 |
  | protocols.login | 12 |
  | web.assets | 8 |
- Assets regenerated, live stack restarted (game 7777, login 2106, gateway 8080, all 127.0.0.1).
- Gateway e2e (`e2e.py`): **ALL OK** on the live stack and on the home bundle.
- `r5c_e2e.py --only contest,blender,berry` **ALL OK** (two players + two NPCs in one contest, the
  interpreter-run blender and berry tree), `--only feebas,link` **ALL OK** (Feebas → Milotic, two players at
  the link blender).
- `frontier_e2e.py`: **ALL OK** on the merged build (every desk and the link multi). On the final build a
  fresh character passed Tower, Factory, Palace, Arena, Dome and Pike in one run, then the Battle Pyramid
  hit the driver's battle-result timeout (a long wild battle); a second run `--only
  pyramid,ttower,hill,tents,exchange,multi` was **ALL OK**. Runs with the e2e accounts lose Tower battles
  because e2e.py trades their strong monsters away (the driver fights with the first move).
- `story.py`: Kanto **ALL OK** (10 scenes, one run); Hoenn **ALL OK** (7 scenes, one run).
- Browser regression (desktop 960×640 and Pixel 7, every shot looked at), `docs/reports/r5-integration/`:
  `d-k1-newgame` / `m-k1-newgame` (a new Kanto game), `d-k2-oaklab` / `d-k3-starter-scene` (Oak's Lab starter
  scene), `d-k5-ssanne` / `m-k5-ssanne` (the seasick captain), `d-k6-silph` / `d-k7-silph-battle`
  (Giovanni at Silph Co.), `cd-d-appeals` / `cd-d-results` / `cm-m-results` (a contest won), `d-t1-tower` /
  `d-t2-tower` / `m-t1-tower` (Battle Tower desk), `d-s-slots` / `m-s-slots` (Celadon slots through
  `playslotmachine`, the coin chip from the shared store), `d-u-A2` / `m-u-B` (Union Room trade request on
  one browser, YES/NO on the other), `d-h1-devon` / `d-h2-devon` / `m-h2-devon` (Hoenn: Devon Corp 3F).
- **Home bundle**: `deploy/home/dist/openmmo-home-20261027-298b7ab2.zip` (248 MB, image
  `openmmo:20261027-298b7ab2`), built with `deploy/home/build-bundle.sh --skip-gradle` after the final
  installDist. Smoke test on this host: unzip + `sha256sum -c`, `docker load`, compose (project `omr5`,
  `TUNNEL=off`, `127.0.0.1:8090`) up and healthy, an invite from `server.login invite create`, two accounts
  registered with it through the gateway, e2e **ALL OK**, then `down -v` (containers, volumes and networks
  removed).


---

# Round 3 integration (branch `own-client`)

Backup tag before the merge: `pre-r3`. Each branch's report is now `docs/reports/r3-<name>.md`.
Integration screenshots are in `docs/reports/r3-integration/`.

## Merged
`feat/r3-battle` (e95a423), then `feat/r3-items`, `feat/r3-field`, `feat/r3-social` and `feat/r3-present`
(all `--no-ff`). Conflicts were resolved by keeping the features from both sides. PROTOCOL.md's r3 sections
were merged into one document, and colliding section numbers were renumbered.

## Reconciliations (one implementation each)
- **Persisted status**: one column, `pokemon.status` (Gen 3 packed status1), added once by
  `V8_1__add_pokemon_battle_state.sql`. `V8_3` no longer adds it. The Poké Center heal, the status items,
  battle carry-over and `/monset` all use it.
- **Evolution**: `EvolutionService` is the only engine. `MonGrowth` keeps level/HP/EV growth only. Rare
  Candy and stones (`FieldItemService`), level-up after battle (`BattleService.afterBattle`) and trades
  (`TradeEvolutionHook` → `EvolutionService.onTraded`) all start its scene. The browser plays that scene
  for every trigger.
- **Friendship**: persisted (`pokemon.friendship`) and changed per Gen 3 (`Friendship.adjust`: steps,
  level-ups, vitamins/berries, fainting). Day/night friendship evolutions work.
- **Badges**: `BadgeService` is the single source of truth, also for HM gating. Gen 3 `obedienceLevel` is
  applied in the battle engine (disobedience: loafing, napping, wrong move, self-hit).
- **Dev commands**, one of each, all permission-gated (DEVELOPER, `/grant` ADMIN):
  - `/tp`: `r:b:m x y`, `r b m x y` or `<MapName> x y [facing]`. It replaces items' `/tp` and field's `/fieldtp`.
  - `/monset <slot> status|held|friendship|hp|level|almost <v>`: replaces `/setstatus`.
  - `/fielddev`, `/grant`, `/givemon`, `/giveitem`, `/givemoney`, `/story`, `/testbattle`, `/testtrainer`, `/catch`.
- **One of each, also**:
  - `Pokemon.heldItem`
  - TileBehavior + `MetatileBehaviorNames` classifier
  - the surf/cave/rock-smash/fishing encounter tables in `EncounterService`
- **Opcodes**: `scripts/check-opcodes.py` and `OpcodeClashTest` check that no opcode is registered twice in
  either direction.

## Hooks wired
- Trade evolution (TradeService → EvolutionService).
- Battle `music` key → gym/elite/champion/rival/team/pvp themes.
- Shiny sparkle when a shiny monster enters.
- Other players' gender (FieldAvatar flag 0x04).
- Duel challenges respect the block list.
- Fishing with the Old/Good/Super Rod.
- Gen 3 field poison.
- Rock Smash encounters.
- The last healed Pokémon Center is stored. Teleport and white-out (with Gen 3 money loss) return there.
- Fanfares pause the map music and resume it at the same position.
- HM07, HM08 and the Wailmer Pail are in the item table.
- The Escape Rope uses each map's `allow_escaping`.

## Gaps closed after the merge
- Friend requests have an accept/decline step.
- Low Kick uses species weight (generated from the decomp's `pokedex_entries.h`).
- Sketch, Snatch and Magic Coat work.
- Trainers switch in battle (Gen 3 AI).
- **Forced movement** (`field/ForcedMovement.kt`): ice, water currents, the waterfall push, walk/slide
  tiles, FRLG spinners, muddy slopes and the Cycling Road pull. The server plays it as unsteerable
  movement, and the browser holds input while the player is carried.
- **Mach Bike** (Emerald): accelerates NORMAL→FAST→FASTEST, rolls on when released and slows one tier per
  step (`bike.c`). A muddy slope can only be climbed at full speed.
- **Field-move visuals**:
  - Cut trees and Rock Smash rocks play their `ANIM_REMOVE_OBSTACLE` frames.
  - Strength boulders kick up dust where they stop.
  - A JumpSpecial hop onto and off the surf blob.
- **Trainer rematches**: rematch tables are generated from both decomps (`RematchParser`, 221 FRLG / 65
  Emerald entries).
  - FRLG VS Seeker (`sRematches`):
    - the battery charges over 100 steps;
    - trainers on screen (±7/±5) answer 70% of the time;
    - tiers are story-gated (`TryGetRematchTrainerIdGivenGameState`);
    - answers reset on every map change.
  - Emerald Match Call (`gRematchTable`):
    - needs 5 badges and 255 steps;
    - on each map entry, each beaten trainer on that map wants a rematch 31% of the time;
    - the ready state is persisted as a story flag.
  - A trainer that is ready approaches or battles with its next party.
- **Reflections**: sprites are mirrored on pond water, puddles and ice, with a water tint, clipped to the
  reflective tiles.

## Not done (with reasons)
- **Acro Bike tricks** (wheelie, bunny hop, rail riding): the exported sheets don't include the
  wheelie/hop frames, and the input model has no B-held trick states. Rails and bumpy slopes are still just
  "Acro Bike only" tiles.
- **Emerald secret bases**: creating a base needs several pieces that don't exist yet:
  - the Secret Power tile interaction;
  - the `SecretBase_*` layout maps as warp targets;
  - per-player persisted bases;
  - a grid placement UI;
  - decoration metatile/object overrides in the pre-rendered map images.

  That is a feature of its own. Decorations can still be bought and stored.
- **Emerald palette-blend animations** (e.g. the Battle Dome lights): maps are exported as pre-rendered
  images, so changing palettes at runtime needs a per-palette layered export.
- **Match Call UI**: there is no Pokénav. Emerald rematches are found by walking up to the trainer. FRLG
  trainers that want a rematch show the "!" emote, not "!!".
- **Rematch intro texts**: rematches reuse the trainer's normal intro text, not its rematch-specific
  dialog.
- The remaining smaller items in `docs/reports/r3-*.md` "Not done" that are not listed above are still
  open, for example:
  - Beauty (Feebas) evolution;
  - Imprison, Grudge and Follow Me;
  - Flame Body egg cycles;
  - bike/flute encounter-rate modifiers.

## Running the stack
```
export JAVA_HOME=~/.jdks/jdk-25.0.5+8
./gradlew --no-daemon -Porg.gradle.java.installations.paths=$JAVA_HOME :keys:generateGame installDist :web.gateway:installDist
./gradlew --no-daemon -Porg.gradle.java.installations.paths=$JAVA_HOME :web.assets:run --args="--decomp decomp --out web/static/assets"
scripts/live-stack.sh start     # game 7777, login 2106, gateway http://127.0.0.1:8080/ (all 127.0.0.1), detached
scripts/live-stack.sh status    # pids (= process groups) in build/live/*.pid
scripts/live-stack.sh stop      # stops exactly those pids; run it before installDist (it replaces the jars)
python3 web/gateway/test/e2e.py --ws ws://127.0.0.1:8080/ws --a yicheng:pikachu123:Yicheng --b friend:bulbasaur123:Friend
```
The two postgres containers (`openmmo-login-db` :20011, `openmmo-game-db` :20021) must be running.

## Verification (final)
- `spotlessCheck :protocols.game:test :server.game:test :client:check :web.assets:test :web.gateway:test installDist :web.gateway:installDist`:
  BUILD SUCCESSFUL, with no failures and no skips:

  | Module | Tests |
  |---|---|
  | server.game | 465 |
  | protocols.game | 99 |
  | codegen | 38 |
  | web.gateway | 36 |
  | web.assets | 8 |
- Gateway e2e against the live stack: **ALL OK**.
- Browser regression (`~/.local/webtools/webshot.sh`, desktop 960×640 and Pixel 7 mobile). Every
  screenshot was looked at.
  - Kanto: new game start; Viridian with a starter; a wild battle with a catch (desktop and mobile).
  - Brock beaten: BOULDERBADGE, TM39 and `FLAG_BADGE01_GET` persisted.
  - Cutting the Cerulean tree (break frames), then walking through it.
  - Viridian pond: the reflection, then the surf hop and surfing.
  - Icefall Cave: an ice slide (6 tiles) with an ice reflection.
  - Viridian Gym: the spinners.
  - Kadabra traded between two browsers, evolving into Alakazam on the receiver's side.
  - A market buy, and the friends whisper and friends list.
  - Hoenn: new game start in the truck (desktop and mobile).
