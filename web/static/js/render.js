// Canvas renderer: 240x160 logical GBA viewport. bottom -> y-sorted entities -> top, connections at
// the edges, border fill outside every map, animated tiles re-blitted from the layout's atlas, player
// name tags, fade on warps.
import { assets, stepAnim, GBA_HZ } from './assets.js';

export const VW = 240;
export const VH = 160;
// GBA camera: the player's 16x16 tile sits at (112, 64) on screen
const CAM_X = 112;
const CAM_Y = 64;

// 3x5 pixel font (rows of 3 bits, top row first) for name tags.
const GLYPHS = {
  A: '010101111101101', B: '110101110101110', C: '011100100100011', D: '110101101101110', E: '111100110100111',
  F: '111100110100100', G: '011100101101011', H: '101101111101101', I: '111010010010111', J: '001001001101010',
  K: '101101110101101', L: '100100100100111', M: '101111111101101', N: '110101101101101', O: '010101101101010',
  P: '110101110100100', Q: '010101101110011', R: '110101110101101', S: '011100010001110', T: '111010010010010',
  U: '101101101101111', V: '101101101101010', W: '101101111111101', X: '101101010101101', Y: '101101010010010',
  Z: '111001010100111', 0: '111101101101111', 1: '010110010010111', 2: '110001010100111', 3: '110001010001110',
  4: '101101111001001', 5: '111100110001110', 6: '011100111101111', 7: '111001010010010', 8: '111101111101111',
  9: '111101111001110', '-': '000000111000000', _: '000000000000111', '.': '000000000000010', '!': '010010010000010',
  '?': '110001010000010', "'": '010010000000000', ' ': '000000000000000',
};

/** A name tag as a small canvas (cached per text+colour). */
const tagCache = new Map();
function tagCanvas(text, self) {
  const key = (self ? '1' : '0') + text;
  let c = tagCache.get(key);
  if (c) return c;
  const t = String(text || '').toUpperCase().slice(0, 12);
  const w = Math.max(1, t.length * 4 - 1) + 4, h = 9;
  c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const x = c.getContext('2d');
  x.fillStyle = 'rgba(16,16,24,0.62)';
  x.fillRect(1, 0, w - 2, h);
  x.fillRect(0, 1, w, h - 2);
  x.fillStyle = self ? '#f8d850' : '#ffffff';
  for (let i = 0; i < t.length; i++) {
    const g = GLYPHS[t[i]] || GLYPHS['?'];
    for (let p = 0; p < 15; p++) if (g[p] === '1') x.fillRect(2 + i * 4 + (p % 3), 2 + ((p / 3) | 0), 1, 1);
  }
  if (tagCache.size > 200) tagCache.clear();
  tagCache.set(key, c);
  return c;
}

