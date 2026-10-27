#!/usr/bin/env python3
"""Generates the Emerald secret base tables (r4) straight from the pokeemerald decomp into

  codegen/src/main/kotlin/de/fiereu/openmmo/progression/SecretBaseTables.kt

* every secret base spot (map.json bg_events of type secret_base): map, x, y, SECRET_BASE_* id
* the 24 base maps (sSecretBaseEntrancePositions in src/secret_base.c): map name, entrance warp,
  the tile in front of the PC, and per tile the metatile id and the raw MB_* behaviour
  (the server needs MB_NORMAL / MB_SECRET_BASE_NORTH_WALL / MB_HOLDS_*_DECORATION, which the
  shared TileBehavior folds together)
* every decoration (src/data/decoration/header.h + tiles.h): permission, shape, metatiles (as
  secondary-tileset indices, DECOR_TILE) or the object gfx id of a doll / cushion
* the MB_* behaviour and layer type of every secret-base secondary metatile a decoration uses
  (src/decoration.c ShowDecorationOnMap_ / CanPlaceDecoration read them)
* the closed / open entrance metatiles (sSecretBaseEntranceMetatiles)

Re-runnable and deterministic. Usage, from the repo root:

  python3 codegen/tools/gen_secret_base_data.py
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EM = ROOT / "decomp/pokeemerald"
OUT = ROOT / "codegen/src/main/kotlin/de/fiereu/openmmo/progression/SecretBaseTables.kt"


def read(p):
    return (EM / p).read_text(encoding="utf-8")


def defines(path, prefix):
    out = {}
    for m in re.finditer(r"#define\s+(" + prefix + r"\w+)\s+(\d+|0x[0-9A-Fa-f]+)\b", read(path)):
        out[m.group(1)] = int(m.group(2), 0)
    return out


def enum_values(text):
    out, n = {}, 0
    for m in re.finditer(r"^\s*(\w+)\s*(?:=\s*([^,\n]+))?,", text, re.M):
        if m.group(2):
            n = int(m.group(2).strip(), 0)
        out[m.group(1)] = n
        n += 1
    return out


MB = enum_values(read("include/constants/metatile_behaviors.h").split("enum {", 1)[1].split("};")[0])
MB_NAME = {v: k for k, v in MB.items()}
LABELS = defines("include/constants/metatile_labels.h", "METATILE_")
GFX = defines("include/constants/event_objects.h", "OBJ_EVENT_GFX_")
DECOR = defines("include/constants/decorations.h", "DECOR_")
SB_IDS = defines("include/constants/secret_bases.h", "SECRET_BASE_")
NUM_PRIMARY = 512

dec_h = read("include/decoration.h")
PERM = enum_values(dec_h.split("enum DecorationPermission", 1)[1].split("{", 1)[1].split("};")[0])
SHAPE = enum_values(dec_h.split("enum DecorationShape", 1)[1].split("{", 1)[1].split("};")[0])
SHAPE_WH = {"1x1": (1, 1), "2x1": (2, 1), "3x1": (3, 1), "4x2": (4, 2), "2x2": (2, 2), "1x2": (1, 2),
            "1x3": (1, 3), "2x4": (2, 4), "3x3": (3, 3), "3x2": (3, 2)}

# ------------------------------------------------------------------ maps and layouts
maps = {}
for p in sorted((EM / "data/maps").glob("*/map.json")):
    j = json.loads(p.read_text(encoding="utf-8"))
    maps[j["id"]] = j
layouts = {l["id"]: l for l in json.loads(read("data/layouts/layouts.json"))["layouts"] if "id" in l}

# tileset symbol -> attributes file
headers = read("src/data/tilesets/headers.h")
metatiles_h = read("src/data/tilesets/metatiles.h")
attr_file = {m.group(1): m.group(2) for m in re.finditer(
    r"gMetatileAttributes_(\w+)\[\]\s*=\s*INCBIN_U16\(\"([^\"]+)\.bin\"\)", metatiles_h)}
attr_file = {k: v + ".bin" for k, v in attr_file.items()}
tileset_attrs = {}
for m in re.finditer(r"const struct Tileset (gTileset_\w+)\s*=\s*\{(.*?)\};", headers, re.S):
    a = re.search(r"\.metatileAttributes\s*=\s*gMetatileAttributes_(\w+)", m.group(2))
    if a and a.group(1) in attr_file:
        tileset_attrs[m.group(1)] = attr_file[a.group(1)]


def attrs(tileset):
    b = (EM / tileset_attrs[tileset]).read_bytes()
    return [b[i] | (b[i + 1] << 8) for i in range(0, len(b), 2)]


def layout_attr(layout, metatile):
    if metatile < NUM_PRIMARY:
        a = attrs(layout["primary_tileset"])
        return a[metatile] if metatile < len(a) else 0
    a = attrs(layout["secondary_tileset"])
    i = metatile - NUM_PRIMARY
    return a[i] if i < len(a) else 0


# ------------------------------------------------------------------ spots
spots = []
for mid, j in sorted(maps.items(), key=lambda kv: kv[1]["name"]):
    for be in j.get("bg_events") or []:
        if be.get("type") == "secret_base":
            spots.append((j["name"], be["x"], be["y"], SB_IDS[be["secret_base_id"]]))

# ------------------------------------------------------------------ base maps
sb_c = read("src/secret_base.c")
pos_block = sb_c.split("sSecretBaseEntrancePositions", 1)[1].split("};", 1)[0]
groups = []
for m in re.finditer(r"\[(SECRET_BASE_\w+)\]\s*=\s*MAP_NUM\((MAP_\w+)\),\s*(\d+),\s*(\d+),\s*(\d+)", pos_block):
    j = maps[m.group(2)]
    lay = layouts[j["layout"]]
    blk = (EM / lay["blockdata_filepath"]).read_bytes()
    w, h = lay["width"], lay["height"]
    cells = []
    for i in range(w * h):
        raw = blk[i * 2] | (blk[i * 2 + 1] << 8)
        mt = raw & 0x3FF
        beh = layout_attr(lay, mt) & 0xFF
        cells.append(f"{mt:x}.{beh:x}")
    groups.append((j["name"], int(m.group(3)), int(m.group(4)), int(m.group(5)), w, h, ",".join(cells)))

entrances = []
for m in re.finditer(r"closedMetatileId\s*=\s*(METATILE_\w+),\s*\.openMetatileId\s*=\s*(METATILE_\w+)", sb_c):
    entrances.append((LABELS[m.group(1)], LABELS[m.group(2)]))

# ------------------------------------------------------------------ decorations
tiles_h = read("src/data/decoration/tiles.h")
gfx_arrays = {}
for m in re.finditer(r"const u16 (DecorGfx_\w+)\[\]\s*=\s*\{(.*?)\};", tiles_h, re.S):
    body = m.group(2)
    tiles = [LABELS["METATILE_SecretBase_" + t] - NUM_PRIMARY for t in re.findall(r"DECOR_TILE\((\w+)\)", body)]
    if not tiles:
        g = re.findall(r"(OBJ_EVENT_GFX_\w+)", body)
        tiles = [-GFX[x] - 1 for x in g]  # negative: an object gfx id (-1 - id)
    gfx_arrays[m.group(1)] = tiles

sb_attrs = attrs("gTileset_SecretBaseRedCave")  # the shared secondary attributes
decors = []
used_tiles = set()
for m in re.finditer(r"\[(DECOR_\w+)\]\s*=\s*\{(.*?)\n    \},", read("src/data/decoration/header.h"), re.S):
    if m.group(1) == "DECOR_NONE":
        continue
    body = m.group(2)
    perm = PERM[re.search(r"\.permission\s*=\s*(\w+)", body).group(1)]
    shape = re.search(r"\.shape\s*=\s*DECORSHAPE_(\w+)", body).group(1)
    tiles = gfx_arrays[re.search(r"\.tiles\s*=\s*(\w+)", body).group(1)]
    w, h = SHAPE_WH[shape]
    for t in tiles:
        if t >= 0:
            used_tiles.add(t)
            used_tiles.add(t | 1)  # the wall-background variant (overlapsWall)
    decors.append((DECOR[m.group(1)], perm, w, h, ",".join(str(t) for t in tiles)))

tile_str = ";".join(f"{t}:{sb_attrs[t] & 0xFF:x}:{(sb_attrs[t] >> 12) & 0xF}" for t in sorted(used_tiles)
                    if t < len(sb_attrs))
mb_used = sorted({int(c.split(".")[1], 16) for g in groups for c in g[6].split(",")}
                 | {sb_attrs[t] & 0xFF for t in used_tiles if t < len(sb_attrs)})
mb_str = ";".join(f"{v:x}:{MB_NAME.get(v, 'MB_NORMAL')}" for v in mb_used)

# ------------------------------------------------------------------ emit


def chunks(s, n=4000):
    return [s[i:i + n] for i in range(0, len(s), n)] or [""]


def kstr(s):
    return " +\n      ".join('"' + p.replace("$", "\\$") + '"' for p in chunks(s))


spot_str = ";".join(f"{n}:{x}:{y}:{i}" for n, x, y, i in spots)
group_str = "|".join(f"{n}/{wid}/{cx}/{cy}/{w}/{h}/{cells}" for n, wid, cx, cy, w, h, cells in groups)
decor_str = ";".join(f"{i}/{p}/{w}/{h}/{t}" for i, p, w, h, t in decors)
ent_str = ";".join(f"{c:x}:{o:x}" for c, o in entrances)

OUT.write_text(f"""// GENERATED by codegen/tools/gen_secret_base_data.py from the pokeemerald decomp. Do not edit.
package de.fiereu.openmmo.progression

