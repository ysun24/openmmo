#!/usr/bin/env python3
"""End-to-end test of the web gateway against running login/game servers.

Speaks PROTOCOL.md over a hand-written WebSocket client (stdlib only), with two accounts:

  python3 web/gateway/test/e2e.py --ws ws://127.0.0.1:8180/ws \
      --a yicheng:pikachu123:Ash --b friend:bulbasaur123:Gary

Both characters need the DEVELOPER permission for the slash commands (see REPORT.md).
Prints one line per check and a latency summary; exits non-zero on the first failure.
"""

import argparse
import base64
import json
import os
import socket
import statistics
import struct
import sys
import threading
import time
from urllib.parse import urlparse


class WsClient:
    """Minimal RFC 6455 client: text frames, masking, ping/close handling."""

    def __init__(self, url, origin=None):
        u = urlparse(url)
        self.sock = socket.create_connection((u.hostname, u.port or 80), timeout=10)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        key = base64.b64encode(os.urandom(16)).decode()
        host = f"{u.hostname}:{u.port or 80}"
        req = (
            f"GET {u.path or '/'} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
            f"Origin: {origin or 'http://' + host}\r\n\r\n"
        )
        self.sock.sendall(req.encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(1)
            if not chunk:
                raise IOError("closed during handshake")
            head += chunk
        status = head.split(b"\r\n", 1)[0]
        if b" 101 " not in status:
            raise IOError(f"handshake refused: {status!r}")
        self.sock.settimeout(None)
        self.lock = threading.Lock()

    def send(self, obj):
        data = json.dumps(obj).encode()
        hdr = bytearray([0x81])
        n = len(data)
        if n < 126:
            hdr.append(0x80 | n)
        elif n < 65536:
            hdr.append(0x80 | 126)
            hdr += struct.pack(">H", n)
        else:
            hdr.append(0x80 | 127)
            hdr += struct.pack(">Q", n)
        mask = os.urandom(4)
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        with self.lock:
            self.sock.sendall(bytes(hdr) + mask + payload)

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise EOFError
            buf += chunk
        return buf

    def recv(self):
        """Next text message as a dict, or None when the socket closed."""
        msg = b""
        while True:
            try:
                b1, b2 = self._recv_exact(2)
            except (EOFError, OSError):
                return None
            op = b1 & 0x0F
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._recv_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._recv_exact(8))[0]
            data = self._recv_exact(n)
            if op == 8:
                return None
            if op == 9:  # ping -> pong
                with self.lock:
                    self.sock.sendall(bytes([0x8A, 0x80 | len(data)]) + b"\0\0\0\0" + data)
                continue
            msg += data
            if b1 & 0x80:
                return json.loads(msg)

    def close(self):
        try:
            with self.lock:
                self.sock.sendall(bytes([0x88, 0x80]) + b"\0\0\0\0")
        except OSError:
            pass
        self.sock.close()


class Player:
    """One browser: keeps the merged state like the browser would."""

    def __init__(self, url, label):
        self.label = label
        self.ws = WsClient(url)
        self.state = {}
        self.messages = []  # (time, msg) for every non-state message
        self.state_times = []  # (time, ackSeq) per state message carrying world
        self.cond = threading.Condition()
        self.closed = False
        self.state_count = 0
        threading.Thread(target=self._reader, daemon=True).start()
        threading.Thread(target=self._pinger, daemon=True).start()

    def _reader(self):
        while True:
            m = self.ws.recv()
            now = time.perf_counter()
            with self.cond:
                if m is None:
                    self.closed = True
                    self.cond.notify_all()
                    return
                if m.get("t") == "state":
                    self.state_count += 1
                    for k, v in m.items():
                        if k not in ("t", "seq"):
                            self.state[k] = v
                    if "world" in m and m["world"]:
                        self.state_times.append((now, m["world"].get("ackSeq")))
                else:
                    self.messages.append((now, m))
                self.cond.notify_all()

    def _pinger(self):
        n = 0
        while not self.closed:
            time.sleep(5)
            n += 1
            try:
                self.ws.send({"t": "ping", "n": n})
            except OSError:
                return

    def send(self, **kw):
        self.ws.send(kw)

    def wait(self, pred, timeout=10.0, what="condition"):
        end = time.time() + timeout
        with self.cond:
            while True:
                try:
                    v = pred(self)
                except (KeyError, TypeError, IndexError):
                    v = None
                if v:
                    return v
                left = end - time.time()
                if left <= 0 or self.closed:
                    raise AssertionError(f"[{self.label}] timed out waiting for {what}")
                self.cond.wait(left)

    def message(self, t, since=0):
        for ts, m in self.messages[since:]:
            if m.get("t") == t:
                return m
        return None

    # helpers over the merged state
    @property
    def world(self):
        return self.state.get("world") or {}

    def self_entity(self):
        w = self.world
        return next((e for e in w.get("entities", []) if e["id"] == w.get("self")), None)

    def battle(self):
        return self.state.get("battle")

    def chat_texts(self):
        return [f'{c["sender"]}: {c["text"]}' for c in self.state.get("chat") or []]


