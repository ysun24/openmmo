// Static assets (PROTOCOL.md section 4): manifest, image cache, map preloading, collision, tileset
// animations. Only the manifest and the current map are needed to start; everything else (connected
// maps, sprites, animation atlases, music) loads lazily. `progress` drives the loading bar.
const BASE = 'assets/';

export const assets = {
  manifest: null,
  ok: false,
  images: new Map(), // url -> {img, ready}
  maps: new Map(), // key -> MapRes
  // loading bar: requests started / finished since the bar was last idle, manifest byte progress
  progress: { started: 0, finished: 0, manifest: 0 },

  track(p) {
    this.progress.started++;
    const done = () => this.progress.finished++;
    p.then(done, done);
    return p;
  },
  /** 0..1 while something is loading, null when idle. */
  loadFraction() {
    const g = this.progress;
    if (g.finished >= g.started) {
      g.started = g.finished = 0;
      return null;
    }
    return g.finished / g.started;
  },

  async load() {
    try {
      const r = await fetch(BASE + 'manifest.json', { cache: 'no-cache' });
      if (!r.ok) throw new Error(r.status);
      const total = +r.headers.get('content-length') || 0;
      if (total && r.body && r.body.getReader) {
        const reader = r.body.getReader();
        const parts = [];
        let got = 0;
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          parts.push(value);
          got += value.length;
          this.progress.manifest = got / total;
        }
        const all = new Uint8Array(got);
        let o = 0;
        for (const p of parts) all.set(p, o), (o += p.length);
        this.manifest = JSON.parse(new TextDecoder().decode(all));
      } else this.manifest = await r.json();
      this.progress.manifest = 1;
      this.ok = true;
    } catch (e) {
      console.warn('assets: no manifest, using placeholder drawing', e);
      this.manifest = { version: 0, maps: {}, objects: {}, players: {}, pokemon: {}, ui: {} };
      this.progress.manifest = 1;
    }
    return this.ok;
  },

  /** Cached image; returns the entry immediately, `ready` flips when loaded. */
  image(path) {
    if (!path) return null;
    let e = this.images.get(path);
    if (!e) {
      const img = new Image();
      e = { img, ready: false, failed: false, promise: null };
      e.promise = new Promise((res) => {
        img.onload = () => ((e.ready = true), res(e));
        img.onerror = () => ((e.failed = true), res(e));
      });
      this.track(e.promise);
      img.decoding = 'async';
      img.src = BASE + path;
      this.images.set(path, e);
    }
    return e;
  },
  url(path) { return path ? BASE + path : null; },

  /** Map resources for `key` ("region:bank:map"); starts loading on first call. */
  map(key) {
    if (!key) return null;
    let m = this.maps.get(key);
    if (m) return m;
    const info = this.manifest && this.manifest.maps[key];
    m = { key, info, bottom: null, top: null, border: null, col: null, anim: null, ready: false, promise: null };
    this.maps.set(key, m);
    if (!info) {
      m.promise = Promise.resolve(m);
      return m;
    }
    m.bottom = this.image(info.bottom);
    m.top = this.image(info.top);
    m.border = this.image(info.border);
    const col = this.track(fetch(BASE + info.collision)
      .then((r) => (r.ok ? r.arrayBuffer() : null))
      .then((b) => (m.col = b ? new Uint8Array(b) : null))
      .catch(() => null));
    m.promise = Promise.all([m.bottom.promise, m.top.promise, m.border && m.border.promise, col]).then(() => ((m.ready = true), m));
    // animated tiles: drawn once ready, the static images already show frame 0
    if (info.anim) m.promise.then(() => this.loadAnim(m, info));
    return m;
  },

  loadAnim(m, info) {
    fetch(BASE + info.anim)
      .then((r) => (r.ok ? r.json() : null))
      .then((a) => {
        if (!a) return;
        m.anim = buildAnim(a, info.width, info.height, this.image(a.image));
      })
      .catch(() => null);
  },

  /** Load `key`, then every map connected to it (after `key` itself, so it comes first). */
  preload(key) {
    const m = this.map(key);
    if (!m) return Promise.resolve(null);
    m.promise.then(() => {
      if (m.info) for (const c of m.info.connections || []) this.map(c.map);
    });
    return m.promise;
  },

  /** Sprite sheet descriptor for an entity; objects are keyed "region:gfx" (gfx ids differ per game). */
  sheetFor(ent, region = 0) {
    const man = this.manifest;
    if (!man || !man.objects) return null;
    let d = man.objects[`${region}:${ent.gfx}`] || null;
    if (!d && ent.kind === 'player') {
      const p = man.players && man.players[String(region)];
      const gfx = p ? (ent.gfx === p.femaleGfx ? p.femaleGfx : p.maleGfx) : 0;
      d = man.objects[`${region}:${gfx}`] || null;
      if (!d && p) {
        // players entry without an objects entry: same frame layout as a standard 16x32 sheet
        d = man.objects[`_p${region}:${gfx}`] = { sheet: ent.gfx === p.femaleGfx ? p.female : p.male, frameW: 16, frameH: 32, frames: DEFAULT_FRAMES, mirrorRight: true };
      }
    }
    if (!d) return null;
    if (!d._img) d._img = this.image(d.sheet);
    return d;
  },

  pokemon(dex, kind) {
    const p = this.manifest && this.manifest.pokemon && this.manifest.pokemon[String(dex)];
    return p && p[kind] ? BASE + p[kind] : null;
  },
};

