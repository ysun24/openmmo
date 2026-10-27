// Overworld simulation (PROTOCOL.md section 5): self prediction + reconciliation, interpolation of
// other entities, collision from col.bin + entity tiles, seamless map connections.
import { assets, connOrigin } from './assets.js';

export const STEP_WALK = 250;
export const STEP_RUN = 125;
const TURN_HOLD = 90; // ms a fresh direction press only turns before walking
const MAX_PENDING = 8;
const DIRS = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] };

const keyOf = (m) => (m ? `${m.region}:${m.bank}:${m.map}` : null);

function newMover() {
  return { x: 0, y: 0, fx: 0, fy: 0, t: 0, dur: STEP_WALK, moving: false, facing: 'down', parity: 0, px: 0, py: 0 };
}
function place(o) {
  if (o.moving) {
    const p = Math.min(1, o.t / o.dur);
    o.px = (o.fx + (o.x - o.fx) * p) * 16;
    o.py = (o.fy + (o.y - o.fy) * p) * 16;
  } else {
    o.px = o.x * 16;
    o.py = o.y * 16;
  }
}

export class World {
  constructor(send) {
    this.send = send;
    this.reset();
  }

  reset() {
    this.mapKey = null;
    this.map = null; // server map descriptor
    this.selfId = null;
    this.self = Object.assign(newMover(), { name: '', kind: 'player', gfx: 0, id: null });
    this.hasSelf = false;
    this.ents = new Map();
    this.drawList = [];
    this.pending = [];
    this.seq = 0;
    this.ackSeq = 0;
    this.turnWait = 0;
    this.lastLocalAct = 0;
    this.lastBump = 0;
    this.conns = []; // [{key, ox, oy, info, res}]
    this.fade = 0; // 1 = black
    this.fadeHoldUntil = 0;
    this.fadeWaiting = null;
    this.stats = { snaps: 0, steps: 0 };
  }

  get res() { return assets.map(this.mapKey); }

  // ------------------------------------------------------------------ server -> sim
  onServer(w, now) {
    if (!w) return;
    this.selfId = w.self;
    const newKey = keyOf(w.map);
    if (newKey !== this.mapKey) this.changeMap(newKey, w.map, now);
    this.map = w.map;
    const seen = new Set();
    let s = null;
    for (const e of w.entities || []) {
      if (e.map && keyOf(e.map) !== newKey) continue;
      if (e.id === w.self) {
        s = e;
        continue;
      }
      seen.add(e.id);
      this.serverEntity(e, now);
    }
    for (const id of this.ents.keys()) if (!seen.has(id)) this.ents.delete(id);
    if (s) this.reconcile(s, w.ackSeq ?? 0, now);
  }

  changeMap(newKey, desc, now) {
    const c = this.conns.find((c) => c.key === newKey);
    if (c && this.mapKey && this.hasSelf) {
      // seamless: shift our frame of reference into the connected map
      const sh = (o) => {
        o.x -= c.ox; o.y -= c.oy; o.fx -= c.ox; o.fy -= c.oy;
      };
      sh(this.self);
      for (const p of this.pending) (p.x -= c.ox), (p.y -= c.oy);
      for (const e of this.ents.values()) sh(e);
      place(this.self);
    } else {
      this.pending = [];
      this.self.moving = false;
      this.hasSelf = false; // snap on reconcile
      this.ents.clear();
      if (this.mapKey !== null || newKey !== null) this.startFade(newKey, now);
    }
    this.mapKey = newKey;
    this.computeConns();
    assets.preload(newKey).then(() => this.computeConns());
  }

  startFade(key, now) {
    this.fade = 1;
    this.fadeHoldUntil = now + 150;
    const p = assets.preload(key);
    this.fadeWaiting = p;
    Promise.race([p, new Promise((r) => setTimeout(r, 1500))]).then(() => {
      if (this.fadeWaiting === p) this.fadeWaiting = null;
    });
  }

