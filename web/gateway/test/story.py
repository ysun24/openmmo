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
    def cmd(self, text, wait=1.0, expect=None):
        """A slash command; the reply line (containing `expect`, when given)."""
        before = len(self.p.chat_texts())
        self.p.send(t="say", text=text)
        end = time.time() + (wait if expect is None else max(wait, 6))
        while time.time() < end:
            new = [c for c in self.p.chat_texts()[before:] if expect is None or expect in c]
            if new:
                return new[-1]
            time.sleep(0.05)
        return ""

    def flag(self, name, value=None):
        r = self.cmd(f"/script flag {name}" + ("" if value is None else (" on" if value else " off")),
                     expect=f"{name} is")
        return " is set" in r

    def trainer(self, name, value):
        self.cmd(f"/script trainer {name} " + ("on" if value else "off"), expect=f"{name} is")

    def var(self, name, value=None):
        r = self.cmd(f"/script var {name}" + ("" if value is None else f" {value}"), expect=f"{name} =")
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
        hard_end = time.time() + timeout * 3
        last_busy = time.time()
        while time.time() < min(end, hard_end):
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
                    # the browser plays the trade scene, then answers
                    time.sleep(2.8)
                    self.p.send(t="script.reply", seq=m["seq"], value=0)
                    texts.append(("menu", "trade", 0))
                    time.sleep(0.6)
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
    """Plays a battle out with the strongest usable move until it ends."""
    p = story.p
    banned = set()
    for _ in range(max_turns * 3):
        b = p.state.get("battle")
        if not b or b.get("phase") == "ENDED":
            break
        if b.get("learn"):
            p.send(t="battle.learn", slot=None)
        elif b.get("phase") == "ACTION":
            moves = (b.get("me") or {}).get("moves") or []
            order = sorted(range(len(moves)), key=lambda i: -(moves[i].get("power") or 0))
            order = [i for i in order if moves[i]["pp"] > 0 and i not in banned] or [0]
            before = list((b.get("log") or []))
            p.send(t="battle.fight", move=order[0])
            time.sleep(0.8)
            log = (p.state.get("battle") or {}).get("log") or []
            new = log[len(before):] if log[:len(before)] == before else log[-2:]
            if any("can't use" in line for line in new):
                banned.add(order[0])
            if os.environ.get("STORY_DEBUG"):
                print("   battle:", moves[order[0]]["name"] if moves else "?", new, flush=True)
            continue
        elif b.get("phase") == "FORCED_SWITCH":
            party = story.party()
            alive = next((i for i, m in enumerate(party) if m["hp"] > 0), 0)
            p.send(t="battle.switch", slot=alive)
        time.sleep(0.5)
    time.sleep(0.5)
    if p.state.get("battle"):
        p.send(t="battle.close")
        time.sleep(0.6)


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)
    ok(msg)


def give_team(s, dex=384, level=100):
    """A party that wins the scenes' battles quickly (RAYQUAZA leads: no type it cannot hit)."""
    party = s.party()
    if not any(m["dex"] == dex for m in party):
        s.cmd(f"/givemon {dex} {level}", 2)
        s.p.wait(lambda q: any(m["dex"] == dex for m in q.state.get("party") or []), 8, "strong mon")
    party = s.party()
    i = next(i for i, m in enumerate(party) if m["dex"] == dex)
    if i != 0:
        s.p.send(t="party.move", **{"from": i, "to": 0})
        s.p.wait(lambda q: (q.state.get("party") or [{}])[0].get("dex") == dex, 8, "lead")
    s.cmd("/script heal", 1)
    time.sleep(0.5)


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


def has_item(s, name):
    """In the bag, or (items the wire catalogue lacks: SILPH SCOPE...) in the server's own count."""
    if any(name in k.replace("é", "E") for k in s.bag()):
        return True
    item = name.replace(' ', '_').replace('.', '')
    for _ in range(3):
        before = len(s.p.chat_texts())
        s.p.send(t="say", text=f"/script hasitem {item}")
        end = time.time() + 4
        while time.time() < end:
            hit = [c for c in s.p.chat_texts()[before:] if f"ITEM_{item} x" in c]
            if hit:
                return int(hit[-1].rsplit(" x", 1)[1]) > 0
            time.sleep(0.1)
    return False


