// r5-frontier: the battle facility screens (PROTOCOL.md "r5-frontier"). The server sends the whole
// screen as world.frontier = {open, seq, desk, answer, bp, symbols, screen:{kind, title, ...}}; every
// answer goes back as {t:'frontier', a:'answer', seq, ...}. Kinds: menu, message, team, between,
// rental, swap, dome, doors (Battle Pike), pyramid (Battle Pyramid floor), shop (Exchange Service
// Corner), card (Frontier Pass), wait, battle.
import { h } from '../util.js';
import { panels, setFocus, rerender } from './panels.js';
import { monImg } from './common.js';
import { isOpen as battleOpen } from './battle.js';

let app = null;
let shownSeq = -1;
let picks = [];
let swapMine = -1;
let swapTheirs = -1;

const FACILITIES = ['tower', 'dome', 'palace', 'arena', 'factory', 'pike', 'pyramid'];
const SYMBOL_NAMES = { tower: 'ABILITY', dome: 'TACTICS', palace: 'SPIRITS', arena: 'GUTS', factory: 'KNOWLEDGE', pike: 'LUCK', pyramid: 'BRAVE' };

export function init(a) {
  app = a;
}

const fr = () => (app.net.state.world && app.net.state.world.frontier) || null;

/** True while a facility screen is up (main.js keeps the player still). */
export function isOpen() {
  return panels.isOpen('frontier');
}

export function update(state) {
  const f = state.world && state.world.frontier;
  // The battle scene has the screen while a facility battle runs; the panel comes back after it.
  if (!f || !f.open || (f.screen && f.screen.kind === 'battle') || battleOpen()) {
    if (panels.isOpen('frontier')) panels.close('frontier');
    if (!f || !f.open) shownSeq = -1;
    else if (battleOpen() || f.screen.kind === 'battle') shownSeq = -1;
    return;
  }
  if (f.seq === shownSeq && panels.isOpen('frontier')) return;
  shownSeq = f.seq;
  picks = [];
  swapMine = -1;
  swapTheirs = -1;
  render(f);
}

function send(body) {
  app.send({ t: 'frontier', ...body });
}

function answer(body) {
  const f = fr();
  if (!f) return;
  send({ a: 'answer', seq: f.seq, ...body });
}

function btn(label, k, fn, cls = '') {
  return h('button' + cls, { type: 'button', 'data-k': k, on: { click: fn } }, label);
}

function render(f) {
  const s = f.screen || {};
  const p = panels.open('frontier', s.title || 'BATTLE FRONTIER', {
    onBack: () => {
      if (['menu', 'team', 'rental', 'message', 'shop', 'card'].includes(s.kind)) answer({ v: 'cancel' });
      if (!f.answer || s.kind === 'wait') send({ a: 'close' });
    },
  });
  p.el.classList.add('frontier');
  p.title.textContent = s.title || 'BATTLE FRONTIER';
  const head = h('div.fr-head', h('span.fr-bp', `BP ${f.bp ?? 0}`), symbolsRow(f.symbols || {}));
  const mirror = s.mirror ? h('p.muted', 'Your partner is choosing...') : null;
  const body = (KINDS[s.kind] || renderMessage)(s, f);
  rerender(p.body, () => [head, mirror, ...(Array.isArray(body) ? body : [body])].filter(Boolean));
  if (s.mirror) p.body.querySelectorAll('button').forEach((b) => (b.disabled = true));
  setFocus(p.body, p.body.querySelector('button:not(:disabled)'));
}

function symbolsRow(sym) {
  return h('span.fr-symbols', FACILITIES.map((k) =>
    h('span.fr-sym.s' + (sym[k] || 0), { title: `${SYMBOL_NAMES[k]} SYMBOL` }, (sym[k] || 0) >= 2 ? '◆' : (sym[k] || 0) === 1 ? '◇' : '·')));
}

function monCard(m, opts = {}) {
  const hp = typeof m.hp === 'number' && m.maxHp ? h('div.fr-hp', h('i', { style: `width:${Math.max(0, Math.min(100, (m.hp * 100) / m.maxHp))}%` })) : null;
  return h('div.fr-mon' + (opts.sel ? '.sel' : '') + (m.ok === false ? '.no' : ''),
    monImg(m.dex, 'icon'),
    h('div.fr-mi',
      h('b', `${m.name}${m.level ? '  Lv' + m.level : ''}`),
      m.status ? h('span.fr-st', ' ' + m.status.toUpperCase()) : null,
      h('div.muted', [m.item ? '@' + m.item : '', m.nature ? m.nature : '', m.ability || ''].filter(Boolean).join(' · ')),
      m.moves ? h('div.fr-moves', m.moves.join(' / ')) : null,
      hp,
      m.why ? h('div.fr-why', m.why) : null));
}

function renderMessage(s) {
  return [h('p.fr-text', s.text || ''), btn('OK', 'fr-ok', () => answer({}))];
}

