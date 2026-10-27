// r3-items UI: POKéDEX (list + entry page), TRAINER CARD with the badge case, decorations (shop and
// PC storage), the day care pickers, the move pickers for Ether / PP Up / TMs, and the learn prompt.
// State comes from the dex / trainer / daycare / decor / learn sections (PROTOCOL.md "r3-items").
import { h, fmtMoney, same } from '../util.js';
import { panels, rerender, setFocus } from './panels.js';
import { monImg } from './common.js';

let app;
const last = {};

export function init(a) {
  app = a;
  // The styles live beside this module so main.css stays untouched.
  if (!document.querySelector('link[data-items-css]')) {
    document.head.append(h('link', { rel: 'stylesheet', href: 'css/items.css', 'data-items-css': '1' }));
  }
  // SELECT on the GBA uses the registered key item; here that is C.
  window.addEventListener('keydown', (e) => {
    if (e.code !== 'KeyC' || e.repeat || e.ctrlKey || e.metaKey || e.altKey) return;
    if (document.activeElement && /INPUT|TEXTAREA/.test(document.activeElement.tagName)) return;
    if (panels.any() || !app.net.state.world || !app.net.state.world.inWorld) return;
    useRegistered();
  });
}

export function update(state, changed) {
  for (const k of ['dex', 'trainer', 'daycare', 'decor', 'learn', 'party', 'ui']) {
    if (!changed.includes(k)) continue;
    if (k === 'dex' && panels.isOpen('dex')) renderDex();
    if (k === 'dex' && panels.isOpen('dexentry')) renderEntry();
    if ((k === 'trainer' || k === 'dex') && panels.isOpen('card')) renderCard();
    if ((k === 'decor') && panels.isOpen('decorpc')) renderDecorPc();
    if (k === 'party' && panels.isOpen('movepick')) renderMovePick();
  }
  syncDaycare(state.daycare);
  syncDecorShop(state.decor && state.decor.shop);
  syncLearn(state.learn);
}

// ------------------------------------------------------------------ registered item
export function useRegistered() {
  // With nothing registered the server answers with a notice saying so.
  app.send({ t: 'item.registered' });
}

// ------------------------------------------------------------------ item on a move
let movePick = null; // {item, mon}

/**
 * Called by the bag's party picker. Items that need a move (Ether, PP Up, Leppa) or a TM on a
 * monster that already knows four moves ask which move first, like the GBA's move window.
 */
export function useOn(item, mon) {
  if (item.use === 'teach') {
    const known = (mon.moves || []).length;
    if (known < 4) return app.send({ t: 'item.move', item: item.item, mon: mon.id, slot: 0 });
  } else if (item.use !== 'move') {
    return app.send({ t: 'item.use', item: item.item, mon: mon.id });
  }
  movePick = { item, monId: mon.id };
  const p = panels.open('movepick', item.use === 'teach' ? `${item.teaches || item.name}: forget which move?` : `${item.name}: which move?`, {
    onBack: () => { movePick = null; panels.close('movepick'); },
  });
  p.title.textContent = item.use === 'teach' ? `Teach ${item.teaches || ''}` : `Use ${item.name}`;
  renderMovePick();
  setFocus(p.body, p.body.querySelector('.item button'));
}

function renderMovePick() {
  const p = panels.get('movepick');
  if (!p || !movePick) return;
  const mon = (app.net.state.party || []).find((m) => m.id === movePick.monId);
  if (!mon) return panels.close('movepick');
  const teach = movePick.item.use === 'teach';
  rerender(p.body, () => [
    h('p.muted', teach
      ? `${mon.nick || mon.name} wants to learn ${movePick.item.teaches}, but already knows four moves. Forget one?`
      : `Choose a move of ${mon.nick || mon.name}.`),
    h('div.list', (mon.moves || []).map((mv) => h('div.item.move',
      h('span.nm', mv.name, h('small', `PP ${mv.pp}/${mv.maxPp}${mv.ppUps ? ' · PP Up ×' + mv.ppUps : ''}`)),
      h('span.act', h('button.go', { type: 'button', 'data-k': 'mv' + mv.slot,
        on: { click: () => { app.send({ t: 'item.move', item: movePick.item.item, mon: mon.id, slot: mv.slot }); if (teach) { movePick = null; panels.close('movepick'); } } } },
        teach ? 'Forget' : 'Use'))))),
    teach ? h('button', { type: 'button', 'data-k': 'giveup', on: { click: () => { movePick = null; panels.close('movepick'); } } }, `Don't learn ${movePick.item.teaches}`) : null,
  ]);
}

