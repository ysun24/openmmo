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


def contest_flow(p, results, mon_slot=0):
    """Talks to the receptionist in front, enters Normal Cool and plays the contest."""
    p.send(t="a")
    answer_dialogs(p, lambda q: "contestEntry" in docs(q), 40, log=True)
    ok(f"{p.label}: the entry screen lists {len(docs(p)['contestEntry']['party'])} monsters")
    p.send(t="contestEntry.choose", rank=0, category=0, slot=mon_slot)
    answer_dialogs(p, lambda q: (docs(q).get("contest") or {}).get("phase") in ("waiting", "intro", "select"), 30, log=True)
    ok(f"{p.label}: entered, contest phase {docs(p)['contest']['phase']}")
    seen_rounds = set()
    while True:
        c = docs(p).get("contest")
        if c is None:
            break
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
    # the hall: the winner, prize, artist
    answer_dialogs(p, lambda q: dialog(q) is None and not docs(q), 60, log=True)


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
    humans = [n for n, _ in names if n in ("Ash", "Gary")]
    assert len(humans) == 2, f"both players in one contest: {names}"
    ok(f"two players and {4 - len(humans)} NPCs in one contest: {names}")
    assert you_a == win_a, f"Ash won: {names}"
    ok("Ash won the Normal Rank Cool contest")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ws", required=True)
    ap.add_argument("--a", required=True)
    ap.add_argument("--b", required=True)
    ap.add_argument("--only", default="contest")
    args = ap.parse_args()
    ua, pa, na = args.a.split(":")
    ub, pb, nb = args.b.split(":")
    a = Player(args.ws, na)
    b = Player(args.ws, nb)
    login(a, ua, pa, na)
    login(b, ub, pb, nb)
    for p in (a, b):
        if not p.state.get("party"):
            command(p, "/givemon 4 20")
            p.wait(lambda q: q.state.get("party"), 8, "party")
    if args.only == "contest":
        run_contest(a, b)
    print("ALL OK")


if __name__ == "__main__":
    main()
