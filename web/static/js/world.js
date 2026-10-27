// Overworld simulation (PROTOCOL.md section 5): self prediction + reconciliation, interpolation of
// other entities, collision from col.bin + beh.bin + entity tiles, seamless map connections.
// The step rules mirror the server's FieldRules (Gen 3 field_player_avatar.c): elevation, ledges,
// surfing, boulders, bike terrain, directional walls (PROTOCOL.md "r3-field").
import { assets, connOrigin } from './assets.js';

export const STEP_WALK = 250;
export const STEP_RUN = 125;
/** Step time per travel mode (GBA px/frame: walk 1, run/surf/acro 2, mach 4 at full speed). */
export const STEP_BY_MODE = { walk: STEP_WALK, run: STEP_RUN, mach_bike: 70, acro_bike: 125, surf: 125, underwater: 250 };
/** Kanto's Bicycle is slower than the Mach Bike. */
const KANTO_BIKE = 95;
/** Mach Bike step times for PLAYER_SPEED_NORMAL, FAST and FASTEST (16, 8 and 4 frames a tile). */
const MACH_TIERS = [250, 125, 70];
/** JumpSpecial on and off the surf blob (1 tile, JUMP_TYPE_HIGH, 16 GBA frames + the rise). */
const SURF_HOP_MS = 320;
export const JUMP_MS = 520; // a 2-tile ledge hop (JUMP_2 = 32 GBA frames)
const TURN_HOLD = 90; // ms a fresh direction press only turns before walking
const MAX_PENDING = 8;
const FORCED_HOLD = 400; // ms after a forced step before the input may move the self again
const DIRS = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] };

// Elevation (MapGridGetElevationAt): 0 and 15 are wildcards, 1 water, 3 land.
const Z_ANY = 0, Z_WATER = 1, Z_LAND = 3, Z_BRIDGE = 15;
export const elevationMismatch = (z, tz) => z !== Z_ANY && tz !== Z_ANY && tz !== Z_BRIDGE && tz !== z;
const elevationAfter = (cur, fromZ, toZ) => (toZ === Z_BRIDGE || fromZ === Z_BRIDGE ? cur : toZ);
const LEDGE = { JUMP_SOUTH: 'down', JUMP_NORTH: 'up', JUMP_EAST: 'right', JUMP_WEST: 'left' };
const ENTRY_BLOCK = { IMPASSABLE_EAST: 'left', IMPASSABLE_WEST: 'right', IMPASSABLE_NORTH: 'down', IMPASSABLE_SOUTH: 'up' };
const EXIT_BLOCK = { IMPASSABLE_EAST: 'right', IMPASSABLE_WEST: 'left', IMPASSABLE_NORTH: 'up', IMPASSABLE_SOUTH: 'down' };
export const SURFABLE = new Set(['WATER', 'POND_WATER', 'DEEP_WATER', 'SEMI_DEEP_WATER', 'WATERFALL', 'SEAWEED',
  'CURRENT_EAST', 'CURRENT_WEST', 'CURRENT_NORTH', 'CURRENT_SOUTH']);
// Boulder gfx per region (OBJ_EVENT_GFX_PUSHABLE_BOULDER).
const BOULDER_GFX = { 0: 97, 1: 87 };
// Cut trees and Rock Smash rocks per region, with their ANIM_REMOVE_OBSTACLE frame time
// (sAnim_TreeCut 6 GBA frames a frame, sAnim_RockBreak 8).
const OBSTACLE_GFX = { 0: { 95: 100, 96: 133 }, 1: { 82: 100, 86: 133 } };

const keyOf = (m) => (m ? `${m.region}:${m.bank}:${m.map}` : null);

function newMover() {
  return { x: 0, y: 0, fx: 0, fy: 0, t: 0, dur: STEP_WALK, moving: false, facing: 'down', parity: 0, px: 0, py: 0, jump: 0, mode: 'walk', run: false };
}
function place(o) {
  if (o.moving) {
    const p = Math.min(1, o.t / o.dur);
    o.px = (o.fx + (o.x - o.fx) * p) * 16;
    o.py = (o.fy + (o.y - o.fy) * p) * 16;
    // ledge hop arc (the sprite rises, its shadow stays on the ground)
    o.jump = o.jumping ? Math.sin(Math.PI * p) * 14 : 0;
  } else {
    o.px = o.x * 16;
    o.py = o.y * 16;
    o.jump = 0;
  }
}