// ------------------------------------------------------------------ learn prompt
function syncLearn(l) {
  if (same(l, last.learn)) return;
  last.learn = l;
  if (!l) return panels.close('learn');
  const p = panels.open('learn', `${l.name} wants to learn ${l.move.name}`, {
    onBack: () => app.send({ t: 'learn.reply', slot: null }),
  });
  p.title.textContent = `${l.name} wants to learn ${l.move.name}`;
  const mv = (m, k, btn) => h('div.item.move', { 'data-k': k },
    h('span.nm', `${m.name}`, h('small', `${m.type || ''} · Pow ${m.power || '—'} · PP ${m.pp}/${m.maxPp}`), m.desc ? h('small', m.desc) : null), btn);
  rerender(p.body, () => [
    h('p.muted', `But ${l.name} can't learn more than four moves. Delete an older move to make room for ${l.move.name}?`),
    h('h3', 'New move'),
    mv(l.move, 'new', null),
    h('h3', 'Forget'),
    h('div.list', l.moves.map((m, i) => mv(m, 'm' + i, h('span.act', h('button.danger', { type: 'button', 'data-k': 'f' + i,
      on: { click: () => app.send({ t: 'learn.reply', slot: i }) } }, 'Forget'))))),
    h('button', { type: 'button', 'data-k': 'stop', on: { click: () => app.send({ t: 'learn.reply', slot: null }) } }, `Stop learning ${l.move.name}`),
  ]);
  setFocus(p.body, p.body.querySelector('[data-k=f0]'));
}

// ------------------------------------------------------------------ day care
function syncDaycare(d) {
  const mode = d && d.open;
  const key = JSON.stringify([mode, d && d.mons, mode === 'deposit' ? app.net.state.party : null]);
  if (key === last.daycare) return;
  last.daycare = key;
  if (!mode) return panels.close('daycare');
  const p = panels.open('daycare', 'DAY CARE', { onBack: () => app.send(mode === 'deposit' ? { t: 'daycare.deposit', mon: null } : { t: 'daycare.withdraw', index: null }) });
  if (mode === 'deposit') {
    p.title.textContent = 'Which POKéMON should we raise?';
    const party = app.net.state.party || [];
    rerender(p.body, () => [
      h('div.list', party.map((m, i) => h('div.item.mon', monImg(m.dex),
        h('span.nm', m.nick ? `${m.nick} (${m.name})` : m.name, h('small', m.egg ? 'EGG' : `Lv${m.level} · HP ${m.hp}/${m.maxHp}`)),
        h('span.act', h('button.go', { type: 'button', 'data-k': 'dep' + i, disabled: !!m.egg,
          on: { click: () => app.send({ t: 'daycare.deposit', mon: m.id }) } }, 'Leave'))))),
      h('button', { type: 'button', 'data-k': 'cancel', on: { click: () => app.send({ t: 'daycare.deposit', mon: null }) } }, 'Cancel'),
    ]);
  } else {
    p.title.textContent = 'Take back which POKéMON?';
    rerender(p.body, () => [
      h('div.list', (d.mons || []).map((m) => h('div.item.mon', monImg(m.dex),
        h('span.nm', m.name, h('small', `Lv${m.level}${m.gained ? ` (+${m.gained})` : ''} · ${fmtMoney(m.fee)}`)),
        h('span.act', h('button.go', { type: 'button', 'data-k': 'wd' + m.index,
          on: { click: () => app.send({ t: 'daycare.withdraw', index: m.index }) } }, 'Take back'))))),
      h('button', { type: 'button', 'data-k': 'cancel', on: { click: () => app.send({ t: 'daycare.withdraw', index: null }) } }, 'Cancel'),
    ]);
  }
  setFocus(p.body, p.body.querySelector('.item button:not(:disabled)'));
}

