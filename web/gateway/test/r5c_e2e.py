#!/usr/bin/env python3
"""r5-contests end-to-end driver over the web gateway (stdlib only; reuses e2e.py's client).

    python3 web/gateway/test/r5c_e2e.py --ws ws://127.0.0.1:8580/ws \
        --a yicheng:pikachu123:Ash --b friend:bulbasaur123:Gary [--only contest|blender|feebas|berry]

* contest: both players enter a Normal Rank Cool contest within the join window (two humans plus
  two NPCs), pick moves for five appeals, Ash (with high conditions) wins and gets the ribbon.
* blender: Ash blends a berry with the Lilycove old-timer (an NPC), pressing at the mark.
* feebas: a Feebas fed Pokeblocks reaches beauty 170 and evolves into Milotic on a level up.
* berry: plant a berry on Route 104, water it, let it grow (/contestdev grow) and pick it.
Both characters need the DEVELOPER permission.
"""
import argparse
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e2e  # noqa: E402
from e2e import Player, login, command, ok  # noqa: E402


B_LABEL = "Gary"  # the --b player (picks the second berry at the link blender)


def docs(p):
    return ((p.state.get("contests") or {}).get("docs")) or {}


def dialog(p):
    return (p.state.get("ui") or {}).get("dialog")


def answer_dialogs(p, until, timeout=60, yes=True, log=False):
    """Presses A through dialog boxes (YES on yes/no unless `yes` is False) until `until(p)`."""
    end = time.time() + timeout
    last = None
    while time.time() < end:
        if until(p):
            return True
        d = dialog(p)
        if d:
            key = (tuple(d.get("lines") or []), d.get("lastPage"))
            if key != last:
                last = key
                if log:
                    print("      dialog:", " / ".join(d.get("lines") or []), d.get("choices"))
                if d.get("choices"):
                    want = 0 if yes else 1
                    while (dialog(p) or {}).get("cursor", 0) != want:
                        p.send(t="cursor", delta=1 if (dialog(p) or {}).get("cursor", 0) < want else -1)
                        time.sleep(0.15)
                time.sleep(0.25)
                p.send(t="a")
            time.sleep(0.15)
        else:
            time.sleep(0.15)
    raise AssertionError(f"[{p.label}] timed out ({until})")


def goto(p, where, x=None, y=None):
    command(p, f"/contestdev goto {where}")
    time.sleep(2.5)


def face(p, d):
    p.send(t="face", dir=d)
    time.sleep(0.3)


def step(p, d, n=1):
    for _ in range(n):
        p.send(t="step", dir=d, run=False, seq=int(time.time() * 1000) % 100000)
        time.sleep(0.35)


def pos(p):
    e = p.self_entity() or {}
    return e.get("x"), e.get("y")


def map_name(p):
    return (p.world.get("map") or {}).get("name")


def contest_flow(p, results, mon_slot=0):
    """Talks to the receptionist in front, enters Normal Cool, sits through the decomp hall
    cutscene (run by the interpreter) and plays the appeals on the contest screen."""
    p.send(t="a")
    answer_dialogs(p, lambda q: "contestEntry" in docs(q), 40, log=True)
    ok(f"{p.label}: the entry screen lists {len(docs(p)['contestEntry']['party'])} monsters")
    p.send(t="contestEntry.choose", rank=0, category=0, slot=mon_slot)
    answer_dialogs(p, lambda q: (docs(q).get("contest") or {}).get("phase") in ("waiting", "intro", "select")
                   or map_name(q) == "ContestHall", 30, log=True)
    ok(f"{p.label}: entered ({(docs(p).get('contest') or {}).get('phase') or map_name(p)})")
    seen_rounds = set()
    hall_lines = []
    end = time.time() + 600
    while time.time() < end:
        c = docs(p).get("contest")
        if c is None:
            if "r" in seen_rounds:
                break
            # the hall cutscene between the screens: the MC, the entries, the judging
            d = dialog(p)
            if d:
                line = " / ".join(d.get("lines") or [])
                if not hall_lines or hall_lines[-1] != line:
                    hall_lines.append(line)
                    print(f"      {p.label} hall: {line}")
                p.send(t="a")
                time.sleep(0.35)
            time.sleep(0.15)
            continue
        ph = c.get("phase")
        if ph == "select" and c.get("appeal") not in seen_rounds:
            seen_rounds.add(c["appeal"])
            moves = c.get("moves") or []
            if c.get("canMove") and moves:
                m = next((m for m in moves if not m.get("repeat")), moves[0])
                p.send(t="contest.move", slot=m["slot"])
                print(f"      {p.label}: appeal {c['appeal'] + 1}: {m['name']} ({m['appeal']} hearts)")
        elif ph == "appeals" and ("a", c.get("round")) not in seen_rounds:
            seen_rounds.add(("a", c.get("round")))
            texts = [e.get("text") for e in c.get("events", []) if e.get("text")]
            print(f"      {p.label}: round {c['round'] + 1}: {len(c['events'])} events, e.g. {texts[:2]}")
            p.send(t="contest.ready")
        elif ph == "results" and "r" not in seen_rounds:
            seen_rounds.add("r")
            rows = sorted(c["results"], key=lambda r: r["place"])
            names = [(c["contestants"][r["index"]]["trainer"], r["total"]) for r in rows]
            results[p.label] = (c["you"], c["winner"], names)
            print(f"      {p.label}: results {names}")
            p.send(t="contest.ready")
        time.sleep(0.2)
    ok(f"{p.label}: the contest screen closed")
    results[p.label + ":hall"] = list(hall_lines)
    # the hall: the winner, the prize, the exit warp; the lobby: the contest artist
    answer_dialogs(p, lambda q: dialog(q) is None and not docs(q) and map_name(q) != "ContestHall"
                   and not (q.state.get("ui") or {}).get("locked"), 120, log=True)
    results[p.label + ":back"] = map_name(p)