export class Renderer {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d', { alpha: false });
    this.ctx.imageSmoothingEnabled = false;
    this.camX = 0;
    this.camY = 0;
    this.patternKey = null;
    this.pattern = null;
    this.highlight = null; // entity id
    this.tags = true; // other players' name tags
    this.selfTag = false; // own name tag
    this.gbaFrame = 0;
  }

  draw(world, now) {
    const ctx = this.ctx;
    const me = world.self;
    let camX = Math.round(me.px - CAM_X);
    let camY = Math.round(me.py - CAM_Y);
    const res = world.res;
    // GBA style: a map smaller than the screen along an axis (indoor rooms) is centred, black around
    let cx = false, cy = false;
    const info = res && res.info;
    if (world.map && !(info && info.connections && info.connections.length)) {
      const mw = world.map.width * 16, mh = world.map.height * 16;
      if (mw <= VW) (camX = Math.floor((mw - VW) / 2)), (cx = true);
      if (mh <= VH) (camY = Math.floor((mh - VH) / 2)), (cy = true);
    }
    this.camX = camX;
    this.camY = camY;
    this.gbaFrame = Math.floor((now * GBA_HZ) / 1000);

    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, VW, VH);
    if (!world.hasSelf) return;

    // border fill (not around centred rooms: the GBA shows black there)
    if (cx || cy) {
      // black
    } else if (res && res.border && res.border.ready) {
      if (this.patternKey !== res.key) {
        this.pattern = ctx.createPattern(res.border.img, 'repeat');
        this.patternKey = res.key;
      }
      ctx.save();
      ctx.translate(-camX, -camY);
      ctx.fillStyle = this.pattern;
      ctx.fillRect(camX, camY, VW, VH);
      ctx.restore();
    } else {
      ctx.fillStyle = '#204018';
      ctx.fillRect(0, 0, VW, VH);
    }

    // bottom layers: connections then current map, animated tiles on top of each
    for (const c of world.conns) {
      this.layer(c.res.bottom, c.ox * 16, c.oy * 16, camX, camY);
      this.anim(c.res.anim, c.ox * 16, c.oy * 16, camX, camY, false);
    }
    if (res && res.bottom && res.bottom.ready) {
      this.layer(res.bottom, 0, 0, camX, camY);
      this.anim(res.anim, 0, 0, camX, camY, false);
    } else if (world.map) this.placeholderMap(world.map.width, world.map.height, camX, camY);

    // entities, y-sorted
    const region = world.map ? world.map.region : 0;
    for (const e of world.drawList) this.entity(e, camX, camY, now, region);

    // top layers
    for (const c of world.conns) {
      this.layer(c.res.top, c.ox * 16, c.oy * 16, camX, camY);
      this.anim(c.res.anim, c.ox * 16, c.oy * 16, camX, camY, true);
    }
    if (res && res.top) {
      this.layer(res.top, 0, 0, camX, camY);
      this.anim(res.anim, 0, 0, camX, camY, true);
    }

    // name tags above everything of the world
    if (this.tags || this.selfTag) {
      for (const e of world.drawList) {
        if (e.kind !== 'player' || !e.name) continue;
        const self = e === me;
        if (self ? !this.selfTag : !this.tags) continue;
        this.tag(e, camX, camY, region, self);
      }
    }

    if (world.fade > 0) {
      ctx.globalAlpha = Math.min(1, world.fade);
      ctx.fillStyle = '#000';
      ctx.fillRect(0, 0, VW, VH);
      ctx.globalAlpha = 1;
    }
  }

  /** Draw the visible part of a full-map image at world px (ox, oy). */
  layer(e, ox, oy, camX, camY) {
    if (!e || !e.ready) return;
    const img = e.img;
    const sx = Math.max(0, camX - ox);
    const sy = Math.max(0, camY - oy);
    const ex = Math.min(img.width, camX + VW - ox);
    const ey = Math.min(img.height, camY + VH - oy);
    if (ex <= sx || ey <= sy) return;
    this.ctx.drawImage(img, sx, sy, ex - sx, ey - sy, ox + sx - camX, oy + sy - camY, ex - sx, ey - sy);
  }

  /** Re-blit the visible animated metatiles of one map from its atlas (bottom or top layer). */
  anim(a, ox, oy, camX, camY, top) {
    if (!a || !a.atlas || !a.atlas.ready || (top && !a.anyTop)) return;
    stepAnim(a, this.gbaFrame);
    const x0 = Math.max(0, Math.floor((camX - ox) / 16)), y0 = Math.max(0, Math.floor((camY - oy) / 16));
    const x1 = Math.min(a.w, Math.ceil((camX + VW - ox) / 16)), y1 = Math.min(a.h, Math.ceil((camY + VH - oy) / 16));
    const img = a.atlas.img, cols = a.cols, grid = a.grid, metas = a.metas, ctx = this.ctx;
    for (let y = y0; y < y1; y++) {
      const row = y * a.w;
      for (let x = x0; x < x1; x++) {
        const s = grid[row + x];
        if (s < 0) continue;
        const m = metas[s];
        if (top && !m.top) continue;
        const cell = m.cell;
        ctx.drawImage(img, (cell % cols) * 16, ((cell / cols) | 0) * 32 + (top ? 16 : 0), 16, 16, ox + x * 16 - camX, oy + y * 16 - camY, 16, 16);
      }
    }
  }

  placeholderMap(w, h, camX, camY) {
    const ctx = this.ctx;
    const x0 = Math.max(0, Math.floor(camX / 16)), y0 = Math.max(0, Math.floor(camY / 16));
    const x1 = Math.min(w, Math.ceil((camX + VW) / 16)), y1 = Math.min(h, Math.ceil((camY + VH) / 16));
    for (let y = y0; y < y1; y++)
      for (let x = x0; x < x1; x++) {
        ctx.fillStyle = (x + y) & 1 ? '#a8d890' : '#98c880';
        ctx.fillRect(x * 16 - camX, y * 16 - camY, 16, 16);
      }
  }

  tag(e, camX, camY, region, self) {
    const d = assets.sheetFor(e, region);
    const fh = d ? d.frameH : 32;
    const c = tagCanvas(e.name, self);
    const bx = Math.round(e.px) - camX;
    // 16x32 overworld frames leave ~10 blank rows above the head
    const headY = Math.round(e.py) - camY + 16 - fh + (fh >= 32 ? 10 : 2);
    let x = bx + 8 - (c.width >> 1);
    let y = headY - c.height - 1;
    if (this.highlight === e.id) y -= 6;
    if (x < -c.width || x > VW || y > VH || y < -c.height - 16) return;
    x = Math.max(1, Math.min(VW - c.width - 1, x));
    y = Math.max(1, y);
    this.ctx.drawImage(c, x, y);
  }

  entity(e, camX, camY, now, region) {
    const ctx = this.ctx;
    const d = assets.sheetFor(e, region);
    const bx = Math.round(e.px) - camX;
    const by = Math.round(e.py) - camY;
    if (bx < -32 || by < -32 || bx > VW + 16 || by > VH + 32) return;
    if (this.highlight === e.id) {
      ctx.fillStyle = '#f8c030';
      const bob = (now / 200) & 1;
      ctx.fillRect(bx + 6, by - 22 + bob, 4, 2);
      ctx.fillRect(bx + 7, by - 20 + bob, 2, 2);
    }
    if (!d || !d._img || !d._img.ready) {
      ctx.fillStyle = e.kind === 'player' ? '#e04848' : '#4878e0';
      ctx.fillRect(bx + 3, by - 8, 10, 22);
      ctx.fillStyle = '#f8d0a8';
      ctx.fillRect(bx + 4, by - 12, 8, 7);
      return;
    }
    const f = d.frames;
    let face = e.facing || 'down';
    const mirror = face === 'right' && d.mirrorRight;
    if (mirror) face = 'left';
    let idx;
    const walking = e.moving && e.t < e.dur * 0.5;
    const Face = face[0].toUpperCase() + face.slice(1);
    const running = e.moving && e.dur <= 130 && f['runWalk' + Face];
    if (walking) {
      const w = running ? f['runWalk' + Face] : f['walk' + Face];
      idx = Array.isArray(w) ? w[e.parity % w.length] : w;
    } else idx = running && f['run' + Face] !== undefined ? f['run' + Face] : f[face];
    if (idx === undefined) idx = f.down ?? 0;
    const fw = d.frameW, fh = d.frameH;
    const img = d._img.img;
    const perRow = Math.max(1, Math.floor(img.width / fw));
    const sx = (idx % perRow) * fw, sy = Math.floor(idx / perRow) * fh;
    const dx = bx + ((16 - fw) >> 1);
    const dy = by + 16 - fh;
    if (mirror) {
      ctx.save();
      ctx.scale(-1, 1);
      ctx.drawImage(img, sx, sy, fw, fh, -dx - fw, dy, fw, fh);
      ctx.restore();
    } else ctx.drawImage(img, sx, sy, fw, fh, dx, dy, fw, fh);
  }

  /** Canvas-relative CSS px -> world tile, using the current camera. */
  pickTile(cssX, cssY) {
    const r = this.canvas.getBoundingClientRect();
    const lx = ((cssX - r.left) / r.width) * VW;
    const ly = ((cssY - r.top) / r.height) * VH;
    return { lx, ly, wx: lx + this.camX, wy: ly + this.camY };
  }
}
