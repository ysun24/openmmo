// Global market: browse (filters + paging), my listings (cancel), sell from party / bag.
import { h, fmtMoney, same } from '../util.js';
import { panels, rerender } from './panels.js';
import { monImg } from './common.js';

const PAGE = 10;
let app, p = null, tab = 'browse', last = null, listBox = null, filt = null;
const q = { kind: null, id: null, min: null, max: null, sort: 'new', page: 0 };

export function init(a) {
  app = a;
}

export function open() {
  if (!p) {
    p = panels.open('market', 'Market');
    const orig = () => {
      panels.close('market');
      p = null;
    };
    p.onBack = orig;
    buildStatic();
  }
  search(0);
  app.send({ t: 'market.mine' });
  app.send({ t: 'market.inventory' });
  update(app.net.state, true);
}
export const isOpen = () => !!p;

function search(page) {
  q.page = Math.max(0, page);
  app.send({ t: 'market.search', ...q });
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
  const tabs = h('div.tabs', ['browse', 'mine', 'sell'].map((t) =>
    h('button', { type: 'button', 'data-k': 'tab-' + t, 'data-tab': t, on: { click: () => setTab(t) } }, { browse: 'Browse', mine: 'My listings', sell: 'Sell' }[t])));
  listBox = h('div.list');
  p.money = h('span.price');
  p.log = h('div.log');
  p.body.replaceChildren(h('div.row', h('b', 'Money: '), p.money), tabs, filt, listBox, p.log);
  setTab(tab);
}

function setTab(t) {
  tab = t;
  p.body.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('on', b.dataset.tab === t));
  filt.classList.toggle('hidden', t !== 'browse');
  if (t === 'mine') app.send({ t: 'market.mine' });
  if (t === 'sell') app.send({ t: 'market.inventory' });
  update(app.net.state, true);
}

function listingRow(l, action) {
  const sub = l.kind === 'mon' ? `Lv${l.level ?? '?'}` : `x${l.qty}`;
  const exp = l.expiresAt ? Math.max(0, Math.round((l.expiresAt * 1000 - Date.now()) / 3600000)) + 'h left' : '';
  return h('div.item', l.kind === 'mon' ? monImg(l.dex) : h('span.ic.ph', '◆'),
    h('span.nm', l.name, h('small', `${sub} · ${exp}`)),
    h('span.act', h('span.price', fmtMoney(l.price)), action));
}

export function update(state, force = false) {
  if (!p) return;
  const m = state.market;
  if (!force && same(m, last)) return;
  last = m;
  p.money.textContent = fmtMoney(m ? m.money : state.ui ? state.ui.money : 0);
  p.log.replaceChildren(...((m && m.log) || []).slice(-4).map((l) => h('div', l)));
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
      return m.mine.length ? m.mine.map((l) => listingRow(l, h('button.danger', { type: 'button', 'data-k': 'cx' + l.id, on: { click: () => app.send({ t: 'market.cancel', id: l.id }) } }, 'Cancel')))
        : h('p.muted', 'You have no active listings.');
    }
    const inv = m.inventory;
    if (!inv) return h('p.muted', 'Loading inventory…');
    return [
      h('h3', 'POKéMON'),
      inv.party.map((mon) => {
        const price = h('input', { type: 'number', min: 1, placeholder: 'price', inputmode: 'numeric', 'aria-label': 'price', 'data-k': 'pm' + mon.slot });
        return h('div.item', monImg(mon.dex), h('span.nm', mon.nick || mon.name, h('small', `Lv${mon.level} · HP ${mon.hp}/${mon.maxHp}`)),
          h('span.act', price, h('button', { type: 'button', 'data-k': 'lm' + mon.slot, on: { click: () => price.value > 0 && app.send({ t: 'market.listMon', slot: mon.slot, price: price.value | 0 }) } }, 'List')));
      }),
      h('h3', 'Items'),
      inv.bag.length ? inv.bag.map((b) => {
        const qty = h('input.qty', { type: 'number', min: 1, max: b.qty, value: 1, inputmode: 'numeric', 'aria-label': 'quantity', 'data-k': 'iq' + b.item });
        const price = h('input', { type: 'number', min: 1, placeholder: 'price', inputmode: 'numeric', 'aria-label': 'price', 'data-k': 'ip' + b.item });
        return h('div.item', h('span.ic.ph', '◆'), h('span.nm', b.name, h('small', `have ${b.qty}`)),
          h('span.act', qty, price, h('button', { type: 'button', 'data-k': 'li' + b.item, on: { click: () => price.value > 0 && app.send({ t: 'market.listItem', item: b.item, qty: Math.max(1, Math.min(b.qty, qty.value | 0)), price: price.value | 0 }) } }, 'List')));
      }) : h('p.muted', 'No items.'),
    ];
  });
}
