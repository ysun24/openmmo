// Canvas renderer: 240x160 logical GBA viewport. bottom -> y-sorted entities -> top, connections at
// the edges, border fill outside every map, fade on warps.
import { assets } from './assets.js';

export const VW = 240;
export const VH = 160;
// GBA camera: the player's 16x16 tile sits at (112, 64) on screen
const CAM_X = 112;
const CAM_Y = 64;

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
  }

  draw(world, now) {
    const ctx = this.ctx;
    const me = world.self;
    const camX = (this.camX = Math.round(me.px - CAM_X));
    const camY = (this.camY = Math.round(me.py - CAM_Y));
    const res = world.res;

    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, VW, VH);
    if (!world.hasSelf) return;

    // border fill
    if (res && res.border && res.border.ready) {
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

    // bottom layers: connections then current map
    for (const c of world.conns) this.layer(c.res.bottom, c.ox * 16, c.oy * 16, camX, camY);
    if (res && res.bottom && res.bottom.ready) this.layer(res.bottom, 0, 0, camX, camY);
    else if (world.map) this.placeholderMap(world.map.width, world.map.height, camX, camY);

    // entities, y-sorted
    const region = world.map ? world.map.region : 0;
    for (const e of world.drawList) this.entity(e, camX, camY, now, region);

    // top layers
    for (const c of world.conns) this.layer(c.res.top, c.ox * 16, c.oy * 16, camX, camY);
    if (res && res.top) this.layer(res.top, 0, 0, camX, camY);

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
