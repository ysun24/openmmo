# r3-present — presentation, sound, UX, Hoenn end-to-end, performance

Branch `feat/r3-present` (worktree `OpenMMO-r3-present`). Stack used: login 2236, game 7907, gateway 8550,
postgres 21311 / 21321 (`openmmo-r3p-{login,game}-db`). Screenshots in `docs/reports/present-shots/` (I looked at
every one; more were taken in scratch while iterating).

## Built

### 1. Game fonts (`:web.assets` `FontExport.kt`, `web/static/js/font.js`, `css/main.css`)
- Exports the decomps' own Latin fonts: FireRed `latin_normal`, Emerald `latin_normal`, `latin_narrow`,
  `latin_small`. The glyph widths come from the C tables (`sFontNormalLatinGlyphWidths`,
  `gFont*LatinGlyphWidths`), and the code points from `charmap.txt`. That covers Latin, the accented letters,
  `…“”‘’♂♀×`, arrows, `▶`, and the Pokédollar sign (₽ on the yen glyph). Every glyph is also at U+E000+byte,
  for PK/MN, LV and the superscripts.
- Each font is written twice from the same pixels:
  - A **bitmap atlas** (red = glyph, green = the font's own shadow) plus JSON. The canvas uses it:
    name tags are now full case, in the game's small font, with any colours.
  - A generated **TrueType** for the DOM (rectangle outlines, 64 units per pixel, 16-px em, valid tables and
    checksum). It is registered via `FontFace`, and the CSS draws the GBA shadow with three `text-shadow`s.
- Characters a font lacks (CJK chat, emoji) fall back per character to the system font. On the canvas they
  are drawn with `fillText`.
- Region-aware: Kanto text uses the FRLG font, Hoenn the RSE font (`body.region-1`). Battle move names use
  FONT_NARROW, as the GBA does.
- Sizes are whole device pixels:
  - inside the game overlay, `16 × --u`;
  - for the HTML panels, a new `--pu` (≈2 CSS px snapped to the DPR).

### 2. Pixel-styled windows (`UiExport.kt`)
- 9-slice `border-image` frames are composed exactly as the tilemap code places the tiles:
  - the dialogue box: Emerald `WindowFunc_DrawDialogueFrame` over `message_box.png`, FireRed's over
    `menu_message.png`;
  - all user frame styles: 20 Emerald, 10 FireRed.
- The bobbing "more" arrow follows `sDownArrowYCoords`. It is placed after the text like `TextPrinterDrawDownArrow`.
- The dialog uses the GBA text window geometry: 216 px wide at x=16, 16-px lines.
- Panels, menus, the login card, toasts, the banner, battle menus and the choice box all use the frame of
  the chosen style. Settings → Window frame changes the style.
- Dialog typewriter (`ui/typewriter.js`):
  - text prints at the GBA text speed (8/4/1 frames per character, or Max);
  - A/B or a tap completes the page;
  - the arrow and the choices appear when the page is done;
  - the duplicate speaker tag is hidden when the text already says "MOM: …".

### 3. Shiny sprites, Gen 3 shininess, animated icons
- `PokemonExport` now also exports:
  - `shiny.front/back`, with the `gMonShinyPaletteTable` palette, same game as the normal art;
  - `iconAnim`, both 32×32 icon frames.
- Party icons cycle like the party menu: ~0.2 s, slower under 25 % HP, still when fainted. The summary and
  battle sprites use the shiny art.
- Server (`battle/Shininess.kt`): `IsShinyOtIdPersonality`, i.e. `TID^SID^PIDhi^PIDlo < 8`. `Pokemon.seed`
  is the personality (the nature is already `seed % 25`). The 32-bit OT id is derived per character
  (splitmix of the id).
  - Wild monsters roll against the player's OT. Trainer monsters are never shiny (`OT_ID_RANDOM_NO_SHINY`).
    Gift monsters use the player's OT.
  - `/givemon <dex> [lvl] shiny` forces a shiny PID with the same nature.
- The flag reaches the browser in two ways:
  - party/battle JSON `shiny`;
  - the battle mon blocks carry it in the byte after the gender. That byte was padding, so non-shiny packets
    are byte-identical.
- SE_SHINY plays when a shiny foe appears.

### 4. Music and sound effects (`MusicExport.kt`, `SampleProfile.kt`, `audio.js`, `sfx.js`)
- **DirectSound instruments from the voicegroups' samples, without shipping samples.** The exporter measures
  each `direct_sound_samples/*.wav` the voicegroups reference:
  - `pitch60` from the m4a pitch chunk (`agbp`) and the autocorrelation f0; it confirms C4 = 261.6 Hz at
    note 60 for the instruments;
  - 24 harmonics of the loop, giving a PeriodicWave;
  - or band energies for drums, giving filtered noise plus a pitched body;
  - a 48-point amplitude envelope, loop point, duration and loudness.

  The browser plays these with the voicegroup ADSR on top. Drum-kit voices play at their own key and
  `no_resample` voices at a fixed pitch. Name-based stand-ins remain only as a fallback.
- **Vibrato / LFO**: MOD (CC1), LFOS (CC21, default 22), MODT (CC22: pitch, volume, pan), LFODL (CC26),
  and TUNE (CC24) as the m4a does them: a triangle at `lfos × 59.73 / 256` Hz and depth `MOD × 6.25`
  cents. 163 of the 210 Emerald songs use MOD.
- **Square sweep**: the NR10 byte drives the period register per `time/128 s`.
- **Reverb**: the m4a `SoundMainRAM_Reverb` comb (frames 6 and 7 back, `rev/512`, mono, DirectSound bus
  only; CGB channels stay dry), using `midi.cfg -R`.
- **SFX**: all `se_*.mid` are exported with their voicegroups. The manifest `sfx` table maps roles to them.
  They play on their own bus:
  - menu cursor/confirm (SE_SELECT on every cursor step);
  - menu open (SE_WIN_OPEN);
  - door in / exit out (SE_DOOR / SE_EXIT, from outdoor↔indoor warps);
  - bump (SE_WALL_HIT, rate-limited like the GBA);
  - PC on / log-in / off;
  - shiny (SE_SHINY);
  - item get (MUS_OBTAIN_ITEM, on "obtained/received/found … !" text);
  - level up (MUS_LEVEL_UP, battle `level` fx);
  - healing (MUS_HEAL, when the whole party returns to full HP and PP during a dialog).
- Fanfares duck the map music and bring it back.
- Settings has separate Music and Effects volume sliders.

### 5. UI / UX
- **Loading screen** (`#splash`): logo, progress bar and status while the manifest and fonts load. Then
  either the login form, or straight back into the game with a remembered session.
- **Session resume without the password**:
  - `LoginClient` supports `TokenLogin` and `stayLoggedIn`, and returns the `SentCredentials` token in the
    ticket.
  - The gateway `login` accepts `token`/`remember` and replies with `token`.
  - The browser stores only the token (`openmmo.session`; "Stay logged in" checkbox). Logging out deletes it.
- **Connection lost**:
  - a banner shows "Connection lost — reconnecting in N s";
  - retries back off 1/2/4/8/15 s, immediately on `online`, or with "Retry now";
  - the world greys while offline;
  - a 12-s heartbeat watchdog catches silent sockets;
  - the token is used to log back in, the server puts you back where you were, and the banner says
    "Reconnected".
- **Settings panel**: music/effects volume, mute, text speed, battle animations on/off, window frame, name
  tags, keyboard remap, controls help.
  - The keyboard remap allows two keys per action. A key moves to the new action, and it can be reset.
    Run is remappable through a small hook in `input.js`.
  - "Battle animations off" runs the battle screen's fast mode. The default is off when
    `prefers-reduced-motion` is set.
- **Mobile onboarding**: on the first touch session, labels point at the D-pad, A, B, RUN, MENU and CHAT
  above a compact card. Menu → Help shows the full control list on any device.
- **Accessibility**:
  - panels get `aria-modal` and `aria-labelledby`;
  - real DOM focus moves into a new panel and returns to the game when it closes;
  - Tab is trapped inside the top panel instead of opening the menu, and Shift-Tab works;
  - Enter activates the focused control, and Esc closes a panel even from a slider;
  - keyboard focus and the arrow-key cursor stay the same element;
  - the canvas is focusable with an aria-label describing the keys;
  - the banner is `role=alert`, the splash `aria-busy`, progressbars have values;
  - reduced motion turns off the CSS animations (arrow, icons) and defaults battle animations to off.
- New character gender (Boy/Girl): login `gender`, and the world `female` flag selects the region's
  female player sheet.

## How verified (real stack, screenshots looked at)
- **Tests**, all pass:
  - `:server.game:test`, including the new `ShininessTest`: the rule, 1/8192 over 4 M PIDs, forced shiny
    keeping the nature;
  - `:protocols.game:test`, including the new `BattleShinyFlagTest`: round trip, and only the flag byte
    differs;
  - `:client:check`;
  - `:web.gateway:test`: `StateJsonTest` updated for `female` / `shiny`;
  - `:web.assets:test`, including the new `PresentExportTest`: glyph mapping/widths/atlas, TTF table order
    and whole-file checksum, frames (white fill, transparent corner), shiny recolour with the same alpha,
    2-frame icons, piano `pitch60` ≈ C4 with loop and harmonics, kick bands, SE songs with reverb.
  - `spotlessApply` was run.
- **Gateway e2e**: `python3 web/gateway/test/e2e.py --ws ws://127.0.0.1:8550/ws --a yicheng:pikachu123:Ash --b
  friend:bulbasaur123:Gary` → **ALL OK**.
  - It needs Ash and Gary in a walkable spot. My `/story oak-grass` name-tag test had left them
    script-locked, so I moved them back to the house in the DB.
  - Twice, the first run right after my manual browser tests failed once at "offers visible" in the trade
    section. One of those tests had left Ash in an unfinished `/testbattle`. The immediate rerun passed both
    times (final run: ALL OK). I did not touch the trade code.
- **Fonts, frames, dialog**:
  - Kanto: Mom's dialog in the FRLG box/font, mid-typing and complete with the red arrow (`a6-dialog`).
  - Hoenn: the RSE box/font (`h2-b`, `h5-b`).
  - Battle text, the menu and the FONT_NARROW move list (`b1-menu`).
  - Panels (`a2-settings`, `a3`), login (`a1-login`).
  - Name tags in full case between two concurrent browsers (`n2-gary`).
- **Shiny**: `/givemon 6 30 shiny` gives a shiny Charizard: black art in the summary (`a3`) and in battle
  (`6.shiny.back.png`). Party icons animate (`img.anim2`).
- **Audio** (analyser on the master and sfx buses):
  - MUS_LITTLEROOT RMS 0.03, with the reverb active and sample-profile voices sounding;
  - every SFX role has a non-zero peak RMS (0.045–0.087);
  - itemGet / levelUp / heal duck the music;
  - door/exit/bump fired from real warps and walls while walking (`sfx.played`);
  - the heal and item triggers were also checked through injected state.
- **Resume / reconnect** (`r1-lost`):
  - login with remember stores `{user, name, region, token}`, and no password anywhere in localStorage;
  - after a reload, it resumes straight into the world without the form;
  - `setOffline(true)` brings up the banner and the countdown;
  - going back online reconnects through the token, and the banner shows "Reconnected" on the same map.
- **Keyboard only**: typing the login with Tab/Enter, then Tab → Menu (focus on POKéMON, aria-modal) →
  Tab ×6 → Settings → Enter opens it with focus on the music slider → → raises the volume to 65 % →
  Esc closes it.
- **Hoenn end-to-end** (new accounts `may` girl and `mayb` boy):
  - truck → Littleroot intro with Mom → May's house with the movers;
  - Route 101 (Birch, Zigzagoon, bag);
  - Oldale (Pokémon Center, animated flowers; the Route 102 blocker NPC);
  - Pokémon Center interior;
  - Petalburg (gym, pond);
  - the Route 102 seamless crossing;
  - Route 103 / 110 / Mauville while wandering.

  Music was right per map (`MUS_LITTLEROOT`, `MUS_ROUTE101`, `MUS_OLDALE`, `MUS_PETALBURG`, `MUS_ROUTE110`,
  battle `MUS_VS_WILD`). Tile animations were active. Shots: `h7-route101`, `h8-oldale`, `h9-a`, `h9-c`,
  `t-pc`, and mobile `m-battle` (Mudkip vs Zigzagoon).
  - Asset issues found and fixed: the girl showed the boy sprite (gender was never chosen or sent), the
    dialog text window was 8 px too narrow, the Emerald window interior was filled with frame palette
    colour 1 (dark grey) instead of white, and relative `url()`s inside CSS variables 404'd.
  - The yellow dithered patches in front of Hoenn doors are the decomp's own tiles (Petalburg secondary
    tiles 80/81/96/97, General palette 5), not a bug.