// ------------------------------------------------------------------ decoration shop
let decorPick = null; // {id, qty}

function syncDecorShop(shop) {
  if (same(shop, last.decorShop) && (!shop || panels.isOpen('decorshop'))) return;
  last.decorShop = shop;
  if (!shop) {
    decorPick = null;
    return panels.close('decorshop');
  }
  panels.open('decorshop', 'DECORATIONS', { onBack: decorBack });
  renderDecorShop();
}

function decorBack() {
  if (decorPick) {
    decorPick = null;
    return renderDecorShop();
  }
  app.send({ t: 'decor.close' });
  panels.close('decorshop');
}

function renderDecorShop() {
  const p = panels.get('decorshop');
  const shop = last.decorShop || [];
  const money = (app.net.state.ui && app.net.state.ui.money) || 0;
  const owned = Object.fromEntries(((app.net.state.decor && app.net.state.decor.owned) || []).map((d) => [d.id, d.qty]));
  if (decorPick) {
    const d = shop.find((x) => x.id === decorPick.id);
    const total = d.price * decorPick.qty;
    const set = (q) => { decorPick.qty = Math.max(1, Math.min(99, q)); renderDecorShop(); };
    rerender(p.body, () => [
      h('div.picker',
        h('div.row', h('b.grow', d.name), h('span.price', fmtMoney(d.price))),
        h('div.qtyrow',
          h('button.step', { type: 'button', 'data-k': 'm10', on: { click: () => set(decorPick.qty - 10) } }, '−10'),
          h('button.step', { type: 'button', 'data-k': 'm1', on: { click: () => set(decorPick.qty - 1) } }, '−'),
          h('span.qtyval', '×' + decorPick.qty),
          h('button.step', { type: 'button', 'data-k': 'p1', on: { click: () => set(decorPick.qty + 1) } }, '+'),
          h('button.step', { type: 'button', 'data-k': 'p10', on: { click: () => set(decorPick.qty + 10) } }, '+10')),
        h('div.row.totals', h('span', 'Total ', h('span.price', fmtMoney(total))), h('span.muted', 'Money after ' + fmtMoney(money - total))),
        h('div.row',
          h('button.go.grow', { type: 'button', 'data-k': 'buy', disabled: total > money,
            on: { click: () => { app.send({ t: 'decor.buy', id: d.id, qty: decorPick.qty }); decorPick = null; renderDecorShop(); } } }, 'Buy'),
          h('button.grow', { type: 'button', 'data-k': 'back', on: { click: decorBack } }, 'Back'))),
    ]);
    return setFocus(p.body, p.body.querySelector('[data-k=buy]'));
  }
  rerender(p.body, () => [
    h('div.row', h('b', 'Money '), h('span.price', fmtMoney(money))),
    h('p.muted', 'Bought decorations are sent to your PC.'),
    h('div.list', shop.map((d) => h('div.item', { 'data-decor': d.id },
      h('span.decor-ic', { 'data-cat': d.category || '' }, decorGlyph(d.category)),
      h('span.nm', d.name, h('small', `${catName(d.category)}${owned[d.id] ? ` · in PC: ${owned[d.id]}` : ''}`)),
      h('span.act', h('span.price', fmtMoney(d.price)),
        h('button.go', { type: 'button', 'data-k': 'd' + d.id, on: { click: () => { decorPick = { id: d.id, qty: 1 }; renderDecorShop(); } } }, 'Buy'))))),
    h('button', { type: 'button', 'data-k': 'close', on: { click: () => decorBack() } }, 'Close'),
  ]);
  setFocus(p.body, p.body.querySelector('.item button'));
}

const CATS = { desk: 'Desk', chair: 'Chair', plant: 'Plant', ornament: 'Ornament', mat: 'Mat', poster: 'Poster', doll: 'Doll', cushion: 'Cushion' };
const catName = (c) => CATS[c] || (c ? c[0].toUpperCase() + c.slice(1) : 'Decoration');
function decorGlyph(c) {
  return { desk: '▭', chair: '⑁', plant: '✿', ornament: '◈', mat: '▦', poster: '▤', doll: '☻', cushion: '◍' }[c] || '◆';
}

