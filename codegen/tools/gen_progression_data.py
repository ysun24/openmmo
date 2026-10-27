#!/usr/bin/env python3
"""Generates the Gen 3 progression tables the item, pokedex and daycare services need, straight from
the pokeemerald decomp, into one Kotlin file of the server:

  server.game/.../progression/Gen3Tables.kt

* TM/HM -> move (`include/constants/tms_hms.h`, the order of `sTMHMMoves` in party_menu.c)
* per species TM/HM compatibility (`src/data/pokemon/tmhm_learnsets.h`)
* egg moves (`src/data/pokemon/egg_moves.h`)
* evolutions (`src/data/pokemon/evolution.h`); item parameters are written as the generated
  `Items.<NAME>` constant the server knows, everything else as the decomp number
* the Hoenn regional dex order (`include/constants/pokedex.h`)
* decorations (`src/data/decoration/header.h`) and the decoration mart stocks
  (`pokemartdecoration` / `pokemartdecoration2` lists in `data/maps/*/scripts.inc`)

Everything is keyed by national dex number. Tables are packed into strings parsed once at start-up
so the class stays far below the JVM's 64 KiB method limit. Re-runnable; the output is
deterministic. Usage, from the repo root:

  python3 codegen/tools/gen_progression_data.py
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EM = ROOT / "decomp/pokeemerald"
OUT = ROOT / ("server.game/src/main/kotlin/de/fiereu/openmmo/server/game/progression/Gen3Tables.kt")
ITEMS_KT = next((ROOT / "codegen/build/generated/source/item").rglob("Items.kt"), None)


def read(p):
    return (EM / p).read_text(encoding="utf-8")


def defines(path, prefix):
    out = {}
    for m in re.finditer(r"#define\s+(" + prefix + r"\w+)\s+(\d+|0x[0-9A-Fa-f]+)\b", read(path)):
        out[m.group(1)] = int(m.group(2), 0)
    return out


MOVES = defines("include/constants/moves.h", "MOVE_")
SPECIES = defines("include/constants/species.h", "SPECIES_")


def enum_names(text, prefix):
    names = []
    for m in re.finditer(r"\b(" + prefix + r"\w+)\s*(?:=\s*[^,]+)?,", text):
        names.append(m.group(1))
    return names


dex_text = read("include/constants/pokedex.h")
nat_block = dex_text.split("enum {", 2)[1].split("};")[0]
NATIONAL = {n: i for i, n in enumerate(enum_names(nat_block, "NATIONAL_DEX_"))}
hoenn_block = dex_text.split("enum {", 3)[2].split("};")[0]
HOENN = enum_names(hoenn_block, "HOENN_DEX_")


def nat_of_species(name):
    """SPECIES_X -> national dex number, or None for forms (Unown letters, eggs)."""
    return NATIONAL.get("NATIONAL_DEX_" + name[len("SPECIES_"):])


# ------------------------------------------------------------------ TM/HM moves
tms = read("include/constants/tms_hms.h")
tm_names = re.findall(r"F\((\w+)\)", tms.split("FOREACH_HM")[0])
hm_names = re.findall(r"F\((\w+)\)", tms.split("#define FOREACH_HM(F)")[1].split("#define")[0])
TMHM = tm_names + hm_names
assert len(tm_names) == 50 and len(hm_names) == 8, (len(tm_names), len(hm_names))
tm_moves = [MOVES["MOVE_" + n] for n in TMHM]

# ------------------------------------------------------------------ TM/HM learnsets
learn = read("src/data/pokemon/tmhm_learnsets.h")
tm_sets = {}
for m in re.finditer(r"\[(SPECIES_\w+)\]\s*=\s*\{\s*\.learnset\s*=\s*\{(.*?)\}\s*\}", learn, re.S):
    nat = nat_of_species(m.group(1))
    if nat is None:
        continue
    bits = 0
    for flag in re.findall(r"\.(\w+)\s*=\s*TRUE", m.group(2)):
        bits |= 1 << TMHM.index(flag)
    tm_sets[nat] = bits

# ------------------------------------------------------------------ egg moves
eggs = read("src/data/pokemon/egg_moves.h")
egg_moves = {}
for m in re.finditer(r"egg_moves\((\w+),(.*?)\)", eggs, re.S):
    nat = nat_of_species("SPECIES_" + m.group(1))
    if nat is None:
        continue
    egg_moves[nat] = [MOVES[x] for x in re.findall(r"MOVE_\w+", m.group(2))]

# ------------------------------------------------------------------ evolutions
item_consts = set()
if ITEMS_KT:
    item_consts = set(re.findall(r"val ([A-Z0-9_]+) = item\(", ITEMS_KT.read_text()))
ITEM_ALIASES = {"THUNDER_STONE": "THUNDERSTONE", "KINGS_ROCK": "KING_S_ROCK", "DEEP_SEA_TOOTH": "DEEPSEATOOTH",
                "DEEP_SEA_SCALE": "DEEPSEASCALE"}


def item_const(name):
    n = name[len("ITEM_"):]
    n = ITEM_ALIASES.get(n, n)
    if item_consts and n not in item_consts:
        raise SystemExit(f"evolution item {name} has no Items constant")
    return n


evo_text = read("src/data/pokemon/evolution.h")
evolutions = {}
for m in re.finditer(r"\[(SPECIES_\w+)\]\s*=\s*\{(.*?)\}\s*,\s*(?=\[|\})", evo_text, re.S):
    nat = nat_of_species(m.group(1))
    if nat is None:
        continue
    for e in re.finditer(r"\{(EVO_\w+),\s*(\w+),\s*(SPECIES_\w+)\}", m.group(2)):
        method, param, target = e.groups()
        tnat = nat_of_species(target)
        if tnat is None:
            continue
        if param.startswith("ITEM_"):
            p = "I" + item_const(param)
        else:
            p = str(int(param, 0))
        evolutions.setdefault(nat, []).append((method[len("EVO_"):], p, tnat))

# ------------------------------------------------------------------ decorations
DECOR = defines("include/constants/decorations.h", "DECOR_")
dec_text = read("src/data/decoration/header.h")
decorations = []
for m in re.finditer(
        r"\[(DECOR_\w+)\]\s*=\s*\{.*?\.name\s*=\s*_\(\"([^\"]*)\"\).*?\.category\s*=\s*DECORCAT_(\w+)"
        r".*?\.price\s*=\s*(\d+)", dec_text, re.S):
    did = DECOR[m.group(1)]
    if did == 0:
        continue
    decorations.append((did, m.group(2), m.group(3), int(m.group(4))))

stocks = {}
for path in sorted((EM / "data/maps").glob("*/scripts.inc")):
    text = path.read_text(encoding="utf-8")
    for m in re.finditer(r"^(\w+):\s*\n((?:\s*\.2byte DECOR_\w+\s*\n)+)\s*(?:pokemartlistend|\.2byte 0\b)", text, re.M):
        stocks[m.group(1)] = [DECOR[d] for d in re.findall(r"DECOR_\w+", m.group(2))]

# ------------------------------------------------------------------ emit


def chunks(s, n=4000):
    return [s[i:i + n] for i in range(0, len(s), n)] or [""]


def kstr(s):
    parts = chunks(s)
    return " +\n        ".join('"' + p.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'
                               for p in parts)


tm_str = ",".join(map(str, tm_moves))
set_str = ";".join(f"{k}:{v:x}" for k, v in sorted(tm_sets.items()))
egg_str = ";".join(f"{k}:" + ",".join(map(str, v)) for k, v in sorted(egg_moves.items()))
evo_str = ";".join(f"{k}:" + "|".join(f"{a}/{b}/{c}" for a, b, c in v) for k, v in sorted(evolutions.items()))
hoenn_order = []
for n in HOENN[1:]:
    nat = NATIONAL.get("NATIONAL_DEX_" + n[len("HOENN_DEX_"):])
    if nat:
        hoenn_order.append(nat)
    if n == "HOENN_DEX_DEOXYS":
        break
hoenn_str = ",".join(map(str, hoenn_order))
dec_str = ";".join(f"{i}/{n}/{c}/{p}" for i, n, c, p in decorations)
stock_str = ";".join(f"{k}:" + ",".join(map(str, v)) for k, v in sorted(stocks.items()))

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(f"""// GENERATED by codegen/tools/gen_progression_data.py from the pokeemerald decomp. Do not edit.
package de.fiereu.openmmo.server.game.progression