- **Mobile** (Pixel 7 portrait, Pixel 7 landscape): login, onboarding callouts, menu, settings (fixed
  overflowing rows), battle, landscape world (`m-*`, `l-world`).
- **Performance budget**:
  - First meaningful paint on localhost. "Login ready" is the login form visible with fonts;
    "resume" is a reload until the first world frame. All well under 2 s:

    | run | FCP | login ready | resume (first world frame) | login → world |
    |---|---|---|---|---|
    | desktop | 27 ms | 67–209 ms | 247 ms | 204–209 ms |
    | Pixel 7 emulation | 119 ms | 161 ms | 247 ms | 202 ms |
    | Pixel 7 with 4× CPU throttle | 76 ms | 357 ms | 429 ms | 241 ms |

  - JS heap stable over a 10-minute walk. A bot wandered between connected maps (41–43 map changes),
    and the heap was measured after GC every minute. Mobile with 4× throttle: 4.6 MB at start, 5.23
    after warm-up, then 5.33 → 5.36 → 5.36 → 5.37 → … → 5.40 MB at 10 min. Map/image caches plateaued
    (8 maps, 64 images) and the DOM stayed at about 120 nodes. The first, desktop run grew 5.6 → 6.2 MB,
    which is the memory of its own frame recorder's arrays.
  - No frame over 33 ms during map transitions on mobile emulation. With 4× CPU throttle, the worst frame
    in the 1.5 s after each of 41 connection crossings and door warps (Oldale ↔ PC, Oldale ↔ Route 101/103,
    Route 103 ↔ 110 ↔ Mauville) was **16.8 ms**. Over the whole 10 minutes there were 35 611 frames, 2 of
    them over 33 ms (max 50 ms). Both came outside transitions (wild battle UI build).

