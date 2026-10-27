// Emerald secret bases in the browser (PROTOCOL.md section 11): the decorations the server says are
// placed are drawn over the pre-rendered base map from the exported atlases, their collision is
// predicted like decoration.c ShowDecorationOnMap_ (the server's SecretBaseRules), and the open
// entrance metatile is drawn at the spots of the player's and its friends' bases.
import { assets } from './assets.js';

const sb = () => (assets.manifest && assets.manifest.secretBases) || null;

/** The atlas cell of secondary-tileset index [t] (manifest secretBases.tiles). */
function cellOf(t) {
  const m = sb();
  if (!m) return -1;
  if (!m._cell) m._cell = new Map(m.tiles.map((x, i) => [x, i]));
  return m._cell.has(t) ? m._cell.get(t) : -1;
}

function attrOf(t) {
  const m = sb();
  const i = cellOf(t);
  return i >= 0 ? m.tileAttrs[i] : ['NORMAL', 0];
}

/** Decorations of the base the player is in, drawn: {cells: Map(i -> {cell, impassable, beh}), sprites}. */
export function overlay(world) {
  const s = world.secretBase;
  const m = sb();
  const key = world.mapKey;
  if (!s || s.inside === 'none' || !m || !m.bases[key]) return null;
  const sig = key + JSON.stringify(s.decorations) + s.inside;
  if (world._sbOverlay && world._sbOverlay.sig === sig) return world._sbOverlay;
  const base = m.bases[key];
  const w = base.width;
  const h = base.behaviors.length / w;
  const cells = new Map();
  const sprites = [];
  const behAt = (x, y) => {
    if (x < 0 || y < 0 || x >= w || y >= h) return null;
    const c = cells.get(y * w + x);
    return c ? c.beh : base.behaviors[y * w + x];
  };
  for (const p of s.decorations || []) {
    const d = m.decorations[p.id];
    if (!d) continue;
    if (d.gfx !== undefined) {
      sprites.push({ x: p.x, y: p.y, gfx: d.gfx });
      continue;
    }
    for (let j = 0; j < d.h; j++) {
      for (let i = 0; i < d.w; i++) {
        const x = p.x + i, y = p.y - d.h + 1 + j;
        if (x < 0 || y < 0 || x >= w || y >= h) continue;
        let t = d.tiles[j * d.w + i];
        const [mb, layer] = attrOf(t);
        const impassable = mb === 'SECRET_BASE_IMPASSABLE' || (d.perm !== 'pass_floor' && layer !== 0);
        if (d.perm !== 'na_wall' && behAt(x, y) === 'SECRET_BASE_NORTH_WALL') t |= 1;
        const cell = cellOf(t);
        cells.set(y * w + x, { cell, impassable, beh: attrOf(t)[0] });
      }
    }
  }
  // Another player's base shows the Register PC (InitSecretBaseAppearance).
  if (s.inside === 'visiting') {
    const pcAt = base.behaviors.indexOf('SECRET_BASE_PC');
    if (pcAt >= 0) cells.set(pcAt, { cell: cellOf(0x221 - 512), impassable: true, beh: 'SECRET_BASE_REGISTER_PC' });
  }
  world._sbOverlay = { sig, cells, sprites, base, w, h, behAt };
  return world._sbOverlay;
}

/** The predicted collision of (x, y) inside a base: true/false, or undefined when not decorated. */
export function blocks(world, x, y) {
  const o = overlay(world);
  if (!o) return undefined;
  if (o.sprites.some((s) => s.x === x && s.y === y)) return true;
  const c = o.cells.get(y * o.w + x);
  return c ? c.impassable : undefined;
}

/** Open entrance cells on the current map: [{x, y, cell}]. */
export function openSpots(world) {
  const s = world.secretBase;
  const m = sb();
  if (!s || !m || !s.open || !s.open.length) return [];
  const spots = m.spots[world.mapKey] || [];
  return s.open.map((o) => spots.find((p) => p.x === o.x && p.y === o.y)).filter(Boolean);
}

/** CanPlaceDecoration for the cursor (bottom-left at x, y); [px, py] where the player stands. */
export function canPlace(world, decorId, x, y, px, py) {
  const o = overlay(world);
  const m = sb();
  const d = m && m.decorations[decorId];
  if (!o || !d) return false;
  const inside = (cx, cy) => cx >= 0 && cy >= 0 && cx < o.w && cy < o.h;
  const layer = (i) => (d.tiles ? attrOf(d.tiles[i])[1] : 0);
  const sprite = (cx, cy) => o.sprites.some((s) => s.x === cx && s.y === cy);
  const initial = (cx, cy, l) => cx === px && cy === py && l !== 0;
  if (d.perm === 'solid_floor' || d.perm === 'pass_floor') {
    for (let i = 0; i < d.h; i++) for (let j = 0; j < d.w; j++) {
      const cx = x + j, cy = y - i;
      if (!inside(cx, cy)) return false;
      const b = o.behAt(cx, cy);
      if (!(b === 'NORMAL' || (decorId === 33 && b === 'SECRET_BASE_HOLE'))) return false;
      if (initial(cx, cy, layer((d.h - 1 - i) * d.w + j)) || sprite(cx, cy)) return false;
    }
    return true;
  }
  if (d.perm === 'behind_floor') {
    for (let i = 0; i < d.h - 1; i++) for (let j = 0; j < d.w; j++) {
      const cx = x + j, cy = y - i;
      if (!inside(cx, cy)) return false;
      const b = o.behAt(cx, cy), l = layer((d.h - 1 - i) * d.w + j);
      if (b !== 'NORMAL' && !(b === 'SECRET_BASE_TRAINER_SPOT' && l === 0)) return false;
      if (initial(cx, cy, l) || sprite(cx, cy) || (cx === px && cy === py)) return false;
    }
    const top = y - d.h + 1;
    for (let j = 0; j < d.w; j++) {
      const cx = x + j;
      if (!inside(cx, top)) return false;
      const b = o.behAt(cx, top);
      if (b !== 'NORMAL' && b !== 'SECRET_BASE_NORTH_WALL') return false;
      if (initial(cx, top, layer(j)) || sprite(cx, top)) return false;
    }
    return true;
  }
  if (d.perm === 'na_wall') {
    for (let i = 0; i < d.h; i++) for (let j = 0; j < d.w; j++) {
      const cx = x + j, cy = y - i;
      if (!inside(cx, cy) || o.behAt(cx, cy) !== 'SECRET_BASE_NORTH_WALL') return false;
    }
    return true;
  }
  // sprite: on a desk top
  for (let j = 0; j < d.w; j++) {
    const cx = x + j;
    if (!inside(cx, y)) return false;
    const b = o.behAt(cx, y);
    const ok = d.h === 2 ? b === 'HOLDS_LARGE_DECORATION' : b === 'HOLDS_SMALL_DECORATION' || b === 'HOLDS_LARGE_DECORATION';
    if (!ok || sprite(cx, y) || (cx === px && y === py)) return false;
  }
  return true;
}
