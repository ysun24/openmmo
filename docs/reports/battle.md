# feat/battle — battles in our own client + player-vs-player duels

## What was built

### Client (`client/`)
- `net/BattleClient.kt` (new): reads the server's battle packets (field state 0x30, prompt, move events, PP, switch-in, entity delta / EXP, switch prompt, bulk end/fled, ball throw, move-learn prompt, duel invite/outcome) into an immutable `BattleState`. That state holds both sides' parties, the active slots, species/level/hp, moves with pp, the phase (`ACTION` / `FORCED_SWITCH` / `WAITING` / `ENDED`), a text log and the result. Actions it sends: FIGHT (move), SWITCH, RUN, BAG (Poké Ball), move-learn reply, duel challenge and answer. After a battle ends it sends `MapLoadedAck` so the server finishes the battle. Names and max pp come from the codegen tables (new `:codegen` dependency).
- `gfx/BattleScreen.kt` (new): GBA-style layout. The foe sits top-right with its box top-left, our monster's back sprite sits bottom-left with its box (HP bar + numbers) on the right, and the bottom has a text log plus the FIGHT/BAG/POKéMON/RUN menu, the move list or the party list. Sprites come from `decomp/pokefirered` (then `pokeemerald`) `graphics/pokemon/<species>/front.png|back.png` coloured with `normal.pal`. A placeholder ball is drawn when no sprite exists. The screen is drawn at 3x with crisp text.
- `gfx/BattleInput.kt` (new): keyboard control in the Swing window. Arrows move the cursor, Z/Enter picks, X/Esc goes back.
- `BattleCommands.kt` (new): headless script and stdin commands:
  - `/any slash cmd`
  - `battle-test [dex] [lv]`
  - `fight N`, `switch N`, `run`, `ball`
  - `auto [N]`: fights until the end, handling forced switches and skipping move-learn prompts
  - `learn N`, `skip`
  - `duel NAME`, `accept`, `decline`
  - `wait-invite`, `wait-battle`, `wait-end`
  - `battle` (prints the state), `sleep S`
  
  `--snapshot` renders the battle screen whenever a battle is up.

### Server (`server.game/`)
- PvP (`services/PvpBattleService.kt`, `battle/PvpDuel.kt`, new):
  - **Challenge flow:** the challenger sends `DuelChallengePacket` (target name). The target gets `DuelInvitePacket(name = challenger)` and answers with `InGameChallengeResponsePacket(accepted, challengerName)`. The challenger gets `DuelInviteOutcomePacket` (0 declined, 1 accepted, 2 unavailable — our own numbering) plus chat notices. Invites expire after 60s, and "in a battle / no party / all fainted" are rejected.
  - **Battle model:** each player gets their own `BattleInstance` view, linked by a `PvpDuel`. The two views share the same `BattleMonState` objects, so the existing `BattlePacketEmitter` serves both clients unchanged.
  - **Turns:** each turn waits for both actions (mutex-guarded) and is then resolved by the existing `TurnEngine`. A switch goes first, and the mover then hits the incoming monster. A fainted side gets the switch prompt while the other side waits.
  - **Endings:** RUN = forfeit, and disconnecting = forfeit. Items are refused.
  - **No lasting effects:** a duel awards no EXP or prize money, and hp/pp are never written back. The battle-end party sync re-sends the untouched stored party.
- `TurnEngine.resolveTurn` / `resolveSwitchTurn` take an optional `opponentMoveId` (null = AI, as before).
- Dev commands, both DEVELOPER permission only:
  - `/givemon <dexId> [level]`: new characters start with an empty party.
  - `/testtrainer <trainerId> [kanto|hoenn]`: starts a decomp trainer battle without a map script.
- `protocols.game`: registered `DuelChallengePacket` as **C2S opcode 0x5A** (it existed but was unregistered; 0x5A was free on C2S).

## How to test
Start the servers and DBs as in the brief (this branch used login 2116 / game 7787 / dbs 20111, 20121). Use `GAME_DB_SEED_DEV=true` so user 1 gets the DEVELOPER character `Kanto`. For a second dev character, insert a login user and set `permissions = permissions | 256` on its character while the game server is stopped.
```
# wild battle to completion + snapshot
client ... --name Kanto --script "/givemon 4 12; battle-test 19 3; auto; battle" --snapshot wild.png
# trainer
client ... --script "/testtrainer 91; wait-battle; fight 3; battle" --snapshot trainer.png
# PvP (two processes)
client --user friend ... --name Friend --script "/givemon 7 12; wait-invite 40; accept; wait-battle; auto; battle" &
client --user yicheng ... --name Kanto  --script "sleep 8; duel Friend; wait-battle; auto; battle"
```
GUI: `--gui true`. The battle screen replaces the map while a battle is up.

## Evidence (`docs/client-battle/`)
- **Wild:** Charmander vs Rattata ended in VICTORY with +24 EXP. RUN gave "Got away safely!" and Ball gave CAUGHT (Caterpie joined the party). See `wild-end.png`.
- **Trainer:** vs JOSH (trainer 91, 3 mons), with the opposing switch-in and trainer name shown. See `trainer-mid.png`, read and checked.
- **PvP full battle:** Kanto (Charmander 5/32, Caterpie) vs Friend (Squirtle, Bulbasaur). There is a forced switch after the Charmander faint, and the result is Friend VICTORY / Kanto DEFEAT on both clients. See `pk.txt`, `pf.txt`, `pvp-friend.png`, `pvp-kanto.png`.
- **PvP decline, forfeit and disconnect:** all three behave as specified. Hp is restored afterwards: Charmander was 5/32 again and Squirtle 33/33 on re-entry. See `pk2.txt` and `pf2.txt`.
- **Tests:** `:server.game:test :protocols.game:test :client:build` ran 330 tests with 0 failures. That includes the new `PvpBattleServiceTest` (4 tests: linked start, waits for both sides then no persistence, disconnect, decline).

## Not done / known limits
- The PvP challenge is a direct packet only: there's no tier/format rules and no timer, and the `DuelChallengePacket` restriction fields are ignored.
- The client shows no move animations and doesn't page through the log. The move-learn UI is text commands only (`learn N` / `skip`), and the GUI has no learn menu yet.
- The bag holds only a Poké Ball. Status conditions and doubles aren't modelled (the server doesn't model them either).
- The PvP opponent name comes from the duel flow, because the field state carries only the local player's name.

## Shared files edited (small, additive)
- `client/build.gradle.kts` (`:codegen` dep)
- `client/.../net/GameClient.kt` (`battle` field, `command()`, notice forwarding, `onUnhandled` → `BattleClient.handle`)
- `client/.../gfx/GameWindow.kt` (optional `battleScreen`, key routing)
- `client/.../Main.kt` (battle commands, battle snapshot)
- `protocols.game/.../GameProtocol.kt` (C2S 0x5A `DuelChallengePacket` — **watch for opcode clashes on merge**)
- `server.game/.../handler/GameAppHandler.kt` (2 handlers + `PvpBattleService` injection)
- `server.game/.../services/BattleService.kt` (pvp routing in `onBattleAction` / `onDisconnect`, new ctor param `pvp`)
- `battle/BattleInstance.kt` (`pvp` field)
- `battle/BattlePacketEmitter.kt` (PvP shows as TRAINER)
- `battle/TurnEngine.kt` (optional opponent move)
- `di/ChatCommandModule.kt` (2 commands)
- Tests: `BattleServiceTest.kt`, `StoryResetTest.kt`, `testsupport/Services.kt` (pass the new `pvp` ctor arg)