def run_contest(a, b):
    print("== contest (two players + two NPCs, Normal Rank Cool)")
    for p in (a, b):
        command(p, "/contestdev kit", "added")
    command(a, "/contestdev stats 200 90 90 90 90 200", "cool 200")
    command(b, "/contestdev stats 0 0 0 0 0 0", "cool 0")
    # the receptionist stands at (14, 2), the link receptionist at (15, 2), behind the counter (row 3)
    goto(a, "lobby 14 4 up")
    goto(b, "lobby 15 4 up")
    results = {}
    ta = threading.Thread(target=contest_flow, args=(a, results))
    ta.start()
    time.sleep(3)
    face(b, "up")
    tb = threading.Thread(target=contest_flow, args=(b, results))
    tb.start()
    ta.join(400)
    tb.join(400)
    assert a.label in results and b.label in results, results
    you_a, win_a, names = results[a.label]
    # the trainers are the characters' names (an existing character keeps its own name)
    who = {a.label, b.label, getattr(a, "char_name", None), getattr(b, "char_name", None)}
    humans = [n for n, _ in names if n in who]
    assert len(humans) == 2, f"both players in one contest: {names}"
    ok(f"two players and {4 - len(humans)} NPCs in one contest: {names}")
    assert you_a == win_a, f"Ash won: {names}"
    ok("Ash won the Normal Rank Cool contest")
    for p in (a, b):
        hall = results.get(p.label + ":hall") or []
        assert any("participants" in l.lower() or "entry" in l.lower() for l in hall), hall[:6]
        ok(f"{p.label}: the decomp hall cutscene ran ({len(hall)} lines, e.g. {hall[:2]})")
        assert results.get(p.label + ":back") != "ContestHall", results.get(p.label + ":back")
        ok(f"{p.label}: back in {results.get(p.label + ':back')} after the hall")


def bag_qty(p, name):
    for it in (p.state.get("ui") or {}).get("bag") or []:
        if it["name"].lower() == name.lower():
            return it["qty"], it["item"]
    return 0, None


