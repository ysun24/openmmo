// Canvas renderer: 240x160 logical GBA viewport. bottom -> y-sorted entities -> top, connections at
// the edges, border fill outside every map, animated tiles re-blitted from the layout's atlas, player
// name tags, fade on warps.
import { assets, stepAnim, GBA_HZ } from './assets.js';
import { fonts } from './font.js';
import { overlay as sbOverlay, openSpots as sbOpenSpots } from './secretbase.js';
import { drawBerryTree } from './ui/contests.js'; // r5-contests

export const VW = 240;
export const VH = 160;
// The player's object gfx per travel mode (event_objects.h), per region and gender.
const MODE_GFX = {
  0: { m: { mach_bike: 1, acro_bike: 1, surf: 2 }, f: { mach_bike: 8, acro_bike: 8, surf: 9 } },
  1: { m: { mach_bike: 1, acro_bike: 63, surf: 2, underwater: 111 }, f: { mach_bike: 90, acro_bike: 91, surf: 92, underwater: 112 } },
};
// Emerald surf_blob.png frames: down, up, left (right mirrored).
const SURF_FRAME = { down: 0, up: 1, left: 2, right: 2 };
// FireRed's blob: two frames per facing, swapped every 48 GBA frames (sSurfBlobAnim_Face*).
const SURF_FRAME_KANTO = { down: 0, up: 2, left: 4, right: 4 };
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
  // the game's small font in full case once it is loaded (js/font.js), else the 3x5 fallback
  const gf = fonts.canvasFont(0);
  const key = (gf ? 'f' : '') + (self ? '1' : '0') + text;
  let c = tagCache.get(key);
  if (c) return c;
  if (gf) {
    c = fonts.textCanvas(gf, String(text || '').slice(0, 16), {
      fg: self ? '#f8d850' : '#ffffff', shadow: '#303040', bg: 'rgba(16,16,24,0.62)', pad: 2, maxW: 120,
    });
    if (tagCache.size > 200) tagCache.clear();
    tagCache.set(key, c);
    return c;
  }
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
    this.world = world;
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
      if (world.map) this.borderAnim(res, camX, camY, world.map.width, world.map.height);
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
    // r4: secret base decorations / open entrances replace their metatiles
    const sbCells = this.secretBaseCells(world);
    if (sbCells) this.drawCells(sbCells, 0, camX, camY);
    // r5-scripts: metatiles map scripts changed (setmetatile: opened doors, barriers, puzzles)
    const stCells = this.scriptTileCells(world);
    if (stCells) this.drawCells(stCells, 0, camX, camY);

    // ground effects (footprints, splashes, grass rustles), then entities, y-sorted
    this.effects(world, camX, camY, now);
    const region = world.map ? world.map.region : 0;
    // reflections on ponds, puddles and ice below each sprite (GROUND_EFFECT_FLAG_*_REFLECTION)
    for (const e of world.drawList) this.entity(e, camX, camY, now, region, true);
    for (const e of world.drawList) this.entity(e, camX, camY, now, region);

    // top layers
    for (const c of world.conns) {
      this.layer(c.res.top, c.ox * 16, c.oy * 16, camX, camY);
      this.anim(c.res.anim, c.ox * 16, c.oy * 16, camX, camY, true);
    }
    if (res && res.top) {
      if (sbCells) {
        // the map's top layer is replaced too: clip the replaced cells out of it
        ctx.save();
        ctx.beginPath();
        ctx.rect(0, 0, VW, VH);
        for (const c of sbCells) ctx.rect(c.x * 16 - camX, c.y * 16 - camY, 16, 16);
        ctx.clip('evenodd');
        this.layer(res.top, 0, 0, camX, camY);
        ctx.restore();
        this.drawCells(sbCells, 16, camX, camY);
      } else if (stCells) {
        ctx.save();
        ctx.beginPath();
        ctx.rect(0, 0, VW, VH);
        for (const c of stCells) ctx.rect(c.x * 16 - camX, c.y * 16 - camY, 16, 16);
        ctx.clip('evenodd');
        this.layer(res.top, 0, 0, camX, camY);
        ctx.restore();
        this.drawCells(stCells, 16, camX, camY);
      } else this.layer(res.top, 0, 0, camX, camY);
      this.anim(res.anim, 0, 0, camX, camY, true);
    }
    if (world.decorCursor) this.decorCursor(world, camX, camY, now);
    // dark caves until FLASH
    this.darkness(world, camX, camY);

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

  entity(e, camX, camY, now, region, reflect = false) {
    const ctx = this.ctx;
    const d = this.sheetForMode(e, region);
    const bx = Math.round(e.px) - camX;
    const by = Math.round(e.py) - camY;
    if (bx < -32 || by < -32 || bx > VW + 16 || by > VH + 32) return;
    // r5-contests: berry trees are drawn by their growth stage
    if (e.kind === 'npc' && e.gfx === 60) return void (!reflect && drawBerryTree(ctx, e, bx, by, now, region));
    let refl = null;
    if (reflect) {
      if (!d || !d._img || !d._img.ready || e.mode === 'surf' || e.mode === 'underwater') return;
      refl = this.reflectionTiles(e, d.frameH);
      if (!refl) return;
    }
    if (!reflect && this.highlight === e.id) {
      ctx.fillStyle = '#f8c030';
      const bob = (now / 200) & 1;
      ctx.fillRect(bx + 6, by - 22 + bob, 4, 2);
      ctx.fillRect(bx + 7, by - 20 + bob, 2, 2);
    }
    // under the sprite: the surf blob, the ledge-hop shadow
    const surfing = e.mode === 'surf';
    const bobY = surfing ? ((now / 266) | 0) & 1 : 0; // the blob bobs 1px every 16 frames
    if (surfing && !reflect) this.surfBlob(e, region, now, bx - 8, by - 8 + bobY);
    if (e.jump > 0 && !reflect) this.fxSprite('shadow', 0, bx, by + 10);
    const lift = Math.round(e.jump || 0) - (surfing ? bobY : 0);
    if (!d || !d._img || !d._img.ready) {
      ctx.fillStyle = e.kind === 'player' ? '#e04848' : '#4878e0';
      ctx.fillRect(bx + 3, by - 8 - lift, 10, 22);
      ctx.fillStyle = '#f8d0a8';
      ctx.fillRect(bx + 4, by - 12 - lift, 8, 7);
      return;
    }
    const f = d.frames;
    let face = e.facing || 'down';
    const mirror = face === 'right' && d.mirrorRight;
    if (mirror) face = 'left';
    let idx;
    const walking = e.moving && e.t < e.dur * 0.5 && !e.jumping;
    const Face = face[0].toUpperCase() + face.slice(1);
    const running = e.moving && (e.run || (e.mode === 'walk' && e.dur <= 130)) && f['runWalk' + Face];
    if (walking) {
      const w = running ? f['runWalk' + Face] : f['walk' + Face];
      idx = Array.isArray(w) ? w[e.parity % w.length] : w;
    } else idx = running && f['run' + Face] !== undefined ? f['run' + Face] : f[face];
    if (idx === undefined) idx = f.down ?? 0;
    if (e.mode === 'acro_bike' && d.anims) idx = this.acroFrame(e, d.anims, face, now) ?? idx;
    if (e.breakFrame !== undefined) idx = Math.min(3, e.breakFrame); // ANIM_REMOVE_OBSTACLE
    const fw = d.frameW, fh = d.frameH;
    const img = d._img.img;
    const perRow = Math.max(1, Math.floor(img.width / fw));
    const sx = (idx % perRow) * fw, sy = Math.floor(idx / perRow) * fh;
    const dx = bx + ((16 - fw) >> 1);
    const dy = by + 16 - fh - lift;
    if (refl) return this.drawReflection(img, sx, sy, fw, fh, dx, dy, by + 16, mirror, refl, camX, camY);
    if (mirror) {
      ctx.save();
      ctx.scale(-1, 1);
      ctx.drawImage(img, sx, sy, fw, fh, -dx - fw, dy, fw, fh);
      ctx.restore();
    } else ctx.drawImage(img, sx, sy, fw, fh, dx, dy, fw, fh);
    // standing in tall grass: the grass covers the legs (FLDEFF_TALL_GRASS's resting frame)
    if (this.world && !e.jump && !surfing) {
      const tx = e.moving && e.t < e.dur * 0.5 ? Math.round(e.fx) : e.x;
      const ty = e.moving && e.t < e.dur * 0.5 ? Math.round(e.fy) : e.y;
      const b = this.world.behAt(tx, ty);
      if (b === 'TALL_GRASS' || b === 'ASH_GRASS' || b === 'LONG_GRASS') this.grassCover(tx, ty, camX, camY, b === 'LONG_GRASS');
    }
    // a trainer's "!" when it spots the player (FLDEFF_EXCLAMATION_MARK_ICON, ~1 s)
    if (e.exclaimAt && now - e.exclaimAt < 1000) {
      const t = now - e.exclaimAt;
      if (e.exclaimKind === 'double') {
        // sAnimCmd_DoubleExclMark: images 6, 7 (4 GBA frames each), then 8 (52), which
        // sSpriteImages_Emoticons maps to pictures 3, 4, 5 of the 16x16 sheet
        const f = t < 67 ? 3 : t < 133 ? 4 : 5;
        if (!this.fxSprite('emoticonsKanto', f, bx, by - 30)) this.fxSprite('exclamation', 0, bx, by - 30);
      } else this.fxSprite('exclamation', 0, bx, by - 30 + (t < 120 ? 4 : 0));
    }
  }

  /**
   * ObjectEventGetNearbyReflectionType: the reflective tiles (pond water, puddles, ice) in the
   * sprite's height below its feet, or null when there are none. 'ice' reflections keep the
   * sprite's colours, water ones are tinted.
   */
  reflectionTiles(e, h) {
    const w = this.world;
    if (!w) return null;
    const ty = Math.floor(e.py / 16);
    const rows = Math.max(1, Math.ceil(h / 16)) + (Math.floor(e.py / 16) !== Math.ceil(e.py / 16) ? 1 : 0);
    let tiles = null;
    let ice = false;
    for (let i = 1; i <= rows; i++) {
      // a sprite between two tiles reflects in both columns (and rows) it spans
      const x0 = Math.floor(e.px / 16), x1 = Math.ceil(e.px / 16);
      for (const x of x0 === x1 ? [x0] : [x0, x1]) {
        const b = w.behAt(x, ty + i);
        if (b !== 'POND_WATER' && b !== 'PUDDLE' && b !== 'ICE') continue;
        (tiles || (tiles = [])).push([x, ty + i]);
        if (b === 'ICE') ice = true;
      }
    }
    return tiles && { tiles, ice };
  }

  /**
   * The sprite frame drawn at ([dx], [dy]) mirrored about its feet line [footY] (so a jump drops
   * the reflection as far as it lifts the sprite), clipped to the reflective tiles of [refl].
   */
  drawReflection(img, sx, sy, fw, fh, dx, dy, footY, mirror, refl, camX, camY) {
    const ctx = this.ctx;
    let c = this._reflCanvas;
    if (!c) c = this._reflCanvas = document.createElement('canvas');
    if (c.width !== fw || c.height !== fh) (c.width = fw), (c.height = fh);
    const rc = c.getContext('2d');
    rc.globalCompositeOperation = 'source-over';
    rc.clearRect(0, 0, fw, fh);
    rc.drawImage(img, sx, sy, fw, fh, 0, 0, fw, fh);
    if (!refl.ice) {
      // the reflection palette: the sprite's colours darkened towards the water's blue
      rc.globalCompositeOperation = 'source-atop';
      rc.fillStyle = 'rgba(40, 72, 152, 0.45)';
      rc.fillRect(0, 0, fw, fh);
    }
    ctx.save();
    ctx.beginPath();
    for (const [x, y] of refl.tiles) ctx.rect(x * 16 - camX, y * 16 - camY, 16, 16);
    ctx.clip();
    ctx.globalAlpha = refl.ice ? 0.5 : 0.7;
    ctx.translate(0, footY * 2);
    ctx.scale(mirror ? -1 : 1, -1);
    ctx.drawImage(c, mirror ? -dx - fw : dx, dy, fw, fh);
    ctx.restore();
  }

  /**
   * The surf blob in the player's palette (FldEff_SurfBlob: oam.paletteNum = 0, PALSLOT_PLAYER):
   * FireRed's own 6-frame blob in player.pal, Emerald's in Brendan's or May's palette.
   */
  surfBlob(e, region, now, x, y) {
    const mirror = e.facing === 'right';
    if (region === 0) {
      const f = (SURF_FRAME_KANTO[e.facing] ?? 0) + (((now / 800) | 0) & 1);
      if (this.fxSprite('surfBlobKanto', f, x, y, mirror)) return;
    } else if (this.isFemale(e, region) && this.fxSprite('surfBlobMay', SURF_FRAME[e.facing] ?? 0, x, y, mirror)) return;
    this.fxSprite('surfBlob', SURF_FRAME[e.facing] ?? 0, x, y, mirror);
  }

  isFemale(e, region) {
    const man = assets.manifest;
    const p = man && man.players && man.players[String(region)];
    return !!(p && (e.female || e.gfx === p.femaleGfx));
  }

  /**
   * The Acro Bike frame for [e] (sAnimTable_AcroBike, exported as manifest `anims`): the pop and
   * lower animations play once, a wheelie stands on frame 10/14/18 (the end of
   * ANIM_BUNNY_HOP_BACK_WHEEL_*) and pedals through ANIM_MOVING_WHEELIE_* while rolling.
   */
  acroFrame(e, anims, face, now) {
    const play = (a, t, loop) => {
      const seq = a && a[face];
      if (!seq || !seq.length) return undefined;
      const total = seq.reduce((s, x) => s + x[1], 0);
      let fr = Math.floor(t / (1000 / 60));
      if (fr >= total) {
        if (!loop) return null;
        fr %= total;
      }
      for (const [i, n] of seq) {
        if (fr < n) return i;
        fr -= n;
      }
      return seq[seq.length - 1][0];
    };
    const a = e.acroAnim;
    if (a && anims[a.name]) {
      const i = play(anims[a.name], now - a.t0, false);
      if (i !== null && i !== undefined) return i;
    }
    const pose = e.acro;
    if (pose !== 'wheelie' && pose !== 'hop') return undefined;
    if (pose === 'wheelie' && e.moving && !e.jumping) return play(anims.movingWheelie, now, true);
    const hop = anims.bunnyHopBackWheel && anims.bunnyHopBackWheel[face];
    return hop ? hop[hop.length - 1][0] : undefined;
  }

  /**
   * The cells a secret base replaces on the current map: [{x, y, img, cell}] (atlas row 0 = bottom
   * layer, row 1 = top layer), or null.
   */
  secretBaseCells(world) {
    const m = assets.manifest && assets.manifest.secretBases;
    if (!m || !world.secretBase) return null;
    const out = [];
    const o = sbOverlay(world);
    if (o) {
      const b = m.bases[world.mapKey];
      if (!b._img) b._img = assets.image(b.atlas);
      if (!b._img.ready) return null;
      for (const [i, c] of o.cells) if (c.cell >= 0) out.push({ x: i % o.w, y: (i / o.w) | 0, img: b._img.img, cell: c.cell });
    }
    const spots = sbOpenSpots(world);
    if (spots.length && m.entrances) {
      if (!m._entImg) m._entImg = assets.image(m.entrances);
      if (m._entImg.ready) for (const s of spots) out.push({ x: s.x, y: s.y, img: m._entImg.img, cell: s.cell });
    }
    return out.length ? out : null;
  }

  /** r5-scripts: the cells the server's map scripts replaced (manifest scriptTiles atlas). */
  scriptTileCells(world) {
    const tiles = world.scriptTiles;
    if (!tiles || !tiles.length) return null;
    const m = assets.manifest && assets.manifest.scriptTiles && assets.manifest.scriptTiles[world.mapKey];
    if (!m) return null;
    if (!m._img) m._img = assets.image(m.atlas);
    if (!m._img.ready) return null;
    const out = [];
    for (const t of tiles) {
      const cell = m.ids.indexOf(t.tile);
      if (cell >= 0) out.push({ x: t.x, y: t.y, img: m._img.img, cell });
    }
    return out.length ? out : null;
  }

  drawCells(cells, row, camX, camY) {
    for (const c of cells) {
      const dx = c.x * 16 - camX, dy = c.y * 16 - camY;
      if (dx < -16 || dy < -16 || dx > VW || dy > VH) continue;
      this.ctx.drawImage(c.img, c.cell * 16, row, 16, 16, dx, dy, 16, 16);
    }
  }

  /** The decoration being placed (decoration.c's cursor): its outline, green where it may go. */
  decorCursor(world, camX, camY, now) {
    const c = world.decorCursor;
    const ctx = this.ctx;
    const x = c.x * 16 - camX, y = (c.y - c.h + 1) * 16 - camY;
    ctx.save();
    ctx.globalAlpha = 0.35 + 0.15 * Math.sin(now / 120);
    ctx.fillStyle = c.ok ? '#30d060' : '#e03030';
    ctx.fillRect(x, y, c.w * 16, c.h * 16);
    ctx.globalAlpha = 1;
    ctx.strokeStyle = c.ok ? '#f8f8f8' : '#ffb0b0';
    ctx.lineWidth = 1;
    ctx.strokeRect(x + 0.5, y + 0.5, c.w * 16 - 1, c.h * 16 - 1);
    ctx.restore();
  }

  /** The sprite sheet for an entity, swapped for its bike/surf/underwater variant (players). */
  sheetForMode(e, region) {
    if (e.kind === 'player' && e.mode && e.mode !== 'walk') {
      const man = assets.manifest;
      const p = man && man.players && man.players[String(region)];
      const female = p && (e.female || e.gfx === p.femaleGfx);
      const table = MODE_GFX[region] && MODE_GFX[region][female ? 'f' : 'm'];
      const gfx = table && table[e.mode];
      if (gfx !== undefined) {
        const d = man.objects[`${region}:${gfx}`];
        if (d) {
          if (!d._img) d._img = assets.image(d.sheet);
          if (d._img.ready) return d;
        }
      }
    }
    return assets.sheetFor(e, region);
  }

  /** One frame of a field effect sprite (manifest fieldEffects) at screen (x, y). */
  fxSprite(name, frame, x, y, mirror = false) {
    const m = assets.manifest && assets.manifest.fieldEffects && assets.manifest.fieldEffects[name];
    if (!m) return false;
    if (!m._img) m._img = assets.image(m.sheet);
    if (!m._img.ready) return false;
    const img = m._img.img;
    const perRow = Math.max(1, Math.floor(img.width / m.frameW));
    const i = Math.max(0, Math.min(m.count - 1, frame | 0));
    const sx = (i % perRow) * m.frameW, sy = Math.floor(i / perRow) * m.frameH;
    const ctx = this.ctx;
    if (mirror) {
      ctx.save();
      ctx.scale(-1, 1);
      ctx.drawImage(img, sx, sy, m.frameW, m.frameH, -x - m.frameW, y, m.frameW, m.frameH);
      ctx.restore();
    } else ctx.drawImage(img, sx, sy, m.frameW, m.frameH, x, y, m.frameW, m.frameH);
    return true;
  }

  /** Re-draw the lower half of a grass tile over a sprite standing in it. */
  grassCover(tx, ty, camX, camY, long) {
    const w = this.world;
    const r = w.res;
    let img = null, ox = 0, oy = 0;
    if (w.map && tx >= 0 && ty >= 0 && tx < w.map.width && ty < w.map.height) img = r && r.bottom && r.bottom.ready ? r.bottom.img : null;
    else {
      const c = w.conns.find((c) => tx >= c.ox && ty >= c.oy && tx < c.ox + c.info.width && ty < c.oy + c.info.height);
      if (c && c.res.bottom && c.res.bottom.ready) (img = c.res.bottom.img), (ox = c.ox), (oy = c.oy);
    }
    if (!img) return;
    const cut = long ? 4 : 8; // long grass hides more of the sprite
    this.ctx.drawImage(img, (tx - ox) * 16, (ty - oy) * 16 + cut, 16, 16 - cut, tx * 16 - camX, ty * 16 - camY + cut, 16, 16 - cut);
  }

  /** Ground effects under the sprites (footprints, splashes, rustles), GBA timing. */
  effects(world, camX, camY, now) {
    for (const f of world.fx) {
      if (now < f.t0) continue;
      const p = (now - f.t0) / f.dur;
      const x = f.x * 16 - camX, y = f.y * 16 - camY;
      if (x < -16 || y < -16 || x > VW || y > VH) continue;
      switch (f.kind) {
        case 'footprints':
        case 'deepFootprints':
        case 'tireTracks': {
          // vertical/horizontal frames; they blink out at the end like the GBA's
          if (p > 0.75 && ((now / 66) | 0) & 1) break;
          const horiz = f.dir === 'left' || f.dir === 'right';
          this.fxSprite(f.kind, horiz ? 1 : 0, x, y, f.dir === 'right');
          break;
        }
        case 'tallGrass':
        case 'longGrass':
        case 'ripple':
        case 'ashPuff':
          this.fxSprite(f.kind, Math.floor(p * 5), x, y);
          break;
        case 'splash':
          this.fxSprite('splash', Math.floor(p * 2), x, y + 8);
          break;
        case 'jumpSplash':
        case 'jumpGrass':
        case 'dust':
          this.fxSprite(f.kind, Math.floor(p * 3), x, y + 8);
          break;
      }
    }
  }

  /** Dark caves: black outside the FLASH circle around the player (radius as the GBA's). */
  darkness(world, camX, camY) {
    const f = world.field;
    if (!f || !f.dark) return;
    const ctx = this.ctx;
    const me = world.self;
    const cx = Math.round(me.px) - camX + 8, cy = Math.round(me.py) - camY + 8;
    const r = f.flash ? 72 : 24;
    ctx.save();
    ctx.fillStyle = '#000';
    ctx.beginPath();
    ctx.rect(0, 0, VW, VH);
    ctx.arc(cx, cy, r, 0, Math.PI * 2, true);
    ctx.fill('evenodd');
    ctx.restore();
  }

  /** Animated border cells (the sea around routes): the map's anim atlas, border-patterned. */
  borderAnim(res, camX, camY, mapW, mapH) {
    const slots = res.info && res.info.borderAnim;
    const a = res.anim;
    if (!slots || !a || !a.atlas || !a.atlas.ready) return;
    stepAnim(a, this.gbaFrame);
    const bw = res.info.borderWidth || 2, bh = res.info.borderHeight || 2;
    const x0 = Math.floor(camX / 16), y0 = Math.floor(camY / 16);
    const x1 = Math.ceil((camX + VW) / 16), y1 = Math.ceil((camY + VH) / 16);
    const img = a.atlas.img, cols = a.cols, ctx = this.ctx;
    for (let y = y0; y < y1; y++)
      for (let x = x0; x < x1; x++) {
        if (x >= 0 && y >= 0 && x < mapW && y < mapH) continue;
        if (this.world && this.world.conns.some((c) => x >= c.ox && y >= c.oy && x < c.ox + c.info.width && y < c.oy + c.info.height)) continue;
        const s = slots[(((y % bh) + bh) % bh) * bw + (((x % bw) + bw) % bw)];
        if (s === undefined || s < 0 || !a.metas[s]) continue;
        const cell = a.metas[s].cell;
        ctx.drawImage(img, (cell % cols) * 16, ((cell / cols) | 0) * 32, 16, 16, x * 16 - camX, y * 16 - camY, 16, 16);
      }
  }

  /** Canvas-relative CSS px -> world tile, using the current camera. */
  pickTile(cssX, cssY) {
    const r = this.canvas.getBoundingClientRect();
    const lx = ((cssX - r.left) / r.width) * VW;
    const ly = ((cssY - r.top) / r.height) * VH;
    return { lx, ly, wx: lx + this.camX, wy: ly + this.camY };
  }
}
