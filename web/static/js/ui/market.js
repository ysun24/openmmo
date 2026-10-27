// Global market: browse (filters + paging, seller names), my listings (cancel), sales history,
// sell from party / bag (fee shown), and a price history chart per species / item (r3-social).
// Opens from the menu, or when the player talks to a market clerk (market.openSeq grows).
import { h, fmtMoney, same } from '../util.js';
import { panels, rerender } from './panels.js';
import { monImg } from './common.js';

const PAGE = 20;
let app, p = null, tab = 'browse', last = null, listBox = null, filt = null, chartBox = null, lastOpenSeq = null;
let chartFor = null; // {kind, id}
const q = { kind: null, id: null, min: null, max: null, sort: 'new', page: 0 };

export function init(a) {
  app = a;
}

export function open() {
  if (!p) {
    p = panels.open('market', 'Market');
    p.onBack = () => {
      panels.close('market');
      p = null;
      chartFor = null;
    };
    p.onClose = () => { p = null; chartFor = null; };
    buildStatic();
  }
  search(q.page);
  app.send({ t: 'market.mine' });
  app.send({ t: 'market.inventory' });
  app.send({ t: 'market.info' });
  update(app.net.state, true);
}
export const isOpen = () => !!p;

function search(page) {
  q.page = Math.max(0, page);
  app.send({ t: 'market.search', ...q });
}

/** Shows (and asks for) the price history of a species (kind 'mon') or item. */
export function showHistory(kind, id) {
  chartFor = { kind, id };
  app.send({ t: 'market.history', kind, id });
  update(app.net.state, true);
}

function buildStatic() {
  const kind = h('select', { 'aria-label': 'kind', 'data-k': 'fkind' }, h('option', { value: '' }, 'All'), h('option', { value: 'mon' }, 'POKéMON'), h('option', { value: 'item' }, 'Items'));
  const id = h('input', { type: 'number', placeholder: 'dex / item id', inputmode: 'numeric', 'aria-label': 'dex or item id', 'data-k': 'fid' });
  const min = h('input', { type: 'number', placeholder: 'min ₽', inputmode: 'numeric', 'aria-label': 'min price', 'data-k': 'fmin' });
  const max = h('input', { type: 'number', placeholder: 'max ₽', inputmode: 'numeric', 'aria-label': 'max price', 'data-k': 'fmax' });
  const sort = h('select', { 'aria-label': 'sort', 'data-k': 'fsort' }, h('option', { value: 'new' }, 'Newest'), h('option', { value: 'price' }, 'Price ↑'), h('option', { value: 'price-desc' }, 'Price ↓'));
  const go = h('button', { type: 'button', 'data-k': 'fgo', on: { click: () => {
    q.kind = kind.value || null;
    q.id = id.value ? +id.value : null;
    q.min = min.value ? +min.value : null;
    q.max = max.value ? +max.value : null;
    q.sort = sort.value;
    search(0);
  } } }, 'Search');
  filt = h('div.filters', kind, id, min, max, sort, go);
  const tabs = h('div.tabs', ['browse', 'mine', 'sales', 'sell'].map((t) =>
    h('button', { type: 'button', 'data-k': 'tab-' + t, 'data-tab': t, on: { click: () => setTab(t) } },
      { browse: 'Browse', mine: 'My listings', sales: 'Sales', sell: 'Sell' }[t])));
  listBox = h('div.list');
  chartBox = h('div.mk-chartwrap');
  p.money = h('span.price');
  p.fee = h('span.feeinfo');
  p.log = h('div.log');
  p.body.replaceChildren(h('div.row', h('b', 'Money: '), p.money, h('span.grow'), p.fee), tabs, filt, chartBox, listBox, p.log);
  setTab(tab);
}

