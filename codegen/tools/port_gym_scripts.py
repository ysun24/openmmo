#!/usr/bin/env python3
"""Ports the gym scripts of both decomps into the server's generated script files, replacing their
`TODO("port ...")` stubs: every gym leader (badge, TM, the decomp's own flag and var writes) and the
plain gym trainers (`trainerbattle_single` + one msgbox).

Leader shapes read from `data/maps/<Gym>/scripts.inc`:

  FireRed: trainerbattle_single TRAINER_LEADER_X, Intro, Defeat, DefeatedLabel
           goto_if_unset FLAG_GOT_TMxx..., GiveLabel / msgbox PostBattle
           DefeatedLabel: setflag/clearflag/setvar/addvar..., goto GiveLabel
           GiveLabel: msgbox Before / giveitem_msg Received, ITEM_TMxx / setflag / msgbox After
  Emerald: the same, with `message ReceivedBadge` + `msgbox BadgeInfo` in DefeatedLabel, a
           `call GiveLabel` whose body is giveitem / msgbox Explain / setflag, and a match call
           registration line afterwards.

Usage, from the repo root, after `./gradlew :codegen:build`: python3 codegen/tools/port_gym_scripts.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "server.game/src/main/kotlin/de/fiereu/openmmo/server/game/script/generated"
GEN = ROOT / "codegen/build/generated/source"
REGIONS = {"kanto": ("pokefirered", "Kanto"), "hoenn": ("pokeemerald", "Hoenn")}
SKIP_LINE = re.compile(r"^(famechecker|set_gym_trainers|call_if_\w+|call Common_|setrespawn|special|"
                       r"playse|playfanfare|waitfanfare|waitmessage|closemessage|delay|"
                       r"call \w+_AnimateFlash|setvar VAR_0x8008|release|end|lock|faceplayer)\b")


def body(text, label):
    m = re.search(r"^" + re.escape(label) + r"::?\n(.*?)(?=^\S|\Z)", text, re.M | re.S)
    return [l.strip() for l in m.group(1).splitlines() if l.strip()] if m else []


def dialog_enums(region):
    """Every DialogLine enum constant of the region: label -> 'Enum.Constant'."""
    out = {}
    for kt in (GEN / "dialog").rglob(f"generated/{region}/*.kt"):
        enum = kt.stem
        for c in re.findall(r"\*/ ([A-Za-z_0-9]+)\(0x", kt.read_text()):
            out[(enum, c)] = f"{enum}.{c}"
    return out


def text_ref(label, mapname, enums):
    for prefix in (mapname + "_Text_",):
        if label.startswith(prefix) and (mapname, label[len(prefix):]) in enums:
            return enums[(mapname, label[len(prefix):])], mapname
    if ("Misc", label) in enums:
        return enums[("Misc", label)], "Misc"
    return None, None


def tm_number(item, decomp):
    m = re.match(r"ITEM_TM(\d\d)$", item)
    if m:
        return f"TM{m.group(1)}"
    m = re.match(r"ITEM_TM_(\w+)$", item)
    if m:
        tms = (ROOT / f"decomp/{decomp}/include/constants/tms_hms.h").read_text()
        names = re.findall(r"F\((\w+)\)", tms.split("FOREACH_HM")[0])
        return f"TM{names.index(m.group(1)) + 1:02d}"
    return None


def port_region(region, decomp, Region):
    enums = dialog_enums(region)
    trainers = {k: int(v) for k, v in re.findall(
        r"#define (TRAINER_\w+)\s+(\d+)", (ROOT / f"decomp/{decomp}/include/constants/opponents.h").read_text())}
    flags_kt = next((GEN / "story").rglob(f"{Region}Flags.kt")).read_text()
    vars_kt = next((GEN / "story").rglob(f"{Region}Vars.kt")).read_text()
    known_flags = set(re.findall(r"const val (FLAG_\w+) =", flags_kt))
    known_vars = set(re.findall(r"const val (VAR_\w+) =", vars_kt))
    ported = 0
    for inc in sorted((ROOT / f"decomp/{decomp}/data/maps").glob("*Gym*/scripts.inc")):
        mapname = inc.parent.name
        kt = SCRIPTS / region / f"{mapname}.kt"
        if not kt.exists():
            continue
        text = inc.read_text()
        src = kt.read_text()
        imports = set()
        stubs = list(re.finditer(r"internal object (\w+) : Script \{\n  override suspend fun run\(ctx: ScriptContext\) =\s*\n?\s*TODO\(\"port \1\"\)\n\}", src))
        # From the end, so the earlier matches' offsets stay valid.
        for m in reversed(stubs):
            name = m.group(1)
            lines = body(text, name)
            code = None
            tb = next((l for l in lines if l.startswith(("trainerbattle_single ", "trainerbattle_double "))), None)
            if tb:
                args = [a.strip() for a in tb.split(" ", 1)[1].split(",")]
                if tb.startswith("trainerbattle_double") and len(args) >= 4:
                    del args[3]  # the "you need two monsters" text
                if len(args) >= 4 and args[3] != "NO_MUSIC" and "LEADER" in args[0] or re.search(r"_1$", args[0]) and len(args) >= 4:
                    code = leader(lines, args, text, mapname, enums, trainers, known_flags, known_vars, Region, decomp, imports)
                else:
                    # A fourth argument is a callback after the win (a room brightening); the
                    # field branch owns those effects, so the trainer is ported without it.
                    code = trainer(lines, args, mapname, enums, trainers, Region, imports)
            if code is None:
                continue
            src = src[:m.start()] + f"internal object {name} : Script {{\n  override suspend fun run(ctx: ScriptContext) {{\n{code}  }}\n}}" + src[m.end():]
            ported += 1
        if imports:
            for imp in sorted(imports):
                line = f"import {imp}\n"
                if line not in src:
                    i = src.index("import de.fiereu.openmmo.server.game.script.Script\n")
                    src = src[:i] + line + src[i:]
            kt.write_text(src)
    return ported


def trainer(lines, args, mapname, enums, trainers, Region, imports):
    tid = trainers.get(args[0])
    intro, ie = text_ref(args[1], mapname, enums)
    defeat, de = text_ref(args[2], mapname, enums)
    post_line = next((l for l in lines if l.startswith("msgbox ")), None)
    if tid is None or not post_line:
        return None
    post, pe = text_ref(post_line.split()[1].rstrip(","), mapname, enums)
    if not post:
        return None
    region = Region.lower()
    for e in (ie, de, pe):
        if e:
            imports.add(f"de.fiereu.openmmo.dialog.generated.{region}.{e}")
    imports.add("de.fiereu.openmmo.server.game.progression.gymTrainer")
    # The defeat line is battle text (not a dialog line) in both decomps' tables; null when absent.
    return f"    // {args[0]}\n    ctx.gymTrainer({tid}, {intro or 'null'}, {defeat or 'null'}, {post})\n"


def leader(lines, args, text, mapname, enums, trainers, known_flags, known_vars, Region, decomp, imports):
    region = Region.lower()
    tid = trainers.get(args[0])
    intro, ie = text_ref(args[1], mapname, enums)
    defeat, de = text_ref(args[2], mapname, enums)
    defeated = body(text, args[3])
    give_label = next((l.split(",")[1].strip() for l in lines if l.startswith("goto_if_unset FLAG_")), None)
    tm_flag = next((l.split(",")[0].split()[1] for l in lines if l.startswith("goto_if_unset FLAG_")), None)
    post_line = next((l for l in lines if l.startswith("msgbox ")), None)
    if None in (tid, give_label, tm_flag, post_line) or not defeated:
        return None
    post, pe = text_ref(post_line.split()[1].rstrip(","), mapname, enums)
    give = body(text, give_label)
    if not post or not give or tm_flag not in known_flags:
        return None
    for e in (ie, de, pe):
        if e:
            imports.add(f"de.fiereu.openmmo.dialog.generated.{region}.{e}")
    imports.add(f"de.fiereu.openmmo.story.generated.{region}.{Region}Flags")
    imports.add("de.fiereu.openmmo.server.game.battle.BattleResult")
    imports.add("de.fiereu.openmmo.server.game.progression.badgeAwarded")
    imports.add("de.fiereu.openmmo.items.generated.Items")

    def emit(seq, indent="      "):
        out = []
        for l in seq:
            if SKIP_LINE.match(l):
                continue
            op, _, rest = l.partition(" ")
            a = [x.strip() for x in rest.split(",")]
            if op in ("message", "msgbox"):
                ref, e = text_ref(a[0], mapname, enums)
                if ref:
                    imports.add(f"de.fiereu.openmmo.dialog.generated.{region}.{e}")
                    out.append(f"{indent}ctx.say({ref})\n")
            elif op == "setflag" and a[0] in known_flags:
                out.append(f"{indent}ctx.setFlag({Region}Flags.{a[0]})\n")
            elif op == "clearflag" and a[0] in known_flags:
                out.append(f"{indent}ctx.clearFlag({Region}Flags.{a[0]})\n")
            elif op == "setvar" and a[0] in known_vars:
                imports.add(f"de.fiereu.openmmo.story.generated.{region}.{Region}Vars")
                out.append(f"{indent}ctx.setVar({Region}Vars.{a[0]}, {int(a[1], 0)})\n")
            elif op == "addvar" and a[0] in known_vars:
                imports.add(f"de.fiereu.openmmo.story.generated.{region}.{Region}Vars")
                out.append(f"{indent}ctx.setVar({Region}Vars.{a[0]}, ctx.getVar({Region}Vars.{a[0]}) + {int(a[1], 0)})\n")
            elif op in ("goto", "call") and a[0].rstrip("2") == give_label.rstrip("2"):
                out.append(f"{indent}GIVE\n")
        return out

    # The TM hand-over, its lines in decomp order.
    give_code = []
    tm = None
    for l in give:
        op, _, rest = l.partition(" ")
        a = [x.strip() for x in rest.split(",")]
        if op in ("giveitem", "giveitem_msg"):
            item = a[-1] if op == "giveitem_msg" else a[0]
            tm = tm_number(item, decomp)
            if tm is None:
                return None
            give_code.append(f"      if (!ctx.giveItem(Items.{tm})) return\n")
            if op == "giveitem_msg":
                ref, e = text_ref(a[0], mapname, enums)
                if ref:
                    imports.add(f"de.fiereu.openmmo.dialog.generated.{region}.{e}")
                    give_code.append(f"      ctx.say({ref})\n")
        elif op == "msgbox":
            ref, e = text_ref(a[0], mapname, enums)
            if ref:
                imports.add(f"de.fiereu.openmmo.dialog.generated.{region}.{e}")
                give_code.append(f"      ctx.say({ref})\n")
        elif op == "setflag" and a[0] in known_flags:
            give_code.append(f"      ctx.setFlag({Region}Flags.{a[0]})\n")
    if tm is None:
        return None
    badge = next((int(m.group(1)) for l in defeated for m in [re.match(r"setflag FLAG_BADGE0(\d)_GET", l)] if m), None)
    defeat_flag = next((l.split()[1] for l in defeated if l.startswith("setflag FLAG_DEFEATED")), None)
    if badge is None or defeat_flag not in known_flags:
        return None
    on_defeat = emit(defeated)
    lam = "".join(c.replace("return", "return@giveTm", 1) for c in give_code)
    out = f"    // {args[0]}: badge {badge}, {tm}\n"
    out += f"    val giveTm: suspend () -> Unit = giveTm@{{\n{lam}    }}\n"
    out += f"    if (ctx.isFlagSet({Region}Flags.{defeat_flag})) {{\n"
    out += f"      if (!ctx.isFlagSet({Region}Flags.{tm_flag})) giveTm() else ctx.say({post})\n"
    out += "      return\n    }\n"
    if intro:
        out += f"    ctx.say({intro})\n"
    out += f"    if (ctx.trainerBattle({tid}) != BattleResult.VICTORY) return\n"
    if defeat:
        out += f"    ctx.say({defeat})\n"
    gave = False
    for x in on_defeat:
        if x.strip() == "GIVE":
            out += "    giveTm()\n"
            gave = True
        else:
            out += x.replace("      ", "    ", 1)
    if not gave:
        out += "    giveTm()\n"
    out += f"    ctx.badgeAwarded({badge})\n"
    return out


if __name__ == "__main__":
    total = 0
    for region, (decomp, Region) in REGIONS.items():
        n = port_region(region, decomp, Region)
        print(f"{region}: ported {n} gym scripts")
        total += n
    sys.exit(0 if total else 1)