  computeConns() {
    this.conns.length = 0;
    const r = this.res;
    if (!r || !r.info) return;
    for (const c of r.info.connections || []) {
      const res = assets.map(c.map);
      if (!res.info) continue;
      const [ox, oy] = connOrigin(r.info, c, res.info);
      this.conns.push({ key: c.map, ox, oy, info: res.info, res, dir: c.dir });
    }
  }

  serverEntity(e, now) {
    let o = this.ents.get(e.id);
    if (!o) {
      o = Object.assign(newMover(), { id: e.id, lastMove: 0 });
      o.x = o.fx = e.x;
      o.y = o.fy = e.y;
      this.ents.set(e.id, o);
    }
    o.kind = e.kind;
    o.name = e.name;
    o.gfx = e.gfx;
    if (e.x !== o.x || e.y !== o.y) {
      const d = Math.abs(e.x - o.x) + Math.abs(e.y - o.y);
      if (d === 1) {
        place(o);
        o.fx = o.px / 16;
        o.fy = o.py / 16;
        const since = now - o.lastMove;
        o.dur = Math.max(110, Math.min(STEP_WALK, since > 0 ? since : STEP_WALK));
        o.t = 0;
        o.moving = true;
        o.parity ^= 1;
      } else {
        o.fx = e.x;
        o.fy = e.y;
        o.moving = false;
      }
      o.x = e.x;
      o.y = e.y;
      o.lastMove = now;
    }
    o.facing = e.facing || o.facing;
  }

  reconcile(s, ack, now) {
    const me = this.self;
    me.id = s.id;
    me.name = s.name;
    me.gfx = s.gfx;
    me.kind = 'player';
    if (!this.hasSelf) {
      this.snap(s, false);
      this.hasSelf = true;
      return;
    }
    this.ackSeq = ack;
    let pred = null;
    while (this.pending.length && this.pending[0].seq <= ack) pred = this.pending.shift();
    if (pred) {
      if (pred.x !== s.x || pred.y !== s.y) this.snap(s, true);
    } else if (!this.pending.length && !me.moving && (me.x !== s.x || me.y !== s.y)) {
      this.snap(s, true);
    }
    if (!this.pending.length && !me.moving && now - this.lastLocalAct > 400 && s.facing) me.facing = s.facing;
  }

  snap(s, slide) {
    const me = this.self;
    const d = Math.abs(s.x - me.x) + Math.abs(s.y - me.y);
    if (slide && d === 1) {
      place(me);
      me.fx = me.px / 16;
      me.fy = me.py / 16;
      me.t = 0;
      me.dur = 120;
      me.moving = true;
    } else {
      me.moving = false;
    }
    me.x = s.x;
    me.y = s.y;
    if (!me.moving) (me.fx = s.x), (me.fy = s.y);
    if (s.facing) me.facing = s.facing;
    this.pending = [];
    this.stats.snaps++;
    place(me);
  }

  // ------------------------------------------------------------------ collision
  /** A warp tile of the current map (manifest `warps`): pushing against a wall there still steps. */
  isWarp(x, y) {
    const wp = this.res && this.res.info && this.res.info.warps;
    if (!wp) return false;
    for (let i = 0; i < wp.length; i += 2) if (wp[i] === x && wp[i + 1] === y) return true;
    return false;
  }

  blocked(x, y) {
    const r = this.res;
    const w = this.map ? this.map.width : r && r.info ? r.info.width : 0;
    const hgt = this.map ? this.map.height : r && r.info ? r.info.height : 0;
    if (x >= 0 && y >= 0 && x < w && y < hgt) {
      // collision not loaded yet (just warped): predict nothing until it is
      if (!r || !r.col || !r.info) return true;
      if (r.col[y * r.info.width + x] & 1) return true;
    } else {
      const c = this.conns.find((c) => x >= c.ox && y >= c.oy && x < c.ox + c.info.width && y < c.oy + c.info.height);
      if (!c || !c.res.col) return true;
      if (c.res.col[(y - c.oy) * c.info.width + (x - c.ox)] & 1) return true;
    }
    for (const e of this.ents.values()) {
      if (e.x === x && e.y === y) return true;
      if (e.moving && Math.round(e.fx) === x && Math.round(e.fy) === y && e.t < e.dur * 0.5) return true;
    }
    return false;
  }