const DEFAULT_FRAMES = { down: 0, up: 1, left: 2, walkDown: [3, 4], walkUp: [5, 6], walkLeft: [7, 8] };

/** GBA frames per second: tileset animation counters advance once per frame. */
export const GBA_HZ = 59.7275;

/**
 * Runtime form of a layout's `.anim.json`: per channel the frame at every counter value, per
 * animated metatile its atlas cell for each frame combination, and a grid (map cell -> metatile slot).
 */
function buildAnim(a, w, h, atlas) {
  const channels = a.channels.map((c) => {
    const at = new Uint8Array(c.cycle);
    const ev = c.events;
    for (let k = 0; k < ev.length; k += 2) {
      const end = k + 2 < ev.length ? ev[k + 2] : c.cycle;
      at.fill(ev[k + 1], ev[k], end);
    }
    return { cycle: c.cycle, at };
  });
  const grid = new Int16Array(w * h).fill(-1);
  const metas = a.metatiles.map((m, slot) => {
    const combos = new Map();
    for (const c of m.combos) {
      let key = 0;
      for (let i = 0; i < m.ch.length; i++) key = key * 64 + c[i];
      combos.set(key, c[m.ch.length]);
    }
    for (let i = 0; i < m.cells.length; i += 2) grid[m.cells[i + 1] * w + m.cells[i]] = slot;
    return { ch: m.ch, combos, top: m.top, cell: m.combos.length ? m.combos[0][m.ch.length] : 0 };
  });
  return { atlas, cols: a.cols, channels, metas, grid, w, h, frame: -1, anyTop: metas.some((m) => m.top) };
}

/** Brings `anim`'s per-metatile atlas cell up to GBA frame `f`. */
export function stepAnim(anim, f) {
  if (anim.frame === f) return;
  anim.frame = f;
  const cur = anim.channels.map((c) => c.at[f % c.cycle]);
  for (const m of anim.metas) {
    let key = 0;
    for (const ci of m.ch) key = key * 64 + cur[ci];
    const cell = m.combos.get(key);
    if (cell !== undefined) m.cell = cell;
  }
}

/** Position of connected map `c` relative to map `a`'s origin, in tiles. */
export function connOrigin(aInfo, c, bInfo) {
  switch (c.dir) {
    case 'up': return [c.offset, -bInfo.height];
    case 'down': return [c.offset, aInfo.height];
    case 'left': return [-bInfo.width, c.offset];
    case 'right': return [aInfo.width, c.offset];
    default: return [0, 0];
  }
}
