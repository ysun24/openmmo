// Shop panel: buy from offers / sell from bag. Server cursor drives keyboard selection.
import { h, fmtMoney, same } from '../util.js';
import { panels, rerender } from './panels.js';
import { itemIc } from './common.js';

let app, last = null, tab = 'buy', lastBag = null, p = null;

export function init(a) {
  app = a;
}

export function update(state) {
  const s = state.ui && state.ui.shop;
  const bag = (state.ui && state.ui.bag) || [];
  const money = state.ui ? state.ui.money : 0;
  if (!s) {
    if (p) panels.close('shop');
    p = null;
    last = null;
    return;
  }
  if (same(s, last) && same(bag, lastBag) && p && p.money === money) return;
  last = s;
  lastBag = bag;
  if (!p) {
    tab = 'buy';
    p = panels.open('shop', 'Shop', { onBack: () => app.send({ t: 'shop.close' }) });
  }
  p.money = money;
  rerender(p.body, () => [
    h('div.row', h('span.grow', h('b', 'Money: '), h('span.price', fmtMoney(money))), h('span.muted', s.status || '')),
    h('div.tabs',
      h('button', { type: 'button', 'data-k': 'tab-buy', class: tab === 'buy' ? 'on' : '', on: { click: () => setTab('buy') } }, 'Buy'),
      h('button', { type: 'button', 'data-k': 'tab-sell', class: tab === 'sell' ? 'on' : '', on: { click: () => setTab('sell') } }, 'Sell')),
    tab === 'buy'
      ? h('div.list', s.offers.map((o, i) => row(o, i === s.cursor, `₽${o.price}`, 'Buy', (q) => app.send({ t: 'shop.buy', item: o.item, qty: q }), money >= o.price, 'b' + o.item)))
      : h('div.list', bag.length ? bag.map((b) => row(b, false, `x${b.qty}`, 'Sell', (q) => app.send({ t: 'shop.sell', item: b.item, qty: q }), true, 's' + b.item, b.qty)) : h('p.muted', 'Your bag is empty.')),
  ]);
}

function setTab(t) {
  tab = t;
  last = null;
  update(app.net.state);
}

function row(o, sel, right, label, act, enabled, k, max = 99) {
  const qty = h('input.qty', { type: 'number', min: 1, max, value: 1, inputmode: 'numeric', 'aria-label': 'quantity', 'data-k': 'q' + k });
  return h('div.item', { class: sel ? 'sel' : '', 'data-item': o.item },
    itemIc(),
    h('span.nm', o.name, h('small', right)),
    h('span.act', qty, h('button', { type: 'button', 'data-k': k, disabled: !enabled, on: { click: () => act(Math.max(1, Math.min(max, qty.value | 0))) } }, label)));
}

export const isOpen = () => !!last;
/** A on keyboard: buy one of the cursor offer. */
export function confirmCursor() {
  if (!last || tab !== 'buy') return;
  const o = last.offers[last.cursor];
  if (o) app.send({ t: 'shop.buy', item: o.item, qty: 1 });
}