@scene(0, "bill")
def scene_bill(s):
    """Cerulean: Bill as a Clefairy, the Cell Separator, the S.S. TICKET (Route25_SeaCottage)."""
    for f in ("FLAG_HELPED_BILL_IN_SEA_COTTAGE", "FLAG_GOT_SS_TICKET"):
        s.flag(f, False)
    s.flag("FLAG_HIDE_BILL_HUMAN_SEA_COTTAGE", True)
    give_team(s)
    s.tp("Route25_SeaCottage", 10, 7, "up")
    try:
        s.p.wait(lambda q: any(e["kind"] == "npc" and e["x"] == 10 and e["y"] == 6
                               for e in q.world.get("entities", [])), 6, "clefairy")
    except AssertionError:
        print("   entities:", [(e["kind"], e["x"], e["y"]) for e in s.p.world.get("entities", [])], s.map_name())
    check(any(e["kind"] == "npc" and e["x"] == 10 and e["y"] == 6 for e in s.p.world.get("entities", [])),
          "ON_TRANSITION shows Bill the CLEFAIRY at (10, 6)")
    texts = s.talk(choices=[True], timeout=25)
    check(any("BILL" in t.upper() for t in texts if isinstance(t, str)), f"Bill asks for help: {texts[:1]}")
    # Walk to the PC: a warp would clear the temp flag that says Bill is in the teleporter.
    s.step("left", 6)
    s.step("up")
    s.face("up")
    texts = s.talk(timeout=40)
    check(s.flag("FLAG_HELPED_BILL_IN_SEA_COTTAGE"), "the Cell Separator ran: FLAG_HELPED_BILL_IN_SEA_COTTAGE")
    # Leave and come back: Bill stands on his template tile again (the map reloads its objects).
    s.tp("Route25", 51, 6)
    bx, by = npc(0, "Route25_SeaCottage", "LOCALID_BILL_HUMAN")
    s.stand_by("Route25_SeaCottage", bx, by, "down")
    s.talk(timeout=25)
    check(s.flag("FLAG_GOT_SS_TICKET"), "Bill gave the ticket: FLAG_GOT_SS_TICKET")
    check(has_item(s, "TICKET"), f"S.S. TICKET in the bag: {sorted(s.bag())}")


@scene(0, "mtmoon")
def scene_mtmoon(s):
    """Mt Moon B2F: Miguel's battle, then the fossil choice (yes/no, removeobject, giveitem)."""
    s.flag("FLAG_GOT_FOSSIL_FROM_MT_MOON", False)
    s.flag("FLAG_GOT_DOME_FOSSIL", False)
    s.var("VAR_MAP_SCENE_MT_MOON_B2F", 0)
    s.trainer("TRAINER_SUPER_NERD_MIGUEL", False)
    give_team(s)
    mx, my = npc(0, "MtMoon_B2F", "LOCALID_MIGUEL")
    s.stand_by("MtMoon_B2F", mx, my, "up")
    texts = s.talk(timeout=60)
    check(s.var("VAR_MAP_SCENE_MT_MOON_B2F") == 1, f"Miguel beaten (trainerbattle_no_intro), scene var 1: {texts[-1:]}")
    fx, fy = npc(0, "MtMoon_B2F", "LOCALID_DOME_FOSSIL")
    s.stand_by("MtMoon_B2F", fx, fy, "down")
    s.talk(choices=[True], timeout=30)
    check(s.flag("FLAG_GOT_FOSSIL_FROM_MT_MOON") and s.flag("FLAG_GOT_DOME_FOSSIL"), "took the DOME FOSSIL")
    check(has_item(s, "DOME"), f"DOME FOSSIL in the bag")


@scene(0, "ssanne")
def scene_ssanne(s):
    """S.S. Anne: the seasick captain gives HM01 CUT (message + fanfare + giveitem_msg)."""
    s.flag("FLAG_GOT_HM01", False)
    cx, cy = npc(0, "SSAnne_CaptainsOffice", "LOCALID_SS_ANNE_CAPTAIN")
    s.stand_by("SSAnne_CaptainsOffice", cx, cy, "down")
    texts = s.talk(timeout=40)
    check(s.flag("FLAG_GOT_HM01"), f"FLAG_GOT_HM01 set: {[t for t in texts if 'HM01' in str(t)][:1]}")
    check(has_item(s, "HM01"), "HM01 in the bag")
    check(s.var("VAR_MAP_SCENE_VERMILION_CITY") == 1, "Vermilion scene var advanced")