/** The behaviour grid of a map resource, fetched once (`<layout>.beh.bin`, PROTOCOL r3-field). */
function behOf(res) {
  if (!res || !res.info || !res.info.behavior) return null;
  if (res.beh !== undefined) return res.beh;
  res.beh = null;
  fetch(assets.url(res.info.behavior))
    .then((r) => (r.ok ? r.arrayBuffer() : null))
    .then((b) => b && (res.beh = new Uint8Array(b)))
    .catch(() => null);
  return null;
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
    this.self = Object.assign(newMover(), { name: '', kind: 'player', gfx: 0, id: null, z: Z_LAND });
    this.hasSelf = false;
    this.ents = new Map();
    this.drawList = [];
    this.pending = [];
    this.seq = 0;
    this.ackSeq = 0;
    this.turnWait = 0;
    this.lastLocalAct = 0;
    this.lastBump = 0;
    /** performance.now() until which the server, not the input, moves the self (ice, currents). */
    this.forcedUntil = 0;
    /** Hoenn Mach Bike acceleration (bike.c bikeFrameCounter / bikeSpeed). */
    this.mach = { counter: 0, speed: 0 };
    /** Removed cut trees / smashed rocks still playing their break animation. */
    this.dying = [];
    this.conns = []; // [{key, ox, oy, info, res}]
    this.fade = 0; // 1 = black
    this.fadeHoldUntil = 0;
    this.fadeWaiting = null;
    this.stats = { snaps: 0, steps: 0, jumps: 0 };
    /** world.field from the server (dark, flash, canRun, moves, fly...), or null. */
    this.field = null;
    /** Field effects to draw: {kind, x, y, t0, dur, dir} in world tiles. */
    this.fx = [];
  }

  get res() { return assets.map(this.mapKey); }

  // ------------------------------------------------------------------ server -> sim
  onServer(w, now) {
    if (!w) return;
    this.selfId = w.self;
    this.field = w.field || null;
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
    for (const [id, o] of this.ents) {
      if (seen.has(id)) continue;
      this.ents.delete(id);
      this.breakObstacle(o, newKey, now);
    }
    if (s) this.reconcile(s, w.ackSeq ?? 0, now);
  }

  /** A cut tree or smashed rock next to the self that the server removed: play its break frames. */
  breakObstacle(o, mapKey, now) {
    const region = this.map ? this.map.region : 0;
    const ms = o.kind === 'npc' && OBSTACLE_GFX[region] && OBSTACLE_GFX[region][o.gfx];
    if (!ms || mapKey !== this.mapKey || !this.hasSelf) return;
    if (Math.abs(o.x - this.self.x) + Math.abs(o.y - this.self.y) > 1) return;
    o.moving = false;
    o.facing = 'down';
    o.breakAt = now;
    o.breakMs = ms;
    o.breakFrame = 0;
    this.dying.push(o);
  }

  changeMap(newKey, desc, now) {
    this.dying = [];
    const c = this.conns.find((c) => c.key === newKey);
    if (c && this.mapKey && this.hasSelf) {
      // seamless: shift our frame of reference into the connected map
      const sh = (o) => {
        o.x -= c.ox; o.y -= c.oy; o.fx -= c.ox; o.fy -= c.oy;
      };
      sh(this.self);
      for (const p of this.pending) (p.x -= c.ox), (p.y -= c.oy);
      for (const e of this.ents.values()) sh(e);
      for (const f of this.fx) (f.x -= c.ox), (f.y -= c.oy);
      place(this.self);
    } else {
      this.pending = [];
      this.self.moving = false;
      this.self.jumping = false;
      this.hasSelf = false; // snap on reconcile
      this.ents.clear();
      this.fx.length = 0;
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
    behOf(r);
    for (const c of r.info.connections || []) {
      const res = assets.map(c.map);
      if (!res.info) continue;
      behOf(res);
      const [ox, oy] = connOrigin(r.info, c, res.info);
      this.conns.push({ key: c.map, ox, oy, info: res.info, res, dir: c.dir });
    }
  }

  serverEntity(e, now) {
    let o = this.ents.get(e.id);
    if (!o) {
      o = Object.assign(newMover(), { id: e.id, lastMove: 0, jumpSeq: e.jump || 0, exclaimSeq: e.exclaim || 0, exclaimAt: 0 });
      o.x = o.fx = e.x;
      o.y = o.fy = e.y;
      this.ents.set(e.id, o);
    }
    o.kind = e.kind;
    o.name = e.name;
    o.gfx = e.gfx;
    o.mode = e.mode || 'walk';
    o.female = !!e.female;
    o.run = !!e.run;
    const jumped = (e.jump || 0) > o.jumpSeq;
    const boulder = o.kind === 'npc' && o.gfx === BOULDER_GFX[this.map ? this.map.region : 0];
    o.jumpSeq = e.jump || 0;
    if ((e.exclaim || 0) > o.exclaimSeq) o.exclaimAt = now;
    o.exclaimSeq = e.exclaim || 0;
    if (e.x !== o.x || e.y !== o.y) {
      const d = Math.abs(e.x - o.x) + Math.abs(e.y - o.y);
      if (d === 1 || (jumped && d === 2)) {
        this.leaveTile(o, now);
        place(o);
        o.fx = o.px / 16;
        o.fy = o.py / 16;
        const since = now - o.lastMove;
        const base = STEP_BY_MODE[o.mode] || STEP_WALK;
        o.dur = jumped ? JUMP_MS : Math.max(o.run ? 110 : Math.min(base, 110), Math.min(o.run ? STEP_RUN : base, since > 0 ? since : base));
        o.jumping = jumped;
        o.t = 0;
        o.moving = true;
        o.parity ^= 1;
      } else {
        o.fx = e.x;
        o.fy = e.y;
        o.moving = false;
        o.jumping = false;
      }
      o.x = e.x;
      o.y = e.y;
      o.lastMove = now;
      this.enterTile(o, now);
      // DoBoulderFinish: FLDEFF_DUST where a STRENGTH boulder comes to rest.
      if (boulder && o.moving) this.fx.push({ kind: 'dust', x: o.x, y: o.y, t0: now + o.dur, dur: 300 });
    }
    o.facing = e.facing || o.facing;
  }

  reconcile(s, ack, now) {
    const me = this.self;
    me.id = s.id;
    me.name = s.name;
    me.gfx = s.gfx;
    me.kind = 'player';
    const mode = s.mode || 'walk';
    // Starting to surf is a JumpSpecial onto the blob (1 tile).
    if (mode === 'surf' && me.mode === 'walk') this.surfHop = true;
    if (mode !== me.mode) {
      me.mode = mode;
      if (mode === 'surf' || mode === 'underwater') me.z = Z_WATER;
    }
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
    const hop = this.surfHop;
    this.surfHop = false;
    if (slide && d === 1) {
      place(me);
      me.fx = me.px / 16;
      me.fy = me.py / 16;
      me.t = 0;
      me.dur = hop ? SURF_HOP_MS : 120;
      me.moving = true;
      me.jumping = !!hop;
    } else {
      me.moving = false;
      me.jumping = false;
    }
    me.x = s.x;
    me.y = s.y;
    if (!me.moving) (me.fx = s.x), (me.fy = s.y);
    if (s.facing) me.facing = s.facing;
    // the server's elevation is the tile's (water when surfing)
    const t = this.tileAt(s.x, s.y);
    if (me.mode === 'surf' || me.mode === 'underwater') me.z = Z_WATER;
    else if (t && !t.loading && t.z !== Z_BRIDGE) me.z = t.z === Z_WATER ? Z_LAND : t.z;
    else me.z = null; // learnt from the tile once its collision has loaded
    this.pending = [];
    this.mach.counter = this.mach.speed = 0;
    this.stats.snaps++;
    // Each step of server forced movement keeps the input held off a little longer.
    if (this.forcedUntil) this.forcedUntil = performance.now() + FORCED_HOLD;
    place(me);
  }

  // ------------------------------------------------------------------ tiles
  /** {col, z, beh} of world tile (x, y) (current map or a connection), or null outside. */
  tileAt(x, y) {
    const r = this.res;
    const w = this.map ? this.map.width : r && r.info ? r.info.width : 0;
    const hgt = this.map ? this.map.height : r && r.info ? r.info.height : 0;
    let res, lx, ly;
    if (x >= 0 && y >= 0 && x < w && y < hgt) {
      res = r;
      lx = x;
      ly = y;
    } else {
      const c = this.conns.find((c) => x >= c.ox && y >= c.oy && x < c.ox + c.info.width && y < c.oy + c.info.height);
      if (!c) return null;
      res = c.res;
      lx = x - c.ox;
      ly = y - c.oy;
    }
    if (!res || !res.col || !res.info) return { loading: true };
    const i = ly * res.info.width + lx;
    const c = res.col[i];
    const beh = behOf(res);
    const names = assets.manifest && assets.manifest.behaviors;
    return { col: c & 1, z: c >> 4, beh: beh && names ? names[beh[i]] || 'NORMAL' : 'NORMAL' };
  }

  /** The behaviour name of a world tile ('NORMAL' when unknown). */
  behAt(x, y) {
    const t = this.tileAt(x, y);
    return t && t.beh ? t.beh : 'NORMAL';
  }

  /** A warp tile of the current map (manifest `warps`): pushing against a wall there still steps. */
  isWarp(x, y) {
    const wp = this.res && this.res.info && this.res.info.warps;
    if (!wp) return false;
    for (let i = 0; i < wp.length; i += 2) if (wp[i] === x && wp[i + 1] === y) return true;
    return false;
  }

  entityBlocks(x, y) {
    for (const e of this.ents.values()) {
      if (e.x === x && e.y === y) return e;
      if (e.moving && Math.round(e.fx) === x && Math.round(e.fy) === y && e.t < e.dur * 0.5) return e;
    }
    return null;
  }

  /** Kept for callers: true when a plain step onto (x, y) would be refused. */
  blocked(x, y) {
    const t = this.tileAt(x, y);
    if (!t || t.loading || t.col) return true;
    if (elevationMismatch(this.self.z, t.z)) return true;
    return !!this.entityBlocks(x, y);
  }

  /**
   * The Gen 3 step rules for the predicted self (server FieldRules.step): returns
   * {kind: 'walk'|'jump'|'stopSurf'|'push'|'block', x, y, z}.
   */
  predictStep(dir) {
    const me = this.self;
    const [dx, dy] = DIRS[dir];
    const nx = me.x + dx, ny = me.y + dy;
    const from = this.tileAt(me.x, me.y);
    const to = this.tileAt(nx, ny);
    if (!to || to.loading) return { kind: 'block' };
    if (me.z === null || me.z === undefined) {
      if (!from || from.loading) return { kind: 'block' };
      me.z = me.mode === 'surf' ? Z_WATER : from.z === Z_BRIDGE ? Z_LAND : from.z;
    }
    const water = me.mode === 'surf' || me.mode === 'underwater';
    if (!water && LEDGE[to.beh] === dir) {
      const lx = me.x + dx * 2, ly = me.y + dy * 2;
      const land = this.tileAt(lx, ly);
      if (!land || land.loading || land.col || this.entityBlocks(lx, ly)) return { kind: 'block' };
      return { kind: 'jump', x: lx, y: ly, z: elevationAfter(me.z, from ? from.z : me.z, land.z) };
    }
    if (to.col || ENTRY_BLOCK[to.beh] === dir || (from && EXIT_BLOCK[from.beh] === dir)) return { kind: 'block' };
    const ent = this.entityBlocks(nx, ny);
    if (elevationMismatch(me.z, to.z)) {
      if (me.mode === 'surf' && to.z === Z_LAND && !ent) return { kind: 'stopSurf', x: nx, y: ny, z: Z_LAND };
      return { kind: 'block' };
    }
    if (ent) {
      const region = this.map ? this.map.region : 0;
      if (ent.kind === 'npc' && ent.gfx === BOULDER_GFX[region] && this.field && this.field.strength && me.mode === 'walk') return { kind: 'push' };
      return { kind: 'block' };
    }
    // ForcedMovement_MuddySlope: only a Mach Bike at full speed climbs it.
    if (to.beh === 'MUDDY_SLOPE' && dir === 'up' && (me.mode !== 'mach_bike' || (this.machAccel() && this.mach.counter < 2))) return { kind: 'block' };
    if ((to.beh === 'BUMPY_SLOPE' || to.beh === 'RAIL') && me.mode !== 'acro_bike') return { kind: 'block' };
    return { kind: 'walk', x: nx, y: ny, z: elevationAfter(me.z, from ? from.z : me.z, to.z) };
  }

  /** Whether a step onto behaviour [b] starts server forced movement (server ForcedMovement). */
  forcesMovement(b) {
    const mode = this.self.mode;
    const water = mode === 'surf' || mode === 'underwater';
    const bike = mode === 'mach_bike' || mode === 'acro_bike';
    if (b === 'ICE' || /^(WALK|SLIDE|SPIN)_/.test(b)) return true;
    if (/^CURRENT_/.test(b)) return water;
    if (b === 'WATERFALL') return mode === 'surf';
    if (b === 'MUDDY_SLOPE') return mode !== 'mach_bike';
    if (b === 'CYCLING_ROAD_PULL_DOWN') return bike;
    return false;
  }

  /** Emerald's Mach Bike accelerates (FRLG's Bicycle rides at one speed). */
  machAccel() {
    return this.self.mode === 'mach_bike' && !!this.map && this.map.region !== 0;
  }

  /** How long one step of the predicted self takes. */
  stepMs(run) {
    const me = this.self;
    if (me.mode === 'mach_bike') return this.machAccel() ? MACH_TIERS[this.mach.counter] : KANTO_BIKE;
    if (me.mode !== 'walk') return STEP_BY_MODE[me.mode] || STEP_WALK;
    const canRun = !this.field || this.field.canRun;
    return run && canRun && this.behAt(me.x, me.y) !== 'NO_RUNNING' ? STEP_RUN : STEP_WALK;
  }

  // ------------------------------------------------------------------ field effects
  /** An entity arrived on (x, y): grass rustle, splashes, ash puffs (GBA ground effects). */
  enterTile(o, now) {
    const b = this.behAt(o.x, o.y);
    const add = (kind, dur) => this.fx.push({ kind, x: o.x, y: o.y, t0: now + (o.dur || 0) * 0.5, dur, dir: o.facing });
    if (b === 'TALL_GRASS' || b === 'ASH_GRASS') add('tallGrass', 300);
    else if (b === 'LONG_GRASS') add('longGrass', 300);
    else if (b === 'PUDDLE' || b === 'SHALLOW_WATER') add('splash', 260);
    if (b === 'ASH_GRASS') add('ashPuff', 400);
    if (o.jumping) this.fx.push({ kind: SURFABLE.has(b) ? 'jumpSplash' : b === 'TALL_GRASS' ? 'jumpGrass' : 'dust', x: o.x, y: o.y, t0: now + JUMP_MS, dur: 300 });
    if (this.fx.length > 120) this.fx.splice(0, this.fx.length - 120);
  }

  /** An entity is leaving (o.x, o.y): sand footprints / bike tracks stay behind for a while. */
  leaveTile(o, now) {
    const b = this.behAt(o.x, o.y);
    if (b !== 'SAND' && b !== 'DEEP_SAND' && b !== 'FOOTPRINTS') return;
    const bike = o.mode === 'mach_bike' || o.mode === 'acro_bike';
    this.fx.push({ kind: bike ? 'tireTracks' : b === 'DEEP_SAND' ? 'deepFootprints' : 'footprints', x: o.x, y: o.y, t0: now, dur: 2400, dir: o.facing });
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
          me.jumping = false;
          me.fx = me.x;
          me.fy = me.y;
          justMoved = true;
          this.tryStep(input, carry, true, now);
        }
      } else if (this.turnWait > 0) {
        this.turnWait -= dt;
        if (!input.dir || !input.enabled) this.turnWait = 0;
        else if (this.turnWait <= 0) this.tryStep(input, 0, true, now);
      } else this.tryStep(input, 0, justMoved, now);
      place(me);
    }
    for (const e of this.ents.values()) {
      if (e.moving) {
        e.t += dt;
        if (e.t >= e.dur) (e.moving = false), (e.jumping = false), (e.fx = e.x), (e.fy = e.y);
      }
      place(e);
    }
    if (this.dying.length) {
      for (const o of this.dying) o.breakFrame = Math.floor((now - o.breakAt) / o.breakMs);
      this.dying = this.dying.filter((o) => o.breakFrame < 4);
    }
    // expire effects
    if (this.fx.length) this.fx = this.fx.filter((f) => now < f.t0 + f.dur);
    // y-sorted draw list, reused (no per-frame allocation)
    const dl = this.drawList;
    dl.length = 0;
    for (const e of this.ents.values()) dl.push(e);
    for (const o of this.dying) dl.push(o);
    if (this.hasSelf) dl.push(me);
    dl.sort(byY);
  }

  tryStep(input, carry, continuing, now = performance.now()) {
    const me = this.self;
    if (!input.enabled || this.pending.length >= MAX_PENDING) return;
    if (this.forcedUntil) {
      if (now < this.forcedUntil) return;
      this.forcedUntil = 0;
    }
    const mach = this.machAccel() ? this.mach : null;
    if (!mach) this.mach.counter = this.mach.speed = 0;
    let dir = input.dir;
    let coast = false;
    if (!dir) {
      // MachBikeTransition_TrySlowDown: a released Mach Bike rolls on, one speed tier slower per step.
      if (!mach || !mach.speed || !continuing) return;
      mach.counter = --mach.speed;
      dir = me.facing;
      coast = true;
    }
    if (dir !== me.facing && !continuing) {
      me.facing = dir;
      this.send({ t: 'face', dir });
      this.lastLocalAct = performance.now();
      this.turnWait = TURN_HOLD;
      return;
    }
    const out = this.predictStep(dir);
    if (out.kind === 'block' || out.kind === 'push') {
      this.mach.counter = this.mach.speed = 0; // Bike_SetBikeStill
      if (coast) return;
      const [dx, dy] = DIRS[dir];
      const nx = me.x + dx, ny = me.y + dy;
      if (me.facing !== dir) {
        me.facing = dir;
        this.send({ t: 'face', dir });
        this.lastLocalAct = performance.now();
      } else if (performance.now() - this.lastBump > STEP_WALK &&
        (out.kind === 'push' || (this.res && this.res.col && !this.entityAt(nx, ny) && (this.isWarp(me.x, me.y) || this.isWarp(nx, ny))))) {
        // Unpredicted steps still go to the server: stair warps trigger on a push against them,
        // and a STRENGTH boulder slides while the player stays put.
        this.lastBump = performance.now();
        const seq = ++this.seq;
        this.pending.push({ seq, x: me.x, y: me.y });
        this.send({ t: 'step', dir, run: !!input.run, seq });
      }
      return;
    }
    this.leaveTile(me, now);
    me.facing = dir;
    me.fx = me.x;
    me.fy = me.y;
    me.x = out.x;
    me.y = out.y;
    me.z = out.z;
    me.jumping = out.kind === 'jump' || out.kind === 'stopSurf';
    me.dur = out.kind === 'jump' ? JUMP_MS : out.kind === 'stopSurf' ? SURF_HOP_MS : this.stepMs(input.run);
    if (mach && !coast) {
      // MachBikeTransition_TrySpeedUp: NORMAL, FAST, then FASTEST.
      mach.speed = mach.counter + (mach.counter >> 1);
      if (mach.counter < 2) mach.counter++;
    }
    me.run = me.dur === STEP_RUN && me.mode === 'walk';
    me.t = carry;
    me.moving = true;
    me.parity ^= 1;
    if (out.kind === 'stopSurf') me.mode = 'walk';
    if (out.kind === 'jump') this.stats.jumps++;
    const seq = ++this.seq;
    this.pending.push({ seq, x: out.x, y: out.y });
    this.send(out.kind === 'jump' ? { t: 'step', dir, run: !!input.run, seq, jump: true } : { t: 'step', dir, run: !!input.run, seq });
    this.lastLocalAct = performance.now();
    this.stats.steps++;
    if (this.forcesMovement(this.behAt(out.x, out.y))) this.forcedUntil = now + FORCED_HOLD + 200;
    this.enterTile(me, now);
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
