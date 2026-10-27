#!/usr/bin/env python3
"""r5-frontier: exports the Battle Frontier tables from the pret decomps to one JSON resource.

    python3 codegen/tools/gen_frontier_data.py [--decomp decomp] [--out server.game/src/main/resources/frontier/frontier.json]

Everything comes from the decomp sources under decomp/ (no ROMs):
- pokeemerald src/data/battle_frontier/battle_frontier_mons.h       (gBattleFrontierMons, 882 sets)
- pokeemerald src/data/battle_frontier/battle_frontier_trainers.h   (gBattleFrontierTrainers, 300)
- pokeemerald src/data/battle_frontier/battle_frontier_trainer_mons.h (each trainer's mon set)
- pokeemerald src/data/battle_frontier/battle_tent.h                (the three Battle Tents)
- pokeemerald src/frontier_util.c        (sFrontierBrainsMons)
- pokeemerald src/battle_pike.c          (sLvl50Mons / sLvlOpenMons, the Pike wild mons)
- pokeemerald src/data/battle_frontier/battle_pyramid_*_wild_mons.h, src/battle_pyramid.c
                                         (pyramid wild mons per round, pickup items, floor templates)
- pokeemerald src/battle_arena.c         (sMindRatings)
- pokeemerald data/maps/BattleFrontier_ExchangeServiceCorner/scripts.inc + battle_frontier_exchange_corner.h
- pokeemerald src/data/battle_frontier/trainer_hill.h    (the four Trainer Hill modes)
- pokefirered src/trainer_tower_sets.c                   (FRLG Trainer Tower floors)

Species are written as national dex numbers (what the server's SpeciesRegistry keys by), moves as
decomp move ids (= the server's MoveRegistry ids), items as ITEM_ suffixes (resolved by ItemNames),
natures as their index (HARDY = 0).
"""
import argparse
import json
import os
import re
import sys


# ------------------------------------------------------------------ constants

def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


class Consts:
    def __init__(self):
        self.values = {}

    def add_defines(self, path):
        for m in re.finditer(r"^[ \t]*#define[ \t]+(\w+)[ \t]+(.+?)[ \t]*$", strip_comments(read(path)), re.M):
            name, expr = m.group(1), m.group(2)
            if name in self.values:
                continue
            v = self.eval(expr)
            if v is not None:
                self.values[name] = v

    def add_enum(self, path, prefix):
        text = strip_comments(read(path))
        for body in re.findall(r"enum\s*\w*\s*\{(.*?)\}", text, re.S):
            n = 0
            for part in body.split(","):
                part = part.strip()
                if not part:
                    continue
                if "=" in part:
                    name, expr = [p.strip() for p in part.split("=", 1)]
                    v = self.eval(expr)
                    if v is not None:
                        n = v
                else:
                    name = part
                if name.startswith(prefix):
                    self.values.setdefault(name, n)
                n += 1

    def eval(self, expr):
        expr = expr.strip()
        toks = re.findall(r"0x[0-9A-Fa-f]+|\d+|\w+|<<|>>|[()|&+\-*/~]", expr)
        out = []
        for t in toks:
            if re.fullmatch(r"0x[0-9A-Fa-f]+|\d+", t):
                out.append(str(int(t, 0)))
            elif re.fullmatch(r"\w+", t):
                if t in self.values:
                    out.append(str(self.values[t]))
                elif t in ("u8", "u16", "u32", "s8", "s16", "s32"):
                    continue
                else:
                    return None
            else:
                out.append(t if t != "/" else "//")
        if not out:
            return None
        try:
            return int(eval("".join(out), {"__builtins__": {}}))
        except Exception:
            return None


# ------------------------------------------------------------------ C initializer parser

TOKEN = re.compile(r'__?\("(?:[^"\\]|\\.)*"\)|"(?:[^"\\]|\\.)*"|0x[0-9A-Fa-f]+|\w+|<<|>>|\.\.\.|[{}\[\]=,.()|&+\-*/~]')


class Init:
    """A parsed initializer: a list of (designator, value) for braces, or a token list for scalars."""


def tokenize(text):
    return TOKEN.findall(strip_comments(text))


