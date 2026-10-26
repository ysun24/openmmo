# web.assets — static asset exporter (branch feat/web-assets)

## Built
- Gradle module `:web.assets` (`web/assets-export/`, registered in `settings.gradle.kts`, depends on
  `:client`). `Main.kt` → `Exporter`: `MapExport` (maps), `ObjectExport` (overworld sprites),
  `PokemonExport` (front/back/icon), `Decomp` (INCBIN/INCGFX symbol resolver, `#define` tables,
  indexed PNG reader), `Output` (write-if-changed + prune of stale files).
- Writes everything in `docs/web/PROTOCOL.md` §4 to `web/static/assets/` (gitignored).
- `web/assets-export/preview.html`: dev-only canvas preview (`?maps=PalletTown@0,LittlerootTown@1&mons=1-160&objects=1&col=1`).

## Run
```
export JAVA_HOME=~/.jdks/jdk-25.0.5+8
./gradlew --no-daemon -Porg.gradle.java.installations.paths=$JAVA_HOME :web.assets:run --args="--decomp decomp --out web/static/assets"
# preview
mkdir -p web/static/dev && cp web/assets-export/preview.html web/static/dev/
python3 -m http.server 8290 --bind 127.0.0.1 --directory web/static   # open /dev/preview.html?maps=PalletTown@0
```
Needs the decomp submodules (`git submodule update --init --depth 1`).

## Result
- 943 maps (425 FireRed + 518 Emerald) from 715 distinct layouts, 391 object sprites
  (152 FireRed + 239 Emerald), 386 Pokemon (front + back + icon), 0 failures.
- 4410 files, 24 MiB (25.4 MB); manifest.json 443 KB.
- Export itself 2.6–3.0 s (parallel, 16 threads); ~8 s wall including Gradle. Deterministic:
  a rerun rewrites nothing (byte-identical files skipped), stale files are pruned.

## Verified
- `:web.assets:test` (ExportTest): Pallet Town `0:3:0` is 24x20, connects up to Route 1 `0:3:19`,
  tree (0,0) and house wall (6,6) blocked, road (8,10) walkable at elevation 3; full export has no
  failures, Littleroot is `1:50:9`, 386 species, Bulbasaur front 64 px with transparent corner,
  players for both regions, rerun gives identical manifest. `spotlessCheck` clean for both modules.
- Screenshots (looked at; palettes, layers, frames correct) in
  `/home/yichengs/.kiro/crew/scratch/runtime-0765f63e/shots/`: `pallet.png`, `route1.png`,
  `viridian.png`, `littleroot.png`, `caves.png` (Mt Moon 1F, Granite Cave 1F), `mons.png` (#1-160),
  `objects1.png` (all Emerald object sprites).

## Contract changes (docs/web/PROTOCOL.md §4, updated)
- Objects keyed `region:gfx` (gfx ids differ between the games); `players` is per region and also
  gives the gfx id. Object entries add `name`, `count`, run frames for player sheets.
- Hoenn bank = group index + 50 (as the server and `PretData.mapName` number them).
- Map files are per layout (`maps/<region>/<layout>.*`); added `borderWidth/borderHeight`, border
  lookup rule `(x mod bw, y mod bh)`, `music` is the decomp constant.
- `top` wording fixed to the real GBA rule: top layer of NORMAL and SPLIT metatiles covers sprites,
  COVERED metatiles go entirely to `bottom` (the previous wording said the opposite for NORMAL).

## Files outside web/ touched
- `client/.../gfx/PretData.kt`, `ObjectEventGraphics.kt`: accept pokeemerald's `INCGFX_U*` macro
  (Emerald tilesets and sprites failed to resolve before).
- `client/.../gfx/MetatilePainter.kt` (new): the layer-split painter, shared by `WorldRenderer`
  (now delegates) and the exporter. `WorldRenderer` border lookup fixed to `x mod bw` (the decomp's
  `+1` applies to MAP_OFFSET-shifted coordinates).
- `settings.gradle.kts` (+ module), `.gitignore` (+ `web/static/dev/`).

## Not done
- Tile animations (water/flowers), shiny palettes, animated (2-frame) icons, field effects, fonts.
- Object `OBJ_EVENT_GFX_VAR_*` ids are not exported (server-side substitution).
