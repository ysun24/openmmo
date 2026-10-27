#!/usr/bin/env python3
"""r5-scripts: story playthroughs over the gateway WebSocket, driving the decomp script
interpreter on the real stack (login + game servers + gateway).

  python3 web/gateway/test/story.py --ws ws://127.0.0.1:8570/ws [--only kanto|hoenn|<name>]

Each scene puts the character where the scene starts (/tp, /script flag|var for the story state
that comes before it), plays it through talking, walking, answering menus and battling like a
player would, and checks what the decomp scripts leave behind (flags, vars, items, party, map).
Prints one line per check; exits non-zero on the first failure.
"""

import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from e2e import Player, login, ok  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
GAMES = {0: "pokefirered", 1: "pokeemerald"}
BANK_OFFSET = {0: 0, 1: 50}


def map_key(region, name):
    """`region:bank:map` of a decomp map directory name."""
    groups = json.load(open(os.path.join(ROOT, "decomp", GAMES[region], "data/maps/map_groups.json")))
    for gi, g in enumerate(groups["group_order"]):
        if name in groups[g]:
            return f"{region}:{gi + BANK_OFFSET[region]}:{groups[g].index(name)}"
    raise KeyError(name)


def map_json(region, name):
    return json.load(open(os.path.join(ROOT, "decomp", GAMES[region], f"data/maps/{name}/map.json")))


def npc(region, name, local_id):
    """(x, y) of object event `local_id` (1-based, or a LOCALID_ name) of a map."""
    objs = map_json(region, name)["object_events"]
    if isinstance(local_id, str):
        idx = next(i for i, o in enumerate(objs) if o.get("local_id") == local_id)
    else:
        idx = local_id - 1
    o = objs[idx]
    return o["x"], o["y"]


def npc_by_script(region, name, script):
    for o in map_json(region, name)["object_events"]:
        if o.get("script") == script:
            return o["x"], o["y"]
    raise KeyError(script)


class Story:
    def __init__(self, p, region):
        self.p = p
        self.region = region

    # ---- commands and state
    def cmd(self, text, wait=1.0):
        before = len(self.p.chat_texts())
        self.p.send(t="say", text=text)
        end = time.time() + wait
        while time.time() < end:
            new = self.p.chat_texts()[before:]
            if new:
                return new[-1]
            time.sleep(0.05)
        return ""

    def flag(self, name, value=None):
        r = self.cmd(f"/script flag {name}" + ("" if value is None else (" on" if value else " off")))
        return " is set" in r

    def var(self, name, value=None):
        r = self.cmd(f"/script var {name}" + ("" if value is None else f" {value}"))
        try:
            return int(r.rsplit("=", 1)[1].strip(" ."))
        except (IndexError, ValueError):
            return None

    @property
    def ui(self):
        return self.p.state.get("ui") or {}

    def script_ui(self):
        return self.ui.get("script") or {}

    def bag(self):
        return {b["name"].upper(): b["qty"] for b in self.ui.get("bag") or []}

    def party(self):
        return self.p.state.get("party") or []

    def map_name(self):
        return (self.p.world.get("map") or {}).get("name")

    def pos(self):
        e = self.p.self_entity() or {}
        return e.get("x"), e.get("y")

    # ---- moving
    def tp(self, name, x, y, facing="down"):
        key = map_key(self.region, name)
        r, b, m = (int(v) for v in key.split(":"))
        self.p.send(t="say", text=f"/tp {key} {x} {y} {facing}")
        self.p.wait(lambda q: (q.world.get("map") or {}).get("bank") == b and q.world["map"]["map"] == m
                    and q.world.get("inWorld") and (q.self_entity() or {}).get("x") == x
                    and (q.self_entity() or {}).get("y") == y, 15, f"/tp {name}")
        time.sleep(1.2)
        self.settle(4)

    def face(self, d):
        self.p.send(t="face", dir=d)
        time.sleep(0.3)

    seq = 1000

    def step(self, d, n=1):
        for _ in range(n):
            Story.seq += 1
            s = Story.seq
            self.p.send(t="step", dir=d, run=False, seq=s)
            self.p.wait(lambda q: (q.world.get("ackSeq") or 0) >= s, 5, f"step {d}")
            time.sleep(0.32)

    def stand_by(self, map_name, tx, ty, side="down"):
        """Teleports next to tile (tx, ty), on its `side`, facing it."""
        dx, dy, face = {"down": (0, 1, "up"), "up": (0, -1, "down"), "left": (-1, 0, "right"),
                        "right": (1, 0, "left")}[side]
        self.tp(map_name, tx + dx, ty + dy, face)

    # ---- scripts
    def busy(self):
        u = self.ui
        return bool(u.get("dialog") or (u.get("script") or {}).get("menu") or u.get("locked")
                    or self.p.state.get("battle"))

    def settle(self, quiet=1.5, timeout=8.0, choices=None, menu=None, battle=None):
        """Advances whatever scripts show until nothing has shown for `quiet` seconds.

        `choices`: list answered in order for yes/no boxes (True = YES, default YES);
        `menu`: callable(menu) -> value for script menus (default 0);
        `battle`: callable(story) that plays a battle out (default: fight with move 0)."""
        choices = list(choices or [])
        texts = []
        end = time.time() + timeout
        last_busy = time.time()
        while time.time() < end:
            u = self.ui
            d = u.get("dialog")
            m = (u.get("script") or {}).get("menu")
            b = self.p.state.get("battle")
            if b and b.get("phase") != "ENDED":
                (battle or fight)(self)
                last_busy = time.time()
                end = max(end, time.time() + timeout)
                continue
            if b and b.get("phase") == "ENDED":
                self.p.send(t="battle.close")
                time.sleep(0.6)
                continue
            if m:
                v = menu(m) if menu else (m["args"].get("default") or 0)
                if m["kind"] == "trade":
                    time.sleep(3.2)
                    last_busy = time.time()
                    continue
                self.p.send(t="script.reply", seq=m["seq"], value=v)
                texts.append(("menu", m["kind"], v))
                time.sleep(0.6)
                last_busy = time.time()
                continue
            if d:
                line = " ".join(d.get("lines") or [])
                if not texts or texts[-1] != line:
                    texts.append(line)
                if d.get("lastPage") and d.get("choices"):
                    yes = choices.pop(0) if choices else True
                    self.p.send(t="choose", index=0 if yes else 1)
                else:
                    self.p.send(t="a")
                time.sleep(0.45)
                last_busy = time.time()
                end = max(end, time.time() + 4)
                continue
            if u.get("locked"):
                last_busy = time.time()
            if time.time() - last_busy > quiet:
                break
            time.sleep(0.1)
        return texts

    def talk(self, **kw):
        self.p.send(t="a")
        time.sleep(0.5)
        return self.settle(**kw)

    def run(self, label, **kw):
        self.cmd(f"/script run {label}")
        time.sleep(0.4)
        return self.settle(**kw)