export function openDecorPc() {
  panels.open('decorpc', 'PC: DECORATIONS');
  renderDecorPc();
}

function renderDecorPc() {
  const p = panels.get('decorpc');
  const owned = (app.net.state.decor && app.net.state.decor.owned) || [];
  rerender(p.body, () => [
    h('p.muted', 'Your decorations, kept in the PC for a SECRET BASE.'),
    h('div.list', owned.length ? owned.map((d) => h('div.item', h('span.decor-ic', { 'data-cat': d.category || '' }, decorGlyph(d.category)),
      h('span.nm', d.name, h('small', catName(d.category))), h('span.act', '×' + d.qty))) : h('p.muted', 'No decorations yet. Decoration shops are in SLATEPORT, FORTREE and LILYCOVE.')),
  ]);
}

// ------------------------------------------------------------------ pokedex
let dexMode = 'regional', dexEntry = null;

export function openPokedex() {
  const p = panels.open('dex', 'POKéDEX');
  renderDex();
  setFocus(p.body, p.body.querySelector('.tabs button.on'));
}

function renderDex() {
  const p = panels.get('dex');
  const d = app.net.state.dex;
  if (!d) return rerender(p.body, () => h('p.muted', 'No POKéDEX data yet.'));
  const byDex = new Map(d.entries.map((e) => [e.dex, e]));
  const regional = dexMode === 'regional';
  const order = regional ? d.regional : Array.from({ length: d.nationalSize }, (_, i) => i + 1);
  // Like the GBA list: every number up to the highest one seen, unknown ones as dashes.
  let lastSeen = 0;
  order.forEach((n, i) => { if (byDex.has(n)) lastSeen = i + 1; });
  const rows = order.slice(0, Math.max(lastSeen, 1));
  const label = (n, i) => String(regional ? i + 1 : n).padStart(3, '0');
  const regionName = d.region === 'hoenn' ? 'HOENN' : 'KANTO';
  rerender(p.body, () => [
    h('div.tabs', { role: 'tablist' },
      h('button', { type: 'button', role: 'tab', class: regional ? 'on' : '', 'data-k': 'tab-r', on: { click: () => { dexMode = 'regional'; renderDex(); } } }, `${regionName} DEX`),
      h('button', { type: 'button', role: 'tab', class: regional ? '' : 'on', 'data-k': 'tab-n', on: { click: () => { dexMode = 'national'; renderDex(); } } }, 'NATIONAL')),
    h('div.row.dex-counts',
      h('span.chip', `SEEN ${regional ? d.regionalSeen : d.seen}`),
      h('span.chip', `OWN ${regional ? d.regionalCaught : d.caught}`)),
    h('div.list.dexlist', rows.map((n, i) => {
      const e = byDex.get(n);
      if (!e) return h('div.item.dexrow.unknown', h('span.dexno', label(n, i)), h('span.nm', '----------'));
      return h('div.item.dexrow', { 'data-dex': n },
        h('span.dexno', label(n, i)),
        e.caught ? h('span.ball', { title: 'caught' }, '●') : h('span.ball.seen', ' '),
        monImg(n),
        h('span.nm', e.name, h('small', e.types.join(' / '))),
        h('span.act', h('button', { type: 'button', 'data-k': 'e' + n, on: { click: () => openEntry(n) } }, 'Info')));
    })),
  ]);
}

function openEntry(n) {
  dexEntry = n;
  panels.open('dexentry', 'POKéDEX');
  renderEntry();
}

