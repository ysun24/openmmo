# Round 5 integration (branch `own-client`)

Backup tag before the merge: `pre-r5`. Lane reports: `docs/reports/r5-{scripts,contests,frontier,minigames}.md`
(r5-scripts left no REPORT.md; its summary was written from its commits). Screenshots:
`docs/reports/r5-integration/`.

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
  - kept: the contest receptionists (entry screen, matchmaking with other online players and the live
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
scripts/live-stack.sh start     # game 7777, login 2106, gateway http://127.0.0.1:8080/ (all 127.0.0.1)
scripts/live-stack.sh status    # pids in build/live/*.pid; stop by those pids
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
