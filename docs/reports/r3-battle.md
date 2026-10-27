# r3-battle: the Gen 3 battle system

## What was built

### Server battle engine (`server.game/.../battle/`)
`TurnEngine` was rewritten as a Gen 3 engine. The rules follow the decomp's `src/battle_main.c` (turn order), `battle_script_commands.c` (move effects, catch formula) and `battle_util.c` (end of turn, abilities, items). It is split over `TurnEngine` (API), `MoveRunner` (order, attack canceler, accuracy, damage), `MoveEffects`, `StatusRules`, `AbilityRules`, `ItemRules`, `BattleAi`, `CatchCalculator` and `BattleCtx`.

- **Turn order.** Switches and items go first. Moves then go by priority, then Quick Claw, then speed (paralysis quarters it; Swift Swim, Chlorophyll and Macho Brace apply), then a random tie-break. The engine works on battle positions (`BattlerRef`), so singles and doubles share one code path.
- **Major status.**
  - Burn: 1/8 hp at the end of the turn, and halves physical damage unless the monster has Guts.
  - Poison 1/8; bad poison n/16, growing each turn.
  - Paralysis: a 25% chance to lose the turn, and speed quartered.
  - Sleep: lasts 2-5 turns (Early Bird counts down twice as fast), and the monster acts on the turn it wakes. Rest gives a fixed sleep.
  - Freeze: 20% thaw chance per turn. A fire hit, Flame Wheel or Sacred Fire also thaws it; nothing freezes in sun.
  - Type immunities (Poison/Steel, Fire, Ice) and ability immunities (Insomnia, Vital Spirit, Immunity, Limber, Water Veil, Magma Armor) apply, as do Safeguard and Synchronize.
- **Volatile effects.**
  - Confusion lasts 2-5 turns, with a 50% chance of a 40-power self-hit.
  - Flinch only works if the target hasn't moved yet; Inner Focus blocks it; Fake Out flinches for certain.
  - Also implemented: attraction, Leech Seed, trapping moves, Disable, Encore, Taunt, Yawn, Substitute, Protect / Endure (with the falling chance on repeated use), Focus Energy, Foresight, Lock-On, Curse, Nightmare, Perish Song, Destiny Bond, Ingrain, Charge, Stockpile, and Rage.
