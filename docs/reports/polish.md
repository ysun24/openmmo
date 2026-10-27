# feat/polish — overworld experience (web client)

## Built
- **Name tags**: other players get a tag above the head, drawn on the canvas in a 3x5 bitmap pixel
  font (crisp at every scale, uppercase). It sits on a dark plate, is clamped inside the screen,
  is drawn above the top layer and below DOM overlays, and is cached per name. NPCs get no tag.
  Menu → *Sound & display* has toggles for the name tags and for your own tag (yellow). Both
  settings are stored in localStorage (`openmmo.display`).
- **Tile animations** (`:web.assets` `TilesetAnims.kt` + `MiniC.kt`): `MiniC` is a small interpreter
  for the C subset that the decomps' `src/tileset_anims.c` uses (if/else, typed `u8`/`u16` locals
  that wrap, casts, arrays, calls). For every value of the animation counter, the exporter runs
  the real `TilesetAnim_*` callbacks and their `InitTilesetAnim_*` maxima, and records each
  `AppendTilesetAnimToBuffer(frame, VRAM tile, size)`. The tile ranges, frame order and timing
  therefore come straight from the game, Emerald's odd ones included (Mauville/EverGrande
  per-tile phases, Lavaridge steam, Rustboro).
  - For each layout, every animated metatile is pre-rendered once per frame combination it
    actually shows, with the shared `MetatilePainter` and patched tiles. The results go into a
    `.anim.png` atlas and a `.anim.json` file.
  - The browser keeps a per-map grid. Each frame it re-blits only the *visible* animated cells
    (bottom, and top where animated) for the current map and its connections, at GBA timing
    (59.7275 Hz). There are no per-frame allocations.
  - Coverage: 176 FireRed and 109 Emerald layouts animate (water, flowers, fountains, lava,
    waterfalls, flags, ...).
- **Music** (`MusicExport.kt`, `web/static/js/audio.js`):
  - The exporter copies all 92 FireRed and 209 Emerald `mus_*.mid` files unchanged. For each song
    it writes the `midi.cfg` volume and the voices that the song's programs/notes resolve to in its
    voicegroup. Voicegroups are read as one flat assembled table, so drum kits running past a
    group work as they do on the hardware. Key splits and drum kits are resolved per used note.
    PSG wave RAM is exported too.
  - The browser has a self-written SMF parser with tempo map and `[`/`]` loop markers, and a
    lookahead WebAudio scheduler (60 ms tick, 0.3 s ahead). It plays:
    - square (4 duties via PeriodicWave), programmable wave (from the real 4-bit samples) and noise (white or 7-bit LFSR) for the PSG channels, with GBA envelopes;
    - sample-free stand-ins for the DirectSound instruments, picked from the sample name (pluck, strings, brass, organ, flute, bass, bell), plus synthesized kick/snare/hat/tom/clap;
    - CC7/11 volume, CC10 pan, pitch bend with m4a BENDR (CC20). A limiter follows the master gain.
  - Map music comes from the manifest `music` field. Changing map crossfades (0.9 s).
  - Battles play `MUS_VS_WILD` / `MUS_VS_TRAINER`. On `ENDED` + `VICTORY` the client plays the
    victory jingle (wild/trainer), and `MUS_CAUGHT` for `CAUGHT`. After `battle.close` the map
    music comes back.
  - The AudioContext starts on the first user gesture, which satisfies autoplay rules. It is
    suspended while the tab is hidden.
  - Volume and mute are in the menu (*Sound & display*) and stored in localStorage
    (`openmmo.audio`).
  - Debug handle: `__game.music.status()`.
- **Mobile**:
  - Portrait: the game uses the full width. The scale is an integer device-pixel scale when that
    reaches ≥94 % of the width, otherwise it is fractional and snapped to device pixels. The image
    stays pixelated, with the controls below.
  - Landscape: the game is centred between the side controls, and the CHAT/RUN/MENU pills move
    to the top-left corner instead of covering the game.
  - Small phones (≤359 px): the D-pad and the A/B buttons scale down so they no longer touch.
  - Zoom and refresh: double-tap zoom is blocked (iOS `touchend` double-tap guard, `dblclick`),
    and so are pinch and pull-to-refresh (existing `overscroll-behavior`/`touch-action`).
  - Install: `app.webmanifest` (display fullscreen) and pixel-art icons (192/512/maskable, plus an
    Apple touch icon) make it work with Add to Home Screen.