def fight(story, max_turns=40):
    """Plays a battle out with the first move until it ends."""
    p = story.p
    for _ in range(max_turns * 3):
        b = p.state.get("battle")
        if not b or b.get("phase") == "ENDED":
            break
        if b.get("phase") == "ACTION":
            p.send(t="battle.fight", move=0)
        elif b.get("phase") == "FORCED_SWITCH":
            party = story.party()
            alive = next((i for i, m in enumerate(party) if m["hp"] > 0), 0)
            p.send(t="battle.switch", slot=alive)
        elif b.get("learn"):
            p.send(t="battle.learn", slot=None)
        time.sleep(0.5)
    time.sleep(0.5)
    if p.state.get("battle"):
        p.send(t="battle.close")
        time.sleep(0.6)


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)
    ok(msg)


def give_team(s, dex=150, level=100):
    """A party that wins the scenes' battles quickly."""
    if not any(m["level"] >= level - 1 for m in s.party()):
        s.cmd(f"/givemon {dex} {level}", 2)
        s.p.wait(lambda q: any(m["level"] >= level - 1 for m in q.state.get("party") or []), 8, "strong mon")
    s.cmd("/script heal", 1)


# ------------------------------------------------------------------ scenes

SCENES = {}


def scene(region, name):
    def deco(f):
        SCENES[name] = (region, f)
        return f
    return deco


@scene(0, "smoke")
def scene_smoke_kanto(s):
    """A plain interpreted script (no hand port): its msgbox comes through."""
    texts = s.run("CeladonCity_Gym_EventScript_GymStatue")
    check(any(isinstance(t, str) and t for t in texts), f"interpreted sign text shown: {texts[:2]}")


def dev():
    """DEVELOPER permission for the slash commands (as e2e.py's setup does)."""
    os.system(
        "docker exec openmmo-r5s-game-db psql -q -U openmmo_game_user -d openmmo_game_db -c "
        "\"update characters set permissions = permissions | 256\" >/dev/null 2>&1")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ws", default="ws://127.0.0.1:8570/ws")
    ap.add_argument("--only", default="")
    ap.add_argument("--user", default="yicheng:pikachu123")
    a = ap.parse_args()
    user, pw = a.user.split(":")
    only = set(filter(None, a.only.split(",")))
    wanted = [n for n, (r, _) in SCENES.items()
              if not only or n in only or {0: "kanto", 1: "hoenn"}[r] in only]
    by_region = {}
    for n in wanted:
        by_region.setdefault(SCENES[n][0], []).append(n)
    for region, names in by_region.items():
        p = Player(a.ws, f"{user}/{region}")
        name = {0: "Ash", 1: "May"}[region]
        p.send(t="login", user=user, **{"pass": pw}, name=name, region=region)
        p.wait(lambda q: q.message("login"), 30, "login")
        p.wait(lambda q: q.world.get("inWorld"), 20, "in world")
        dev()
        time.sleep(1)
        s = Story(p, region)
        s.settle(2)
        for n in names:
            print(f"== {n}", flush=True)
            SCENES[n][1](s)
        p.ws.close()
    print("ALL OK", flush=True)


if __name__ == "__main__":
    main()
