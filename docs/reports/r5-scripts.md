# r5-scripts — the decomp script interpreter (summary written at integration)

Branch `feat/r5-scripts` (worktree `OpenMMO-r5-scripts`), base own-client `95c5bee`. The lane ended
without a REPORT.md; this summary was written by the r5 integration from its 12 commits
(`64eb5aa`..`f41c2da`) and code. The integration's own changes are in `docs/INTEGRATION.md`.

## Built
- **codegen**: a mini pret assembler (cpp + the GNU `as` macros of `asm/macros/*.inc`) that builds
  each decomp's own script bytecode (`data/event_scripts.s`) into a resource image (labels, texts,
  flag / var names, constants, multichoice menus, std strings, string tables, in-game trades).
- **server.game `script/vm/`**: `ScriptVm`, an interpreter over that bytecode (every command the
  scripts of both games reach), with routing (`DecompScript`), map script tables (ON_LOAD,
  ON_TRANSITION, ON_WARP_INTO, ON_FRAME, ON_RESUME), movement (`applymovement` on NPCs and the
  player), trainer battles (`trainerbattle_*`, rematches), scripted wild battles, the special
  handlers for the story / field / party / trainer / misc specials (`StorySpecials`,
  `TrainerSpecials`, `PartySpecials`, `WorldSpecials`, `MiscSpecials`), and a static coverage walk
  (`ScriptCoverageTest`: 0 unimplemented commands and specials across both decomps).
- The 2275 `TODO("port ...")` stubs were removed from `script/generated/**`; the generator stops
  emitting them, so `ScriptRegistry.forLabel` falls back to the interpreter.
- A script UI channel (c2s / s2c 0xCE `ScriptUi*Packet`): menus, party picker, naming, trade scene,
  sounds, fades, shakes, the monster picture, the money / coins boxes, changed metatiles (with
  server collision for `setmetatile`). Gateway `ui.script` and `web/static/js/ui/scripts.js`.
- `:web.assets` `ScriptMetatileExport` (metatile atlases for `setmetatile`).
- `/script` dev command (`flag`, `var`, `trainer`, `item`, `run <label>`) and the story playthrough
  driver `web/gateway/test/story.py` (Kanto: Bill, Mt Moon, S.S. Anne, Rocket Hideout, Pokemon
  Tower, Silph Co, the Route 2 trade, the Seagallop; Hoenn: Petalburg Woods, Devon Corp, Mt Chimney,
  Weather Institute, Regirock).

## Not done (as left by the lane)
- The lane's last commit was "story driver: wip helpers"; no final report, screenshots or
  container cleanup. The integration finished the driver (see `docs/INTEGRATION.md`).