def ok(msg):
    print(f"  OK  {msg}", flush=True)


def login(p, user, pw, name):
    since = len(p.messages)
    p.send(t="login", user=user, **{"pass": pw}, name=name)
    m = p.wait(lambda q: q.message("login", since), 30, "login reply")
    assert m["ok"], f"login failed: {m}"
    p.char_name = m.get("name")
    p.wait(
        lambda q: all(k in q.state for k in
                      ("world", "chat", "ui", "party", "battle", "invite", "trade", "market")),
        10, "full first state")
    ok(f"{p.label}: login as {m['name']} selfId={m['selfId']}, first state has every section "
       f"(map={p.world['map']}, pos={(p.self_entity() or {}).get('x')},{(p.self_entity() or {}).get('y')})")


def command(p, text, expect=None, timeout=5):
    """A slash command; waits for the chat reply containing `expect`."""
    before = len(p.chat_texts())
    p.send(t="say", text=text)
    if expect:
        p.wait(lambda q: any(expect in c for c in q.chat_texts()[before - 1 if before else 0:]),
               timeout, f"reply to {text}")


def ensure_party(p, dex, level):
    if not p.state.get("party"):
        command(p, f"/givemon {dex} {level}")
        p.wait(lambda q: q.state.get("party"), 8, "party after /givemon")
    ok(f"{p.label}: party {[(m['name'], m['level'], m['hp'], m['maxHp']) for m in p.state['party']]}")


def walk(p, seq_start, dirs):
    """Steps with prediction-style seq; returns step->ackSeq latencies in ms."""
    lat = []
    seq = seq_start
    for d in dirs:
        seq += 1
        before = p.self_entity()
        t0 = time.perf_counter()
        p.send(t="step", dir=d, run=False, seq=seq)
        p.wait(lambda q: (q.world.get("ackSeq") or 0) >= seq, 5, f"ackSeq {seq}")
        with p.cond:
            t1 = next(ts for ts, a in p.state_times if a is not None and a >= seq)
        lat.append((t1 - t0) * 1000)
        after = p.self_entity()
        moved = (before["x"], before["y"]) != (after["x"], after["y"])
        print(f"      step {seq} {d}: ({before['x']},{before['y']}) -> ({after['x']},{after['y']})"
              f" {'moved' if moved else 'blocked'} facing={after['facing']} moving={after['moving']}"
              f" ack {lat[-1]:.2f} ms")
        time.sleep(0.27)  # one walking step at the GBA's pace
    return seq, lat


def fight_until_over(players, label, max_turns=60):
    """Every player in `players` fights move 0 (or the first move with pp) until a result."""
    for turn in range(max_turns):
        if all((p.battle() or {}).get("result") for p in players):
            break
        for p in players:
            b = p.battle()
            if b and b.get("learn"):
                p.send(t="battle.learn", slot=None)
            if b and b["phase"] == "FORCED_SWITCH":
                slot = next((i for i, m in enumerate(b["party"]) if m["hp"] > 0), None)
                if slot is not None:
                    p.send(t="battle.switch", slot=slot)
            if b and b["phase"] == "ACTION" and not b.get("result"):
                moves = b["me"]["moves"]
                idx = next((i for i, m in enumerate(moves) if m["pp"] > 0), 0)
                p.send(t="battle.fight", move=idx)
        time.sleep(0.4)
    for p in players:
        p.wait(lambda q: (q.battle() or {}).get("result"), 20, f"{label} result")
        b = p.battle()
        ok(f"{p.label}: {label} over, result={b['result']} me={b['me']['name']} "
           f"{b['me']['hp']}/{b['me']['maxHp']} foe={b['foe'] and b['foe']['name']} "
           f"log tail={b['log'][-3:]}")
        p.send(t="battle.close")
        p.wait(lambda q: q.state.get("battle") is None, 5, "battle closed")


def warp(A, seq):
    """Takes the house stairs once (2F <-> 1F); returns the last step seq."""
    start_map = A.world["map"]
    # The house's stairs (10,2) on both floors: stand beside them, then walk into them.
    on_2f = start_map["name"].endswith("2F")
    target, push = ((11, 2), "left") if on_2f else ((9, 2), "right")
    blocked = None
    pushing = False
    for _ in range(60):
        if A.world["map"] != start_map:
            break
        me = A.self_entity()
        dx, dy = target[0] - me["x"], target[1] - me["y"]
        pushing = pushing or (dx, dy) == (0, 0)
        if pushing:
            order = [push]
        else:
            order = ([("right" if dx > 0 else "left")] if dx else []) + \
                    ([("down" if dy > 0 else "up")] if dy else [])
            if blocked == order[0] and len(order) > 1:
                order.reverse()
        before = (me["x"], me["y"])
        seq += 1
        A.send(t="step", dir=order[0], run=False, seq=seq)
        A.wait(lambda q: q.world.get("ackSeq", 0) >= seq, 5, "warp step ack")
        time.sleep(0.35)
        after = A.self_entity() or {}
        blocked = order[0] if (after.get("x"), after.get("y")) == before else None
    A.wait(lambda q: q.world["map"] != start_map and q.world["inWorld"], 10, "warp")
    ok(f"{A.label} warped {start_map['name']} -> {A.world['map']['name']}")
    time.sleep(0.5)
    return seq


