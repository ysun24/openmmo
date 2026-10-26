#!/usr/bin/env node
// Dev-only: generates PLACEHOLDER assets in the PROTOCOL.md section 4 layout from the pret
// pokefirered decomp layouts (collision/elevation come from the real map.bin; graphics are
// flat coloured stand-ins). Used until the real exporter output (web.assets) exists.
//
//   node gen-placeholder-assets.js [outDir]   (default: ./placeholder-assets)
'use strict';
const fs = require('fs');
const path = require('path');
const { Img } = require('./png');

const ROOT = path.resolve(__dirname, '../..');
const DECOMP = path.join(ROOT, 'decomp/pokefirered');
const OUT = path.resolve(process.argv[2] || path.join(__dirname, 'placeholder-assets'));
const WANT = ['PalletTown', 'Route1', 'Route21_North', 'ViridianCity', 'Route22', 'Route2'];

const json = (p) => JSON.parse(fs.readFileSync(path.join(DECOMP, p), 'utf8'));
const groups = json('data/maps/map_groups.json');
const layouts = Object.fromEntries(json('data/layouts/layouts.json').layouts.filter((l) => l.id).map((l) => [l.id, l]));

const keyOf = {};
const constToName = {};
groups.group_order.forEach((g, gi) =>
  groups[g].forEach((m, mi) => {
    keyOf[m] = `0:${gi}:${mi}`;
    const mj = json(`data/maps/${m}/map.json`);
    constToName[mj.id] = m;
  }),
);

function hash(n) {
  n = (n ^ 61) ^ (n >>> 16);
  n = (n + (n << 3)) | 0;
  n ^= n >>> 4;
  n = Math.imul(n, 0x27d4eb2d);
  return (n ^ (n >>> 15)) >>> 0;
}
function tileColor(meta, blocked) {
  const h = hash(meta);
  if (blocked) return [40 + (h % 30), 90 + ((h >> 8) % 40), 50 + ((h >> 16) % 30)];
  return [150 + (h % 40), 200 + ((h >> 8) % 40), 110 + ((h >> 16) % 40)];
}
function drawMeta(img, ox, oy, v) {
  const meta = v & 0x3ff;
  const blocked = (v >> 10) & 3;
  const c = tileColor(meta, blocked);
  img.rect(ox, oy, 16, 16, c);
  const d = c.map((x) => Math.max(0, x - 25));
  for (let i = 0; i < 16; i++) img.set(ox + i, oy + 15, d);
  for (let j = 0; j < 16; j++) img.set(ox + 15, oy + j, d);
  if (blocked) {
    img.disc(ox + 8, oy + 7, 5, c.map((x) => Math.min(255, x + 30)));
  } else if (hash(meta) % 5 === 0) {
    img.set(ox + 4, oy + 5, d);
    img.set(ox + 10, oy + 9, d);
  }
}
function readU16(p) {
  const b = fs.readFileSync(path.join(DECOMP, p));
  const a = [];
  for (let i = 0; i < b.length; i += 2) a.push(b.readUInt16LE(i));
  return a;
}

fs.mkdirSync(path.join(OUT, 'maps'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'maps/0'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'objects/0'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'pokemon'), { recursive: true });
const manifest = { version: 1, placeholder: true, maps: {}, objects: {}, players: {}, pokemon: {}, ui: { font: null } };

for (const name of WANT) {
  const mj = json(`data/maps/${name}/map.json`);
  const lay = layouts[mj.layout];
  const w = lay.width, h = lay.height;
  const blocks = readU16(lay.blockdata_filepath);
  const border = readU16(lay.border_filepath);
  const key = keyOf[name];
  const base = '0/' + lay.name.replace(/_Layout$/, '').toLowerCase();
  const bottom = new Img(w * 16, h * 16);
  const top = new Img(w * 16, h * 16);
  const col = Buffer.alloc(w * h);
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const v = blocks[y * w + x];
      drawMeta(bottom, x * 16, y * 16, v);
      // a "roof" on blocked tiles that sit above a walkable tile: exercises the top layer
      const below = y + 1 < h ? blocks[(y + 1) * w + x] : v;
      if ((v >> 10) & 3 && !((below >> 10) & 3)) top.rect(x * 16 + 2, y * 16 + 10, 12, 6, [30, 70, 40, 220]);
      col[y * w + x] = ((v >> 10) & 3 ? 1 : 0) | (((v >> 12) & 0xf) << 4);
    }
  const bimg = new Img(lay.border_width * 16, lay.border_height * 16);
  border.forEach((v, i) => drawMeta(bimg, (i % lay.border_width) * 16, Math.floor(i / lay.border_width) * 16, v));
  fs.writeFileSync(path.join(OUT, `maps/${base}.bottom.png`), bottom.encode());
  fs.writeFileSync(path.join(OUT, `maps/${base}.top.png`), top.encode());
  fs.writeFileSync(path.join(OUT, `maps/${base}.border.png`), bimg.encode());
  fs.writeFileSync(path.join(OUT, `maps/${base}.col.bin`), col);
  manifest.maps[key] = {
    name, width: w, height: h,
    bottom: `maps/${base}.bottom.png`, top: `maps/${base}.top.png`,
    collision: `maps/${base}.col.bin`, border: `maps/${base}.border.png`, borderWidth: lay.border_width, borderHeight: lay.border_height,
    connections: (mj.connections || [])
      .filter((c) => keyOf[constToName[c.map]])
      .map((c) => ({ dir: c.direction, offset: c.offset, map: keyOf[constToName[c.map]] })),
    music: null,
  };
}