function renderMenu(s) {
  return [h('p.fr-text', s.text || ''), h('div.fr-opts', (s.options || []).map((o) => btn(o.label, 'fr-o-' + o.id, () => answer({ v: o.id }))))];
}

function choosePanel(s, list, count, key, done) {
  const rows = list.map((m, i) => {
    const sel = picks.includes(i);
    return h('button.fr-pick' + (sel ? '.sel' : ''), {
      type: 'button', 'data-k': `${key}${i}`, disabled: m.ok === false,
      on: { click: () => { picks = sel ? picks.filter((x) => x !== i) : picks.length < count ? [...picks, i] : picks; render(fr()); } },
    }, monCard(m, { sel }), sel ? h('span.fr-order', String(picks.indexOf(i) + 1)) : null);
  });
  return [
    h('p.fr-text', s.text || ''),
    h('div.fr-list', rows),
    h('div.fr-opts',
      btn(`DECIDE (${picks.length}/${count})`, 'fr-decide', () => done(), picks.length === count ? '' : '.dim'),
      btn('CANCEL', 'fr-cancel', () => answer({ v: 'cancel' }))),
  ];
}

function renderTeam(s) {
  return choosePanel(s, s.party || [], s.count || 3, 'fr-t', () => {
    if (picks.length !== (s.count || 3)) return;
    answer({ slots: picks.map((i) => s.party[i].slot) });
  });
}

function renderRental(s) {
  return choosePanel(s, s.mons || [], s.count || 3, 'fr-r', () => {
    if (picks.length !== (s.count || 3)) return;
    answer({ picks });
  });
}

function renderBetween(s) {
  return [
    h('p.fr-text', s.text || ''),
    s.opponent ? h('p.fr-vs', s.brain ? '★ ' : '', 'NEXT: ', h('b', s.opponent)) : null,
    s.hint ? h('p.muted', s.hint) : null,
    h('div.fr-list', (s.team || []).map((m) => monCard(m))),
    h('div.fr-opts', btn('CONTINUE', 'fr-continue', () => answer({ v: 'continue' })), btn('RETIRE', 'fr-retire', () => answer({ v: 'retire' }))),
  ];
}

function renderSwap(s) {
  const pick = (side, i) => {
    if (side === 'mine') swapMine = swapMine === i ? -1 : i;
    else swapTheirs = swapTheirs === i ? -1 : i;
    rerenderSwap(s);
  };
  const col = (list, side, sel) => h('div.fr-col', h('h3', side === 'mine' ? 'YOUR TEAM' : "OPPONENT'S TEAM"),
    list.map((m, i) => h('button.fr-pick' + (sel === i ? '.sel' : ''), { type: 'button', 'data-k': `fr-${side}${i}`, on: { click: () => pick(side, i) } }, monCard(m, { sel: sel === i }))));
  return [
    h('p.fr-text', s.text || ''),
    h('div.fr-swap', col(s.mine || [], 'mine', swapMine), col(s.theirs || [], 'theirs', swapTheirs)),
    h('div.fr-opts',
      btn('SWAP', 'fr-swap', () => { if (swapMine >= 0 && swapTheirs >= 0) answer({ mine: swapMine, theirs: swapTheirs }); }, swapMine >= 0 && swapTheirs >= 0 ? '' : '.dim'),
      btn('KEEP TEAM', 'fr-keep', () => answer({ mine: -1, theirs: -1 }))),
  ];
}

function rerenderSwap() {
  render(fr());
}

function renderDome(s) {
  const rounds = ['ROUND 1', 'ROUND 2', 'SEMIFINAL', 'FINAL'];
  const tree = h('div.fr-bracket', (s.bracket || []).map((e) =>
    h('div.fr-br' + (e.out ? '.out' : '') + (e.player ? '.me' : '') + (e.brain ? '.brain' : ''), `${e.seed}. ${e.name}`)));
  return [
    h('p.fr-text', s.text || ''),
    h('div.fr-dome', tree,
      h('div.fr-col', h('h3', `${rounds[s.round] || ''}: ${s.opponent || ''}`),
        (s.opponentMons || []).map((m) => monCard(m)))),
    ...choosePanel({ text: 'Your POKéMON (choose two):' }, s.mine || [], s.count || 2, 'fr-d', () => {
      if (picks.length === (s.count || 2)) answer({ picks });
    }).slice(1, 2),
    h('div.fr-opts',
      btn(`BATTLE (${picks.length}/${s.count || 2})`, 'fr-decide', () => { if (picks.length === (s.count || 2)) answer({ picks }); }, picks.length === (s.count || 2) ? '' : '.dim'),
      btn('FORFEIT', 'fr-retire', () => answer({ v: 'retire' }))),
  ];
}