@scene(0, "hideout")
def scene_hideout(s):
    """Game Corner poster opens the hideout (setmetatile), Giovanni on B4F, the SILPH SCOPE."""
    s.flag("FLAG_OPENED_ROCKET_HIDEOUT", False)
    give_team(s)
    s.stand_by("CeladonCity_GameCorner", 9, 9, "down") if False else None
    poster = next(b for b in map_json(0, "CeladonCity_GameCorner")["bg_events"]
                  if b.get("script") == "CeladonCity_GameCorner_EventScript_Poster")
    s.stand_by("CeladonCity_GameCorner", poster["x"], poster["y"], "down")
    s.talk(timeout=60)
    check(s.flag("FLAG_OPENED_ROCKET_HIDEOUT"), "the switch behind the poster: FLAG_OPENED_ROCKET_HIDEOUT")
    tiles = s.script_ui().get("metatiles") or []
    check(any(t["x"] == 16 and t["y"] == 2 for t in tiles), f"stairs metatiles changed: {tiles[:3]}")
    s.flag("FLAG_HIDE_HIDEOUT_GIOVANNI", False)
    s.trainer("TRAINER_BOSS_GIOVANNI", False)
    s.flag("FLAG_DEFEATED_ROCKET_HIDEOUT_GIOVANNI", False) if False else None
    gx, gy = npc(0, "RocketHideout_B4F", "LOCALID_HIDEOUT_GIOVANNI")
    s.stand_by("RocketHideout_B4F", gx, gy, "down")
    s.talk(timeout=90)
    check(s.flag("FLAG_HIDE_HIDEOUT_GIOVANNI"), "Giovanni beaten and gone (removeobject sets his flag)")
    sx, sy = npc(0, "RocketHideout_B4F", "LOCALID_SILPH_SCOPE")
    s.stand_by("RocketHideout_B4F", sx, sy, "down")
    s.talk(timeout=20)
    check(has_item(s, "SILPH SCOPE"), "SILPH SCOPE picked up")


@scene(0, "tower")
def scene_tower(s):
    """Pokemon Tower: the Marowak ghost (coord event, Silph Scope), Mr. Fuji, the POKé FLUTE."""
    if not has_item(s, "SILPH SCOPE"):
        s.cmd("/script item SILPH_SCOPE", 2)
    give_team(s)
    s.var("VAR_MAP_SCENE_POKEMON_TOWER_6F", 0)
    s.tp("PokemonTower_6F", 11, 14, "down")
    s.step("down")
    texts = s.settle(timeout=90)
    check(s.var("VAR_MAP_SCENE_POKEMON_TOWER_6F") == 1, f"the ghost was MAROWAK and is laid to rest: {texts[-2:]}")
    for f in ("FLAG_HIDE_TOWER_ROCKET_1", "FLAG_HIDE_TOWER_ROCKET_2", "FLAG_HIDE_TOWER_ROCKET_3"):
        s.flag(f, True)
    s.flag("FLAG_HIDE_TOWER_FUJI", False)
    s.flag("FLAG_RESCUED_MR_FUJI", False)
    s.flag("FLAG_GOT_POKE_FLUTE", False)
    fx, fy = npc_by_script(0, "PokemonTower_7F", "PokemonTower_7F_EventScript_MrFuji")
    s.stand_by("PokemonTower_7F", fx, fy, "down")
    s.talk(timeout=40)
    check(s.flag("FLAG_RESCUED_MR_FUJI"), f"Mr. Fuji rescued, now on {s.map_name()}")
    if s.map_name() != "LavenderTown_VolunteerPokemonHouse":
        s.tp("LavenderTown_VolunteerPokemonHouse", 3, 5, "up")
    fx, fy = npc_by_script(0, "LavenderTown_VolunteerPokemonHouse", "LavenderTown_VolunteerPokemonHouse_EventScript_MrFuji")
    s.stand_by("LavenderTown_VolunteerPokemonHouse", fx, fy, "down")
    texts = s.talk(timeout=30)
    got = s.flag("FLAG_GOT_POKE_FLUTE")
    check(got and has_item(s, "POKE FLUTE"), f"Mr. Fuji gave the POKé FLUTE (flag {got}, bag {sorted(s.bag())}, {texts[-2:]})")