- **Move effects.** Almost every Gen 3 `MoveEffect` is implemented:
  - every stat move, plus secondary chances doubled by Serene Grace and blocked by Shield Dust;
  - multi-hit moves and Triple Kick;
  - drain, recoil and crash moves, and Explosion;
  - two-turn and semi-invulnerable moves (Fly/Dig/Dive with the moves that reach them), and recharge;
  - rampage moves, Rollout and Fury Cutter;
  - fixed-damage moves, OHKO moves, Counter and Mirror Coat;
  - variable-power moves: Flail, Return, Frustration, Eruption, Magnitude, Present, Hidden Power, Weather Ball, Facade, SmellingSalt, Revenge;
  - weather moves (5 turns), screens, Mist, Safeguard and Spikes;
  - healing moves, Wish, Future Sight, Heal Bell, Haze, Pain Split and Transform;
  - Roar (ends a wild battle, drags out a trainer's monster), Teleport, Baton Pass, Metronome, Sleep Talk, Assist, Mirror Move and Nature Power;
  - Thief, Knock Off and Trick; Rapid Spin; Pay Day; Camouflage and Conversion 1/2.

  The few effects left (Sketch, Snatch, Magic Coat and similar) fail with "But it failed!".
- **Damage.** It follows `CalculateBaseDamage` and `Cmd_damagecalc`:
  - stages, with crits ignoring the stages that work against the attacker;
  - crit stages from high-crit moves, Focus Energy, Scope Lens, Lucky Punch and Stick;
  - Reflect and Light Screen (2/3 in doubles), spread moves at half damage, weather, Flash Fire, Mud Sport / Water Sport;
  - the pinch abilities Overgrow, Blaze, Torrent and Swarm;
  - Huge Power, Pure Power, Hustle, Guts, Marvel Scale, Thick Fat and Plus/Minus;
  - held items: type boosters, Choice Band, Light Ball, Thick Club, Soul Dew, DeepSeaTooth/Scale and Metal Powder;
  - STAB, the type chart with Foresight, and the random factor.
- **Abilities.**
  - On entry: Intimidate, Drizzle, Drought, Sand Stream, Trace, Pressure (also costs an extra pp), Cloud Nine and Air Lock.
  - On contact: Static, Flame Body, Poison Point, Effect Spore, Rough Skin and Cute Charm.
  - Absorbing: Levitate, Volt Absorb, Water Absorb and Flash Fire.
  - Wonder Guard, Soundproof, Sturdy (against OHKO moves, as in Gen 3), Damp, Liquid Ooze, Rock Head and Suction Cups.
  - End of turn: Speed Boost, Shed Skin and Rain Dish.
  - Truant, Natural Cure, Compound Eyes, Sand Veil and Battle/Shell Armor.
  - Stat-drop guards: Clear Body, White Smoke, Hyper Cutter and Keen Eye.
  - Trapping: Shadow Tag, Arena Trap and Magnet Pull.
- **Held items.** The effect table is generated from the decomp's `src/data/items.h` (`HOLD_EFFECT_*` and their params, 71 items):
  - Leftovers and Shell Bell;
  - every berry: hp berries, confusion berries keyed to the nature's disliked flavour, pinch stat berries, Lansat, Starf, the status berries, Lum, Persim and Leppa;
  - White Herb, Mental Herb, Quick Claw, King's Rock, BrightPowder / Lax Incense, Focus Band, Scope Lens and Choice Band (with its move lock);
  - Smoke Ball, Macho Brace, Amulet Coin, Lucky Egg, Exp. Share and Everstone.

  Wild monsters roll the species' held items (50% common, 5% rare). Trainer monsters carry their decomp held items.
- **AI.**
  - Wild monsters use a random move at a random foe.
  - Trainers score their moves the way the Gen 3 AI scripts do (check bad move, try to faint, check viability). They skip immune targets, status a monster that already has one, and stat moves at the cap.
  - Trainers use their decomp `items` (potions and status heals) when it makes sense.
- **Escape.** The Gen 3 run formula (speed ratio + 30 per attempt), Smoke Ball, Run Away and the trapping blockers.
- **Catching** (`CatchCalculator`). This is `Cmd_handleballthrow`:
  - catch rate × ball ÷ 10 × hp factor, ×2 for sleep or freeze, ×1.5 for poison, burn or paralysis;
  - the shake test is `1048560/sqrt(sqrt(16711680/odds))` against 16-bit rolls, so a throw shows 0-3 shakes, or 3 and caught;
  - ball multipliers: Poké, Great, Ultra, Master, Safari, Net, Dive (underwater maps), Nest, Repeat (already owns that species), Timer (battle turns), Luxury and Premier;
  - the ball is taken from the bag; trainers block balls ("Don't be a thief!"); a sixth catch goes to the PC.
- **In-battle bag** (`BattleItemEffects`):
  - potions and drinks, revives, single-status heals, Full Heal, Full Restore;
  - PP restorers (one-move items ask for the move);
  - X items, Dire Hit, Guard Spec, Poké Doll / Fluffy Tail;
  - every ball.

  The item is consumed and spends that position's turn. Balls are refused against trainers and players. The usable bag goes to the client in the extension state.
- **Persistence.** Migration `V8_1` adds `pokemon.status` (Gen 3 packed status1), `friendship` and `held_item`, and `Pokemon` gains those three fields.
  - Status, held items and friendship are written back after every battle. Natural Cure clears the status; a faint costs 1 friendship and a level-up gives +5/+3/+2.
  - The status carries into the field and the next battle. A Poké Center heal (`StoryPlayerService.healParty`) and the field status heals clear it.
- **EXP.** It is split between the party monsters that fought the defeated monster, with Exp. Share taking half, Lucky Egg ×1.5 and traded monsters ×1.5. EVs go to every recipient, and moves are learned on every level gained.

### Double battles
- **Trainer doubles.** A decomp trainer with `doubleBattle = TRUE` (Hoenn twins, Tate & Liza, and so on) is fought 2v2 when the player has two healthy monsters.
- **Turn flow.** Each position picks with a target. Spread moves hit both foes at half damage; Earthquake-style moves hit the ally too. Fainted positions are refilled one by one; a position with no bench left stays empty.
- **PvP 2v2.** The duel rules carry a `doubles` flag. Both views share the sides and the field, and each player's choices (targets flipped) resolve in one engine call.
- **Per-viewer names.** Battle lines carry name tokens that each viewer reads as "X" / "Foe X" / "Wild X".

### Evolution (`EvolutionService`, `codegen` evolution table)
- **Data.** A new codegen generator reads `src/data/pokemon/evolution.h` (184 rows, national dex numbers). It also emits the GBA item-name table and the hold-effect table.
- **`tryEvolve(mon, trigger)`** follows `GetEvolutionTargetSpecies`. Level-up methods are level, friendship ≥ 220 (day 12-23 / night 0-11), attack vs defense, Silcoon/Cascoon by personality, and Ninjask, which also leaves a Shedinja behind. Trade and trade-with-item are covered too. Stones are matched by item. An Everstone blocks level evolutions.
- **Hooks.** After each battle the monsters that levelled are checked. `useEvolutionItem(session, charId, monId, itemId)` is the stone hook, and `FieldItemService` calls it for evolution stones. `onTraded(charId, mon)` is the trade hook for the social branch.
- **Scene.** It plays over `{"t":"evo"}` and the client answers with `EvolutionReplyPacket`; B stops it.
- **On completion.** The species changes and the stats are recomputed (lost hp stays lost). The new species' moves at the current level are learned, with a prompt when the moveset is full. The party is synced, and a stone is used up only when the evolution completes.

### Wild encounters (`EncounterService`, checked against `src/wild_encounter.c`)
- **Tiles.** Before, only grass rolled. Now cave floors (`MB_CAVE`), `MB_INDOOR_ENCOUNTER`, deep sand / footprints and ash grass roll the land table too. FireRed tiles roll by their metatile **encounter-type bits** (land / water), which the codegen now reads. Surfable water rolls the WATER table.
- **Rate.** Table rate × 16 against 2880, with a 60% gate when stepping onto a new tile type. The lead's ability changes the rate (both games: Stench, Illuminate; Emerald only: White Smoke, Arena Trap), as does a Cleanse Tag.
- **FireRed only.** The per-map step cooldown and the miss rate buff.
- **Emerald only.**
  - Static / Magnet Pull force an Electric / Steel slot half the time.
  - Hustle / Vital Spirit / Pressure push the level to the slot's maximum half the time.
  - Keen Eye / Intimidate skip weak encounters.
- **Slots.** The slot percentages come from the decomp JSON, and the repel works as before.
- **Background.** `terrainFor` gives the battle background as `BattleSetup_GetEnvironmentId` does (grass, long grass, sand, cave, building, water, pond, underwater, mountain, plain).

### Client, gateway and browser
- **Protocol.** There is a new extension channel (see PROTOCOL changes). `BattleClient` turns it into state: format, positions, statuses, confusion, abilities, items, weather, trainer pic, background, music, bag and the evolution scene. It also adds `fightAt` (position and target), `useItem`, `throwBall(item)` and `answerEvolution`. FX entries carry entity ids, sent as strings because 64-bit ids don't fit a JS number.
- **`battle.js` / `battle.css`:**
  - double-battle layout with compact info boxes, and a target picker (foe 1 / foe 2 / ally) for single-target moves;
  - PSN/BRN/PAR/SLP/FRZ status chips in the info boxes and party lists;
  - a full BAG with ITEMS / BALLS / BATTLE pockets and party / move target pickers;
  - the terrain background from the exported battle backgrounds;
  - the trainer's front sprite sliding in and stepping aside at send-out; the player's back sprite with its throw frames;
  - move animation sprite sheets per type flying from attacker to target;
  - ability and item banners, a weather overlay, a confusion swirl, a sleep "Z";
  - ball colours per ball; the break-open animation is now reachable;
  - the evolution scene: flashing silhouettes speeding up, B stops it, "Congratulations!" with the learned moves. It waits until the battle screen is gone.
  - the duel dialog gains a 1v1 / 2v2 choice.
- **Assets** (`BattleAssetsExport`, a new file in `:web.assets`):
  - 166 trainer front sprites;
  - Brendan, May, Red and Leaf back sprites;
  - 20 Emerald and 18 FireRed battle backgrounds, rendered from tiles, tilemap and palette. They include the leader, gym, stadium, Elite Four, champion and Aqua/Magma palettes, chosen by trainer class.
  - 22 animation sprite sheets.
- **Music.** The battle state's `music` key is `wild|trainer|gym|elite|champion|rival|team|pvp`. `manifest.battleMusicExtra[region]` has the elite / champion / rival / team / pvp songs, next to `battleMusic.gym`.

## How it was verified
- **Tests.** `:server.game:test :protocols.game:test :client:check :web.gateway:test :web.assets:test :codegen:test` ran 505 tests with 0 failures. New tests:
  - `Gen3RulesTest` (28 tests):
    - status: burn damage and halving, poison and toxic growth, type immunities, paralysis speed and Thunder Wave vs Ground, sleep turns and acting on wake, freeze thaw by fire, confusion and Own Tempo, flinch / Fake Out, the secondary chance;
    - abilities: Intimidate, Levitate, Sturdy, Volt Absorb, Static rate, Swift Swim, Overgrow;
    - held items: Leftovers, Oran, Lum, Choice Band, Quick Claw;
    - catching: the catch odds and every ball multiplier, the shake distribution;
    - the rest: bag items, doubles spread and targeting, Protect, Rain Dance's 5 turns, escape, priority, status persistence.
  - `EvolutionAndEncounterTest` (14 tests):
    - evolution: level, Everstone, friendship with day/night, stones, trade with and without an item, Wurmple, the scene proceeding and B stopping it, the stone consumed;
    - encounters: encounter tiles, terrain mapping, slot percentages, rate and Stench.
  - `BattleServiceR3Test`:
    - a double trainer battle (format 2, waiting for both picks, a KO of both foes);
    - the trainer blocking a ball;
    - a potion consumed and spending the turn;
    - status persisting after a battle.
  - Codec tests for the new packets and the doubles flag, plus Dispatcher tests for every new message.
- **Gateway e2e.** ALL OK on 3 consecutive runs against freshly created databases. One earlier run timed out at the trade step ("offers visible"); that step is outside the battle code, and the next three runs passed.
- **Real stack** (login 2196, game 7867, gateway 8510, own Postgres pair, docker Chromium). I looked at every screenshot; they are in `docs/web/r3-battle/`.
  - **Status battle (01-05).** The poison set on the monster before the battle shows as PSN when it starts. The BAG offers Antidote with a party target and cures it. Thunder Wave paralyzes Snorlax (PAR chip). Throughout the rest of the run the caught Snorlax kept PAR in the party list.
  - **Catch fails, then succeeds (06-10).** The BALLS pocket; a Poké Ball breaks free ("Oh, no! The POKéMON broke free!"); then the Master Ball (purple) holds: "Gotcha!", result CAUGHT, ball taken from the bag.
  - **Evolution.**
    - 11-13: a Thunderstone on Pikachu evolves it into Raichu.
    - 14 and 14b: Charmander levels to 16 in a battle and the scene starts after the battle screen closes; this run evolved into Charmeleon.
    - 15: B stops Charmeleon → Charizard ("Huh? CHARMELEON stopped evolving!"), and the species is unchanged.
  - **Hoenn double trainer battle (16-20d).** Tate & Liza (`/testtrainer 271 hoenn`) on the gym-leader background, with Claydol and Xatu against Charmeleon and Raichu. The target picker appears (CLAYDOL / XATU / RAICHU ALLY). Claydol's Earthquake hits both of ours and its own ally, and Xatu, a Flying type, is immune. There are two forced replacements, a second turn is fought, and an empty position stays empty.
  - **2v2 PvP (21-24).** Two concurrent browsers duel with `doubles: true`. Each picks targets for both positions. We saw Leech Seed, Thunder Wave paralysis and the end-of-turn sap. Each side reads its own monsters without the "Foe" prefix.
  - **Trainer intro (27-29).** Brawly's front sprite and Red's back sprite before the send-out.
  - **Mobile (25, 26).** A burned monster's battle and the bag on a Pixel 7.

## Not done / known limits
- **Encounter features from other lanes.**
  - Fishing / Rock Smash encounters need the rods and the field move (r3-field / r3-items). `EncounterService` reads the decomp tables for them but does not trigger them.
  - Bike and flute rate modifiers aren't applied; the server has no bike or flute state.
  - Water encounters fire on surfable water tiles, which become reachable once r3-field ships Surf.
- **Trade evolutions.** `EvolutionService.onTraded` is exposed, but `TradeService` (r3-social) still has to call it after a completed trade. I didn't touch it, to avoid a merge conflict in their constructor and tests.
- **Field poison.** The status persists into the field, but poison damage every 4 steps is field-movement logic (r3-field) and is not implemented.
- **Held items outside battle.** The battle uses `Pokemon.heldItem` (wild and trainer items, Thief / Trick). Giving and taking items in the party menu is r3-items' work; `/monset <slot> held <ITEM>` sets one for testing.
- **Moves and mechanics not implemented.** Beauty (Feebas) evolution, Sketch, Snatch, Magic Coat, Imprison, Grudge, Follow Me redirection, Pursuit's switch bonus and Forecast fail or act as a plain hit. Low Kick uses a flat 60 power because the server has no weights. Gen 3 trainer AI switching is not modelled.
- **Music.** The battle state's `music` key and `manifest.battleMusicExtra` are exported, but the player that picks the song is `main.js`'s `wantedSong` (r3-present). It still picks wild/trainer and needs a two-line change to use `battle.music`.
- **Opponent name on the cross-region dev path.** When a trainer from the other region is started with `/testtrainer <id> hoenn`, the very first "X sent out" line could still use the local region's trainer name. Since the extended state now goes first this is fixed in practice, but it is not covered by a test.

## Files touched outside my lane (small, additive)
- `common/.../Pokemon.kt`: three fields with defaults (`status`, `friendship`, `heldItem`) and `StatusCondition`.
- `common/.../TileBehavior.kt`: new entries appended (CAVE, INDOOR_ENCOUNTER, DEEP_SAND, ASH_GRASS, LAND_ENCOUNTER, SAND, MOUNTAIN, POND_WATER, DEEP_WATER) and `hasLandEncounters` / `isSurfableWater`.
- `codegen/.../maps/MetatileBehaviors.kt`: classifies those behaviors and FireRed's encounter-type bits.
- `codegen/build.gradle.kts`: the new `evolution` generator.
- `codegen/.../trainer/*` and `TrainerRegistry.kt`: `pic`, `className`, `items`, `music` and `heldItemName`, all defaulted.
- `server.game/.../storage/CharacterRepository.kt` and `MarketRepository.kt`: map the three new columns.
- `server.game/.../services/StoryPlayerService.kt`: the Poké Center heal clears the status (one line).
- `server.game/.../services/FieldItemService.kt`: an evolution-stone hook (optional constructor param) and the field status cure.
- `server.game/.../handler/GameAppHandler.kt`: 2 handlers and 1 constructor param.
- `server.game/.../di/ChatCommandModule.kt`: the `/monset` dev command.
- `protocols.game/.../GameProtocol.kt`: 3 registrations.
- `web/static/js/net.js`: the `evolution` section name.
- `web/assets-export/.../Main.kt`: puts `battle` and `battleMusicExtra` in the manifest, and adds the exporter's failures.
- Tests adjusted: `TurnEngineTest`, `BattleServiceTest` (start sequence, ball tests), `StateJsonTest` (section list, r3 fields), `DispatcherTest` and `DuelRulesPacketsTest`.

## PROTOCOL changes (additive)
`docs/web/PROTOCOL.md` section **6. r3-battle additions**:
- **Browser → gateway.** New messages `battle.item`, `evolution.answer` and `evolution.close`. Optional `pos` / `target` on `battle.fight`, `item` on `battle.ball`, and `doubles` on `duel`.
- **Gateway → browser.** New `battle` fields, new `fx` kinds and ids, and the `evolution` section.
- **Game protocol.** s2c **0xE6** `BattleExtPacket`, c2s **0xF0** `BattleExtActionPacket` and c2s **0xF2** `EvolutionReplyPacket`. The reserved s2c 0xF0-0xF3 were already taken by retail packets, so s2c uses 0xE6, which was free in every r3 worktree when picked. `DuelRules` packets gain a trailing flags byte.
- **Assets.** `manifest.battle` and `manifest.battleMusicExtra`.
