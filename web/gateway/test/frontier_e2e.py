#!/usr/bin/env python3
"""r5-frontier: drives the battle facilities through the gateway like a browser would.

    python3 web/gateway/test/frontier_e2e.py --ws ws://127.0.0.1:8590/ws \
        --a yicheng:pikachu123:Ash --b friend:bulbasaur123:Gary [--only tower,factory,...]

Every facility screen comes in as state.world.frontier and is answered with {t:'frontier', ...}.
Battles are fought with the first move that has pp. Ends with ALL OK.
"""
import argparse
import os
import sys
import threading
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from e2e import Player, login, command, ok  # noqa: E402


def fr(p):
    return p.world.get("frontier") or {}


def screen(p):
    return fr(p).get("screen") or {}


def wait_screen(p, kinds, timeout=60, after=-1):
    def pred(q):
        f = fr(q)
        if not f.get("open"):
            return None
        s = f.get("screen") or {}
        if f.get("seq", -1) > after and s.get("kind") in kinds:
            return f
        return None
    return p.wait(pred, timeout, f"frontier screen {kinds}")


def answer(p, **kw):
    f = fr(p)
    p.send(t="frontier", a="answer", seq=f["seq"], **kw)


def fight(p, max_turns=200):
    """Fights the running battle (any phase) until it has a result, then closes it."""
    p.wait(lambda q: q.battle() and q.battle().get("phase"), 30, "battle start")
    last = None
    since = time.time()
    retry = 0
    for _ in range(max_turns * 3):
        b = p.battle()
        if not b:
            return None
        if b.get("result"):
            break
        if b.get("learn"):
            p.send(t="battle.learn", slot=None)
        key = (b.get("phase"), b.get("turn"), tuple(b.get("chosen") or []), b.get("choosingPos"))
        # The same screen for a while (a refused move, e.g. no PP / disabled): answer it again,
        # trying the next usable move.
        if key == last and time.time() - since > 4:
            last = None
            retry += 1
        if key != last:
            since = time.time()
            if b["phase"] == "FORCED_SWITCH":
                active = set(b.get("actives") or [])
                slot = next((i for i, m in enumerate(b["party"]) if m["hp"] > 0 and i not in active), None)
                if slot is not None:
                    p.send(t="battle.switch", slot=slot)
            elif b["phase"] == "ACTION":
                me = b.get("me") or {}
                if b.get("mine") and isinstance(b.get("choosingPos"), int):
                    me = b["mine"][b["choosingPos"]] or me
                moves = me.get("moves") or []
                usable = [i for i, m in enumerate(moves) if m["pp"] > 0]
                order = sorted(usable, key=lambda i: -(moves[i].get("power") or 0)) or [0]
                idx = order[retry % len(order)]
                p.send(t="battle.fight", move=idx)
            last = key
        time.sleep(0.25)
    p.wait(lambda q: (q.battle() or {}).get("result"), 60, "battle result")
    res = p.battle()["result"]
    time.sleep(0.5)
    p.send(t="battle.close")
    p.wait(lambda q: q.state.get("battle") is None, 15, "battle closed")
    return res


def drive(p, picks_for, stop_kinds=("message",), max_steps=400, on_screen=None):
    """Answers screens until a message whose text satisfies stop or the desk closes."""
    log = []
    seq = -1
    # The desk opens with a new screen (an earlier desk may still read {"open": false}).
    p.wait(lambda q: fr(q).get("open"), 30, "desk open")
    for _ in range(max_steps):
        f = p.wait(lambda q: (fr(q).get("open") is False and "closed") or
                   (fr(q).get("seq", -1) > seq and fr(q)), 120, "next frontier screen")
        if f == "closed":
            return log
        seq = f["seq"]
        s = f["screen"]
        k = s.get("kind")
        log.append((k, s.get("text", "")))
        if on_screen:
            on_screen(p, s)
        if k == "battle":
            res = fight(p)
            log.append(("result", res))
            continue
        if f.get("answer") is False:
            continue
        r = picks_for(s)
        if r is None:
            return log
        answer(p, **r)
    return log


