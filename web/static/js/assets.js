// Static assets (PROTOCOL.md section 4): manifest, image cache, map preloading, collision.
const BASE = 'assets/';

export const assets = {
  manifest: null,
  ok: false,
  images: new Map(), // url -> {img, ready}
  maps: new Map(), // key -> MapRes

  async load() {
    try {
      const r = await fetch(BASE + 'manifest.json', { cache: 'no-cache' });
      if (!r.ok) throw new Error(r.status);
      this.manifest = await r.json();
      this.ok = true;
    } catch (e) {
      console.warn('assets: no manifest, using placeholder drawing', e);
      this.manifest = { version: 0, maps: {}, objects: {}, players: {}, pokemon: {}, ui: {} };
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
    m = { key, info, bottom: null, top: null, border: null, col: null, pattern: null, ready: false, promise: null };
    this.maps.set(key, m);
    if (!info) {
      m.promise = Promise.resolve(m);
      return m;
    }
    m.bottom = this.image(info.bottom);
    m.top = this.image(info.top);
    m.border = this.image(info.border);
    const col = fetch(BASE + info.collision)
      .then((r) => (r.ok ? r.arrayBuffer() : null))
      .then((b) => (m.col = b ? new Uint8Array(b) : null))
      .catch(() => null);
    m.promise = Promise.all([m.bottom.promise, m.top.promise, m.border.promise, col]).then(() => ((m.ready = true), m));
    return m;
  },

  /** Load `key` and every map connected to it. Resolves when `key` itself is ready. */
  preload(key) {
    const m = this.map(key);
    if (m && m.info) for (const c of m.info.connections || []) this.map(c.map);
    return m ? m.promise : Promise.resolve(null);
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
