// The secret base PC (decoration.c / secret_base.c, PROTOCOL.md section 11): DECORATION places a
// decoration from the PC with an on-map cursor, PUT AWAY returns one, PACK UP moves out, TURN OFF.
// The server checks every placement (SecretBaseRules); the cursor's colour is the same rule
// predicted here (secretbase.js canPlace).
import { h } from '../util.js';
import { panels, setFocus, rerender } from './panels.js';
import { canPlace } from '../secretbase.js';
import { assets } from '../assets.js';

let app = null;
let lastSeq = null;
/** Placing or putting away: {mode: 'place'|'putaway', id, w, h, x, y} on world.decorCursor. */
let cursor = null;

const DIRS = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] };

export function init(a) {
  app = a;
}

const base = () => (app.net.state.world && app.net.state.world.secretBase) || null;

export function update(state) {
  const s = state.world && state.world.secretBase;
  const ui = s && s.ui;
  if (ui && lastSeq !== null && ui.seq !== lastSeq && ui.kind === 'pc') queueMicrotask(openPc);
  if (ui) lastSeq = ui.seq;
  else if (lastSeq === null && s) lastSeq = 0;
  if (cursor && (!s || s.inside !== 'own')) stopCursor();
  if (panels.isOpen('sbdecor')) renderDecor();
}

/** True while the on-map cursor takes the buttons. */
export function placing() {
  return !!cursor;
}

export function openPc() {
  const s = base();
  if (!s || s.inside !== 'own') return;
  const p = panels.open('sbpc', 'SECRET BASE PC', { onBack: () => turnOff() });
  const act = (label, k, fn) => h('button', { type: 'button', 'data-k': k, on: { click: fn } }, label);
  rerender(p.body, () => [
    h('p.muted', 'What would you like to do?'),
    act('DECORATION', 'sb-decor', () => openDecor()),
    act('PUT AWAY', 'sb-putaway', () => startCursor('putaway', 0)),
    act('PACK UP', 'sb-packup', () => packUp()),
    act('TURN OFF', 'sb-off', () => turnOff()),
  ]);
  setFocus(p.body, p.body.querySelector('button'));
}

function turnOff() {
  panels.close('sbdecor');
  panels.close('sbpc');
  app.send({ t: 'secretbase', action: 'close' });
}

function packUp() {
  const p = panels.open('sbpack', 'PACK UP');
  rerender(p.body, () => [
    h('p', 'All decorations and furniture in your SECRET BASE will be returned to your PC. Is that okay?'),
    h('button', { type: 'button', 'data-k': 'sb-pack-yes', on: { click: () => { panels.close('sbpack'); panels.close('sbpc'); app.send({ t: 'secretbase', action: 'packup' }); } } }, 'YES'),
    h('button', { type: 'button', 'data-k': 'sb-pack-no', on: { click: () => panels.close('sbpack') } }, 'NO'),
  ]);
  setFocus(p.body, p.body.querySelectorAll('button')[1]);
}

/** The decorations in the PC that are not placed yet. */
function available() {
  const owned = (app.net.state.decor && app.net.state.decor.owned) || [];
  const placed = (base() && base().decorations) || [];
  return owned
    .map((d) => ({ ...d, left: d.qty - placed.filter((p) => p.id === d.id).length }))
    .filter((d) => d.left > 0);
}

function openDecor() {
  panels.open('sbdecor', 'DECORATION');
  renderDecor();
  const p = panels.get('sbdecor');
  setFocus(p.body, p.body.querySelector('button'));
}

function renderDecor() {
  const p = panels.get('sbdecor');
  if (!p) return;
  const list = available();
  const m = assets.manifest.secretBases;
  const placed = (base() && base().decorations) || [];
  rerender(p.body, () => [
    h('p.muted', `Placed ${placed.length} of 16.`),
    list.length
      ? h('div', list.map((d) => {
        const spec = m.decorations[d.id] || {};
        return h('button', { type: 'button', 'data-k': 'sb-d' + d.id, on: { click: () => startCursor('place', d.id) } },
          `${d.name}  ×${d.left}  (${spec.w}×${spec.h})`);
      }))
      : h('p.muted', 'There are no decorations in the PC. Buy some at a decoration shop.'),
  ]);
}

function startCursor(mode, id) {
  const m = assets.manifest.secretBases;
  const spec = id ? m.decorations[id] : { w: 1, h: 1 };
  const me = app.world.self;
  panels.close('sbdecor');
  panels.close('sbpc');
  cursor = { mode, id, w: spec.w, h: spec.h, x: me.x, y: me.y - 1 };
  refresh();
}

function stopCursor() {
  cursor = null;
  app.world.decorCursor = null;
}

function refresh() {
  const me = app.world.self;
  const ok = cursor.mode === 'place'
    ? canPlace(app.world, cursor.id, cursor.x, cursor.y, me.x, me.y)
    : (base().decorations || []).some((p) => covers(p, cursor.x, cursor.y));
  app.world.decorCursor = { x: cursor.x, y: cursor.y, w: cursor.w, h: cursor.h, ok };
}

function covers(p, x, y) {
  const d = assets.manifest.secretBases.decorations[p.id];
  if (!d) return false;
  if (d.gfx !== undefined) return p.x === x && p.y === y;
  return x >= p.x && x < p.x + d.w && y <= p.y && y > p.y - d.h;
}

/** The buttons while the cursor is up: move, A to place / put away, B to stop. */
export function onPress(btn) {
  if (!cursor) return false;
  if (DIRS[btn]) {
    const [dx, dy] = DIRS[btn];
    cursor.x += dx;
    cursor.y += dy;
    refresh();
    return true;
  }
  if (btn === 'a') {
    if (cursor.mode === 'place') {
      app.send({ t: 'secretbase', action: 'place', decor: cursor.id, x: cursor.x, y: cursor.y });
      stopCursor();
      openPc();
    } else {
      app.send({ t: 'secretbase', action: 'putaway', x: cursor.x, y: cursor.y });
    }
    return true;
  }
  if (btn === 'b') {
    stopCursor();
    openPc();
    return true;
  }
  return true;
}