function renderEntry() {
  const p = panels.get('dexentry');
  const d = app.net.state.dex;
  const e = d && d.entries.find((x) => x.dex === dexEntry);
  if (!e) return panels.close('dexentry');
  const no = String(e.dex).padStart(3, '0');
  p.title.textContent = `No${no} ${e.name}`;
  // The GBA prints imperial units in English; the decomp stores decimetres and hectograms.
  const ft = e.heightM != null ? Math.round(e.heightM * 39.37) : null;
  const lbs = e.weightKg != null ? (e.weightKg * 2.20462).toFixed(1) : null;
  rerender(p.body, () => [
    h('div.dexpage',
      h('div.dexsprite', monImg(e.dex, 'front', '.big')),
      h('div.dexinfo',
        h('div.dexname', h('b', `No${no}`), ' ', e.name),
        h('div.muted', e.caught && e.category ? `${e.category} POKéMON` : '? POKéMON'),
        h('div.chips', e.types.map((t) => h('span.type', { 'data-type': t }, t))),
        h('div', 'HT ', h('b', e.caught && ft != null ? `${Math.floor(ft / 12)}'${String(ft % 12).padStart(2, '0')}"` : `?'??"`),
          e.caught && e.heightM != null ? h('span.muted', ` (${e.heightM} m)`) : null),
        h('div', 'WT ', h('b', e.caught && lbs != null ? `${lbs} lbs.` : '????.? lbs.'),
          e.caught && e.weightKg != null ? h('span.muted', ` (${e.weightKg} kg)`) : null),
        e.hoenn ? h('div.muted', `HOENN No${String(e.hoenn).padStart(3, '0')}`) : null)),
    h('p.dextext', e.caught ? e.text || '' : 'Catch it to see its POKéDEX entry.'),
  ]);
}

// ------------------------------------------------------------------ trainer card + badge case
export function openTrainerCard() {
  const p = panels.open('card', 'TRAINER CARD');
  renderCard();
  setFocus(p.body, p.body.querySelector('button'));
}

let cardRegion = null;

function fmtTime(s) {
  const hrs = Math.floor(s / 3600);
  const min = Math.floor((s % 3600) / 60);
  return `${hrs}:${String(min).padStart(2, '0')}`;
}

function renderCard() {
  const p = panels.get('card');
  const t = app.net.state.trainer;
  if (!t) return rerender(p.body, () => h('p.muted', 'Loading…'));
  const region = cardRegion || t.region;
  const badges = t.badges[region] || [];
  const names = region === 'hoenn'
    ? ['STONE', 'KNUCKLE', 'DYNAMO', 'HEAT', 'BALANCE', 'FEATHER', 'MIND', 'RAIN']
    : ['BOULDER', 'CASCADE', 'THUNDER', 'RAINBOW', 'SOUL', 'MARSH', 'VOLCANO', 'EARTH'];
  rerender(p.body, () => [
    h('div.tcard', { 'data-region': region },
      h('div.tc-top', h('span.tc-title', 'TRAINER CARD'), h('span.tc-id', t.admin ? 'ADMIN' : t.developer ? 'DEV' : '')),
      h('div.tc-row', h('span', 'NAME'), h('b', t.name || '?')),
      h('div.tc-row', h('span', 'MONEY'), h('b', fmtMoney(t.money))),
      h('div.tc-row', h('span', 'POKéDEX'), h('b', String(t.dexCaught))),
      h('div.tc-row', h('span', 'TIME'), h('b', fmtTime(t.playSeconds || 0))),
      h('div.badgecase', badges.map((has, i) => h('div.badge', { class: has ? 'got' : 'empty', 'data-badge': i + 1, 'data-region': region, title: names[i] + ' BADGE' },
        h('i'), h('small', has ? names[i] : '—'))))),
    h('div.tabs', h('button', { type: 'button', class: region === 'kanto' ? 'on' : '', 'data-k': 'rk', on: { click: () => { cardRegion = 'kanto'; renderCard(); } } }, 'KANTO badges'),
      h('button', { type: 'button', class: region === 'hoenn' ? 'on' : '', 'data-k': 'rh', on: { click: () => { cardRegion = 'hoenn'; renderCard(); } } }, 'HOENN badges')),
    t.registeredName ? h('p.muted', `Registered (C): ${t.registeredName}`) : null,
  ]);
}

/** Menu entries this module adds (misc.js lists them in the main menu). */
export function menuItems() {
  const t = app.net.state.trainer;
  return [
    ['POKéDEX', 'dex', openPokedex],
    ['Trainer card', 'card', openTrainerCard],
    ['Decorations', 'decorpc', openDecorPc],
    t && t.registeredName ? [`Use ${t.registeredName}`, 'reg', useRegistered] : null,
  ].filter(Boolean);
}

export const _test = { fmtTime };
