// r5-scripts: what the server's decomp script interpreter shows besides text boxes (PROTOCOL.md
// "r5-scripts"): its menus (multichoice / elevator floors, the party picker, the in-game trade
// scene, the naming screen) and its effects (sounds and music, screen fades and shakes, the mon
// picture, the money / coins boxes, changed metatiles). State: `ui.script` {menu, fx, metatiles}.
import { h } from '../util.js';
import { panels, rerender, setFocus } from './panels.js';
import { monImg } from './common.js';
import { music } from '../audio.js';
import { assets } from '../assets.js';

let app;
let menuSeq = -1;
let lastFx = -1;
let firstState = true;

export function init(a) {
  app = a;
  if (!document.querySelector('link[data-scripts-css]')) {
    document.head.append(h('link', { rel: 'stylesheet', href: 'css/scripts.css', 'data-scripts-css': '1' }));
  }
  // Inside the canvas-sized overlay, below the text box so a message shows over a fade.
  const ov = document.querySelector('#ov');
  if (ov && !document.querySelector('#script-overlay')) {
    ov.prepend(h('div#script-overlay',
      h('div#script-fade'),
      h('div#script-monpic'),
      h('div#script-money.hidden'),
      h('div#script-coins.hidden')));
  }
}

const region = () => (app.net.state.world && app.net.state.world.map ? app.net.state.world.map.region : 0);
const reply = (seq, value, text) => app.send({ t: 'script.reply', seq, value, ...(text !== undefined ? { text } : {}) });

export function update(state, changed) {
  if (!changed.includes('ui')) return;
  const s = state.ui && state.ui.script;
  if (!s) return;
  // effects newer than the last one played (on the first state only remember where we are)
  for (const f of s.fx || []) {
    if (f.n <= lastFx) continue;
    lastFx = f.n;
    if (!firstState) playFx(f);
  }
  firstState = false;
  app.world.scriptTiles = s.metatiles || [];
  syncMenu(s.menu);
}

// ------------------------------------------------------------------ effects
function playFx(f) {
  const a = f.args || {};
  switch (f.kind) {
    case 'sound': return sound(a);
    case 'fade': return fade(a.mode);
    case 'fx': return effect(a);
    case 'monpic': return monpic(a);
    case 'moneybox': return box('#script-money', a.hide, () => `₽${a.money}`);
    case 'coinsbox': return box('#script-coins', a.hide, () => `COINS ${a.coins}`);
    default: return null;
  }
}

function sound(a) {
  const r = region();
  if (a.kind === 'se' || a.kind === 'fanfare') return music.oneShot(r, a.name, { fanfare: a.kind === 'fanfare' });
  if (a.kind === 'cry') return null; // cries are in the battle assets; the field plays none yet
  if (a.kind === 'bgm' && a.name) return music.play(r, a.name);
  if (a.kind === 'encounter' && a.name) return music.play(r, 'MUS_ENCOUNTER_' + a.name);
  if (a.kind === 'fadeout') return music.play(r, null);
  if (a.kind === 'bgm' && a.default) {
    const info = assets.manifest && assets.manifest.maps[app.world.mapKey];
    if (info && info.music) music.play(r, info.music);
  }
  return null;
}

function fade(mode) {
  // FADE_FROM_BLACK 0, FADE_TO_BLACK 1, FADE_FROM_WHITE 2, FADE_TO_WHITE 3
  const el = document.querySelector('#script-fade');
  if (!el) return;
  el.classList.toggle('white', mode === 2 || mode === 3);
  el.classList.toggle('on', mode === 1 || mode === 3);
}

function effect(a) {
  const screen = document.querySelector('#screen');
  if (a.kind === 'shake' && screen) {
    screen.style.setProperty('--shake-x', `${Math.min(6, a.x || 0) + 1}px`);
    screen.style.setProperty('--shake-y', `${Math.min(6, a.y || 0) + 1}px`);
    screen.classList.remove('shake');
    void screen.offsetWidth;
    screen.classList.add('shake');
    setTimeout(() => screen.classList.remove('shake'), Math.max(200, a.ms || 600));
  } else if (a.kind === 'flash') {
    app.world.flash = a.level === 0;
  } else if (a.kind === 'map_name' && a.name) {
    const m = document.querySelector('#mapname');
    if (m) m.classList.add('pop'), setTimeout(() => m.classList.remove('pop'), 1500);
  }
}