def run_blender(a):
    print("== blender (Ash + the old-timer at BERRY BLENDER 1)")
    command(a, "/contestdev kit", "added")
    goto(a, "lobby 27 6 up")
    a.send(t="a")
    answer_dialogs(a, lambda q: (docs(q).get("blender") or {}).get("phase") == "choose", 40, log=True)
    berries = docs(a)["blender"]["berries"]
    ok(f"Ash: the blender's berry menu lists {len(berries)} berries")
    a.send(t="blender.berry", berry=berries[0]["berry"])
    a.wait(lambda q: (docs(q).get("blender") or {}).get("phase") in ("play", "start"), 30, "blender start")
    seats = a.wait(lambda q: docs(q)["blender"].get("seats"), 10, "seats")
    you = docs(a)["blender"]["you"]
    start = seats[you]["hitStart"]
    ok(f"Ash: blending with {[x['name'] for x in seats]}")
    presses = 0
    t0 = time.time()
    last_frame = -1
    while time.time() - t0 < 240:
        d = docs(a).get("blender") or {}
        if d.get("phase") == "result":
            break
        if d.get("phase") == "play" and d.get("frame") != last_frame and d.get("speed"):
            last_frame = d["frame"]
            target = (start * 256) & 0xFFFF
            delta = (target - d["arrow"]) & 0xFFFF
            frames = delta / d["speed"]
            if frames < 8:  # the mark comes within this update window
                time.sleep(max(0.0, frames / 60.0 - 0.01))
                a.send(t="blender.press", pos=target)
                presses += 1
        time.sleep(0.01)
    d = a.wait(lambda q: (docs(q).get("blender") or {}).get("phase") == "result" and docs(q)["blender"], 60, "blender result")
    me = next(r for r in d["ranking"] if r["seat"] == you)
    ok(f"Ash: {presses} presses, BEST {me['best']} GOOD {me['good']} MISS {me['miss']}, max {d['maxRpm'] / 100:.2f} RPM")
    ok(f"Ash: got {d['block']['name']} LV{d['block']['level']} feel {d['block']['feel']} (received={d['received']})")
    assert d["received"]
    a.send(t="blender.close")
    answer_dialogs(a, lambda q: dialog(q) is None and not docs(q).get("blender"), 30)
    a.send(t="pokeblocks.open")
    blocks = a.wait(lambda q: (docs(q).get("pokeblocks") or {}).get("blocks"), 10, "case")
    ok(f"Ash: the POKéBLOCK CASE holds {len(blocks)} blocks")
    a.send(t="pokeblocks.close")


def press_loop(p, limit=240):
    """Presses at the player's own mark from the extrapolated arrow; returns the press count."""
    presses, last, t0 = 0, -1, time.time()
    while time.time() - t0 < limit:
        d = docs(p).get("blender") or {}
        if d.get("phase") == "result":
            break
        if d.get("phase") == "play" and d.get("frame") != last and d.get("speed") and d.get("seats"):
            last = d["frame"]
            target = (d["seats"][d["you"]]["hitStart"] * 256) & 0xFFFF
            frames = ((target - d["arrow"]) & 0xFFFF) / d["speed"]
            if frames < 8:
                time.sleep(max(0.0, frames / 60.0 - 0.01))
                p.send(t="blender.press", pos=target)
                presses += 1
        time.sleep(0.01)
    return presses


def blend_one(p, where, out):
    command(p, "/contestdev kit", "added")
    goto(p, where)
    p.send(t="a")
    answer_dialogs(p, lambda q: (docs(q).get("blender") or {}).get("phase") == "choose", 40)
    p.send(t="blender.berry", berry=docs(p)["blender"]["berries"][1 if p.label == B_LABEL else 0]["berry"])
    p.wait(lambda q: (docs(q).get("blender") or {}).get("phase") == "play", 60, "play")
    press_loop(p)
    d = p.wait(lambda q: (docs(q).get("blender") or {}).get("phase") == "result" and docs(q)["blender"], 60, "result")
    out[p.label] = [r["name"] for r in d["ranking"]], d["block"]["name"]
    p.send(t="blender.close")
    answer_dialogs(p, lambda q: dialog(q) is None and not docs(q).get("blender"), 30)


def run_blender_link(a, b):
    print("== link blender (two players, no NPCs)")
    out = {}
    ta = threading.Thread(target=blend_one, args=(a, "lobby 23 6 up", out))
    tb = threading.Thread(target=blend_one, args=(b, "lobby 22 5 right", out))
    ta.start(); time.sleep(2); tb.start(); ta.join(400); tb.join(400)
    print("      ", out)
    assert sorted(out[a.label][0]) == sorted([a.label, b.label]), out
    ok(f"Ash and Gary blended together: {out}")


