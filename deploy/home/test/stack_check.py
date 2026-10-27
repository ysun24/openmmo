#!/usr/bin/env python3
"""Checks a running home stack (deploy/home/compose.yml) through its gateway, stdlib only.

  python3 deploy/home/test/stack_check.py --ws ws://127.0.0.1:8090/ws \
      --invite CODE --user friend --pass bulbasaur123 --name Gary [--compose "docker compose -p x ..."]

1. origin guard: the gateway accepts its own origin and Pinggy-style origins, refuses others
2. register with an invite -> {"t":"register","ok":true} then a normal login reply
3. the same invite again -> BAD_INVITE; bad input is refused before the login server
4. log in again with the new account (password path)
Exits non-zero on the first failure.
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "web", "gateway", "test"))
from e2e import Player, WsClient, ok  # noqa: E402


def expect_refused(url, origin, headers=None):
    try:
        WsClient(url, origin=origin).close()
    except IOError as e:
        assert b"403" in str(e).encode() or "403" in str(e), e
        return
    raise AssertionError(f"origin {origin} was accepted")


def ws_with_headers(url, origin, extra):
    """WsClient with extra request headers (X-Forwarded-*), the way Pinggy would forward."""
    import base64, socket
    from urllib.parse import urlparse
    u = urlparse(url)
    s = socket.create_connection((u.hostname, u.port or 80), timeout=10)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (f"GET {u.path} HTTP/1.1\r\nHost: gateway:8080\r\nUpgrade: websocket\r\n"
           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
           f"Origin: {origin}\r\n" + "".join(f"{k}: {v}\r\n" for k, v in extra.items()) + "\r\n")
    s.sendall(req.encode())
    head = s.recv(256)
    s.close()
    return head.split(b"\r\n", 1)[0]


def register(p, **fields):
    since = len(p.messages)
    p.send(t="register", **fields)
    return p.wait(lambda q: q.message("register", since), 30, "register reply"), since


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ws", required=True)
    ap.add_argument("--invite", required=True)
    ap.add_argument("--user", default="friend")
    ap.add_argument("--pass", dest="pw", default="bulbasaur123")
    ap.add_argument("--name", default="Gary")
    a = ap.parse_args()
    host = a.ws.split("//", 1)[1].split("/", 1)[0]

    print("origin guard")
    WsClient(a.ws, origin=f"http://{host}").close()
    ok("own origin accepted")
    WsClient(a.ws, origin="https://abc123-1-2-3-4.a.pinggy.link").close()
    ok("https://<random>.a.pinggy.link accepted")
    WsClient(a.ws, origin="https://xyz.a.free.pinggy.link").close()
    ok("https://<random>.a.free.pinggy.link accepted")
    expect_refused(a.ws, "https://evil.example")
    ok("https://evil.example refused (403)")
    expect_refused(a.ws, "https://x.y.a.pinggy.link")
    ok("two-label subdomain refused")
    st = ws_with_headers(a.ws, "https://mygame.example.org",
                         {"X-Forwarded-Host": "mygame.example.org", "X-Forwarded-For": "203.0.113.9"})
    assert b" 101 " in st, st
    ok("origin matching X-Forwarded-Host accepted (tunnel with a custom domain)")

    print("register")
    p = Player(a.ws, "new")
    m, _ = register(p, user="ab", invite=a.invite, name=a.name, region=0, **{"pass": a.pw})
    assert not m["ok"] and m["error"] == "BAD_USERNAME", m
    ok("short username refused: BAD_USERNAME")
    m, _ = register(p, user=a.user, invite=a.invite, name=a.name, region=0, **{"pass": "short"})
    assert not m["ok"] and m["error"] == "BAD_PASSWORD", m
    ok("short password refused: BAD_PASSWORD")
    m, since = register(p, user=a.user, invite=a.invite, name=a.name, region=0, gender=1,
                        remember=True, **{"pass": a.pw})
    assert m["ok"], f"register failed: {m}"
    lm = p.wait(lambda q: q.message("login", since), 40, "login after register")
    assert lm["ok"], lm
    assert lm.get("token"), "remember=true should hand out a token"
    p.wait(lambda q: "world" in q.state, 10, "first state")
    ok(f"registered '{a.user}' and logged in as {lm['name']} (map {p.world.get('map')})")
    p.ws.close()
    time.sleep(1)

    q = Player(a.ws, "again")
    m, _ = register(q, user=a.user + "2", invite=a.invite, name="Other", region=0, **{"pass": a.pw})
    assert not m["ok"] and m["error"] == "BAD_INVITE", m
    ok("the used-up invite is refused: BAD_INVITE")
    q.ws.close()

    print("login")
    r = Player(a.ws, "login")
    since = len(r.messages)
    r.send(t="login", user=a.user.upper(), name=a.name, **{"pass": a.pw})
    lm = r.wait(lambda x: x.message("login", since), 30, "login reply")
    assert lm["ok"], lm
    ok(f"password login with the new account (case-insensitive username) as {lm['name']}")
    r.ws.close()
    print("ALL OK")


if __name__ == "__main__":
    main()