function setTab(t) {
  tab = t;
  p.body.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('on', b.dataset.tab === t));
  filt.classList.toggle('hidden', t !== 'browse');
  if (t === 'mine') app.send({ t: 'market.mine' });
  if (t === 'sell') { app.send({ t: 'market.inventory' }); app.send({ t: 'market.info' }); }
  if (t === 'sales') app.send({ t: 'market.sales' });
  update(app.net.state, true);
}

const refOf = (l) => (l.kind === 'mon' ? { kind: 'mon', id: l.dex } : { kind: 'item', id: l.item });

function chartBtn(ref) {
  return h('button', { type: 'button', title: 'Price history', 'aria-label': 'price history', 'data-k': `ph-${ref.kind}-${ref.id}`,
    on: { click: () => showHistory(ref.kind, ref.id) } }, '📈');
}

function listingRow(l, action, showSeller = true) {
  const sub = l.kind === 'mon' ? `Lv${l.level ?? '?'}` : `x${l.qty}`;
  const hours = l.expiresAt ? Math.max(0, Math.round((l.expiresAt * 1000 - Date.now()) / 3600000)) : null;
  const exp = hours === null ? '' : hours >= 48 ? `${Math.round(hours / 24)}d left` : `${hours}h left`;
  return h('div.item', l.kind === 'mon' ? monImg(l.dex) : h('span.ic.ph', '◆'),
    h('span.nm', l.name, h('small', sub, exp ? ` · ${exp}` : '',
      showSeller && l.seller ? [' · by ', h('span.seller', l.mine ? 'you' : l.seller)] : '')),
    h('span.act', h('span.price', fmtMoney(l.price)), chartBtn(refOf(l)), action));
}

/** SVG chart: min-max band, average line and points, volume bars, for the last 30 days. */
function chart(hist) {
  const days = hist.days;
  const W = 520, H = 150, L = 46, R = 8, T = 8, B = 22;
  const today = Math.floor(Date.now() / 86400000);
  const d0 = today - 29;
  const x = (d) => L + ((d - d0) / 29) * (W - L - R);
  const hi = Math.max(1, ...days.map((d) => d.max));
  const lo = Math.min(hi, ...days.map((d) => d.min));
  const pad = Math.max(1, Math.round((hi - lo) * 0.15));
  const top = hi + pad, bot = Math.max(0, lo - pad);
  const y = (v) => T + (1 - (v - bot) / Math.max(1, top - bot)) * (H - T - B);
  const vmax = Math.max(1, ...days.map((d) => d.volume));
  const ns = 'http://www.w3.org/2000/svg';
  const el = (tag, attrs, text) => {
    const e = document.createElementNS(ns, tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    if (text !== undefined) e.textContent = text;
    return e;
  };
  const svg = el('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': `price history of ${hist.name}` });
  svg.append(el('line', { class: 'axis', x1: L, y1: H - B, x2: W - R, y2: H - B }), el('line', { class: 'axis', x1: L, y1: T, x2: L, y2: H - B }));
  for (const v of [top, (top + bot) / 2, bot]) svg.append(el('text', { x: L - 4, y: y(v) + 3, 'text-anchor': 'end' }, '₽' + Math.round(v).toLocaleString('en-US')));
  for (const [d, label, anchor] of [[d0, '30d ago', 'start'], [d0 + 15, '15d', 'middle'], [today, 'today', 'end']]) svg.append(el('text', { x: x(d), y: H - 6, 'text-anchor': anchor }, label));
  const bw = Math.max(3, (W - L - R) / 30 - 3);
  for (const d of days) {
    const bh = (d.volume / vmax) * 26;
    svg.append(el('rect', { class: 'vol', x: x(d.day) - bw / 2, y: H - B - bh, width: bw, height: bh }));
  }
  if (days.length) {
    const up = days.map((d) => `${x(d.day)},${y(d.max)}`), dn = days.slice().reverse().map((d) => `${x(d.day)},${y(d.min)}`);
    svg.append(el('polygon', { class: 'band', points: [...up, ...dn].join(' ') }));
    svg.append(el('polyline', { class: 'avg', points: days.map((d) => `${x(d.day)},${y(d.avg)}`).join(' ') }));
    for (const d of days) svg.append(el('circle', { class: 'pt', cx: x(d.day), cy: y(d.avg), r: 3 }, undefined));
  }
  return svg;
}