## Not done / limitations
- **Fanfare behaviour**: fanfares duck the map music instead of pausing and resuming it at the same
  position.
- **Shiny sparkle**: there is no shiny sparkle animation in battle (only SE_SHINY). That belongs in
  battle.js (r3-battle's lane).
- **Wild shiny encounters**: these follow the Gen 3 odds, so they were only verified through the unit test
  and the shared code path. In the browser I checked an own shiny via `/givemon … shiny`.
- **Other players' gender**: unknown to the client (no field in the entity data), so other girls are drawn
  with the male sheet. Only your own character uses `female`.
- **Text speed**: it applies to field dialogs (GBA timing) and to battle text, where it scales the battle
  screen's own pacing. Battle text keeps its own typing.
- **Japanese**: the Japanese GBA fonts are not exported. CJK uses the system font, as asked.
- **Heal detection**: heal / item-get fanfares are detected from state and text, because the server sends
  no "fanfare" event. A heal without a dialog (e.g. a future instant heal) plays nothing.
- **DirectSound timbre**: instruments remain approximations (harmonic and noise models of the real
  samples, no PCM playback). Pitch-bend LFO phase and per-note LFO restarts are only approximated
  (one LFO per channel; LFODL is applied per note).

## Files touched outside my area (small, additive hooks)
- `server.game/.../services/BattleService.kt`: the wild-roll shininess line (+imports).
  `services/StoryPlayerService.kt`: gift shininess. `services/command/GiveMonCommand.kt`: the `shiny`
  argument. `battle/BattleMonState.kt`: `shiny =` in both blocks. New `battle/Shininess.kt` + test.
- `protocols.game/.../battle/BattleMonBlock.kt`: an optional `shiny` field in the formerly padding byte.
  New test `BattleShinyFlagTest`.
- `client/.../net/BattleClient.kt`: `BattleMon.shiny`, set from the blocks, plus `shiny` in `monFx`.
  `net/LoginClient.kt`: token login / remember / token in `GameTicket`. `net/GameClient.kt` and
  `world/WorldState.kt`: character gender in the snapshot.
- `web/gateway/.../WsSession.kt`: login `token` / `remember` / `gender`, `token` in the reply.
  `StateJson.kt`: `world.female`, battle mon `shiny`. `StateJsonTest.kt`: expectations for these fields.
- `web/static/js/ui/battle.js`: `setSprite(..., shiny)` (3 call sites), the settings import, `fast()`
  honouring battle animations off, typing interval from text speed.
- `web/static/js/render.js`: name tags drawn with the game font (fallback kept). `input.js`: `export KEYS`
  and a remappable `run` key (2 lines). `net.js`: `lastRx` for the heartbeat. `ui/common.js`: `monImg`
  options (animated icon, shiny).
- `docs/web/PROTOCOL.md`: new §4.2 "r3-present".
- New files: `js/{font,settings,sfx}.js`, `js/ui/typewriter.js`, `FontExport.kt`, `UiExport.kt`,
  `SampleProfile.kt`, `PresentExportTest.kt`, `docs/reports/present-shots/*`.

## PROTOCOL changes
Additive only, all in `docs/web/PROTOCOL.md` §4.2:
- login `token` / `remember` / `gender`, and reply `token`;
- `world.female`;
- battle mon `shiny`;
- manifest `pokemon[].shiny` / `iconAnim`, `sfx`, `ui.fonts`, `ui.windows`;
- music index `samples`, voice `sample`, song `rev`.

No new opcodes.