function monpic(a) {
  const el = document.querySelector('#script-monpic');
  if (!el) return;
  if (!a.show) return el.replaceChildren(), el.classList.remove('on');
  el.replaceChildren(h('div.frame', monImg(a.dex, 'front', '.pic')));
  el.classList.add('on');
  if (a.hatch) setTimeout(() => el.classList.remove('on'), 2500);
}

function box(sel, hide, text) {
  const el = document.querySelector(sel);
  if (!el) return;
  if (hide) return el.classList.add('hidden');
  el.textContent = text();
  el.classList.remove('hidden');
}

// ------------------------------------------------------------------ menus
function syncMenu(m) {
  if (!m) {
    if (menuSeq >= 0) panels.close('scriptmenu');
    menuSeq = -1;
    return;
  }
  if (m.seq === menuSeq) return;
  menuSeq = m.seq;
  const a = m.args || {};
  const cancelValue = m.kind === 'party' ? -1 : (a.cancel ? 127 : null);
  const p = panels.open('scriptmenu', titleOf(m), {
    onBack: () => {
      if (cancelValue === null) return; // a menu that cannot be cancelled (ignoreBPress)
      reply(m.seq, cancelValue);
    },
  });
  p.title.textContent = titleOf(m);
  p.el.classList.add('script-menu', 'kind-' + m.kind);
  if (m.kind === 'party') renderParty(p, m);
  else if (m.kind === 'trade') renderTrade(p, m);
  else if (m.kind === 'name') renderName(p, m);
  else renderList(p, m);
}

function titleOf(m) {
  const a = m.args || {};
  if (m.kind === 'party') return a.prompt || 'Choose a POKéMON';
  if (m.kind === 'trade') return 'Trade';
  if (m.kind === 'name') return 'Give it a nickname?';
  if (m.kind === 'elevator') return 'Which floor?';
  return 'Choose';
}

function renderList(p, m) {
  const a = m.args || {};
  const items = a.items || [];
  const cols = Math.max(1, a.columns || 1);
  rerender(p.body, () => h('div.choices', { style: `grid-template-columns: repeat(${cols}, 1fr)` },
    items.map((t, i) => h('button', { type: 'button', 'data-k': 'c' + i, on: { click: () => reply(m.seq, i) } }, t))));
  setFocus(p.body, p.body.querySelector(`[data-k="c${a.default || 0}"]`) || p.body.querySelector('button'));
}

function renderParty(p, m) {
  const mons = (m.args && m.args.mons) || [];
  rerender(p.body, () => [
    h('div.list', mons.map((x) => h('div.item.mon', monImg(x.egg ? 0 : x.dex),
      h('span.nm', x.egg ? 'EGG' : x.name, h('small', x.egg ? '' : `Lv${x.level} · HP ${x.hp}`)),
      h('span.act', h('button.go', { type: 'button', 'data-k': 'm' + x.slot, on: { click: () => reply(m.seq, x.slot) } }, 'Choose'))))),
    h('button', { type: 'button', 'data-k': 'cancel', on: { click: () => reply(m.seq, -1) } }, 'Cancel'),
  ]);
  setFocus(p.body, p.body.querySelector('.go'));
}

function renderTrade(p, m) {
  const a = m.args || {};
  // The trade scene: the player's mon leaves, the partner's arrives (trade.c's animation, short).
  const give = h('div.trade-mon.give', monImg(a.giveDex, 'front', '.pic'), h('span', a.giveName || ''));
  const get = h('div.trade-mon.get', monImg(a.getDex, 'front', '.pic'), h('span', `${a.getName || ''} (OT ${a.ot || ''})`));
  rerender(p.body, () => h('div.trade-scene', give, h('div.arrow', '⇄'), get));
  setTimeout(() => give.classList.add('away'), 300);
  setTimeout(() => get.classList.add('here'), 1400);
  setTimeout(() => { if (menuSeq === m.seq) reply(m.seq, 0); }, 2800);
}

function renderName(p, m) {
  const a = m.args || {};
  const input = h('input', { type: 'text', maxlength: a.max || 10, value: a.current || '', 'data-k': 'name' });
  rerender(p.body, () => h('div.name',
    input,
    h('button', { type: 'button', 'data-k': 'ok', on: { click: () => reply(m.seq, 0, input.value) } }, 'OK'),
    h('button', { type: 'button', 'data-k': 'no', on: { click: () => reply(m.seq, -1) } }, 'Cancel')));
  setFocus(p.body, p.body.querySelector('[data-k="ok"]'));
}

export const isOpen = () => menuSeq >= 0;