function chartView(m) {
  if (!chartFor) return [];
  const hist = m && m.history;
  const ready = hist && hist.kind === chartFor.kind && hist.id === chartFor.id;
  const close = h('button', { type: 'button', 'data-k': 'chclose', on: { click: () => { chartFor = null; update(app.net.state, true); } } }, '✕');
  if (!ready) return h('div.mk-chart', h('div.row', h('b.grow', 'Price history'), close), h('p.muted', 'Loading…'));
  const all = hist.recent;
  const vol = hist.days.reduce((a, d) => a + d.volume, 0);
  const avg = vol ? Math.round(hist.days.reduce((a, d) => a + d.avg * d.volume, 0) / vol) : 0;
  return h('div.mk-chart',
    h('div.row', h('b.grow', `${hist.name} — price per unit, last 30 days`), close),
    hist.days.length ? [
      h('div.mk-stats', h('span', 'sold ', h('b', String(vol))), h('span', 'avg ', h('b', fmtMoney(avg))),
        h('span', 'low ', h('b', fmtMoney(Math.min(...hist.days.map((d) => d.min))))), h('span', 'high ', h('b', fmtMoney(Math.max(...hist.days.map((d) => d.max))))),
        all.length ? h('span', 'last ', h('b', fmtMoney(all[0].price))) : null),
      chart(hist),
      h('div.muted', 'Recent: ', all.slice(0, 6).map((s) => `${fmtMoney(s.price)}${s.qty > 1 ? ' ×' + s.qty : ''}`).join(' · ')),
    ] : h('p.muted', 'No sales in the last 30 days.'));
}