def progressed(log):
    """A battle was fought: a result, or a later screen showing the streak went up."""
    if any(k == "result" for k, _ in log):
        return True
    return any(k in ("between", "doors", "pyramid", "dome") and ("streak: 1" in t.lower() or "battle 2" in t.lower() or "room 2" in t.lower() or "round 2" in t.lower()) for k, t in log) or \
        any(k == "message" and ("lost" in t or "won" in t or "roof" in t) for k, t in log)


def team_picks(s):
    party = s.get("party") or []
    ok_slots = [m["slot"] for m in sorted(party, key=lambda m: -m["level"]) if m.get("ok")]
    return {"slots": ok_slots[: s.get("count", 3)]}


def menus(choice_order):
    def f(s):
        k = s["kind"]
        if k == "menu":
            ids = [o["id"] for o in s["options"]]
            for c in choice_order:
                if c in ids:
                    return {"v": c}
            return {"v": ids[0]}
        if k == "team":
            return team_picks(s)
        if k == "between":
            return {"v": "continue"}
        if k == "message":
            return {}
        if k == "rental":
            return {"picks": [0, 1, 2]}
        if k == "swap":
            return {"mine": 0, "theirs": 0}
        if k == "dome":
            return {"picks": [0, 1]}
        if k == "doors":
            return {"door": 1}
        if k == "shop":
            return {"v": "cancel"}
        if k == "card":
            return {"v": "cancel"}
        return {}
    return f