@scene(0, "silph")
def scene_silph(s):
    """Silph Co.: the elevator (ListMenu, dynamic warp), a CARD KEY door (setmetatile), Giovanni,
    the president's MASTER BALL."""
    give_team(s)
    s.tp("SilphCo_Elevator", 1, 3, "up") if False else None
    bg = map_json(0, "SilphCo_Elevator")["bg_events"][0]
    s.stand_by("SilphCo_Elevator", bg["x"], bg["y"], "down")
    seen = []
    s.talk(timeout=30, menu=lambda m: (seen.append(m), 0)[1])  # 11F is the first floor listed
    check(seen and seen[0]["kind"] == "multichoice" and "11F" in seen[0]["args"]["items"][0],
          f"floor list shown: {seen[0]['args']['items'][:3] if seen else None}")
    # step out of the elevator through its door: MAP_DYNAMIC takes us to 11F
    warp = map_json(0, "SilphCo_Elevator")["warp_events"][0]
    s.tp("SilphCo_Elevator", warp["x"], warp["y"] - 1, "down")
    s.step("down")
    s.step("down")
    s.p.wait(lambda q: (q.world.get("map") or {}).get("name") == "SilphCo_11F", 10, "arrive on 11F")
    check(s.map_name() == "SilphCo_11F", "the elevator's dynamic warp leads to 11F")
    s.cmd("/script item CARD_KEY", 2)
    for f in ("FLAG_SILPH_11F_DOOR",):
        s.flag(f, False)
    s.stand_by("SilphCo_11F", 5, 16, "up")
    s.talk(timeout=20)
    tiles = s.script_ui().get("metatiles") or []
    check(any(t["y"] in (16, 17) for t in tiles), f"the CARD KEY opened the door (setmetatile): {tiles[:2]}")
    s.var("VAR_MAP_SCENE_SILPH_CO_11F", 0)
    s.trainer("TRAINER_BOSS_GIOVANNI_2", False)
    s.flag("FLAG_HIDE_SILPH_ROCKETS", False)
    s.tp("SilphCo_11F", 5, 14, "down")
    s.step("down")
    s.settle(timeout=90)
    check(s.var("VAR_MAP_SCENE_SILPH_CO_11F") == 1, "Giovanni beaten in Silph Co.")
    s.flag("FLAG_GOT_MASTER_BALL_FROM_SILPH", False)
    px, py = npc_by_script(0, "SilphCo_11F", "SilphCo_11F_EventScript_President")
    s.stand_by("SilphCo_11F", px, py, "down")
    s.talk(timeout=30)
    check(s.flag("FLAG_GOT_MASTER_BALL_FROM_SILPH") and has_item(s, "MASTER BALL"), "the MASTER BALL")


@scene(0, "trade")
def scene_trade(s):
    """Route 2: Reyley trades his MR. MIME (MIMIEN) for an ABRA (in-game trade + party picker)."""
    s.flag("FLAG_DID_MIMIEN_TRADE", False)
    if not any(m["dex"] == 63 for m in s.party()):
        s.cmd("/givemon 63 10", 2)
        s.p.wait(lambda q: any(m["dex"] == 63 for m in q.state.get("party") or []), 8, "abra")
    abra = next(i for i, m in enumerate(s.party()) if m["dex"] == 63)
    rx, ry = npc_by_script(0, "Route2_House", "Route2_House_EventScript_Reyley")
    s.stand_by("Route2_House", rx, ry, "down")
    kinds = []

    def answer(m):
        kinds.append(m["kind"])
        return abra if m["kind"] == "party" else 0
    s.talk(choices=[True], menu=answer, timeout=40)
    check("party" in kinds and "trade" in kinds, f"party picker and trade scene shown: {kinds}")
    check(any(m["dex"] == 122 for m in s.party()), "MR. MIME (MIMIEN) is in the party")
    mm = next(m for m in s.party() if m["dex"] == 122)
    check((mm.get("nick") or mm["name"]) == "MIMIEN" and mm.get("ot") == "REYLEY", f"nickname and OT: {mm.get('nick')} / {mm.get('ot')}")