function renderDoors(s) {
  return [
    h('p.fr-text', s.text || ''),
    h('p.fr-hint', '“' + (s.hint || '') + '”'),
    h('div.fr-doors', ['LEFT', 'CENTER', 'RIGHT'].map((d, i) => btn(d, 'fr-door' + i, () => answer({ door: i }), '.fr-door'))),
    h('div.fr-list', (s.team || []).map((m) => monCard(m))),
    h('div.fr-opts', btn('RETIRE', 'fr-retire', () => answer({ v: 'retire' }))),
  ];
}

let pyrUse = null;

function renderPyramid(s) {
  const at = s.at;
  const adj = (i) => Math.abs((i % 4) - (at % 4)) + Math.abs(Math.floor(i / 4) - Math.floor(at / 4)) === 1;
  const icon = { exit: '⇧', trainer: '!', item: '●', floor: '', dark: '' };
  const grid = h('div.fr-grid', { style: `--light:${s.light}` }, (s.grid || []).map((c) => {
    const here = c.i === at;
    const label = here ? '◉' : icon[c.kind] || '';
    return h('button.fr-cell.k-' + c.kind + (c.lit ? '.lit' : '') + (here ? '.here' : ''), {
      type: 'button', 'data-k': 'fr-cell' + c.i, disabled: !adj(c.i),
      'aria-label': `square ${c.i} ${c.kind}`,
      on: { click: () => answer({ v: 'move', to: c.i }) },
    }, label);
  }));
  const bag = (s.bag || []).map((b) => btn(`${b.name} ×${b.count}`, 'fr-bag-' + b.item, () => { pyrUse = b.item; render(fr()); }, pyrUse === b.item ? '.sel' : ''));
  const team = (s.team || []).map((m, i) => h('button.fr-pick', { type: 'button', 'data-k': 'fr-pt' + i, disabled: !pyrUse, on: { click: () => { const item = pyrUse; pyrUse = null; answer({ v: 'use', item, target: i }); } } }, monCard(m)));
  return [
    h('p.fr-text', `FLOOR ${s.floor}  ·  LIGHT ${s.light}`),
    s.text ? h('p.muted', s.text) : null,
    h('div.fr-pyr', grid, h('div.fr-col', h('h3', 'PYRAMID BAG'), h('div.fr-bag', bag.length ? bag : [h('p.muted', 'Empty')]),
      pyrUse ? h('p.muted', 'Use on which POKéMON?') : null)),
    h('div.fr-list', team),
    h('div.fr-opts', btn('RETIRE', 'fr-retire', () => answer({ v: 'retire' }))),
  ];
}

function renderShop(s, f) {
  return [
    h('p.fr-text', s.text || ''),
    h('div.fr-shop', (s.items || []).map((it) => btn(`${it.name}  —  ${it.cost} BP`, 'fr-buy' + it.idx, () => answer({ buy: it.idx }), (f.bp ?? 0) >= it.cost ? '' : '.dim'))),
    h('div.fr-opts', btn('QUIT', 'fr-quit', () => answer({ v: 'cancel' }))),
  ];
}

function renderCard(s) {
  const sym = s.symbols || {};
  return [
    h('div.fr-pass',
      h('p', h('b', `BATTLE POINTS: ${s.bp}`), `  (earned ${s.earned})`),
      h('div.fr-symgrid', FACILITIES.map((k) => h('div.fr-symcell.s' + (sym[k] || 0),
        h('span.fr-symbig', (sym[k] || 0) >= 2 ? '◆' : (sym[k] || 0) === 1 ? '◇' : '·'),
        h('small', k.toUpperCase()), h('small.muted', (sym[k] || 0) >= 2 ? 'GOLD' : (sym[k] || 0) === 1 ? 'SILVER' : '—')))),
      h('table.fr-rec', h('tr', h('th', 'RECORD'), h('th', 'NOW'), h('th', 'BEST')),
        (s.records || []).map((r) => h('tr', h('td', r.key.toUpperCase()), h('td', String(r.current)), h('td', String(r.best))))),
      Object.keys(s.hill || {}).length ? h('p.muted', 'TRAINER HILL: ' + Object.entries(s.hill).map(([k, v]) => `${k} ${v}`).join(', ')) : null,
      Object.keys(s.ttower || {}).length ? h('p.muted', 'TRAINER TOWER: ' + Object.entries(s.ttower).map(([k, v]) => `${k} ${v}`).join(', ')) : null),
    btn('CLOSE', 'fr-close', () => answer({ v: 'cancel' })),
  ];
}

function renderWait(s) {
  return [h('p.fr-text', s.text || 'Please wait...'), btn('CANCEL', 'fr-cancel', () => send({ a: 'close' }))];
}

const KINDS = {
  menu: renderMenu, message: renderMessage, team: renderTeam, rental: renderRental, between: renderBetween,
  swap: renderSwap, dome: renderDome, doors: renderDoors, pyramid: renderPyramid, shop: renderShop,
  card: renderCard, wait: renderWait,
};

/** The Frontier Pass from the menu. */
export function openPass() {
  send({ a: 'open', desk: 'card' });
}