def run_feebas(a):
    print("== Feebas -> Milotic (Pokeblocks raise beauty, evolution on a level up)")
    command(a, "/contestdev kit", "added")
    if not any(m["dex"] == 349 for m in a.state.get("party") or []):
        command(a, "/givemon 349 20")
        a.wait(lambda q: any(m["dex"] == 349 for m in q.state.get("party") or []), 10, "Feebas")
    for _ in range(4):
        command(a, "/contestdev block 0 60 0 0 0 5", "added")
    a.send(t="pokeblocks.open")
    case = a.wait(lambda q: (docs(q).get("pokeblocks") or {}).get("party"), 10, "case")
    feebas = next(m for m in case if m["dex"] == 349)
    for i in range(4):
        case = docs(a)["pokeblocks"]
        blk = next(b for b in case["blocks"] if b["dry"] == 60)
        seq = a.state["contests"]["seq"]
        a.send(t="pokeblocks.feed", slot=blk["slot"], mon=feebas["id"])
        a.wait(lambda q: q.state["contests"]["seq"] > seq, 10, "fed")
        m = next(m for m in docs(a)["pokeblocks"]["party"] if m["id"] == feebas["id"])
        print(f"      fed block {i + 1}: {docs(a)['pokeblocks']['message']!r} beauty={m['beauty']} sheen={m['sheen']}")
    assert m["beauty"] >= 170, m
    ok(f"Feebas beauty {m['beauty']} (EVO_BEAUTY needs 170)")
    a.send(t="pokeblocks.close")
    time.sleep(0.5)
    qty, candy = bag_qty(a, "Rare Candy")
    assert candy, "rare candy"
    a.send(t="item.use", item=candy, mon=feebas["id"])
    ev = a.wait(lambda q: q.state.get("evolution"), 20, "evolution scene")
    ok(f"evolution scene: {ev['name']} -> {ev['toName']} (dex {ev['fromDex']} -> {ev['toDex']})")
    assert ev["toDex"] == 350
    while True:
        ev = a.state.get("evolution")
        if not ev:
            break
        if ev["phase"] in ("done", "finished", "end"):
            a.send(t="evolution.close")
        else:
            a.send(t="evolution.answer", proceed=True)
        time.sleep(1.0)
        if any(m["dex"] == 350 for m in a.state.get("party") or []) and not a.state.get("evolution"):
            break
        if any(m["dex"] == 350 for m in a.state.get("party") or []):
            a.send(t="evolution.close")
    a.wait(lambda q: any(m["dex"] == 350 for m in q.state.get("party") or []), 30, "Milotic in party")
    ok("Feebas evolved into Milotic")


def run_berry(a):
    print("== berry tree (Route 104 soil at 35,6: plant, water, grow, pick)")
    command(a, "/contestdev kit", "added")
    goto(a, "route104 35 7 up")
    a.send(t="berry.trees")  # the browser asks on every map change
    a.wait(lambda q: (docs(q).get("berryTrees") or {}).get("map") == "Route104", 15, "map trees")
    tree = lambda q: next(t for t in docs(q)["berryTrees"]["trees"] if t["tree"] == 9)
    if tree(a)["stage"] != 0:
        a.send(t="a")
        answer_dialogs(a, lambda q: tree(q)["stage"] == 0, 30, log=True)
    before, _ = bag_qty(a, "Cheri Berry")
    a.send(t="a")
    answer_dialogs(a, lambda q: "berryChoose" in docs(q), 30, log=True)
    item = next(b for b in docs(a)["berryChoose"]["berries"] if b["name"] == "CHERI")["item"]
    a.send(t="berryChoose.pick", item=item)
    answer_dialogs(a, lambda q: tree(q)["stage"] == 1 and dialog(q) is None, 30, log=True)
    ok(f"planted a CHERI BERRY: {tree(a)}")
    for stage in range(1, 5):
        a.send(t="a")
        answer_dialogs(a, lambda q: tree(q)["watered"] >= stage and dialog(q) is None, 30, log=True)
        command(a, "/contestdev grow 3", "grew")
        a.wait(lambda q: tree(q)["stage"] == stage + 1, 10, f"stage {stage + 1}")
        print(f"      watered and grew: {tree(a)}")
    t = tree(a)
    assert t["stage"] == 5, t
    ok(f"berries: {t['yield']} (watered {t['watered']} times)")
    a.send(t="a")
    answer_dialogs(a, lambda q: tree(q)["stage"] == 0 and dialog(q) is None, 30, log=True)
    after, _ = bag_qty(a, "Cheri Berry")
    ok(f"picked: Cheri Berries {before - 1} -> {after}")
    assert after == before - 1 + t["yield"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ws", required=True)
    ap.add_argument("--a", required=True)
    ap.add_argument("--b", required=True)
    ap.add_argument("--only", default="contest")
    args = ap.parse_args()
    ua, pa, na = args.a.split(":")
    ub, pb, nb = args.b.split(":")
    global B_LABEL
    B_LABEL = nb
    a = Player(args.ws, na)
    b = Player(args.ws, nb)
    login(a, ua, pa, na)
    login(b, ub, pb, nb)
    for p in (a, b):
        if not p.state.get("party"):
            command(p, "/givemon 4 20")
            p.wait(lambda q: q.state.get("party"), 8, "party")
    for part in args.only.split(","):
        {"contest": lambda: run_contest(a, b), "blender": lambda: run_blender(a),
         "feebas": lambda: run_feebas(a), "link": lambda: run_blender_link(a, b), "berry": lambda: run_berry(a)}[part]()
    print("ALL OK")


if __name__ == "__main__":
    main()