def parse_value(toks, i):
    if toks[i] == "{":
        i += 1
        items = []
        while toks[i] != "}":
            key = None
            if toks[i] == "." and toks[i + 2] == "=":
                key = toks[i + 1]
                i += 3
            elif toks[i] == "[":
                j = i + 1
                depth = 1
                while depth:
                    if toks[j] == "[":
                        depth += 1
                    elif toks[j] == "]":
                        depth -= 1
                    j += 1
                key = toks[i + 1:j - 1]
                i = j + 1  # skip '='
            val, i = parse_value(toks, i)
            items.append((key, val))
            if toks[i] == ",":
                i += 1
        return ("list", items), i + 1
    j = i
    depth = 0
    while True:
        t = toks[j]
        if depth == 0 and t in (",", "}"):
            break
        if t == "(":
            depth += 1
        elif t == ")":
            depth -= 1
        j += 1
    return ("expr", toks[i:j]), j


def find_array(text, name):
    """The initializer of `... name[...] = { ... };`."""
    toks = tokenize(text)
    for i in range(len(toks) - 1):
        if toks[i] == name and (toks[i + 1] == "[" or toks[i + 1] == "="):
            j = i + 1
            while toks[j] != "=":
                j += 1
            val, _ = parse_value(toks, j + 1)
            return val
    raise KeyError(name)


def all_arrays(text, pattern):
    """name -> initializer for every top-level `const T name[] = {...};` whose name matches."""
    toks = tokenize(text)
    out = {}
    i = 0
    while i < len(toks):
        t = toks[i]
        if re.fullmatch(pattern, t) and i + 1 < len(toks) and toks[i + 1] in ("[", "="):
            j = i + 1
            while toks[j] != "=":
                if toks[j] == ";":
                    break
                j += 1
            if toks[j] == "=":
                val, k = parse_value(toks, j + 1)
                out[t] = val
                i = k
                continue
        i += 1
    return out


def fields(v):
    assert v[0] == "list", v
    return {k: val for k, val in v[1] if isinstance(k, str)}


def items(v):
    assert v[0] == "list", v
    return [val for _, val in v[1]]


def text_of(v):
    toks = v[1]
    s = "".join(toks)
    m = re.match(r'__?\("(.*)"\)$', s)
    if m:
        return m.group(1).replace("$", "").strip()
    m = re.match(r'"(.*)"$', s)
    return m.group(1) if m else s