export function update(state, force = false) {
  const m = state.market;
  // A clerk asked to open the window. The first value seen is only the baseline.
  if (m && typeof m.openSeq === 'number') {
    if (lastOpenSeq !== null && m.openSeq > lastOpenSeq && !p) {
      lastOpenSeq = m.openSeq;
      open();
      return;
    }
    lastOpenSeq = m.openSeq;
  }
  if (!p) return;
  if (force !== true && same(m, last)) return;
  last = m;
  p.money.textContent = fmtMoney(m ? m.money : state.ui ? state.ui.money : 0);
  const info = m && m.info;
  p.fee.textContent = info ? `fee ${(info.feePermille / 10).toFixed(1)}% (min ${fmtMoney(info.minFee)}) · ${info.active}/${info.maxActive} listed` : '';
  p.log.replaceChildren(...((m && m.log) || []).slice(-4).map((l) => h('div', l)));
  rerender(chartBox, () => chartView(m));
  rerender(listBox, () => {
    if (!m) return h('p.muted', 'Loading…');
    if (tab === 'browse') {
      const pages = Math.max(1, Math.ceil((m.browseTotal || 0) / PAGE));
      return [
        m.browse.length ? m.browse.map((l) => listingRow(l, l.mine ? h('span.muted', 'yours')
          : h('button.go', { type: 'button', 'data-k': 'buy' + l.id, disabled: (m.money ?? 0) < l.price, on: { click: () => app.send({ t: 'market.buy', id: l.id }) } }, 'Buy'))) : h('p.muted', 'No listings match.'),
        h('div.row',
          h('button', { type: 'button', 'data-k': 'prev', disabled: m.page <= 0, on: { click: () => search(m.page - 1) } }, '◀ Prev'),
          h('span.grow.muted', { style: 'text-align:center' }, `page ${m.page + 1} / ${pages} · ${m.browseTotal} listings`),
          h('button', { type: 'button', 'data-k': 'next', disabled: m.page + 1 >= pages, on: { click: () => search(m.page + 1) } }, 'Next ▶')),
      ];
    }
    if (tab === 'mine') {
      return m.mine.length ? m.mine.map((l) => listingRow(l, h('button.danger', { type: 'button', 'data-k': 'cx' + l.id, on: { click: () => app.send({ t: 'market.cancel', id: l.id }) } }, 'Cancel'), false))
        : h('p.muted', 'You have no active listings.');
    }
    if (tab === 'sales') {
      if (!m.sales) return h('p.muted', 'Loading…');
      const total = m.sales.reduce((a, s) => a + s.price - s.fee, 0);
      return m.sales.length ? [
        h('p.muted', `${m.sales.length} sold · earned ${fmtMoney(total)} after fees`),
        m.sales.map((s) => h('div.item', s.kind === 'mon' ? monImg(s.dex) : h('span.ic.ph', '◆'),
          h('span.nm', s.kind === 'mon' ? s.name : `${s.qty}x ${s.name}`,
            h('small', 'to ', h('span.seller', s.buyer), ` · ${new Date(s.at * 1000).toLocaleString()}${s.fee ? ' · fee ' + fmtMoney(s.fee) : ''}`)),
          h('span.act', h('span.price', fmtMoney(s.price)), chartBtn(s.kind === 'mon' ? { kind: 'mon', id: s.dex } : { kind: 'item', id: s.item })))),
      ] : h('p.muted', 'Nothing sold yet.');
    }
    const inv = m.inventory;
    if (!inv) return h('p.muted', 'Loading inventory…');
    const feeOf = (price) => (!info || !info.feePermille ? 0 : Math.max(info.minFee, Math.floor((price * info.feePermille) / 1000)));
    const feeHint = (price) => h('small.feeinfo', { 'data-fee': '1' }, price.value > 0 ? `fee ${fmtMoney(feeOf(+price.value))}` : '');
    const priceInput = (k) => {
      const i = h('input', { type: 'number', min: 1, placeholder: 'price', inputmode: 'numeric', 'aria-label': 'price', 'data-k': k });
      i.addEventListener('input', () => { const f = i.parentElement && i.parentElement.querySelector('[data-fee]'); if (f) f.textContent = i.value > 0 ? `fee ${fmtMoney(feeOf(+i.value))}` : ''; });
      return i;
    };
    return [
      h('h3', 'POKéMON'),
      inv.party.map((mon) => {
        const price = priceInput('pm' + mon.slot);
        return h('div.item', monImg(mon.dex), h('span.nm', mon.nick || mon.name, h('small', `Lv${mon.level} · HP ${mon.hp}/${mon.maxHp}`)),
          h('span.act', chartBtn({ kind: 'mon', id: mon.dex }), price, feeHint(price), h('button', { type: 'button', 'data-k': 'lm' + mon.slot, disabled: inv.party.length <= 1,
            on: { click: () => price.value > 0 && app.send({ t: 'market.listMon', slot: mon.slot, price: price.value | 0 }) } }, 'List')));
      }),
      h('h3', 'Items'),
      inv.bag.length ? inv.bag.filter((b) => b.pocket !== 'key').map((b) => {
        const qty = h('input.qty', { type: 'number', min: 1, max: b.qty, value: 1, inputmode: 'numeric', 'aria-label': 'quantity', 'data-k': 'iq' + b.item });
        const price = priceInput('ip' + b.item);
        return h('div.item', h('span.ic.ph', '◆'), h('span.nm', b.name, h('small', `have ${b.qty}`)),
          h('span.act', chartBtn({ kind: 'item', id: b.item }), qty, price, feeHint(price), h('button', { type: 'button', 'data-k': 'li' + b.item,
            on: { click: () => price.value > 0 && app.send({ t: 'market.listItem', item: b.item, qty: Math.max(1, Math.min(b.qty, qty.value | 0)), price: price.value | 0 }) } }, 'List')));
      }) : h('p.muted', 'No items.'),
    ];
  });
}
