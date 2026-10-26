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
    if A.world["map"] != B.world["map"]:
        warp(A, 10_000)
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