# ------------------------------------------------------------------ main export


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--decomp", default="decomp")
    ap.add_argument("--out", default="server.game/src/main/resources/frontier/frontier.json")
    args = ap.parse_args()
    em = os.path.join(args.decomp, "pokeemerald")
    fr = os.path.join(args.decomp, "pokefirered")

    c = Consts()
    for h in ["species.h", "moves.h", "items.h", "battle_frontier_mons.h", "battle_frontier.h",
              "trainers.h", "pokemon.h", "decorations.h", "easy_chat.h", "battle_pike.h",
              "battle_pyramid.h", "battle_frontier_trainers.h", "abilities.h", "battle_tent_mons.h", "trainer_tower.h"]:
        p = os.path.join(em, "include/constants", h)
        if os.path.exists(p):
            c.add_defines(p)
    # Emerald constants first, FireRed's own facility classes and pics go in a second table.
    for h in ["battle_frontier_trainers.h", "trainers.h", "decorations.h"]:
        p = os.path.join(em, "include/constants", h)
        if os.path.exists(p):
            c.add_enum(p, "")
    c.add_defines(os.path.join(em, "include/constants/battle_tent.h"))
    c.add_defines(os.path.join(fr, "include/constants/trainer_tower.h"))

    # national dex by species suffix
    dex_names = []
    for m in re.finditer(r"NATIONAL_DEX_(\w+)", strip_comments(read(os.path.join(em, "include/constants/pokedex.h")))):
        if m.group(1) not in dex_names:
            dex_names.append(m.group(1))
    national = {name: i for i, name in enumerate(dex_names)}  # NONE = 0

    def species_dex(tok):
        suf = tok.removeprefix("SPECIES_")
        return national.get(suf, 0)

    natures = re.findall(r"#define\s+NATURE_(\w+)\s+(\d+)", read(os.path.join(em, "include/constants/pokemon.h")))
    nature_idx = {n: int(v) for n, v in natures}

    move_id = {}
    for m in re.finditer(r"#define\s+MOVE_(\w+)\s+(\d+)", read(os.path.join(em, "include/constants/moves.h"))):
        move_id[m.group(1)] = int(m.group(2))

    def move(tok):
        return move_id.get(tok.removeprefix("MOVE_"), 0)

    def ev(expr):
        return c.eval(" ".join(expr[1]))

    # --- easy chat words
    ec_text = {}
    word_strings = {}
    for f in os.listdir(os.path.join(em, "src/data/easy_chat")):
        t = read(os.path.join(em, "src/data/easy_chat", f))
        for m in re.finditer(r'(gEasyChatWord_\w+)\[\]\s*=\s*_\("([^"]*)"\)', t):
            word_strings[m.group(1)] = m.group(2)
        for m in re.finditer(r"\[EC_INDEX\(EC_WORD_(\w+)\)\]\s*=\s*\{\s*\.text\s*=\s*(gEasyChatWord_\w+)", t):
            ec_text[m.group(1)] = m.group(2)

    species_names = {}
    for m in re.finditer(r'\[SPECIES_(\w+)\]\s*=\s*_\("([^"]*)"\)', read(os.path.join(em, "src/data/text/species_names.h"))):
        species_names[m.group(1)] = m.group(2)
    move_names = {}
    for m in re.finditer(r'\[MOVE_(\w+)\]\s*=\s*_\("([^"]*)"\)', read(os.path.join(em, "src/data/text/move_names.h"))):
        move_names[m.group(1)] = m.group(2)

    def speech(v):
        words = []
        for _, w in v[1]:
            s = "".join(w[1])
            m = re.match(r"EC_WORD_(\w+)$", s)
            if m:
                words.append(word_strings.get(ec_text.get(m.group(1), ""), m.group(1).replace("_", " ")))
                continue
            m = re.match(r"EC_MOVE2?\((\w+)\)$", s)
            if m:
                words.append(move_names.get(m.group(1), m.group(1)))
                continue
            m = re.match(r"EC_POKEMON(?:_NATIONAL)?\((\w+)\)$", s)
            if m:
                words.append(species_names.get(m.group(1), m.group(1)))
                continue
        return " ".join(words).replace(" !", "!").replace(" ?", "?").replace(" ...", "...")

    # --- facility class -> pic / class, both games
    def class_tables(base):
        t = read(os.path.join(base, "src/data/pokemon/trainer_class_lookups.h"))
        pics = dict(re.findall(r"\[FACILITY_CLASS_(\w+)\]\s*=\s*TRAINER_PIC_(\w+)", t))
        classes = dict(re.findall(r"\[FACILITY_CLASS_(\w+)\]\s*=\s*TRAINER_CLASS_(\w+)", t))
        return pics, classes

    em_pics, em_classes = class_tables(em)
    fr_lookup = os.path.join(fr, "src/data/pokemon/trainer_class_lookups.h")
    fr_pics, fr_classes = class_tables(fr) if os.path.exists(fr_lookup) else (em_pics, em_classes)

    held = {}
    t = read(os.path.join(em, "src/battle_tower.c"))
    for m in re.finditer(r"\[(BATTLE_FRONTIER_ITEM_\w+)\]\s*=\s*ITEM_(\w+)", t):
        held[m.group(1)] = m.group(2)

    def facility_mon(v):
        f = fields(v)
        return {
            "species": species_dex("".join(f["species"][1])),
            "moves": [move("".join(x[1])) for x in items(f["moves"])],
            "item": held.get("".join(f["itemTableId"][1]), "NONE"),
            "evSpread": ev(f["evSpread"]) or 0,
            "nature": nature_idx["".join(f["nature"][1]).removeprefix("NATURE_")],
        }

    def mon_table(text, name):
        arr = find_array(text, name)
        out = []
        for key, val in arr[1]:
            idx = c.eval(" ".join(key)) if key else len(out)
            while len(out) <= idx:
                out.append(None)
            out[idx] = facility_mon(val)
        return out

    def trainer_table(text, name, monsets, pics, classes):
        arr = find_array(text, name)
        out = []
        for key, val in arr[1]:
            f = fields(val)
            fc = "".join(f["facilityClass"][1]).removeprefix("FACILITY_CLASS_")
            ms = "".join(f["monSet"][1])
            out.append({
                "name": text_of(f["trainerName"]),
                "facilityClass": fc,
                "pic": pics.get(fc, ""),
                "class": classes.get(fc, ""),
                "monSet": monsets.get(ms, []),
                "before": speech(f["speechBefore"]),
                "win": speech(f["speechWin"]),
                "lose": speech(f["speechLose"]),
            })
        return out

    def monsets_in(text, pattern):
        res = {}
        # Expand the FRONTIER_MONS_* macros by plain substitution first.
        macros = {}
        for m in re.finditer(r"#define\s+(FRONTIER_MONS_\w+)\s*\\\n((?:.*\\\n)*.*)", text):
            macros[m.group(1)] = m.group(2).replace("\\", " ")
        body = text
        for _ in range(3):
            for k, v in macros.items():
                body = re.sub(r"\b" + k + r"\b", v, body)
        for name, val in all_arrays(body, pattern).items():
            ids = []
            for _, x in val[1]:
                n = c.eval(" ".join(x[1]))
                if n is None or n < 0 or n == 0xFFFF:
                    continue
                ids.append(n)
            res[name] = ids
        return res

    bfm = read(os.path.join(em, "src/data/battle_frontier/battle_frontier_mons.h"))
    bft = read(os.path.join(em, "src/data/battle_frontier/battle_frontier_trainers.h"))
    bftm = read(os.path.join(em, "src/data/battle_frontier/battle_frontier_trainer_mons.h"))
    mons = mon_table(bfm, "gBattleFrontierMons")
    monsets = monsets_in(bftm, r"gBattleFrontierTrainerMons_\w+")
    trainers = trainer_table(bft, "gBattleFrontierTrainers", monsets, em_pics, em_classes)

    # --- tents (their mon constants are defines in include/constants/battle_tent.h)
    tent = read(os.path.join(em, "src/data/battle_frontier/battle_tent.h"))
    tents = {}
    for key, prefix in (("slateport", "Slateport"), ("verdanturf", "Verdanturf"), ("fallarbor", "Fallarbor")):
        sets = monsets_in(tent, r"g%sBattleTentTrainerMons_\w+" % prefix)
        tents[key] = {
            "mons": mon_table(tent, "g%sBattleTentMons" % prefix),
            "trainers": trainer_table(tent, "g%sBattleTentTrainers" % prefix, sets, em_pics, em_classes),
        }

    # --- frontier brains
    fu = read(os.path.join(em, "src/frontier_util.c"))
    brain_arr = find_array(fu, "sFrontierBrainsMons")
    facility_keys = ["tower", "dome", "palace", "arena", "factory", "pike", "pyramid"]
    brain_names = {
        "tower": ("ANABEL", "SALON_MAIDEN_ANABEL", "SALON_MAIDEN"),
        "dome": ("TUCKER", "DOME_ACE_TUCKER", "DOME_ACE"),
        "palace": ("SPENSER", "PALACE_MAVEN_SPENSER", "PALACE_MAVEN"),
        "arena": ("GRETA", "ARENA_TYCOON_GRETA", "ARENA_TYCOON"),
        "factory": ("NOLAND", "FACTORY_HEAD_NOLAND", "FACTORY_HEAD"),
        "pike": ("LUCY", "PIKE_QUEEN_LUCY", "PIKE_QUEEN"),
        "pyramid": ("BRANDON", "PYRAMID_KING_BRANDON", "PYRAMID_KING"),
    }
    brains = {}
    for key, val in brain_arr[1]:
        fac = "".join(key).removeprefix("FRONTIER_FACILITY_").lower()
        sets = []
        for symbol in items(val):
            team = []
            for mv in items(symbol):
                f = fields(mv)
                team.append({
                    "species": species_dex("".join(f["species"][1])),
                    "item": "".join(f["heldItem"][1]).removeprefix("ITEM_"),
                    "iv": ev(f["fixedIV"]),
                    "nature": nature_idx["".join(f["nature"][1]).removeprefix("NATURE_")],
                    "evs": [c.eval(" ".join(x[1])) for x in items(f["evs"])],
                    "moves": [move("".join(x[1])) for x in items(f["moves"])],
                })
            sets.append(team)
        name, pic, cls = brain_names[fac]
        brains[fac] = {"name": name, "pic": pic, "class": cls, "silver": sets[0], "gold": sets[1]}

    # --- pike wild mons
    pike = read(os.path.join(em, "src/battle_pike.c"))
    pike_arrs = all_arrays(pike, r"sLvl(50|Open)_Mons\d")
    pike_wild = {"lvl50": [], "open": []}
    for name in sorted(pike_arrs):
        tbl = []
        for v in items(pike_arrs[name]):
            f = fields(v)
            tbl.append({"species": species_dex("".join(f["species"][1])),
                        "levelDelta": ev(f["levelDelta"]),
                        "moves": [move("".join(x[1])) for x in items(f["moves"])]})
        pike_wild["lvl50" if "50" in name else "open"].append(tbl)

    # --- pyramid
    pyr = read(os.path.join(em, "src/battle_pyramid.c"))
    def pyramid_wild(path, prefix):
        arrs = all_arrays(read(path), prefix + r"_Round\d+")
        rounds = []
        for i in range(1, 21):
            name = "%s_Round%d" % (prefix, i)
            if name not in arrs:
                break
            tbl = []
            for v in items(arrs[name]):
                f = fields(v)
                ab = "".join(f["abilityNum"][1])
                tbl.append({"species": species_dex("".join(f["species"][1])),
                            "level": ev(f["lvl"]),
                            "ability": -1 if ab == "ABILITY_RANDOM" else (c.eval(ab) or 0),
                            "moves": [move("".join(x[1])) for x in items(f["moves"])]})
            rounds.append(tbl)
        return rounds
    pyramid = {
        "wild50": pyramid_wild(os.path.join(em, "src/data/battle_frontier/battle_pyramid_level_50_wild_mons.h"), "sLevel50WildMons"),
        "wildOpen": pyramid_wild(os.path.join(em, "src/data/battle_frontier/battle_pyramid_open_level_wild_mons.h"), "sOpenLevelWildMons"),
        "pickup50": [["".join(x[1]).removeprefix("ITEM_") for x in items(r)] for r in items(find_array(pyr, "sPickupItemsLvl50"))],
        "pickupOpen": [["".join(x[1]).removeprefix("ITEM_") for x in items(r)] for r in items(find_array(pyr, "sPickupItemsLvlOpen"))],
        "pickupSlots": [[ev(x) for x in items(r)] for r in items(find_array(pyr, "sPickupItemSlots"))],
        "floorTemplates": [{"numItems": ev(fields(v)["numItems"]), "numTrainers": ev(fields(v)["numTrainers"]),
                            "runMultiplier": ev(fields(v)["runMultiplier"])} for v in items(find_array(pyr, "sPyramidFloorTemplates"))],
        "floorOptions": [[ev(x) for x in items(r)] for r in items(find_array(pyr, "sPyramidFloorTemplateOptions"))],
    }

    # --- arena mind ratings
    arena = read(os.path.join(em, "src/battle_arena.c"))
    mind = {}
    for key, val in find_array(arena, "sMindRatings")[1]:
        mind[str(move("".join(key)))] = ev(val)

    # --- exchange corner (prices from the clerk scripts)
    sc = read(os.path.join(em, "data/maps/BattleFrontier_ExchangeServiceCorner/scripts.inc"))
    exchange = []
    for m in re.finditer(r"EventScript_(\w+)::\n(?:.*\n){0,4}?\s*setvar VAR_0x8008, (\d+)\n\s*setvar VAR_0x8009, ((?:DECOR|ITEM)_\w+)", sc):
        what = m.group(3)
        exchange.append({"cost": int(m.group(2)),
                         "kind": "decor" if what.startswith("DECOR_") else "item",
                         "id": what.split("_", 1)[1]})
    ec = read(os.path.join(em, "src/data/battle_frontier/battle_frontier_exchange_corner.h"))
    groups = {}
    for gname in ("Decor1", "Decor2", "Vitamins", "HoldItems"):
        lst = [("".join(x[1])) for x in items(find_array(ec, "sFrontierExchangeCorner_" + gname))]
        groups[gname] = [x.split("_", 1)[1] for x in lst if x != "ITEM_LIST_END"]
    for e in exchange:
        e["clerk"] = next((g for g, lst in groups.items() if e["id"] in lst), "")

    # --- BattleTowerPokemon-style mons (Trainer Hill, Trainer Tower)
    def bt_mon(v):
        f = fields(v)
        g = lambda k, d=0: (ev(f[k]) if k in f else d)
        if "species" not in f:
            return None
        return {
            "species": species_dex("".join(f["species"][1])),
            "item": "".join(f["heldItem"][1]).removeprefix("ITEM_") if "heldItem" in f else "NONE",
            "moves": [move("".join(x[1])) for x in items(f["moves"])] if "moves" in f else [],
            "evs": [g("hpEV"), g("attackEV"), g("defenseEV"), g("speedEV"), g("spAttackEV"), g("spDefenseEV")],
            "ivs": [g("hpIV"), g("attackIV"), g("defenseIV"), g("speedIV"), g("spAttackIV"), g("spDefenseIV")],
            "ability": g("abilityNum"),
            "personality": g("personality"),
            "friendship": g("friendship", 255),
            "nickname": text_of(f["nickname"]) if "nickname" in f else "",
        }

    def bt_trainer(v, pics, classes):
        f = fields(v)
        fc = "".join(f["facilityClass"][1]).removeprefix("FACILITY_CLASS_") if "facilityClass" in f else ""
        return {
            "name": text_of(f["name"]) if "name" in f else "",
            "facilityClass": fc, "pic": pics.get(fc, ""), "class": classes.get(fc, ""),
            "before": speech(f["speechBefore"]) if "speechBefore" in f else "",
            "win": speech(f["speechWin"]) if "speechWin" in f else "",
            "lose": speech(f["speechLose"]) if "speechLose" in f else "",
            "after": speech(f["speechAfter"]) if "speechAfter" in f else "",
            "mons": [m for m in (bt_mon(x) for x in items(f["mons"])) if m] if "mons" in f else [],
        }

    th = read(os.path.join(em, "src/data/battle_frontier/trainer_hill.h"))
    hill = {}
    for mode in ("Normal", "Variety", "Unique", "Expert"):
        floors = []
        for fv in items(find_array(th, "sFloors_" + mode)):
            ff = fields(fv)
            floors.append({"trainers": [bt_trainer(x, em_pics, em_classes) for x in items(ff["trainers"])]})
        hill[mode.lower()] = floors

    tts = read(os.path.join(fr, "src/trainer_tower_sets.c"))
    floors_by_name = {}
    for name, val in all_arrays(tts, r"sTrainerTowerFloor_\w+").items():
        ff = fields(val)
        floors_by_name[name] = {
            "type": "".join(ff["challengeType"][1]).removeprefix("CHALLENGE_TYPE_").lower(),
            "prize": ev(ff["prize"]),
            "trainers": [bt_trainer(x, fr_pics, fr_classes) for x in items(ff["trainers"]) if x[0] == "list"],
        }
    tower_sets = {}
    for key, val in find_array(tts, "gTrainerTowerFloors")[1]:
        kind = "".join(key).removeprefix("CHALLENGE_TYPE_").lower()
        tower_sets[kind] = [floors_by_name["".join(x[1]).lstrip("&")] for x in items(val)]
    tt = read(os.path.join(fr, "src/trainer_tower.c"))
    tower_prizes = ["".join(x[1]).removeprefix("ITEM_") for x in items(find_array(tt, "sPrizeList"))]

    # --- factory battle styles (battle_factory.c sMoveStyles order)
    bfac = read(os.path.join(em, "src/battle_factory.c"))
    styles = []
    for name in ("sMoves_TotalPreparation", "sMoves_SlowAndSteady", "sMoves_Endurance",
                 "sMoves_HighRiskHighReturn", "sMoves_WeakeningTheFoe", "sMoves_ImpossibleToPredict",
                 "sMoves_DependsOnTheBattlesFlow"):
        styles.append([move("".join(x[1])) for x in items(find_array(bfac, name)) if "".join(x[1]) != "MOVE_NONE"])

    banned = []
    for x in items(find_array(fu, "gFrontierBannedSpecies")):
        s = "".join(x[1])
        if s.startswith("SPECIES_"):
            banned.append(species_dex(s))

    out = {
        "mons": mons,
        "trainers": trainers,
        "tents": tents,
        "brains": brains,
        "pikeWild": pike_wild,
        "pyramid": pyramid,
        "mindRatings": mind,
        "exchange": exchange,
        "trainerHill": hill,
        "trainerTower": {"sets": tower_sets, "prizes": tower_prizes},
        "bannedSpecies": banned,
        "factoryStyles": styles,
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print("mons %d trainers %d tents %s brains %d exchange %d hill %s tower %s banned %d -> %s" % (
        len(mons), len(trainers), {k: (len(v["mons"]), len(v["trainers"])) for k, v in tents.items()},
        len(brains), len(exchange), {k: len(v) for k, v in hill.items()},
        {k: len(v) for k, v in tower_sets.items()}, len(banned), args.out), file=sys.stderr)


if __name__ == "__main__":
    main()