/** Packed Emerald secret base tables; [SecretBaseData] parses them. */
object SecretBaseTables {{
  /** Every spot: `MapName:x:y:SECRET_BASE id;...` */
  val SPOTS =
      {kstr(spot_str)}

  /**
   * Base maps by group (id / 10): `MapName/entranceWarp/pcX/pcY/w/h/metatile.MB,...|...`, the cells
   * row by row, both in hex.
   */
  val GROUPS =
      {kstr(group_str)}

  /** `id/permission/w/h/tile,tile,...;...`; a tile < 0 is an object gfx id (-1 - tile). */
  val DECORATIONS =
      {kstr(decor_str)}

  /** Secondary-tileset index : MB (hex) : layer type, for the decoration metatiles. */
  val DECOR_TILES =
      {kstr(tile_str)}

  /** MB value (hex) : MB_* name, for every value above. */
  val MB_NAMES =
      {kstr(mb_str)}

  /** sSecretBaseEntranceMetatiles: `closed:open` metatile ids (hex). */
  val ENTRANCES =
      {kstr(ent_str)}
}}
""", encoding="utf-8")
print(f"wrote {OUT.relative_to(ROOT)}: {len(spots)} spots, {len(groups)} base maps, {len(decors)} decorations, "
      f"{len(used_tiles)} decor tiles")