  // ------------------------------------------------------------------ per frame
  /** input: {dir: 'up'|...|null, run: bool, enabled: bool} */
  update(dt, input, now) {
    const me = this.self;
    if (this.fade > 0 && !this.fadeWaiting && now > this.fadeHoldUntil) this.fade = Math.max(0, this.fade - dt / 250);
    if (this.hasSelf) {
      let justMoved = false;
      if (me.moving) {
        me.t += dt;
        if (me.t >= me.dur) {
          const carry = Math.min(me.t - me.dur, 40);
          me.moving = false;
          me.fx = me.x;
          me.fy = me.y;
          justMoved = true;
          this.tryStep(input, carry, true);
        }
      } else if (this.turnWait > 0) {
        this.turnWait -= dt;
        if (!input.dir || !input.enabled) this.turnWait = 0;
        else if (this.turnWait <= 0) this.tryStep(input, 0, true);
      } else this.tryStep(input, 0, justMoved);
      place(me);
    }
    for (const e of this.ents.values()) {
      if (e.moving) {
        e.t += dt;
        if (e.t >= e.dur) (e.moving = false), (e.fx = e.x), (e.fy = e.y);
      }
      place(e);
    }
    // y-sorted draw list, reused (no per-frame allocation)
    const dl = this.drawList;
    dl.length = 0;
    for (const e of this.ents.values()) dl.push(e);
    if (this.hasSelf) dl.push(me);
    dl.sort(byY);
  }

  tryStep(input, carry, continuing) {
    const me = this.self;
    if (!input.enabled || !input.dir || this.pending.length >= MAX_PENDING) return;
    const dir = input.dir;
    if (dir !== me.facing && !continuing) {
      me.facing = dir;
      this.send({ t: 'face', dir });
      this.lastLocalAct = performance.now();
      this.turnWait = TURN_HOLD;
      return;
    }
    const [dx, dy] = DIRS[dir];
    const nx = me.x + dx, ny = me.y + dy;
    if (this.blocked(nx, ny)) {
      if (me.facing !== dir) {
        me.facing = dir;
        this.send({ t: 'face', dir });
        this.lastLocalAct = performance.now();
      } else if (this.res && this.res.col && !this.entityAt(nx, ny) && (this.isWarp(me.x, me.y) || this.isWarp(nx, ny)) && performance.now() - this.lastBump > STEP_WALK) {
        // Walking into a wall still goes to the server (unpredicted): stair warps and the like
        // trigger on a step against them, as on the GBA.
        this.lastBump = performance.now();
        const seq = ++this.seq;
        this.pending.push({ seq, x: me.x, y: me.y });
        this.send({ t: 'step', dir, run: !!input.run, seq });
      }
      return;
    }
    me.facing = dir;
    me.fx = me.x;
    me.fy = me.y;
    me.x = nx;
    me.y = ny;
    me.dur = input.run ? STEP_RUN : STEP_WALK;
    me.t = carry;
    me.moving = true;
    me.parity ^= 1;
    const seq = ++this.seq;
    this.pending.push({ seq, x: nx, y: ny });
    this.send({ t: 'step', dir, run: !!input.run, seq });
    this.lastLocalAct = performance.now();
    this.stats.steps++;
  }

  /** Tile the player faces. */
  facingTile() {
    const [dx, dy] = DIRS[this.self.facing] || [0, 1];
    return [this.self.x + dx, this.self.y + dy];
  }
  entityAt(x, y) {
    for (const e of this.ents.values()) if (e.x === x && e.y === y) return e;
    return null;
  }
}
const byY = (a, b) => a.py - b.py;