// --- object sheets: 9 frames 16x32: down, up, left, walkDown x2, walkUp x2, walkLeft x2
function person(img, fx, body, hair, facing, step) {
  const ox = fx * 16;
  const skin = [248, 208, 168];
  const legOff = step === 0 ? 0 : step === 1 ? 1 : -1;
  // shadow
  img.rect(ox + 4, 29, 8, 2, [0, 0, 0, 70]);
  // legs
  img.rect(ox + 5, 25 + Math.max(0, legOff), 2, 4 - Math.max(0, legOff), [60, 60, 90]);
  img.rect(ox + 9, 25 + Math.max(0, -legOff), 2, 4 - Math.max(0, -legOff), [60, 60, 90]);
  // body
  img.rect(ox + 4, 18, 8, 8, body);
  img.rect(ox + 3, 19, 1, 5, skin);
  img.rect(ox + 12, 19, 1, 5, skin);
  // head
  img.disc(ox + 8, 13, 5, skin);
  img.rect(ox + 3, 7, 10, 4, hair);
  if (facing === 'up') img.rect(ox + 3, 7, 10, 9, hair);
  if (facing === 'down') {
    img.set(ox + 6, 14, [20, 20, 20]);
    img.set(ox + 10, 14, [20, 20, 20]);
  }
  if (facing === 'left') {
    img.rect(ox + 9, 7, 4, 8, hair);
    img.set(ox + 5, 14, [20, 20, 20]);
  }
}
function sheet(body, hair) {
  const img = new Img(16 * 9, 32);
  const fr = [['down', 0], ['up', 0], ['left', 0], ['down', 1], ['down', 2], ['up', 1], ['up', 2], ['left', 1], ['left', 2]];
  fr.forEach(([f, s], i) => person(img, i, body, hair, f, s));
  return img.encode();
}
const FRAMES = { down: 0, up: 1, left: 2, walkDown: [3, 4], walkUp: [5, 6], walkLeft: [7, 8] };
for (let g = 0; g < 160; g++) {
  const h = hash(g + 7);
  fs.writeFileSync(path.join(OUT, `objects/0/${g}.png`), sheet([h % 200 + 40, (h >> 8) % 200 + 40, (h >> 16) % 200 + 40], [(h >> 4) % 120, (h >> 12) % 80, (h >> 20) % 60]));
  manifest.objects[`0:${g}`] = { name: `PLACEHOLDER_${g}`, sheet: `objects/0/${g}.png`, frameW: 16, frameH: 32, count: 9, frames: FRAMES, mirrorRight: true };
}
fs.writeFileSync(path.join(OUT, 'objects/0/0.png'), sheet([200, 40, 40], [210, 30, 30]));
fs.writeFileSync(path.join(OUT, 'objects/0/7.png'), sheet([60, 120, 220], [240, 220, 120]));
manifest.players = { 0: { male: 'objects/0/0.png', maleGfx: 0, female: 'objects/0/7.png', femaleGfx: 7 } };

// --- pokemon: front/back 64x64 blobs, icon 32x32
for (let d = 1; d <= 386; d++) {
  const h = hash(d * 31);
  const c = [h % 180 + 60, (h >> 8) % 180 + 60, (h >> 16) % 180 + 60];
  const dark = c.map((x) => x - 50);
  const front = new Img(64, 64);
  front.disc(32, 38, 20, dark);
  front.disc(32, 37, 18, c);
  front.disc(24, 30, 3, [255, 255, 255]);
  front.disc(40, 30, 3, [255, 255, 255]);
  front.set(24, 30, [0, 0, 0]);
  front.set(40, 30, [0, 0, 0]);
  front.disc(18, 18, 5, c);
  front.disc(46, 18, 5, c);
  const back = new Img(64, 64);
  back.disc(32, 40, 22, dark);
  back.disc(32, 39, 20, c);
  back.disc(16, 22, 6, c);
  back.disc(48, 22, 6, c);
  const icon = new Img(32, 32);
  icon.disc(16, 18, 9, dark);
  icon.disc(16, 18, 8, c);
  icon.set(13, 16, [0, 0, 0]);
  icon.set(19, 16, [0, 0, 0]);
  fs.writeFileSync(path.join(OUT, `pokemon/${d}.front.png`), front.encode());
  fs.writeFileSync(path.join(OUT, `pokemon/${d}.back.png`), back.encode());
  fs.writeFileSync(path.join(OUT, `pokemon/${d}.icon.png`), icon.encode());
  manifest.pokemon[String(d)] = { front: `pokemon/${d}.front.png`, back: `pokemon/${d}.back.png`, icon: `pokemon/${d}.icon.png` };
}

fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(manifest));
console.log(`placeholder assets -> ${OUT} (${Object.keys(manifest.maps).length} maps)`);
