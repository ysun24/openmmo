#!/usr/bin/env python3
"""Generates the Hoenn contest / berry / Pokeblock tables (r5-contests) from the pokeemerald decomp into

  codegen/src/main/resources/contest/contest_data.json

Everything is read from the pret decomp, nothing is invented:

* gContestMoves (src/data/contest_moves.h): per move its contest effect, category, combo starter id and
  the combo starter ids it finishes; gContestEffects (appeal, jam, effect type); gComboStarterLookupTable
* gContestOpponents + gPostgameContestOpponentFilter (src/data/contest_opponents.h)
* the contest AI scripts (data/contest_ai_scripts.s), assembled into a flat instruction list with the
  opcodes and arguments of asm/macros/contest_ai_script.inc (labels resolved to instruction indices)
* sContestExcitementTable (src/contest.c)
* the contest texts (data/text/contest_strings.inc) and the string tables of
  src/data/contest_text_tables.h (effect descriptions, appeal result texts, round result texts)
* gBerries (src/berry.c), the berry tree objects of every map (data/maps/*/map.json with
  BerryTreeScript and their trainer_sight_or_berry_tree_id), the new-game trees (data/scripts/new_game.inc)
* gPokeblockFlavorCompatibilityTable, gPokeblockNames (src/pokeblock.c, src/strings.c)
* the Berry Blender's NPC tables (src/berry_blender.c): sOpponentBerrySets, sBerryMasterBerries,
  sBlackPokeblockFlavorFlags, sArrowHitRangeStart, sNumPlayersToSpeedDivisor, sPlayerIdMap, names

Usage, from the repo root:  python3 codegen/tools/gen_contest_data.py
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EM = ROOT / "decomp/pokeemerald"
OUT = ROOT / "codegen/src/main/resources/contest/contest_data.json"


def read(p):
    return (EM / p).read_text(encoding="utf-8")


def defines(path, prefix=""):
    out = {}
    for m in re.finditer(r"#define\s+(" + prefix + r"\w*)\s+\(1 << (\d+)\)", read(path)):
        out[m.group(1)] = 1 << int(m.group(2))
    for m in re.finditer(r"#define\s+(" + prefix + r"\w*)\s+\(?([0-9xA-Fa-f]+)\)?\s*(?://.*)?$", read(path), re.M):
        out[m.group(1)] = int(m.group(2), 0)
    return out


def enum_values(text, prefix):
    """C enum members (implicit values) in declaration order."""
    out, n = {}, 0
    for m in re.finditer(r"\b(" + prefix + r"\w+)\s*(=\s*([^,\n]+))?,", text):
        if m.group(3):
            v = m.group(3).strip()
            n = out[v] if v in out else int(v, 0)
        out[m.group(1)] = n
        n += 1
    return out


moves = defines("include/constants/moves.h", "MOVE_")
species = defines("include/constants/species.h", "SPECIES_")
items = defines("include/constants/items.h", "ITEM_")
contest_c = defines("include/constants/contest.h", "")
contest_c.update(defines("include/constants/global.h", "CONTEST_"))
contest_c.update(enum_values(read("include/contest.h"), "CONTEST_STRING_"))
contest_c["CONTEST_NUM_APPEALS"] = 5
contest_c["CONTEST_LAST_APPEAL"] = 4
national = enum_values(read("include/constants/pokedex.h"), "NATIONAL_DEX_")


def resolve(v):
    v = v.strip()
    for t in (moves, species, items, contest_c):
        if v in t:
            return t[v]
    if v in ("TRUE",):
        return 1
    if v in ("FALSE",):
        return 0
    return int(v, 0)


def species_to_dex(name):
    base = name[len("SPECIES_"):]
    return national.get("NATIONAL_DEX_" + base, 0)


def c_string(s):
    s = re.sub(r"\{PAUSE[^}]*\}", "", s)
    return s.replace("\\n", "\n").replace("\\l", "\n").replace("\\p", "\n\n")


# ------------------------------------------------------------------ contest moves / effects
cm = read("src/data/contest_moves.h")
moves_out = {}
body = cm.split("gContestMoves[MOVES_COUNT] =", 1)[1].split("};", 1)[0]
for m in re.finditer(r"\[(MOVE_\w+)\]\s*=\s*\{\s*\n(.*?)\n    \}", body, re.S):
    b = m.group(2)
    eff = re.search(r"\.effect\s*=\s*(\w+)", b)
    if not eff:
        continue
    cat = re.search(r"\.contestCategory\s*=\s*(\w+)", b).group(1)
    sm = re.search(r"\.comboStarterId\s*=\s*(\w+)", b)
    starter = sm.group(1) if sm else "0"
    cmm = re.search(r"\.comboMoves\s*=\s*\{([^}]*)\}", b)
    combos = cmm.group(1) if cmm else ""
    combo_ids = [resolve(c) for c in combos.split(",") if c.strip() and c.strip() != "0"]
    moves_out[moves[m.group(1)]] = {
        "effect": contest_c[eff.group(1)],
        "category": contest_c[cat],
        "starter": resolve(starter),
        "combos": combo_ids,
    }

effects_out = []
eb = cm.split("gContestEffects[] =", 1)[1].split("};", 1)[0]
for m in re.finditer(r"\[(CONTEST_EFFECT_\w+)\]\s*=\s*\{(.*?)\}", eb, re.S):
    b = m.group(2)
    effects_out.append({
        "id": contest_c[m.group(1)],
        "type": contest_c[re.search(r"\.effectType\s*=\s*(\w+)", b).group(1)],
        "appeal": int(re.search(r"\.appeal\s*=\s*(\d+)", b).group(1)),
        "jam": int(re.search(r"\.jam\s*=\s*(\d+)", b).group(1)),
    })
effects_out.sort(key=lambda e: e["id"])
lookup = cm.split("gComboStarterLookupTable[] =", 1)[1].split("};", 1)[0]
combo_lookup = [1 if t.strip() == "TRUE" else 0 for t in lookup.replace("{", "").split(",") if t.strip()]

# ------------------------------------------------------------------ excitement table
cc = read("src/contest.c")
ex = cc.split("sContestExcitementTable[CONTEST_CATEGORIES_COUNT][CONTEST_CATEGORIES_COUNT] =", 1)[1].split("};", 1)[0]
excitement = []
for row in re.finditer(r"\[CONTEST_CATEGORY_\w+\]\s*=\s*\{(.*?)\}", ex, re.S):
    excitement.append([int(v) for v in re.findall(r"=\s*([+-]?\d+)", row.group(1))])

# ------------------------------------------------------------------ opponents
co = read("src/data/contest_opponents.h")
ai_sets = {}
for m in re.finditer(r"#define\s+(CONTEST_AI_SET_\w+)\s+\((.*?)\)\n", co):
    v = 0
    for part in m.group(2).split("|"):
        p = part.strip()
        if p == "CONTEST_AI_COMMON":
            v |= sum(1 << i for i in range(12))
        else:
            v |= contest_c[p]
    ai_sets[m.group(1)] = v
filt_body = co.split("gPostgameContestOpponentFilter[] =", 1)[1].split("};", 1)[0]
filters = {m.group(1): m.group(2) for m in re.finditer(r"\[(CONTEST_OPPONENT_\w+)\]\s*=\s*(CONTEST_FILTER_\w+)", filt_body)}
FILT = {"CONTEST_FILTER_NONE": 0, "CONTEST_FILTER_NO_POSTGAME": 1, "CONTEST_FILTER_ONLY_POSTGAME": 2}
ob = co.split("gContestOpponents[] =", 1)[1]
opponents = []
for m in re.finditer(r"\[(CONTEST_OPPONENT_\w+)\]\s*=\s*\{(.*?)\n    \},?\n", ob, re.S):
    b = m.group(2)

    def f(k):
        r = re.search(r"\." + k + r"\s*=\s*([^,\n]+),", b)
        return r.group(1).strip() if r else None

    mv = re.search(r"\.moves\s*=\s*\{(.*?)\}", b, re.S).group(1)
    opponents.append({
        "key": m.group(1)[len("CONTEST_OPPONENT_"):],
        "dex": species_to_dex(f("species")),
        "nickname": re.search(r'\.nickname\s*=\s*_\("([^"]*)"\)', b).group(1),
        "trainer": re.search(r'\.trainerName\s*=\s*_\("([^"]*)"\)', b).group(1),
        "gfx": f("trainerGfxId"),
        "ai": ai_sets[f("aiFlags")],
        "rank": contest_c[f("whichRank")],
        "pools": [1 if f("aiPool_" + c) == "TRUE" else 0 for c in ("Cool", "Beauty", "Cute", "Smart", "Tough")],
        "moves": [moves[x.strip()] for x in mv.split(",") if x.strip()],
        "stats": [int(f(k)) for k in ("cool", "beauty", "cute", "smart", "tough", "sheen")],
        "filter": FILT[filters.get(m.group(1), "CONTEST_FILTER_NONE")],
    })

# ------------------------------------------------------------------ AI scripts
macros = read("asm/macros/contest_ai_script.inc")
macro_defs = {}
for m in re.finditer(r"\.macro[ \t]+(\w+)[ \t]*([^\n]*)\n(.*?)\.endm", macros, re.S):
    params = [p.split(":")[0].strip() for p in re.split(r"[,\s]+", m.group(2)) if p.strip()]
    macro_defs[m.group(1)] = (params, m.group(3))


def expand(name, args):
    """Expand one macro call into (opcode, [args...]) with label args kept as strings."""
    params, body = macro_defs[name]
    sub = dict(zip(params, args))
    out = []
    for line in body.strip().splitlines():
        line = line.strip()
        if not line:
            continue
        for k, v in sub.items():
            line = line.replace("\\" + k, v)
        mm = re.match(r"\.(byte|2byte|4byte)\s+(.*)", line)
        if mm:
            out.append((mm.group(1), mm.group(2).strip()))
        else:
            parts = line.split(None, 1)
            sub_args = [a.strip() for a in parts[1].split(",")] if len(parts) > 1 else []
            out.extend(expand(parts[0], sub_args))
    return out


ai_src = read("data/contest_ai_scripts.s")
MON = {"MON_1": 0, "MON_2": 1, "MON_3": 2, "MON_4": 3}
table = re.findall(r"\.4byte\s+(AI_\w+)", ai_src.split("gContestAI_ScriptsTable::", 1)[1].split("\n\n", 1)[0])
program, labels = [], {}
for raw in ai_src.split("gContestAI_ScriptsTable::", 1)[1].split("\n"):
    line = raw.split("@")[0].strip()
    if not line or line.startswith(".4byte") or line.startswith(".align"):
        continue
    lm = re.match(r"(\w+)::?$", line)
    if lm:
        labels[lm.group(1)] = len(program)
        continue
    parts = line.split(None, 1)
    args = [a.strip() for a in parts[1].split(",")] if len(parts) > 1 else []
    if parts[0] not in macro_defs:
        continue
    bytes_ = expand(parts[0], args)
    if not bytes_:
        raise SystemExit("empty expansion: " + line + " " + repr(macro_defs[parts[0]]))
    op = int(bytes_[0][1], 0)
    ins_args, target = [], None
    for kind, val in bytes_[1:]:
        if kind == "4byte":
            target = val
        else:
            if val in MON:
                ins_args.append(MON[val])
            else:
                try:
                    ins_args.append(resolve(val))
                except Exception:
                    ins_args.append(int(eval(val, {}, dict(contest_c))))
    program.append([op, ins_args, target])
for ins in program:
    if ins[2] is not None:
        ins[2] = labels[ins[2]]
    else:
        ins[2] = -1
ai_table = [labels[t] for t in table]

# ------------------------------------------------------------------ texts
strings = {}
for m in re.finditer(r"^(\w+)::\n((?:\s*\.string \"[^\n]*\"\n)+)", read("data/text/contest_strings.inc"), re.M):
    strings[m.group(1)] = c_string("".join(re.findall(r'\.string "([^"]*)"', m.group(2)))).rstrip("$")
tt = read("src/data/contest_text_tables.h")


def text_table(name, index_prefix=None):
    b = tt.split(name, 1)[1].split("};", 1)[0]
    if index_prefix:
        out = {}
        for m in re.finditer(r"\[(" + index_prefix + r"\w+)\]\s*=\s*(\w+)", b):
            out[contest_c[m.group(1)]] = strings.get(m.group(2), "")
        n = max(out) + 1 if out else 0
        return [out.get(i, "") for i in range(n)]
    return [strings.get(x, "") for x in re.findall(r"(gText_\w+)", b)]


descriptions = text_table("gContestEffectDescriptionPointers[] =", "CONTEST_EFFECT_")
appeal_results = text_table("sAppealResultTexts[] =", "CONTEST_STRING_")
round_results = text_table("sRoundResultTexts[] =")
conditions = text_table("sContestConditions[] =", "CONTEST_CATEGORY_")
misc = {k: strings[k] for k in (
    "gText_MonAppealedWithMove", "gText_MonCantAppealNextTurn", "gText_RepeatedAppeal",
    "gText_MonsXDidntGoOverWell", "gText_MonsXWentOverGreat", "gText_MonsXGotTheCrowdGoing",
    "gText_CrowdContinuesToWatchMon", "gText_MonsMoveIsIgnored", "gText_MonWasTooNervousToMove",
    "gText_MonWasWatchingOthers", "gText_AllOutOfAppealTime", "gText_Contest_Shyness",
    "gText_Contest_Anxiety", "gText_Contest_Laziness", "gText_Contest_Hesitancy", "gText_Contest_Fear",
    "gText_AppealComboWentOverWell", "gText_JudgeLookedAtMonExpectantly",
    "gText_AppealNumWhichMoveWillBePlayed", "gText_AppealNumButItCantParticipate") if k in strings}

# ------------------------------------------------------------------ berries
bc = read("src/berry.c")
descs = {m.group(1): m.group(2) for m in re.finditer(r'static const u8 (sBerryDescriptionPart\d_\w+)\[\]\s*=\s*_\("([^"]*)"\);', bc)}
berries = []
bb = bc.split("const struct Berry gBerries[] =", 1)[1].split("\n};", 1)[0]
FIRM = {"BERRY_FIRMNESS_UNKNOWN": 0, "BERRY_FIRMNESS_VERY_SOFT": 1, "BERRY_FIRMNESS_SOFT": 2,
        "BERRY_FIRMNESS_HARD": 3, "BERRY_FIRMNESS_VERY_HARD": 4, "BERRY_FIRMNESS_SUPER_HARD": 5}
for m in re.finditer(r"\[(ITEM_\w+) - FIRST_BERRY_INDEX\]\s*=\s*\{(.*?)\}", bb, re.S):
    b = m.group(2)

    def g(k):
        return re.search(r"\." + k + r"\s*=\s*([^,\n]+),", b).group(1).strip()

    berries.append({
        "item": m.group(1),
        "name": re.search(r'\.name\s*=\s*_\("([^"]*)"\)', b).group(1),
        "firmness": FIRM[g("firmness")],
        "size": int(g("size")),
        "maxYield": int(g("maxYield")),
        "minYield": int(g("minYield")),
        "description": [descs.get(g("description1"), ""), descs.get(g("description2"), "")],
        "stageHours": int(g("stageDuration")),
        "flavors": [int(g(k)) for k in ("spicy", "dry", "sweet", "bitter", "sour")],
        "smoothness": int(g("smoothness")),
    })

tree_ids = defines("include/constants/berry.h", "BERRY_TREE_")
groups = json.loads(read("data/maps/map_groups.json"))
trees = []
for group in groups["group_order"]:
    for name in groups[group]:
        mj = EM / "data/maps" / name / "map.json"
        if not mj.exists():
            continue
        d = json.loads(mj.read_text())
        for idx, o in enumerate(d.get("object_events", [])):
            if o.get("script") == "BerryTreeScript":
                tid = o["trainer_sight_or_berry_tree_id"]
                trees.append({"map": name, "x": o["x"], "y": o["y"], "localId": idx + 1,
                              "tree": tree_ids.get(tid, 0), "key": tid})
initial = []
STAGES = {"BERRY_STAGE_NO_BERRY": 0, "BERRY_STAGE_PLANTED": 1, "BERRY_STAGE_SPROUTED": 2,
          "BERRY_STAGE_TALLER": 3, "BERRY_STAGE_FLOWERING": 4, "BERRY_STAGE_BERRIES": 5}
for m in re.finditer(r"setberrytree\s+(BERRY_TREE_\w+),\s*ITEM_TO_BERRY\((ITEM_\w+)\),\s*(BERRY_STAGE_\w+)",
                     read("data/scripts/new_game.inc")):
    initial.append({"tree": tree_ids[m.group(1)], "item": m.group(2), "stage": STAGES[m.group(3)]})

# ------------------------------------------------------------------ pokeblocks / blender
pb = read("src/pokeblock.c")
compat_body = pb.split("gPokeblockFlavorCompatibilityTable[NUM_NATURES * FLAVOR_COUNT] =", 1)[1].split("};", 1)[0]
compat_rows = []
for line in compat_body.splitlines():
    nums = re.findall(r"(-?\d+)", line.split("//")[0])
    if len(nums) == 5:
        compat_rows.append([int(n) for n in nums])
strings_c = read("src/strings.c")
pb_names = []
for m in re.finditer(r"\[(PBLOCK_CLR_\w+)\]\s*=\s*(\w+)", pb.split("gPokeblockNames[] =", 1)[1].split("};", 1)[0]):
    sm = re.search(r"const u8 " + m.group(2) + r'\[\]\s*=\s*_\("([^"]*)"\);', strings_c)
    pb_names.append(sm.group(1).replace("{POKEBLOCK}", "POKéBLOCK") if sm else "")

bl = read("src/berry_blender.c")
berry_index = {b["item"]: i + 1 for i, b in enumerate(berries)}


def item_to_berry_expr(expr):
    # "ITEM_TO_BERRY(ITEM_ASPEAR_BERRY) - 1" -> berry index - 1
    m = re.match(r"ITEM_TO_BERRY\((ITEM_\w+)\)\s*-\s*1", expr.strip())
    return berry_index[m.group(1)] - 1


sets = []
sb = bl.split("sOpponentBerrySets[NUM_NPC_BERRIES * 2][3] =", 1)[1].split("};", 1)[0]
for row in re.finditer(r"\{(ITEM_TO_BERRY[^}]*)\}", sb):
    sets.append([item_to_berry_expr(e) for e in row.group(1).split(",") if e.strip()])
master = [item_to_berry_expr(e) for e in bl.split("sBerryMasterBerries[] = {", 1)[1].split("};", 1)[0].split(",") if e.strip()]
FL = {"FLAVOR_SPICY": 0, "FLAVOR_DRY": 1, "FLAVOR_SWEET": 2, "FLAVOR_BITTER": 3, "FLAVOR_SOUR": 4}
black = []
for line in bl.split("sBlackPokeblockFlavorFlags[] = {", 1)[1].split("};", 1)[0].splitlines():
    fl = re.findall(r"1 << (FLAVOR_\w+)", line)
    if fl:
        black.append(sum(1 << FL[f] for f in fl))
hit_start = [int(x) for x in re.search(r"sArrowHitRangeStart\[BLENDER_MAX_PLAYERS\] = \{([^}]*)\}", bl).group(1).split(",")]
divisor = [int(x) for x in re.search(r"sNumPlayersToSpeedDivisor\[\] = \{([^}]*)\}", bl).group(1).split(",")]
names = {m.group(1): m.group(2) for m in re.finditer(r'static const u8 sText_(\w+)\[\] = _\("([^"]*)"\);', bl)}

# ------------------------------------------------------------------ paintings
cp_strings = {}
for m in re.finditer(r"^(\w+)::\n((?:\s*\.string \"[^\n]*\"\n)+)", read("data/text/contest_painting.inc"), re.M):
    cp_strings[m.group(1)] = c_string("".join(re.findall(r'\.string "([^"]*)"', m.group(2)))).rstrip("$")
painting = read("src/contest_painting.c")
caps = re.findall(r"=\s*(gContestPainting\w+),", painting.split("sMuseumCaptions[", 1)[1].split("};", 1)[0])
default_winners = []
dw = co.split("gDefaultContestWinners[] =", 1)[1].split("\n};", 1)[0]
for m in re.finditer(r"\[CONTEST_WINNER_\w+ - 1\]\s*=\s*\{(.*?)\}", dw, re.S):
    b = m.group(1)
    default_winners.append({
        "dex": species_to_dex(re.search(r"\.species\s*=\s*(\w+)", b).group(1)),
        "category": contest_c[re.search(r"\.contestCategory\s*=\s*(\w+)", b).group(1)],
        "nickname": re.search(r'\.monName\s*=\s*_\("([^"]*)"\)', b).group(1),
        "trainer": re.search(r'\.trainerName\s*=\s*_\("([^"]*)"\)', b).group(1),
        "rank": contest_c[re.search(r"\.contestRank\s*=\s*(\w+)", b).group(1)],
    })

data = {
    "paintings": {
        "museumCaptions": [cp_strings.get(c, "") for c in caps],
        "hallCaption": cp_strings.get("gContestHallPaintingCaption", ""),
        "rankNames": [cp_strings.get(k, "") for k in ("gContestRankNormal", "gContestRankSuper", "gContestRankHyper", "gContestRankMaster")],
        "contestNames": [cp_strings.get(k, "") for k in ("gContestCoolness", "gContestBeauty", "gContestCuteness", "gContestSmartness", "gContestToughness")],
        "defaultWinners": default_winners,
    },
    "moves": {str(k): v for k, v in sorted(moves_out.items())},
    "effects": effects_out,
    "comboStarterLookup": combo_lookup,
    "excitement": excitement,
    "opponents": opponents,
    "ai": {"program": program, "table": ai_table},
    "texts": {
        "effectDescriptions": descriptions,
        "appealResults": appeal_results,
        "roundResults": round_results,
        "conditions": conditions,
        "misc": misc,
    },
    "berries": berries,
    "berryTrees": trees,
    "initialBerryTrees": initial,
    "pokeblock": {"compatibility": compat_rows, "names": pb_names},
    "blender": {
        "opponentBerrySets": sets,
        "berryMasterBerries": master,
        "blackFlavorFlags": black,
        "arrowHitRangeStart": hit_start,
        "speedDivisor": divisor,
        "names": {k.upper(): names[k] for k in ("Mister", "Laddie", "Lassie", "Master", "Dude", "Miss")},
    },
}
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
print(f"wrote {OUT}: {len(moves_out)} moves, {len(effects_out)} effects, {len(opponents)} opponents, "
      f"{len(program)} AI instructions, {len(berries)} berries, {len(trees)} berry trees")