def pyramid_picks():
    visited = set()

    def f(s):
        if s["kind"] != "pyramid":
            return menus(["challenge", "50"])(s)
        at = s["at"]
        visited.add(at)
        grid = s["grid"]
        adj = [c for c in grid if abs(c["i"] % 4 - at % 4) + abs(c["i"] // 4 - at // 4) == 1]
        exit_ = next((c for c in adj if c["kind"] == "exit"), None)
        if exit_:
            return {"v": "move", "to": exit_["i"]}
        fresh = [c for c in adj if c["i"] not in visited]
        pick = (fresh or adj)[int(time.time() * 1000) % len(fresh or adj)]
        return {"v": "move", "to": pick["i"]}
    return f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ws", required=True)
    ap.add_argument("--a", required=True)
    ap.add_argument("--b", required=True)
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    only = set(filter(None, args.only.split(",")))
    want = lambda k: not only or k in only

    ua, pa, na = args.a.split(":")
    A = Player(args.ws, na)
    login(A, ua, pa, na)
    # A strong level 50 team: Metagross, Salamence, Tyranitar, Blaziken, Gardevoir, Swampert.
    if len(A.state.get("party") or []) < 6:
        for dex in (376, 373, 248, 257, 282, 260):
            command(A, f"/givemon {dex} 50")
            time.sleep(0.4)
    A.wait(lambda q: len(q.state.get("party") or []) >= 4, 15, "party")
    ok(f"{A.label}: party {[(m['name'], m['level']) for m in A.state['party']]}")

    def run(desk, picks, label, check):
        command(A, f"/frontier {desk}")
        log = drive(A, picks)
        battles = [r for k, r in log if k == "result"]
        texts = [t for k, t in log if k == "message"]
        print(f"      {label}: battles {battles}; last: {texts[-1:] }", flush=True)
        check(log, battles, texts)
        ok(f"{label}")
        return log

    if want("tower"):
        run("tower:singles", menus(["challenge", "50"]), "Battle Tower singles round of 7",
            lambda log, b, t: (len(b) == 7 and all(r == "VICTORY" for r in b) and "won the challenge" in t[-1]) or (_ for _ in ()).throw(AssertionError(log[-5:])))
    if want("factory"):
        for attempt in range(8):
            log = run("factory:singles", menus(["challenge", "50"]), f"Battle Factory rental run (try {attempt + 1})",
                      lambda log, b, t: progressed(log) or (_ for _ in ()).throw(AssertionError(log)))
            if any(k == "swap" for k, _ in log):
                ok("Battle Factory: won a battle and swapped a rental")
                break
        else:
            raise AssertionError("no factory swap in 8 tries")
    if want("palace"):
        run("palace:singles", menus(["challenge", "50"]), "Battle Palace (auto moves)",
            lambda log, b, t: progressed(log) or (_ for _ in ()).throw(AssertionError(log)))
    if want("arena"):
        run("arena", menus(["challenge", "50"]), "Battle Arena (3-turn judging)",
            lambda log, b, t: progressed(log) or (_ for _ in ()).throw(AssertionError(log)))
    if want("dome"):
        run("dome:singles", menus(["challenge", "50"]), "Battle Dome tournament",
            lambda log, b, t: progressed(log) or (_ for _ in ()).throw(AssertionError(log)))
    if want("pike"):
        run("pike", menus(["challenge", "50"]), "Battle Pike rooms",
            lambda log, b, t: any(k == "doors" for k, _ in log) or (_ for _ in ()).throw(AssertionError(log)))
    if want("pyramid"):
        run("pyramid", pyramid_picks(), "Battle Pyramid floors",
            lambda log, b, t: any(k == "pyramid" for k, _ in log) or (_ for _ in ()).throw(AssertionError(log)))
    if want("ttower"):
        run("ttower", menus(["single"]), "Trainer Tower run",
            lambda log, b, t: progressed(log) or (_ for _ in ()).throw(AssertionError(log)))
    if want("hill"):
        run("hill", menus(["normal"]), "Trainer Hill run",
            lambda log, b, t: progressed(log) or (_ for _ in ()).throw(AssertionError(log)))
    if want("tents"):
        for t in ("verdanturf", "fallarbor", "slateport"):
            run("tent:" + t, menus(["challenge"]), f"{t} tent",
                lambda log, b, tx: progressed(log) or (_ for _ in ()).throw(AssertionError(log)))
    if want("exchange"):
        command(A, "/frontier bp 100")
        time.sleep(0.5)
        command(A, "/frontier exchange:HoldItems")
        f = wait_screen(A, {"shop"})
        bp0 = f["bp"]
        answer(A, buy=0)
        f2 = wait_screen(A, {"message"}, after=f["seq"])
        answer(A)
        f3 = wait_screen(A, {"shop"}, after=f2["seq"])
        assert f3["bp"] == bp0 - 48, (bp0, f3["bp"])
        answer(A, v="cancel")
        ok(f"exchange: bought {f['screen']['items'][0]['name']} for 48 BP ({bp0} -> {f3['bp']})")
    if want("rest"):
        import subprocess

        def rest_db():
            db = os.environ.get("OPENMMO_GAME_DB_CONTAINER", "openmmo-game-db")
            out = subprocess.run(
                ["docker", "exec", db, "psql", "-At", "-U", "openmmo_game_user", "-d", "openmmo_game_db",
                 "-c", "select data::jsonb->'rest'->>'desk', data::jsonb->'rest'->>'step' from frontier_records "
                       "where data::jsonb ? 'rest'"], capture_output=True, text=True)
            return out.stdout.strip()

        def rest_run(desk, label, reach, at_rest, resumed):
            """Answers screens until `reach(screen)`, then REST; the desk again resumes it."""
            command(A, f"/frontier {desk}")
            picks = menus(["challenge", "50"])
            state = {}

            def f(s):
                if reach(s):
                    state["at"] = at_rest(s)
                    return {"v": "rest"}
                return picks(s)
            log = drive(A, f)
            texts = [t for k, t in log if k == "message"]
            assert any("has been saved" in t for t in texts), log[-4:]
            row = rest_db()
            assert row.startswith(desk), row
            ok(f"{label}: REST at {state['at']} saved to the database ({row})")
            command(A, f"/frontier {desk}")
            seen = {}

            def g(s):
                if s["kind"] in ("between", "doors", "pyramid"):
                    seen["s"] = s
                    return {"v": "retire"}
                return picks(s)
            log = drive(A, g)
            texts = [t for k, t in log if k == "message"]
            assert any("waiting for you" in t for t in texts), log[:3]
            got = resumed(seen.get("s") or {})
            assert got == state["at"], (got, state["at"], seen)
            assert not rest_db(), "resuming cleared the paused challenge"
            ok(f"{label}: resumed from the attendant at {got}")

        rest_run("tower:singles", "Battle Tower",
                 lambda s: s["kind"] == "between" and "Battle 2 of 7" in s.get("text", ""),
                 lambda s: s["text"].split(".")[0], lambda s: (s.get("text") or "").split(".")[0])
        rest_run("pike", "Battle Pike",
                 lambda s: s["kind"] == "doors" and s.get("room") == 2,
                 lambda s: (s["room"], [m.get("hp") for m in s.get("team") or []]),
                 lambda s: (s.get("room"), [m.get("hp") for m in s.get("team") or []]))
        moved = {"n": 0}

        def pyr_reach(s):
            if s["kind"] != "pyramid":
                return False
            moved["n"] += 1
            return moved["n"] >= 3

        pyr_moves = pyramid_picks()

        def pyr_drive(s):
            return pyr_moves(s)
        # pyramid: two moves, then REST on the third screen of the floor
        command(A, "/frontier pyramid")
        state = {}

        def pf(s):
            if pyr_reach(s):
                state["at"] = (s["floor"], s["at"], s["light"], [c["kind"] for c in s["grid"]])
                return {"v": "rest"}
            return pyr_drive(s)
        log = drive(A, pf)
        assert any("has been saved" in t for k, t in log if k == "message"), log[-4:]
        row = rest_db()
        assert row.startswith("pyramid"), row
        ok(f"Battle Pyramid: REST on floor {state['at'][0]} square {state['at'][1]} saved ({row})")
        command(A, "/frontier pyramid")
        seen = {}

        def pg(s):
            if s["kind"] == "pyramid":
                seen["s"] = s
                return {"v": "retire"}
            return menus(["challenge", "50"])(s)
        log = drive(A, pg)
        s2 = seen["s"]
        got = (s2["floor"], s2["at"], s2["light"], [c["kind"] for c in s2["grid"]])
        assert got == state["at"], (got, state["at"])
        ok(f"Battle Pyramid: resumed on floor {got[0]} square {got[1]} with the same squares and light")

    if want("multi"):
        ub, pb, nb = args.b.split(":")
        B = Player(args.ws, nb)
        login(B, ub, pb, nb)
        if len(B.state.get("party") or []) < 2:
            for dex in (376, 373):
                command(B, f"/givemon {dex} 50")
                time.sleep(0.4)
        B.wait(lambda q: len(q.state.get("party") or []) >= 2, 15, "B party")
        command(A, "/frontier tower:linkmultis")
        drive_a = {}

        def host():
            drive_a["log"] = drive(A, menus(["challenge", "50", "host"]))
        th = threading.Thread(target=host, daemon=True)
        th.start()
        A.wait(lambda q: screen(q).get("kind") == "wait", 60, "host waiting")
        command(B, "/frontier tower:linkmultis")
        blog = drive(B, menus(["challenge", "50"] + [f"join:{A.world.get('self')}"] + ["host"]))
        th.join(1800)
        battles = [r for k, r in drive_a.get("log", []) if k == "result"]
        bb = [r for k, r in blog if k == "result"]
        print(f"      link multis: host battles {battles}, partner battles {bb}")
        assert battles and bb, (drive_a.get("log"), blog)
        ok("Battle Tower link multi battle with two players")
    print("ALL OK")


if __name__ == "__main__":
    main()