@scene(0, "sevii")
def scene_sevii(s):
    """Vermilion harbor: the Seagallop (destination menu, ferry scene) to One Island."""
    s.cmd("/script item TRI_PASS", 2)
    s.var("VAR_MAP_SCENE_VERMILION_CITY", 3)
    s.var("VAR_MAP_SCENE_ONE_ISLAND_POKEMON_CENTER_1F", 1)
    sx, sy = npc(0, "VermilionCity", "LOCALID_VERMILION_FERRY_SAILOR")
    s.stand_by("VermilionCity", sx, sy, "up")
    menus = []
    s.talk(choices=[True], menu=lambda m: (menus.append(m), 0)[1], timeout=40)
    check(menus, f"destination menu shown: {[m['args'].get('items') for m in menus][:1]}")
    s.p.wait(lambda q: "OneIsland" in ((q.world.get("map") or {}).get("name") or ""), 20, "arrive on One Island")
    check("OneIsland" in s.map_name(), f"arrived: {s.map_name()}")


@scene(1, "woods")
def scene_woods(s):
    """Petalburg Woods: the Aqua grunt cornering the Devon researcher (coord event), the battle."""
    s.var("VAR_PETALBURG_WOODS_STATE", 0)
    s.trainer("TRAINER_GRUNT_PETALBURG_WOODS", False)
    for f in ("FLAG_HIDE_PETALBURG_WOODS_AQUA_GRUNT", "FLAG_HIDE_PETALBURG_WOODS_DEVON_EMPLOYEE"):
        s.flag(f, False)
    give_team(s)
    s.tp("PetalburgWoods", 26, 22, "down")
    s.step("down")
    texts = s.settle(timeout=90)
    check(s.var("VAR_PETALBURG_WOODS_STATE") == 1, f"the grunt is beaten, woods state 1: {texts[-1:]}")


@scene(1, "devon")
def scene_devon(s):
    """Rustboro: Devon Corp 3F's ON_TRANSITION / ON_WARP_INTO / ON_FRAME: meeting Mr. Stone,
    the LETTER and the POKéNAV."""
    s.var("VAR_DEVON_CORP_3F_STATE", 0)
    s.flag("FLAG_RECEIVED_POKENAV", False)
    s.flag("FLAG_HIDE_RUSTBORO_CITY_DEVON_CORP_3F_EMPLOYEE", False)
    w = map_json(1, "RustboroCity_DevonCorp_3F")["warp_events"][0]
    s.tp("RustboroCity_DevonCorp_3F", w["x"], w["y"] + 1, "down")
    texts = s.settle(timeout=120, quiet=3)
    check(s.flag("FLAG_RECEIVED_POKENAV"), f"Mr. Stone gave the POKéNAV (ON_FRAME cutscene): {texts[-2:]}")
    check(s.var("VAR_DEVON_CORP_3F_STATE") == 1, "Devon 3F state 1")
    check(has_item(s, "LETTER"), "the LETTER for Steven")


@scene(1, "chimney")
def scene_chimney(s):
    """Mt Chimney: Maxie at the summit (trainerbattle_no_intro), Magma leaves, Archie comes up."""
    s.flag("FLAG_HIDE_MT_CHIMNEY_TEAM_MAGMA", False)
    s.flag("FLAG_HIDE_MT_CHIMNEY_TEAM_AQUA", False)
    s.trainer("TRAINER_MAXIE_MT_CHIMNEY", False)
    give_team(s)
    mx, my = npc(1, "MtChimney", "LOCALID_MT_CHIMNEY_MAXIE")
    s.stand_by("MtChimney", mx, my, "down")
    texts = s.talk(timeout=120)
    check(s.flag("FLAG_HIDE_MT_CHIMNEY_TEAM_MAGMA"), f"Maxie beaten, Team Magma gone: {texts[-1:]}")


@scene(1, "weather")
def scene_weather(s):
    """Weather Institute 2F: Shelly (trainerbattle_single with an event script), then the
    scientist's gift CASTFORM (givemon)."""
    s.flag("FLAG_HIDE_ROUTE_119_TEAM_AQUA", False)
    s.flag("FLAG_RECEIVED_CASTFORM", False)
    s.var("VAR_WEATHER_INSTITUTE_STATE", 0)
    s.trainer("TRAINER_SHELLY_WEATHER_INSTITUTE", False)
    give_team(s)
    party = len(s.party())
    if party >= 6:
        raise AssertionError("party full")
    shx, shy = npc(1, "Route119_WeatherInstitute_2F", "LOCALID_WEATHER_INSTITUTE_2F_SHELLY")
    s.stand_by("Route119_WeatherInstitute_2F", shx, shy, "right")
    texts = s.talk(timeout=120)
    check(s.var("VAR_WEATHER_INSTITUTE_STATE") == 1, f"Shelly beaten, the event script ran: {texts[-1:]}")
    sx, sy = npc(1, "Route119_WeatherInstitute_2F", "LOCALID_WEATHER_INSTITUTE_2F_SCIENTIST")
    s.stand_by("Route119_WeatherInstitute_2F", sx, sy, "left")
    s.talk(choices=[False], timeout=40)
    check(s.flag("FLAG_RECEIVED_CASTFORM"), "the scientist gave CASTFORM")
    check(any(m["dex"] == 351 for m in s.party()), "CASTFORM is in the party")