/** Packed Gen 3 tables; [Gen3Data] parses them. Keys are national dex numbers. */
internal object Gen3Tables {{
  /** Move id of TM01..TM50 then HM01..HM08. */
  const val TMHM_MOVES =
      {kstr(tm_str)}

  /** `dex:hexbits`, bit n = TMHM index n (TM01 = 0, HM01 = 50). */
  val TMHM_LEARNSETS =
      {kstr(set_str)}

  /** `dex:move,move,...` */
  val EGG_MOVES =
      {kstr(egg_str)}

  /** `dex:METHOD/param/target|...`, an item param is `I<Items constant>`. */
  val EVOLUTIONS =
      {kstr(evo_str)}

  /** The Hoenn regional dex, in order, as national numbers. */
  val HOENN_DEX =
      {kstr(hoenn_str)}

  /** `id/NAME/CATEGORY/price;...` */
  val DECORATIONS =
      {kstr(dec_str)}

  /** Decoration mart stock lists by their decomp label: `label:id,id;...` */
  val DECOR_STOCKS =
      {kstr(stock_str)}
}}
""", encoding="utf-8")
print(f"wrote {OUT.relative_to(ROOT)}: {len(tm_sets)} tm sets, {len(egg_moves)} egg sets, "
      f"{len(evolutions)} evolving species, {len(hoenn_order)} hoenn dex, {len(decorations)} decor, "
      f"{len(stocks)} decor stocks")
