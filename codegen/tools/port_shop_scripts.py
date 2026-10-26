#!/usr/bin/env python3
"""Ports the decomp's mart clerk and Pokemon Center nurse scripts into the server's generated
script files, replacing their `TODO("port ...")` stubs.

ScriptGenerator only fills in single-msgbox scripts and rewrites the whole package when it runs,
so everything else is ported by hand in place. Marts and nurses follow a handful of fixed shapes,
which this tool reads straight from the decomp (`data/maps/*/scripts.inc`):

  lock / faceplayer / message <greeting> / waitmessage
  [goto_if_set|goto_if_unset FLAG, Label] [goto_if_eq VAR, n, Label] [setflag FLAG] [goto Label]
  pokemart <ItemList>                       -> ctx.say(greeting); ctx.pokemart(items...)
  call (Common_)EventScript_PkmnCenterNurse -> healAtPokemonCenter / healAtKantoPokemonCenter

Anything else in a clerk's body leaves the stub alone. Re-running is harmless: ported scripts
have no TODO left to match. Usage, from the repo root, after `./gradlew :codegen:build`:

  python3 codegen/tools/port_shop_scripts.py [--dry-run]
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "server.game/src/main/kotlin/de/fiereu/openmmo/server/game/script/generated"
GEN = ROOT / "codegen/build/generated/source"
REGIONS = {
    "kanto": ("pokefirered", "Kanto", "healAtKantoPokemonCenter"),
    "hoenn": ("pokeemerald", "Hoenn", "healAtPokemonCenter"),
}
NURSE_CALLS = {"EventScript_PkmnCenterNurse", "Common_EventScript_PkmnCenterNurse"}
SKIP = {
    "lock", "lockall", "faceplayer", "waitmessage", "waitbuttonpress", "closemessage",
    "goto_if_questlog", "release", "releaseall", "setvar",  # setvar VAR_0x800B, LOCALID_NURSE
}
# Decomp item constants whose pokeblack name differs.
ITEM_ALIASES = {
    "PARALYZE_HEAL": "PARLYZ_HEAL",
    "ENERGY_POWDER": "ENERGYPOWDER",
    "THUNDER_STONE": "THUNDERSTONE",
    "BLACK_GLASSES": "BLACKGLASSES",
    "SILK_SCARF": "SILK_SCARF",
    "DEEP_SEA_TOOTH": "DEEPSEATOOTH",
    "DEEP_SEA_SCALE": "DEEPSEASCALE",
    "TWISTED_SPOON": "TWISTEDSPOON",
    "BRIGHT_POWDER": "BRIGHTPOWDER",
    "SILVER_POWDER": "SILVERPOWDER",
    "NEVER_MELT_ICE": "NEVERMELTICE",
    "KINGS_ROCK": "KING_S_ROCK",
}


def parse_labels(text):
    """label -> list of commands (raw lines, comments stripped) up to the next label."""
    out, cur = {}, None
    for raw in text.splitlines():
        line = raw.split("@")[0].rstrip()
        m = re.match(r"^(\w+):{1,2}\s*$", line)
        if m:
            cur = m.group(1)
            out[cur] = []
            continue
        if cur is not None and line.strip():
            out[cur].append(line.strip())
    return out


def item_list(cmds):
    items = []
    for c in cmds:
        m = re.match(r"\.2byte\s+ITEM_(\w+)", c)
        if not m:
            break
        if m.group(1) == "NONE":
            break
        items.append(m.group(1))
    return items


def load_items():
    f = GEN / "item/kotlin/de/fiereu/openmmo/items/generated/Items.kt"
    return set(re.findall(r"^\s*val (\w+) = item\(", f.read_text(), re.M))


def load_dialog(region):
    """text label -> Kotlin reference (`Class.Entry`, class name) for this region."""
    table = json.loads((ROOT / f"codegen/dialog/{region}.json").read_text())["lines"]
    by_id = {}
    for kt in (GEN / f"dialog/kotlin/de/fiereu/openmmo/dialog/generated/{region}").glob("*.kt"):
        cls = kt.stem
        for entry, tid in re.findall(r"\*/\s*(\w+)\((0x[0-9A-Fa-f]+)\)", kt.read_text()):
            by_id[int(tid, 16)] = (f"{cls}.{entry}", cls)
    return {l["label"]: by_id[l["id"]] for l in table if l["id"] in by_id}


def load_story(region, kind):
    f = GEN / f"story/kotlin/de/fiereu/openmmo/story/generated/{region}/{REGIONS[region][1]}{kind}.kt"
    return set(re.findall(r"const val (\w+)\b", f.read_text()))


class Unsupported(Exception):
    pass


class Porter:
    def __init__(self, region, labels):
        self.region = region
        self.labels = labels
        self.dialog = DIALOG[region]
        self.flags = FLAGS[region]
        self.vars = VARS[region]
        self.imports = set()
        self.prefix = REGIONS[region][1]

    def text(self, label):
        ref = self.dialog.get(label)
        if not ref:
            raise Unsupported(f"no dialog line for {label}")
        self.imports.add(f"de.fiereu.openmmo.dialog.generated.{self.region}.{ref[1]}")
        return ref[0]

    def flag(self, name):
        if name not in self.flags:
            raise Unsupported(f"unknown flag {name}")
        self.imports.add(f"de.fiereu.openmmo.story.generated.{self.region}.{self.prefix}Flags")
        return f"{self.prefix}Flags.{name}"

    def var(self, name):
        if name not in self.vars:
            raise Unsupported(f"unknown var {name}")
        self.imports.add(f"de.fiereu.openmmo.story.generated.{self.region}.{self.prefix}Vars")
        return f"{self.prefix}Vars.{name}"

    def body(self, label, depth=0):
        """Kotlin statements for [label]; the last one ends the script."""
        if depth > 6 or label not in self.labels:
            raise Unsupported(f"cannot follow {label}")
        out = []
        for c in self.labels[label]:
            op, _, rest = c.partition(" ")
            args = [a.strip() for a in rest.split(",")] if rest else []
            if op in SKIP:
                continue
            if op in ("end", "return"):
                return out + ["return"]
            if op in ("message", "msgbox"):
                out.append(f"ctx.say({self.text(args[0])})")
            elif op == "setflag":
                out.append(f"ctx.setFlag({self.flag(args[0])})")
            elif op in ("goto_if_set", "goto_if_unset"):
                neg = "!" if op == "goto_if_unset" else ""
                branch = self.body(args[1], depth + 1)
                out.append(f"if ({neg}ctx.isFlagSet({self.flag(args[0])})) {{")
                out.extend("  " + s for s in branch)
                out.append("}")
            elif op == "goto_if_eq":
                branch = self.body(args[2], depth + 1)
                out.append(f"if (ctx.getVar({self.var(args[0])}) == {int(args[1])}) {{")
                out.extend("  " + s for s in branch)
                out.append("}")
            elif op == "goto":
                return out + self.body(args[0], depth + 1)
            elif op == "call" and args[0] in NURSE_CALLS:
                out.append(f"{REGIONS[self.region][2]}(ctx)")
            elif op == "pokemart":
                items = item_list(self.labels.get(args[0], []))
                if not items:
                    raise Unsupported(f"empty item list {args[0]}")
                refs = []
                for it in items:
                    name = ITEM_ALIASES.get(it, it)
                    if name.startswith("TM_") and name[3:] in TM_MOVES:
                        name = f"TM{TM_MOVES.index(name[3:]) + 1:02d}"
                    if name not in ITEMS:
                        if name.endswith("_MAIL"):
                            # Gen 3 mail has no counterpart in the item table the wire uses.
                            DROPPED.append(f"{self.region}/{label}: {it}")
                            continue
                        raise Unsupported(f"no item {it}")
                    refs.append(f"Items.{name}")
                self.imports.add("de.fiereu.openmmo.items.generated.Items")
                # The window does not wait, so nothing after it in the decomp can be shown.
                return out + [f"ctx.pokemart({', '.join(refs)})", "return"]
            else:
                raise Unsupported(f"command {c}")
        raise Unsupported(f"{label} falls through")


def reaches_mart(labels, label, seen=None):
    seen = seen if seen is not None else set()
    if label in seen or label not in labels:
        return False
    seen.add(label)
    flat = " ".join(labels[label])
    if "pokemart " in flat:
        return True
    targets = re.findall(r"(?:goto|goto_if_\w+)\s+(?:\w+,\s*)*(\w+)", flat)
    return any(reaches_mart(labels, t, seen) for t in targets)


def render(label, stmts, kind):
    # Drop the trailing return and any redundant `return` right before a closing brace.
    lines = list(stmts)
    while lines and lines[-1] == "return":
        lines.pop()
    doc = {
        "mart": "/** The decomp's pokemart clerk, with its stock from the item list beside it. */",
        "nurse": "/** The decomp's Pokemon Center nurse: heal the party. */",
    }[kind]
    out = [doc, f"internal object {label} : Script {{", "  override suspend fun run(ctx: ScriptContext) {"]
    for s in lines:
        s = s.replace("  return", "  return") if s.strip() != "return" else s
        out.append("    " + s)
    out += ["  }", "}"]
    return "\n".join(out) + "\n"


STUB = r"(/\*\*\n \* Not ported yet\.(?:(?!\*/).)*\*/\n)?internal object {label} : Script \{{\n  override suspend fun run\(ctx: ScriptContext\) =\s*TODO\(\"port {label}\"\)\n\}}\n"


def add_imports(src, imports):
    have = set(re.findall(r"^import (\S+)$", src, re.M))
    new = sorted(have | imports)
    head, _, tail = src.partition("\n\n")
    pkg = head  # package line
    rest = re.sub(r"^(import \S+\n)+\n?", "", tail, count=1, flags=re.M)
    return pkg + "\n\n" + "".join(f"import {i}\n" for i in new) + "\n" + rest


def main():
    dry = "--dry-run" in sys.argv
    ported, skipped = [], []
    for region, (decomp, _, _) in REGIONS.items():
        for inc in sorted((ROOT / "decomp" / decomp / "data/maps").glob("*/scripts.inc")):
            text = inc.read_text()
            if "pokemart" not in text and not any(n in text for n in NURSE_CALLS):
                continue
            kt = SCRIPTS / region / f"{inc.parent.name}.kt"
            if not kt.exists():
                continue
            src = kt.read_text()
            labels = parse_labels(text)
            porter = Porter(region, labels)
            changed = False
            for label, cmds in labels.items():
                if f'TODO("port {label}")' not in src:
                    continue
                flat = " ".join(cmds)
                kind = "nurse" if any(f"call {n}" in flat for n in NURSE_CALLS) else None
                if kind is None:
                    # A clerk: its body (or a branch it jumps to) opens a mart.
                    if reaches_mart(labels, label):
                        kind = "mart"
                if kind is None:
                    continue
                try:
                    stmts = porter.body(label)
                except Unsupported as e:
                    skipped.append(f"{region}/{label}: {e}")
                    continue
                pat = re.compile(STUB.format(label=re.escape(label)), re.S)
                if not pat.search(src):
                    skipped.append(f"{region}/{label}: stub shape not recognised")
                    continue
                src = pat.sub(lambda _: render(label, stmts, kind), src, count=1)
                ported.append(f"{region}/{label} ({kind})")
                changed = True
            if changed:
                src = add_imports(src, porter.imports)
                if not dry:
                    kt.write_text(src)
    print(f"ported {len(ported)}:")
    for p in ported:
        print("  " + p)
    print(f"dropped {len(DROPPED)} mail items with no wire id:")
    for d in DROPPED:
        print("  " + d)
    print(f"skipped {len(skipped)}:")
    for s in skipped:
        print("  " + s)


ITEMS = load_items()
DROPPED = []
TM_MOVES = re.findall(
    r"F\((\w+)\)",
    (ROOT / "decomp/pokeemerald/include/constants/tms_hms.h").read_text().split("FOREACH_HM")[0],
)
DIALOG = {r: load_dialog(r) for r in REGIONS}
FLAGS = {r: load_story(r, "Flags") for r in REGIONS}
VARS = {r: load_story(r, "Vars") for r in REGIONS}

if __name__ == "__main__":
    main()