@scene(1, "regirock")
def scene_regirock(s):
    """Desert Ruins: ON_LOAD seals the chamber until the braille puzzle is solved; Regirock
    (setwildbattle + StartRegiBattle + GetBattleOutcome)."""
    s.flag("FLAG_SYS_REGIROCK_PUZZLE_COMPLETED", False)
    s.tp("DesertRuins", 8, 27, "up")
    sealed = s.script_ui().get("metatiles") or []
    check(any(t["y"] == 19 and t["impassable"] for t in sealed), f"ON_LOAD sealed the chamber: {sealed[:2]}")
    s.flag("FLAG_SYS_REGIROCK_PUZZLE_COMPLETED", True)
    s.flag("FLAG_HIDE_REGIROCK", False)
    s.flag("FLAG_DEFEATED_REGIROCK", False)
    give_team(s)
    rx, ry = npc_by_script(1, "DesertRuins", "DesertRuins_EventScript_Regirock")
    s.stand_by("DesertRuins", rx, ry, "down")
    check(not (s.script_ui().get("metatiles") or []), "solved: the entrance stays open")
    texts = s.talk(timeout=120)
    check(s.flag("FLAG_DEFEATED_REGIROCK"), f"REGIROCK fought and defeated: {texts[-1:]}")


@scene(1, "sootopolis")
def scene_sootopolis(s):
    """Sootopolis Gym: Juan (trainerbattle_single with a defeat script): the RAIN BADGE, the gym
    trainers set, TM WATER PULSE (giveitem), VAR_SOOTOPOLIS_CITY_STATE 6."""
    for f in ("FLAG_DEFEATED_SOOTOPOLIS_GYM", "FLAG_BADGE08_GET", "FLAG_RECEIVED_TM_WATER_PULSE"):
        s.flag(f, False)
    s.trainer("TRAINER_JUAN_1", False)
    give_team(s)
    jx, jy = npc_by_script(1, "SootopolisCity_Gym_1F", "SootopolisCity_Gym_1F_EventScript_Juan")
    s.stand_by("SootopolisCity_Gym_1F", jx, jy, "down")
    texts = s.talk(timeout=150)
    check(s.flag("FLAG_DEFEATED_SOOTOPOLIS_GYM") and s.flag("FLAG_BADGE08_GET"),
          f"Juan beaten, RAIN BADGE: {texts[-1:]}")
    check(s.flag("FLAG_RECEIVED_TM_WATER_PULSE"), "TM WATER PULSE given")
    check(s.var("VAR_SOOTOPOLIS_CITY_STATE") == 6, "Sootopolis state 6")