# ---------------------------------------------------------------- r3-social helpers

def social(p):
    return p.state.get("social") or {}


def chat_lines(p):
    return p.state.get("chat") or []


def errors_since(p, since):
    return [m["msg"] for _, m in p.messages[since:] if m.get("t") == "error"]


def tp(p, where, x, y, facing="down"):
    """Developer warp (/tp, r3-social) and wait until the map changed and the self tile matches."""
    region, bank, mp = (int(v) for v in where.split(":"))
    p.send(t="say", text=f"/tp {where} {x} {y} {facing}")
    p.wait(lambda q: (q.world.get("map") or {}).get("bank") == bank
           and q.world["map"]["map"] == mp and q.world["map"]["region"] == region
           and q.world.get("inWorld") and (q.self_entity() or {}).get("x") == x
           and (q.self_entity() or {}).get("y") == y, 12, f"/tp {where}")
    time.sleep(0.8)


def leave_guild(p):
    g = social(p).get("guild") or {}
    if not g.get("inGuild"):
        return
    p.send(t="guild.disband" if g.get("myRank") == 5 else "guild.leave")
    p.wait(lambda q: not (social(q).get("guild") or {}).get("inGuild"), 8, "out of the guild")


def press_until(p, pred, what, tries=14):
    """Presses A (or picks choice 0) until pred holds; for npc dialogs that open a window."""
    for _ in range(tries):
        if pred(p):
            return
        d = (p.state.get("ui") or {}).get("dialog")
        if d and d.get("choices"):
            p.send(t="choose", index=0)
        else:
            p.send(t="a")
        time.sleep(0.7)
    p.wait(pred, 5, what)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ws", default="ws://127.0.0.1:8180/ws")
    ap.add_argument("--a", default="yicheng:pikachu123:Ash")
    ap.add_argument("--b", default="friend:bulbasaur123:Gary")
    ap.add_argument("--steps", type=int, default=20)
    args = ap.parse_args()
    ua, pa, na = args.a.split(":")
    ub, pb, nb = args.b.split(":")

    print("== transport")
    try:
        WsClient(args.ws, origin="http://evil.example")
        raise AssertionError("foreign origin was accepted")
    except IOError as e:
        ok(f"foreign Origin refused ({e})")
    probe = Player(args.ws, "probe")
    probe.send(t="nonsense")
    probe.ws.sock.sendall(bytes([0x81, 0x83]) + b"\0\0\0\0" + b"{x}")
    probe.send(t="step", dir="up", seq=1)
    probe.send(t="ping", n=7)
    probe.wait(lambda q: q.message("pong"), 5, "pong")
    errs = [m["msg"] for _, m in probe.messages if m["t"] == "error"]
    assert len(errs) == 3 and not probe.closed, errs
    ok(f"errors without closing: {errs}; pong={probe.message('pong')}")
    for i in range(6):
        probe.send(t="login", user="nobody", **{"pass": "wrong"}, name="x")
        probe.wait(lambda q: sum(1 for _, m in q.messages if m["t"] == "login") >= i + 1, 20,
                   f"login reply {i + 1}")
    replies = [m.get("error") for _, m in probe.messages if m["t"] == "login"]
    assert replies[-1] == "RATE_LIMITED", replies
    ok(f"bad logins: {replies}")
    probe.ws.close()

    print("== login")
    A = Player(args.ws, na)
    B = Player(args.ws, nb)
    login(A, ua, pa, na)
    login(B, ub, pb, nb)
    # A previous run may have ended elsewhere (or failed half way): start both in the house.
    for p, x in ((A, 6), (B, 7)):
        m = p.world["map"]
        if (m["region"], m["bank"], m["map"]) != (0, 4, 1) or (p.state.get("trade") or {}).get("phase") in (
                "OPEN", "REQUEST_SENT", "REQUEST_RECEIVED"):
            p.send(t="trade.cancel")
            p.send(t="battle.close")
            tp(p, "0:4:1", x, 6)
    A.wait(lambda q: any(e["kind"] == "player" and e["name"] == nb for e in q.world["entities"]),
           5, "other player visible")
    ok(f"{na} sees {nb} as entity: "
       f"{next(e for e in A.world['entities'] if e['name'] == nb)}")
    npcs = [e for e in A.world["entities"] if e["kind"] == "npc"]
    ok(f"{na} npcs on map: {[(e['gfx'], e['x'], e['y']) for e in npcs][:6]}")

    print("== walk")
    dirs = (["left", "left", "right", "right", "up", "down"] * 10)[: args.steps]
    seq, lat = walk(A, A.world.get("ackSeq") or 20_000, dirs)
    B.wait(lambda q: next((e for e in q.world["entities"] if e["name"] == na), None) is not None,
           5, "A on B's screen")
    ea = next(e for e in B.world["entities"] if e["name"] == na)
    me = A.self_entity()
    assert (ea["x"], ea["y"]) == (me["x"], me["y"]), (ea, me)
    ok(f"{nb} sees {na} at ({ea['x']},{ea['y']}) matching {na}'s own position")
    # A burst without waiting: the gateway coalesces, the last ack must still arrive.
    t0 = time.perf_counter()
    for d in ["left", "right"] * 4:
        seq += 1
        A.send(t="step", dir=d, run=True, seq=seq)
    A.wait(lambda q: q.world.get("ackSeq") == seq, 5, "burst ack")
    ok(f"burst of 8 run steps acked (ackSeq={seq}) in {(time.perf_counter() - t0) * 1000:.1f} ms")
    print(f"   step->ackSeq latency over {len(lat)} steps: min {min(lat):.2f} ms, "
          f"median {statistics.median(lat):.2f} ms, p90 {sorted(lat)[int(len(lat) * 0.9) - 1]:.2f} ms, "
          f"max {max(lat):.2f} ms")

    print("== chat")
    A.send(t="say", text="hello from the web gateway")
    B.wait(lambda q: f"{na}: hello from the web gateway" in q.chat_texts(), 5, "chat on B")
    ok(f"{nb} chat tail: {B.chat_texts()[-2:]}")

    print("== party")
    ensure_party(A, 4, 15)
    ensure_party(B, 1, 15)

    print("== wild battle")
    A.send(t="say", text="/testbattle 19 3")
    A.wait(lambda q: q.battle() and q.battle()["phase"] == "ACTION", 10, "battle ACTION")
    b = A.battle()
    ok(f"{na}: battle started wild={b['wild']} me={b['me']['name']} moves={b['me']['moves']} "
       f"foe={b['foe']}")
    fight_until_over([A], "wild battle")

    print("== duel")
    A.send(t="duel", name=nb)
    B.wait(lambda q: (q.state.get("invite") or {}).get("from") == na, 8, "duel invite")
    ok(f"{nb} invite: {B.state['invite']}")
    B.send(t="duel.answer", accept=True)
    for p in (A, B):
        p.wait(lambda q: q.battle() and q.battle()["phase"] == "ACTION", 10, "duel ACTION")
    ok(f"duel started: {na} vs {A.battle()['opponentName']}, {nb} vs {B.battle()['opponentName']}")
    fight_until_over([A, B], "duel")

    print("== trade")
    m0 = A.state["ui"]["money"] or 0
    command(A, "/givemoney 5000")
    A.wait(lambda q: q.state["ui"]["money"] == m0 + 5000, 5, "money after /givemoney")
    q0 = next((i["qty"] for i in B.state["ui"]["bag"] if i["item"] == 5017), 0)
    command(B, "/giveitem 5017 3")
    B.wait(lambda q: any(i["item"] == 5017 and i["qty"] == q0 + 3 for i in q.state["ui"]["bag"]),
           5, "potions in B's bag")
    a_pot0 = next((i["qty"] for i in A.state["ui"]["bag"] if i["item"] == 5017), 0)
    a_money0 = A.state["ui"]["money"]
    b_bag0 = {i["item"]: i["qty"] for i in B.state["ui"]["bag"]}
    A.send(t="trade.request", name=nb)
    B.wait(lambda q: (q.state.get("trade") or {}).get("phase") == "REQUEST_RECEIVED", 8, "trade request")
    B.send(t="trade.accept")
    for p in (A, B):
        p.wait(lambda q: (q.state.get("trade") or {}).get("phase") == "OPEN", 8, "trade open")
    A.send(t="trade.money", amount=700)
    B.send(t="trade.item", item=5017, qty=2)
    A.wait(lambda q: q.state["trade"]["theirs"]["items"] and q.state["trade"]["mine"]["money"] == 700,
           8, "offers visible")
    ok(f"{na} trade window: mine={A.state['trade']['mine']} theirs={A.state['trade']['theirs']}")
    for p in (A, B):
        p.send(t="trade.ready")
    for p in (A, B):
        p.wait(lambda q: q.state["trade"]["theirs"]["stage"] == "READY"
               and q.state["trade"]["mine"]["stage"] == "READY", 8, "both ready")
    for p in (A, B):
        p.send(t="trade.confirm")
    for p in (A, B):
        p.wait(lambda q: (q.state.get("trade") or {}).get("phase") in ("COMPLETED", "CLOSED"),
               10, "trade done")
    A.wait(lambda q: q.state["ui"]["money"] == a_money0 - 700, 5, "A money after trade")
    A.wait(lambda q: any(i["item"] == 5017 and i["qty"] == a_pot0 + 2 for i in q.state["ui"]["bag"]), 5,
           "A got potions")
    ok(f"trade done: {A.state['trade']}; {na} money {a_money0} -> {A.state['ui']['money']}, "
       f"{na} bag {A.state['ui']['bag']}; {nb} bag before {b_bag0}")

    print("== market")
    A.send(t="market.inventory")
    A.wait(lambda q: q.state["market"]["inventory"]["bag"], 5, "market inventory")
    A.send(t="market.listItem", item=5017, qty=1, price=111)
    A.wait(lambda q: any(r["price"] == 111 for r in q.state["market"]["mine"]), 8, "own listing")
    listing = next(r for r in A.state["market"]["mine"] if r["price"] == 111)
    ok(f"{na} listed: {listing}")
    B.send(t="market.search", kind="item", id=5017, sort="price", page=0)
    B.wait(lambda q: any(r["id"] == listing["id"] for r in q.state["market"]["browse"]), 8, "search hit")
    ok(f"{nb} search: total={B.state['market']['browseTotal']} first={B.state['market']['browse'][0]}")
    b_money0 = B.state["market"]["money"]
    assert b_money0 == B.state["ui"]["money"], (b_money0, B.state["ui"]["money"])
    B.send(t="market.buy", id=listing["id"])
    B.wait(lambda q: any("BUY ok" in l for l in q.state["market"]["log"]), 8, "buy ok")
    B.wait(lambda q: q.state["market"]["money"] == b_money0 - 111, 5, "B money after buy")
    A.wait(lambda q: not any(r["id"] == listing["id"] for r in q.state["market"]["mine"]), 8,
           "listing gone from seller")
    ok(f"{nb} bought: log={B.state['market']['log'][-1]!r} money {b_money0} -> "
       f"{B.state['market']['money']}; {na} log={A.state['market']['log'][-1:]}")

    print("== warp + npcs")
    seq = warp(A, seq)
    A.wait(lambda q: any(e["kind"] == "npc" for e in q.world["entities"]), 5, "npc on 1F")
    npcs = [e for e in A.world["entities"] if e["kind"] == "npc"]
    ok(f"{na} on {A.world['map']['name']}; npcs: "
       f"{[(e['gfx'], e['x'], e['y'], e['facing']) for e in npcs]}")
    seq = warp(A, seq)
    ok(f"{na} back on {A.world['map']['name']}")


    # ================================================================ r3-social
    print("== chat channels")
    since = len(A.messages)
    A.send(t="say", text="hello world channel", channel="global")
    B.wait(lambda q: any(c.get("channel") == "global" and c["text"] == "hello world channel"
                         for c in chat_lines(q)), 5, "global chat on B")
    A.send(t="say", text="hello trade channel", channel="trade")
    B.wait(lambda q: any(c.get("channel") == "trade" and c["sender"] == na for c in chat_lines(q)), 5,
           "trade chat on B")
    A.send(t="say", text="psst", channel="whisper", to=nb)
    B.wait(lambda q: any(c.get("channel") == "whisper" and c.get("to") == nb and c["sender"] == na
                         for c in chat_lines(q)), 5, "whisper on B")
    A.wait(lambda q: any(c.get("channel") == "whisper" and c.get("to") == nb for c in chat_lines(q)), 5,
           "whisper echo on A")
    A.send(t="say", text="x", channel="whisper", to="NobodyHere")
    A.wait(lambda q: any(c.get("channel") == "system" and "not online" in c["text"] for c in chat_lines(q)),
           5, "whisper to offline refused")
    A.send(t="say", text="y" * 230, channel="global")
    A.wait(lambda q: any("limited to 200" in c["text"] for c in chat_lines(q)), 5, "length limit")
    A.send(t="say", text="z", channel="shout")
    A.wait(lambda q: "bad channel: shout" in errors_since(q, since), 5, "bad channel error")
    ok(f"channels: {[(c.get('channel'), c['sender'], c.get('to'), c['text'][:20]) for c in chat_lines(B)[-4:]]}")

    print("== friends + profile + block")
    for p, other in ((A, nb), (B, na)):
        p.send(t="social.refresh")
        p.wait(lambda q: "social" in q.state, 5, "social section")
        if any(f["name"] == other for f in social(p)["friends"]):
            p.send(t="friends.remove", name=other)
            p.wait(lambda q: not any(f["name"] == other for f in social(q)["friends"]), 5, "clean friends")
        if other in social(p)["blocked"]:
            p.send(t="unblock", name=other)
            p.wait(lambda q: other not in social(q)["blocked"], 5, "clean blocks")
    A.send(t="friends.add", name=nb)
    A.wait(lambda q: any(f["name"] == nb and not f["mutual"] for f in social(q)["friends"]),
           5, "friend request sent")
    B.wait(lambda q: any(f"{na} wants to be your friend" in c["text"] for c in chat_lines(q)), 5,
           "friend request notice on B")
    B.wait(lambda q: na in (social(q).get("requests") or []), 5, "request listed on B")
    # Integration: the accept step; only then are they mutual friends who see each other.
    B.send(t="friends.accept", name=na)
    A.send(t="social.refresh")
    A.wait(lambda q: any(f["name"] == nb and f["mutual"] and f["online"] and f["location"]
                         for f in social(q)["friends"]), 5, "mutual, online with location")
    ok(f"{na} friends: {social(A)['friends']}")
    seq0 = social(A).get("profileSeq", 0)
    A.send(t="profile", name=nb)
    A.wait(lambda q: social(q).get("profileSeq", 0) > seq0, 5, "profile")
    prof = social(A)["profile"]
    assert prof["name"] == nb and prof["online"] and prof["friend"] and prof["party"], prof
    ok(f"profile of {nb}: {prof}")
    B.send(t="block", name=na)
    B.wait(lambda q: na in social(q)["blocked"], 5, "blocked")
    A.send(t="say", text="can you hear me", channel="global")
    A.send(t="say", text="psst again", channel="whisper", to=nb)
    A.wait(lambda q: any("not accepting messages" in c["text"] for c in chat_lines(q)), 5, "whisper blocked")
    time.sleep(0.5)
    assert not any(c["text"] == "can you hear me" for c in chat_lines(B)), chat_lines(B)[-3:]
    ok(f"{nb} blocked {na}: global hidden, whisper refused")
    B.send(t="unblock", name=na)
    B.wait(lambda q: na not in social(q)["blocked"], 5, "unblocked")
    B.send(t="friends.add", name=na)
    B.wait(lambda q: any(f["name"] == na for f in social(q)["friends"]), 5, "B re-adds A")

    print("== guild")
    for p in (A, B):
        leave_guild(p)
    command(A, "/givemoney 20000")
    gname = f"E2E Guild {int(time.time()) % 100000}"
    A.send(t="guild.create", name=gname, tag="E2E")
    A.wait(lambda q: (social(q).get("guild") or {}).get("name") == gname, 8, "guild created")
    A.send(t="guild.invite", name=nb)
    B.wait(lambda q: (social(q).get("invite") or {}).get("guild") == gname, 8, "guild invite")
    ok(f"{nb} invite: {social(B)['invite']}")
    B.send(t="guild.answer", accept=True)
    B.wait(lambda q: (social(q).get("guild") or {}).get("name") == gname, 8, "B joined")
    A.wait(lambda q: len(social(q)["guild"]["members"]) == 2, 8, "two members")
    A.send(t="guild.rank", name=nb, rank=2)
    B.wait(lambda q: social(q)["guild"]["myRankName"] == "Officer", 8, "B promoted")
    B.send(t="guild.motd", text="Meet at the Pokemon Center")
    A.wait(lambda q: social(q)["guild"]["motd"] == "Meet at the Pokemon Center", 8, "motd")
    B.send(t="say", text="guild hello", channel="guild")
    A.wait(lambda q: any(c.get("channel") == "guild" and c["text"] == "guild hello" for c in chat_lines(q)),
           5, "guild chat")
    ok(f"guild: {[(m['name'], m['rankName'], m['online']) for m in social(A)['guild']['members']]} "
       f"motd={social(A)['guild']['motd']!r} log={[l['text'] for l in social(A)['guild']['log'][:3]]}")
    A.send(t="guild.kick", name=nb)
    B.wait(lambda q: not social(q)["guild"]["inGuild"], 8, "B kicked")
    A.send(t="guild.invite", name=nb)
    B.wait(lambda q: (social(q).get("invite") or {}).get("guild") == gname, 8, "second invite")
    B.send(t="guild.answer", accept=False)
    B.wait(lambda q: social(q).get("invite") is None, 5, "invite declined")
    A.send(t="guild.disband")
    A.wait(lambda q: not social(q)["guild"]["inGuild"], 8, "disbanded")
    B.send(t="guild.leave")  # not in a guild: answered with a notice, not an error
    ok("guild kick / decline / disband / leave done")

    print("== pc + market clerk")
    tp(A, "0:5:4", 0, 3, "up")
    tp(B, "0:5:4", 4, 5)
    assert A.state["pc"]["here"], A.state["pc"]
    clerk = A.wait(lambda q: next((e for e in q.world["entities"] if e["kind"] == "npc" and e["gfx"] == 68
                                   and (e["x"], e["y"]) == (0, 2)), None), 5, "market clerk npc")
    ok(f"market clerk in Viridian PC: {clerk}")
    open0 = A.state["market"].get("openSeq", 0)
    A.send(t="a")
    A.wait(lambda q: q.state["market"].get("openSeq", 0) > open0, 5, "clerk opens market")
    A.wait(lambda q: q.state["market"].get("info"), 5, "market info")
    ok(f"clerk opened the market: info={A.state['market']['info']}")
    if len(B.state["party"]) < 2:
        command(B, "/givemon 19 5")
        B.wait(lambda q: len(q.state["party"]) >= 2, 8, "second mon for B")
    npc0 = len(B.state["pc"]["mons"])
    B.send(t="pc.deposit", slot=len(B.state["party"]) - 1)
    B.wait(lambda q: len(q.state["pc"]["mons"]) == npc0 + 1, 8, "deposit")
    ok(f"{nb} deposited, pc={[(m['slot'], m['name']) for m in B.state['pc']['mons']]}")
    B.send(t="pc.withdraw", slot=B.state["pc"]["mons"][-1]["slot"])
    B.wait(lambda q: len(q.state["pc"]["mons"]) == npc0, 8, "withdraw")
    B.send(t="pc.deposit", slot=len(B.state["party"]) - 1)
    B.wait(lambda q: len(q.state["pc"]["mons"]) == npc0 + 1, 8, "deposit again")
    pc_mon = B.state["pc"]["mons"][-1]

    print("== trade: pc monster, walk lock, history")
    a_party0 = len(A.state["party"])
    A.send(t="trade.request", name=nb)
    B.wait(lambda q: (q.state.get("trade") or {}).get("phase") == "REQUEST_RECEIVED", 8, "request")
    exp = B.state["trade"].get("expiresAt", 0) / 1000 - time.time()
    assert 50 < exp <= 61, exp
    ok(f"request lapses in {exp:.0f} s")
    B.send(t="trade.accept")
    for p in (A, B):
        p.wait(lambda q: (q.state.get("trade") or {}).get("phase") == "OPEN", 8, "trade open")
    before = A.self_entity()
    seq += 1
    A.send(t="step", dir="down", run=False, seq=seq)
    time.sleep(1.0)
    after = A.self_entity()
    assert (before["x"], before["y"]) == (after["x"], after["y"]), (before, after)
    ok(f"{na} cannot walk while the trade window is open (stays at {after['x']},{after['y']})")
    A.send(t="trade.mon", slot=0)
    B.wait(lambda q: q.state["trade"]["theirs"]["mons"], 5, "party mon offered")
    A.send(t="trade.mon", slot=0)
    B.wait(lambda q: not q.state["trade"]["theirs"]["mons"], 5, "party mon withdrawn")
    B.send(t="trade.pcMon", slot=pc_mon["slot"])
    A.wait(lambda q: q.state["trade"]["theirs"]["pcMons"], 5, "pc mon offered")
    A.send(t="trade.money", amount=10)
    B.wait(lambda q: q.state["trade"]["theirs"]["money"] == 10, 5, "money offered")
    ok(f"{na} sees PC offer: {A.state['trade']['theirs']['pcMons']}")
    for p in (A, B):
        p.send(t="trade.ready")
    for p in (A, B):
        p.wait(lambda q: q.state["trade"]["theirs"]["stage"] == "READY" and
               q.state["trade"]["mine"]["stage"] == "READY", 8, "both ready")
    for p in (A, B):
        p.send(t="trade.confirm")
    for p in (A, B):
        p.wait(lambda q: (q.state.get("trade") or {}).get("phase") == "COMPLETED", 10, "trade completed")
    A.wait(lambda q: len(q.state["party"]) + len(q.state["pc"]["mons"]) > a_party0, 8, "A got the mon")
    B.wait(lambda q: not any(m["id"] == pc_mon["id"] for m in q.state["pc"]["mons"]), 8, "left B's pc")
    A.send(t="trade.history")
    A.wait(lambda q: social(q).get("tradeHistory"), 5, "trade history")
    ok(f"trade history: {social(A)['tradeHistory'][:2]}")
    A.send(t="trade.request", name=nb)
    B.wait(lambda q: (q.state.get("trade") or {}).get("phase") == "REQUEST_RECEIVED", 8, "request 2")
    B.send(t="trade.decline")
    A.wait(lambda q: (q.state.get("trade") or {}).get("phase") == "CLOSED", 8, "declined")

    print("== market: fees, seller names, cancel, history, sales")
    A.send(t="market.info")
    A.wait(lambda q: q.state["market"].get("info"), 5, "info")
    info = A.state["market"]["info"]
    if len(A.state["party"]) < 2:
        command(A, "/givemon 16 5")
        A.wait(lambda q: len(q.state["party"]) >= 2, 8, "second mon for A")
    A.send(t="market.inventory")
    A.wait(lambda q: len(q.state["market"]["inventory"]["party"]) >= 2, 5, "inventory party")
    mon = A.state["market"]["inventory"]["party"][-1]
    money0 = A.state["ui"]["money"]
    price = 4321
    fee = max(info["minFee"], price * info["feePermille"] // 1000) if info["feePermille"] else 0
    A.send(t="market.listMon", slot=mon["slot"], price=price)
    A.wait(lambda q: any(r["price"] == price and r["kind"] == "mon" for r in q.state["market"]["mine"]), 8,
           "mon listed")
    A.wait(lambda q: q.state["ui"]["money"] == money0 - fee, 5, f"fee {fee} charged")
    listing = next(r for r in A.state["market"]["mine"] if r["price"] == price)
    ok(f"{na} listed {listing['name']} for {price}, fee {fee} (money {money0} -> {A.state['ui']['money']})")
    B.send(t="market.search", kind="mon", id=listing["dex"], sort="new", page=0)
    B.wait(lambda q: any(r["id"] == listing["id"] and r.get("seller") == na
                         for r in q.state["market"]["browse"]), 8, "seller name in browse")
    ok(f"{nb} sees seller: {next(r for r in B.state['market']['browse'] if r['id'] == listing['id'])}")
    B.send(t="market.buy", id=listing["id"])
    B.wait(lambda q: any("BUY ok" in l and listing["name"].upper() in l.upper()
                         for l in q.state["market"]["log"]), 8, "bought mon")
    A.send(t="market.sales")
    A.wait(lambda q: any(r["id"] == listing["id"] and r["buyer"] == nb
                         for r in (q.state["market"].get("sales") or [])), 8, "sales history")
    ok(f"{na} sales: {A.state['market']['sales'][:2]}")
    B.send(t="market.history", kind="mon", id=listing["dex"])
    B.wait(lambda q: (q.state["market"].get("history") or {}).get("id") == listing["dex"]
           and q.state["market"]["history"]["days"], 8, "price history")
    ok(f"price history: {B.state['market']['history']}")
    command(A, "/giveitem 5017 2")
    A.send(t="market.listItem", item=5017, qty=1, price=999)
    A.wait(lambda q: any(r["price"] == 999 for r in q.state["market"]["mine"]), 8, "item listed")
    item_listing = next(r for r in A.state["market"]["mine"] if r["price"] == 999)
    A.send(t="market.cancel", id=item_listing["id"])
    A.wait(lambda q: not any(r["id"] == item_listing["id"] for r in q.state["market"]["mine"]), 8,
           "cancelled")
    A.wait(lambda q: any("CANCEL ok" in l for l in q.state["market"]["log"]), 5, "cancel ok")
    ok(f"listing {item_listing['id']} cancelled: {A.state['market']['log'][-1]!r}")

    print("== shop")
    tp(A, "0:6:3", 4, 3, "left")
    press_until(A, lambda q: (q.state.get("ui") or {}).get("shop"), "mart window")
    shop = A.state["ui"]["shop"]
    offer = shop["offers"][0]
    m0 = A.state["ui"]["money"]
    A.send(t="shop.buy", item=offer["item"], qty=1)
    A.wait(lambda q: q.state["ui"]["money"] == m0 - offer["price"], 8, "bought from mart")
    ok(f"bought 1x {offer['name']} for {offer['price']}")
    sell = A.wait(lambda q: next((s for s in q.state["ui"]["shop"]["sell"] if s["item"] == offer["item"]),
                                 None), 8, "item in the sell list")
    m1 = A.state["ui"]["money"]
    A.send(t="shop.sell", item=sell["item"], qty=1)
    A.wait(lambda q: q.state["ui"]["money"] == m1 + sell["price"], 8, "sold to mart")
    ok(f"sold 1x {sell['name']} for {sell['price']}")
    A.send(t="shop.close")
    A.wait(lambda q: not q.state["ui"].get("shop"), 5, "mart closed")
    press_until(A, lambda q: not (q.state.get("ui") or {}).get("dialog"), "mart dialog closed")

    print("== battle actions")
    command(A, "/giveitem 5004 5")
    if len(A.state["party"]) < 2:
        command(A, "/givemon 16 5")
        A.wait(lambda q: len(q.state["party"]) >= 2, 8, "two mons")
    A.send(t="say", text="/testbattle 19 2")
    A.wait(lambda q: q.battle() and q.battle()["phase"] == "ACTION", 10, "battle ACTION")
    since = len(A.messages)
    A.send(t="battle.learn", slot=None)
    A.wait(lambda q: "nothing to learn" in errors_since(q, since), 5, "learn answered")
    active = A.battle()["activeSlot"]
    target = next(i for i, m in enumerate(A.battle()["party"]) if i != active and m["hp"] > 0)
    A.send(t="battle.switch", slot=target)
    A.wait(lambda q: q.battle()["activeSlot"] == target and q.battle()["phase"] == "ACTION", 10, "switched")
    ok(f"switched to slot {target}: {A.battle()['me']['name']}")
    A.send(t="battle.ball")
    A.wait(lambda q: q.battle() and (q.battle()["phase"] == "ACTION" or q.battle().get("result")), 15,
           "ball thrown")
    ok(f"ball: result={A.battle().get('result')} log tail={A.battle()['log'][-2:]}")
    if not A.battle().get("result"):
        A.send(t="battle.run")
        A.wait(lambda q: q.battle() and (q.battle().get("result") or q.battle()["phase"] == "ACTION"), 10, "ran")
        for _ in range(8):
            if A.battle().get("result"):
                break
            A.send(t="battle.run")
            time.sleep(1.0)
    A.wait(lambda q: q.battle() and q.battle().get("result"), 10, "battle over")
    ok(f"battle ended: {A.battle()['result']}")
    A.send(t="battle.close")
    A.wait(lambda q: q.state.get("battle") is None, 5, "closed")

    print("== anti-abuse")
    for i in range(12):
        B.send(t="say", text=f"spam {i}", channel="global")
    B.wait(lambda q: any("too fast" in c["text"] for c in chat_lines(q)), 5, "chat rate limit")
    time.sleep(0.5)
    got = sum(1 for c in chat_lines(A) if c["text"].startswith("spam "))
    assert got <= 8, got
    ok(f"chat burst of 12: {got} delivered, the rest refused")

    tp(A, "0:4:1", 6, 6)
    tp(B, "0:4:1", 7, 6)

    print("== summary")
    print(f"   state messages: {na} {A.state_count}, {nb} {B.state_count}")
    gaps = [b - a for (a, _), (b, _) in zip(A.state_times, A.state_times[1:])]
    if gaps:
        print(f"   min gap between world updates: {min(gaps) * 1000:.1f} ms (cap is 33 ms)")
    A.ws.close()
    B.ws.close()
    print("ALL OK")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}")
        sys.exit(1)