- **Polish**:
  - Maps smaller than the screen along an axis, with no connections (indoor rooms), are centred
    with black around them, as on the GBA, and no border fill is drawn.
  - The warp fade was verified monotonic 1 → 0 (no flicker).
  - First load: the manifest is streamed and its progress shown, and the login waits for it.
    Only the current map is fetched first; its connections are fetched after it is ready.
    Anim atlases, sprites and music load lazily.
  - A 4 px loading bar at the top shows manifest progress, then outstanding map requests on a
    warp or first spawn.
- **Fixes found while testing**:
  - Stairs and door mats warp when you *push* against a wall while standing on them. The browser
    used to predict that move as blocked, so it never sent the step, and players could not leave
    the house from the browser. It now sends an unpredicted `step` when standing on, or facing,
    a manifest `warps` tile (rate-limited to once per 250 ms).
  - Prediction no longer walks through walls while the new map's `col.bin` is still loading.

## How verified (real stack: login 2166, game 7837, gateway 8480, postgres 20611/20621)
- Tests: `:web.assets:test` (new checks for Pallet's anim channels and the exact water event
  timeline, the 8 water frames, warps, music index/voices/drum kit, battleMusic, and a MiniC unit
  test with u16 wrap), plus `:web.gateway:test`. `spotlessApply` was run.
- `:server.game`, `:protocols.game` and `:client` were not touched, so their tests were not rerun.
- Gateway e2e: `python3 web/gateway/test/e2e.py ... --a yicheng:pikachu123:Ash --b friend:bulbasaur123:Gary` → **ALL OK**
  (on a fresh game DB, after the dev-permission update).
- Browser (Playwright in docker, my runner in `$KIROCREW_SCRATCH/polish/tools/`). I looked at every
  screenshot:
  - Desktop indoor room centred: `shots/d1.png`, `shots/final-indoor.png`.
  - Two concurrent browsers (Gary on account `friend`, Misty on a new account `misty`) see each
    other's tags: `shots/final-tags.png` (Misty's view: GARY), `shots/g-tags.png` (Gary's view:
    MISTY), and `shots/m-tags.png` with the self tag enabled.
  - Animated water: canvas hashes of the Pallet pond differ between frames 302 → 312 → 330 → 367.
    Atlas: 8 distinct water frames. The waves visibly differ in `shots/m-a.png` vs `shots/water-2.png`.
    Flowers sway between `m-a`/`m-b`.
  - Music: `AudioContext` state `running`, song `MUS_PALLET`, analyser RMS 0.02 (non-silent), 6
    active voices.
    - The loop wrapped: `t0` went 0.82 → 44.45 = loop length 43.6 s.
    - Warp Pallet ↔ house keeps `MUS_PALLET`; Oak's lab switched to `MUS_OAK_LAB`.
    - `/testbattle` gave `MUS_VS_WILD`, then `ENDED/VICTORY` gave `MUS_VICTORY_WILD`, then
      `battle.close` gave `MUS_PALLET` again.
  - CPU: 60 fps, render+sim work 0.3–0.45 ms/frame on average (1.4 ms max) with music and anims running.
  - Mobile:
    - Pixel 7 portrait `shots/mob-portrait.png` (412 px wide game, fractional 4.5x);
    - Pixel 7 landscape `shots/mob-landscape.png`;
    - iPhone SE 320x568 `shots/mob-se.png`;
    - iPhone 8 375x667 `shots/mob-iphone8.png` (integer 3x = 360 px).
  - Warp fade sampled per frame: 1, 0.93, …, 0.07, 0 — no flicker.
  - Shot paths are relative to `$KIROCREW_SCRATCH/polish/` (scratch: deleted with the session).

## Not done / limitations
- DirectSound instruments are approximations (no samples, as asked). Vibrato (CC1/LFO), the
  square sweep and reverb are not modelled. Gym-leader battle music needs a "gym" flag that the
  battle section doesn't have, so it plays the trainer theme.
- The border cells around maps are not animated (sea borders show a static frame).
- Name tags are uppercase only (3x5 font).
- The Emerald palette-blend animation (Battle Dome floor lights) is skipped; only tile copies are
  exported.

## Files touched outside my area
- `web/static/js/ui/misc.js`: the menu item and the *Sound & display* panel. No forbidden files
  (`ui/battle.js`, `ui/shop.js`, `ui/dialog.js`, `server.game`) were touched.
- `web/gateway/.../StaticFiles.kt`: `.mid` → `audio/midi`.
- New: `web/static/js/audio.js`, `web/static/app.webmanifest`, `web/static/icons/*`.

## PROTOCOL changes (additive, docs/web/PROTOCOL.md §4.1)
- Map entries: `warps`, `anim` (+ `.anim.json`/`.anim.png` format).
- Top level: `music` (per-region song index) and `battleMusic`.
- No WebSocket message changes.