@scene(1, "eon")
def scene_eon(s):
    """The EON TICKET (a key item since the r5 merge): the Lilycove ferry's decomp script finds it
    (checkitem + FLAG_ENABLE_SHIP_SOUTHERN_ISLAND), sails to Southern Island, and the interior's
    sign starts the Lati@s event (camera pan, addobject, BattleSetup_StartLatiBattle)."""
    s.flag("FLAG_SYS_GAME_CLEAR", True)
    s.flag("FLAG_ENABLE_SHIP_SOUTHERN_ISLAND", True)
    for f in ("FLAG_DEFEATED_LATIAS_OR_LATIOS", "FLAG_CAUGHT_LATIAS_OR_LATIOS",
              "FLAG_ENCOUNTERED_LATIAS_OR_LATIOS", "FLAG_SHOWN_EON_TICKET"):
        s.flag(f, False)
    s.cmd("/script item EON_TICKET", 2)
    check(has_item(s, "EON TICKET"), f"EON TICKET in the bag: {sorted(s.bag())[:8]}")
    ax, ay = npc_by_script(1, "LilycoveCity_Harbor", "LilycoveCity_Harbor_EventScript_FerryAttendant")
    s.stand_by("LilycoveCity_Harbor", ax, ay, "down")
    menus = []

    def pick(m):
        menus.append(m)
        items = [str(i).upper() for i in (m.get("args") or {}).get("items") or []]
        return next((i for i, n in enumerate(items) if "SOUTHERN" in n), 0)

    s.flag("FLAG_SHOWN_EON_TICKET", False)
    # The first time the ticket is shown the sailor takes the player straight there (no menu).
    s.talk(choices=[True], menu=pick, timeout=60)
    s.p.wait(lambda q: (q.world.get("map") or {}).get("name") == "SouthernIsland_Exterior", 25,
             "arrive on Southern Island")
    check(s.map_name() == "SouthernIsland_Exterior", f"sailed: {s.map_name()}")
    give_team(s)
    s.tp("SouthernIsland_Interior", 13, 12, "up")
    # The camera pans up, the Lati@s cries and flies in: seconds without a message.
    s.p.send(t="a")
    s.p.wait(lambda q: (q.state.get("battle") or {}).get("phase"), 30, "the Lati@s battle")
    texts = s.settle(timeout=150)
    check(s.flag("FLAG_ENCOUNTERED_LATIAS_OR_LATIOS"), f"the Lati@s appeared: {texts[:2]}")
    check(s.flag("FLAG_DEFEATED_LATIAS_OR_LATIOS") or s.flag("FLAG_CAUGHT_LATIAS_OR_LATIOS"),
          f"Lati@s battle over: {texts[-1:]}")


def coins(s):
    return ((s.p.state.get("minigames") or {}).get("coins") or {}).get("coins")


@scene(0, "gamecorner")
def scene_gamecorner(s):
    """Celadon Game Corner (r5 merge: the interpreter runs the decomp scripts): the coin clerk sells
    50 COINS (checkcoins / addcoins into r5-minigames' coin store), then a slot machine's
    `playslotmachine` opens r5-minigames' FireRed slots; a 3-coin spin and quit."""
    s.flag("FLAG_GOT_COIN_CASE", True)
    s.cmd("/script item COIN_CASE", 2)
    s.cmd("/givemoney 5000", 1)
    menus = []
    s.run("CeladonCity_GameCorner_EventScript_CoinsClerk", choices=[True],
          menu=lambda m: (menus.append(m), 0)[1], timeout=40)
    s.p.wait(lambda q: (coins(s) or 0) >= 50, 10, "50 coins bought")
    have = coins(s)
    check(have >= 50, f"the clerk sold coins (coin store): {have}")
    s.p.send(t="say", text="/script run CeladonCity_GameCorner_EventScript_SlotMachine0")
    time.sleep(0.6)
    s.settle(quiet=1.0, timeout=20, choices=[True])
    s.p.wait(lambda q: (q.state.get("minigames") or {}).get("slots"), 15, "slots window")
    check(True, "playslotmachine opened r5-minigames' slot machine")
    s.p.send(t="minigame", topic="slots", action="bet", n=3)
    time.sleep(0.8)
    for _ in range(3):
        s.p.send(t="minigame", topic="slots", action="stop")
        time.sleep(0.8)
    time.sleep(1.0)
    after = coins(s)
    slots = (s.p.state.get("minigames") or {}).get("slots") or {}
    check(after is not None and "reels" in slots, f"spun 3 coins: {have} -> {after}, reels {slots.get('reels')}")
    s.p.send(t="minigame", topic="slots", action="quit")
    s.settle(quiet=1.5, timeout=20)


def dev():
    """DEVELOPER permission for the slash commands (as e2e.py's setup does)."""
    db = os.environ.get("OPENMMO_GAME_DB_CONTAINER", "openmmo-game-db")
    os.system(
        f"docker exec {db} psql -q -U openmmo_game_user -d openmmo_game_db -c "
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
        name = "Ash"
        p.send(t="login", user=user, **{"pass": pw}, name=name, region=region)
        p.wait(lambda q: q.message("login"), 30, "login")
        p.wait(lambda q: q.world.get("inWorld"), 20, "in world")
        dev()
        time.sleep(1)
        s = Story(p, region)
        s.settle(3, timeout=60)
        for n in names:
            print(f"== {n}", flush=True)
            SCENES[n][1](s)
        p.ws.close()
    print("ALL OK", flush=True)


if __name__ == "__main__":
    main()
