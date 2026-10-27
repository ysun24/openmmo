# r3-field — field moves, travel modes, field effects, trainers' sight

## Built
**Server (authoritative, `server.game/.../field/` + `services/FieldService.kt`)**
- `FieldRules`: the Gen 3 step rules from `field_player_avatar.c` / `event_object_movement.c`. It covers elevation
  mismatch (0 and 15 are wildcards, water 1, land 3), elevation tracking (`ObjectEventUpdateElevation`), ledge hops
  (2 tiles, landing checked), directional walls (`IMPASSABLE_*`), surfing (water walkable only while surfing,
  `CanStopSurfing` onto default-elevation land), STRENGTH boulders (`TryPushBoulder`: free, same-elevation,
  non-door tile beyond), bike terrain (muddy slopes need the Mach Bike uphill, bumpy slopes/rails need the Acro Bike),
  `canStartSurf` (`IsPlayerFacingSurfableFishableWater`) and `waterfallClimb`. `MovementService` now uses it.
  It also enforces elevation across map connections and sends a ledge landing back to the client.
- HM obstacles: cut trees, Rock Smash rocks (followed by a `rock_smash_mons` encounter) and STRENGTH boulders.
  They are recognised by script/gfx, block movement on the server, and run the decomp's own texts
  (`Text_CutTreeDown` …) with yes/no prompts, badge + `checkpartymove` checks, and per-game badge order
  (`FieldMoves`: FRLG Flash1 Cut2 Fly3 Str4 Surf5 RS6 WF7; Emerald Cut1 Flash2 RS3 Str4 Surf5 Fly6 Dive7 WF8).
  A cut or smashed obstacle stays gone until the next map change (the decomp's temporary flags). Pushed boulder
  positions are persisted in story vars (`field/boulder/r:b:m:id`), and `NpcService` spawns the boulders there.
- Field moves (c2s 0xF4 `FieldActionPacket`): CUT, FLASH (dark maps, `requires_flash`), ROCK SMASH, STRENGTH,
  SURF (party menu or A on water), WATERFALL (surfing north), DIVE / surface (Emerald `dive`/`emerge`
  connections), FLY (outdoors, visited towns, decomp heal-location landing spots, Littleroot by gender),
  TELEPORT (last town visited), DIG and ESCAPE ROPE (`allow_escaping`, returns to the last outdoor spot,
  rope consumed), SWEET SCENT (forced encounter on an encounter tile), bike on/off (Bicycle item; Mach or Acro
  in Hoenn; `allow_cycling`), Itemfinder (nearest hidden item on screen). Running needs the running shoes
  (`FLAG_SYS_B_DASH`) and `allow_running`.
- Water encounters while surfing, plus land encounters on cave/indoor encounter ground. This uses Emerald's
  `MB_CAVE` / `MB_INDOOR_ENCOUNTER` and FRLG's attribute encounter type, so caves now have encounters.
- Item balls (`finditem`, from map scripts and `data/scripts/item_ball_scripts.inc`: 592 balls) and hidden
  items (295 `hidden_item` bg events), each once per flag, with "{PLAYER} found one X!".
- PC tile (`MB_PC`): "booted up the PC", then `ui.open` (Pokémon storage in Pokémon Centers, item storage elsewhere).
- Trainers (676 trainer npcs): codegen extracts `trainerbattle_*` id, sight and intro/defeat/post text ids.
  Line of sight runs after every step (`CheckForTrainersWantingBattle`: facing / look-around directions,
  sight range, clear path). It shows the "!" (s2c 0xE8 flag), the trainer walks up, then intro, battle,
  defeat text, and a defeated flag. Talking to a trainer battles it, or gives its post-battle line once beaten.
- FLY towns are marked visited on entry (`FLAG_WORLD_MAP_*` / `FLAG_VISITED_*`).
- s2c 0xE9 `FieldInfoPacket` is sent on every map entry and change. It carries dark/flash/run/bike/strength/escape
  flags, the usable field moves, visited FLY towns, and a UI request.
- s2c 0xE8 `FieldAvatarPacket` carries travel mode and run state. It goes to the player, its observers, and new
  observers (`PresenceService`). Coord events now respect their elevation (`GetCoordEventScriptAtPosition`).
- Dev commands: `/fielddev [moves]` gives all badges, running shoes, FLY towns, a Bicycle, Escape Ropes, and the
  HM moves taught across the party. `/fieldtp <MapName> <x> <y>` teleports.

**Codegen / data**: `MapDef` gains `name`, `mapsec`, `mapTypeName`, `requiresFlash`, `allowCycling`,
`allowRunning`, `allowEscaping`, `hiddenItems`. `NpcDef` gains `item`, `flag`, `trainer`. `MapManager.getMapByName`.
`TileBehavior` gains water/deep water/waterfall/PC/sand/puddle/ash/bike terrain/impassable/no-running/seaweed/
encounter-ground (appended, so old ordinals are unchanged). `common/enums/MetatileBehaviorNames` is the single
name-based classifier: the server codegen and the web exporter share it, and FRLG's terrain/encounter attribute
bits are honoured.

**Web assets**: per-layout `beh.bin` behaviour grids, map rules (`allowCycling`/`allowRunning`/`requiresFlash`),
`borderAnim` (animated sea borders), and `fieldEffects` sprite strips (surf blob, grass, shadow, footprints,
tire tracks, splash, ripple, jump splash/grass/dust, ash puff, "!").

**Browser**: `world.js` mirrors `FieldRules` (elevation, ledges, surf / stop surf, directional walls, bike
terrain, boulder push = an unpredicted step). It sets step times per mode, runs only with `canRun`, and predicts
the 2-tile ledge hop (520 ms). It tracks other entities' `mode`/`run`/`jump`/`exclaim` and generates ground
effects. `render.js` draws:
- bike/surf/underwater player sheets for self and others, and the surf blob (bobbing)
- the ledge-hop arc with a ground shadow, and tall-grass cover over the legs
- footprints/tire tracks (blinking out), grass rustles, splashes, ash puffs
- the trainer "!", dark caves with a FLASH circle, and animated borders

`ui/field.js` adds a Field button and F key for a field-move panel (moves, bike, Escape Rope, Itemfinder) and a
FLY map of visited towns. The C key toggles the bike. It reacts to `ui.open`.

**Hoenn start**: a new region-1 character starts in the truck. The walk to Littleroot, Mom, the clock, May's house
and May, the Route 101 Birch rescue and the starter pick all played through to "Brendan received the TORCHIC!".
The fix was the starter picker's two prompts, which showed `[text 0x105e8c53]`. They are now in
`codegen/dialog/hoenn.extra.json`.

## How verified
- Real stack (own DBs 21011/21021, login 2206, game 7877, gateway 8520), scripted over the gateway WebSocket and
  in headless Chromium, desktop and mobile. I looked at every screenshot; copies are in `docs/reports/field/`.
  - Ledge jump with shadow (`jump1`, `landed`).
  - Surfing on Pallet's pond, and back on land (`surf1`).
  - Bike (`bike`) and the field menu (`fieldmenu`).
  - Cerulean cut tree: prompt, cut, walk through (`tree`, `cutq`, `pastree`).
  - Rock Tunnel dark, then after FLASH (`dark`, `flash`).
  - Viridian Forest trainer: "!", walk-up, intro (`spot1`-`spot3`).
  - STRENGTH boulder pushed (`b0`, `b2`). By script I also checked the push survives a server restart and that
    the Rare Candy ball behind it can then be taken.
  - FLY map and FLY Viridian → Cerulean (`flymap`, `flown`), and the PC tile opening the PC panel (`pc1`).
  - A second browser (Gary) watching Ash surf (`obs1`).
  - Hoenn Littleroot and Route 101 grass on mobile (`littleroot`, `grass1`).
- Unit tests: `FieldRulesTest` (14: elevation, ledges, surf / stop surf, boulders, bike terrain, directional walls,
  SURF start, waterfall, bike rules, badge tables, trainer sight incl. wall/defeated). Also `FieldStateJsonTest`
  (4: entity fields, `world.field`, `ui.open`, `field` routing) and `ExportTest.behaviourGridsMatchTheServerClassification`.
- `:server.game:test :protocols.game:test :client:check :web.gateway:test :web.assets:test :codegen:test`
  and `spotlessCheck` pass. The gateway e2e ends with **ALL OK**. One run directly after `/story new-game` timed out once in the trade step (outside this branch); the rerun passed.
- Oak lab rival trigger: walking out at x=7 after picking a starter fires the rival battle. Our collision blocks
  row 8 outside x 5-7, the same as the decomp's triggers. The real gap was elevation: the server ignored it, so
  players could walk onto water and cliff tiles the GBA refuses. That is fixed, and coord events now check their
  elevation. An audit over all maps (`trigger_audit.py` in scratch) found 63 open line ends in 112 trigger lines,
  and the decomp has the same layout. On the GBA these are covered by other triggers, NPCs or elevation.

## Not done
- No forced movement: water currents, ice, spinners and FRLG Cycling Road pull-down behave like ordinary tiles.
  Acro Bike tricks (wheelie, bunny hop, rail riding) are not modelled; rails and bumpy slopes are just
  "Acro Bike only". Mach Bike acceleration is not modelled (constant speed).
- No Cut field-move animation or Emerald's grass-cutting around the player. No Rock Smash or Strength animation;
  the boulder slides. No surf hop-on jump animation (it is a 1-tile slide).
- Dive is implemented through `dive`/`emerge` connections but was not tried in the browser; no Hoenn character
  with the Mind Badge was played that far. Underwater movement uses walk speed.
- Teleport goes to the last FLY town visited, not to the last Pokémon Center used: no heal location is stored.
- Emerald palette-blend animations (Battle Dome floor lights) are not done, and neither are reflections.
- The tall-grass cover is drawn from the map image, not the field effect sprite.
- Trainers are scripted generically. Double battles, rematches and Emerald's match-call registration are not
  handled, and Gen 3 "trainer stays where it walked to" resets on map reload. Gym leaders (scripted, not trainer
  NPCs) are untouched.
- Several outdoor maps report `canRun=false` because their map header disallows running. That is
  faithful to the decomp; there is no "run anywhere" option.

## Files touched outside my lane
- `common/.../TileBehavior.kt` (values appended) and the new `MetatileBehaviorNames.kt`.
- `codegen` (maps generator, `MapDef`/`NpcDef`/`MapManager`, `build.gradle.kts` maps args) and
  `codegen/dialog/hoenn.extra.json` (new).
- `protocols.game`: new `packets/field/FieldPackets.kt` and three registrations in `GameProtocol.kt`.
- `server.game`:
  - `PlayerState` (fields), `EncounterService` (water/cave/rock smash/sweet scent), `NpcService` (removed objects,
    boulder positions), `PresenceService` (avatar to new observers), `LoginService` (field map-enter hook).
  - `InteractionService` (field hooks first), `MapEntryScripts`/`MapScriptService` (coord elevation),
    `GameAppHandler` (one handler), `ChatCommandModule` (two commands).
  - Test support `Services.kt` (MovementService now takes FieldService).
- `client`: `GameClient` (FieldAvatar/FieldInfo handling, `fieldAction`, ledge-hop prediction) and
  `WorldState` (entity travel fields, field info).
- `web/gateway`: `Dispatcher` (`field`, `step.jump`), `GameSession`, `StateJson` (entity extras, `world.field`,
  `ui.open`), and a new test.
- `web/static/js/main.js`: import plus `UIS` entry plus one `onPress` line. `web/static/js/ui/field.js` is new.
- `web/assets-export/build.gradle.kts` (+ `:common`).

## PROTOCOL changes (additive)
`docs/web/PROTOCOL.md` "r3-field": c2s `field`, optional `step.jump`; `world.entities[].mode/run/jump/exclaim`,
`world.field`, `ui.open`; map `behavior`/`allowCycling`/`allowRunning`/`requiresFlash`/`borderAnim`, top-level
`behaviors` and `fieldEffects`. Opcodes: c2s 0xF4, s2c 0xE8, 0xE9. s2c 0xF4-0xF7 were already retail, so I took
0xE8/0xE9. I checked the other r3 worktrees: battle uses s2c 0xE6 / c2s 0xF0, 0xF2; items s2c 0xE7 / c2s 0xF8; social c2s 0xFC-0xFE / s2c 0xFD. None clash.
