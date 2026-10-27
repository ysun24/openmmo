// Shop panel: Buy (the clerk's shelf) and Sell (the bag at half price), each with a quantity
// picker that shows the total against your money. Works with touch (big +/- buttons) and with the
// keyboard/D-pad (the server cursor moves the Buy selection; arrows navigate the picker).
import { h, fmtMoney, same } from '../util.js';
import { panels, rerender, setFocus } from './panels.js';
import { itemIc } from './common.js';

const MAX_QTY = 99;
let app, last = null, lastMoney = null, tab = 'buy', p = null;
// The row being bought or sold and how many: {kind:'buy'|'sell', item, qty}.
let pick = null;

export function init(a) {
  app = a;
}

export function update(state) {
  const s = state.ui && state.ui.shop;
  const money = state.ui ? state.ui.money : 0;
  if (!s) {
    if (p) panels.close('shop');
    p = null;
    last = null;
    pick = null;
    return;
  }
  if (same(s, last) && p && lastMoney === money) return;
  last = s;
  lastMoney = money;
  if (!p) {
    tab = 'buy';
    pick = null;
    p = panels.open('shop', 'POKé MART', { onBack: back });
  }
  render(money);
}

export function back() {
  if (pick) {
    pick = null;
    render(lastMoney);
    return;
  }
  app.send({ t: 'shop.close' });
}

function rows() {
  return tab === 'buy' ? last.offers || [] : last.sell || [];
}

function render(money) {
  const list = rows();
  // A sold-out stack disappears from the sell list; drop the picker with it.
  if (pick && !list.some((r) => r.item === pick.item)) pick = null;
  rerender(p.body, () => [
    h('div.row.shop-top',
      h('span.grow', h('b', 'Money '), h('span.price', fmtMoney(money))),
      last.status ? h('span.muted', last.status) : null),
    h('div.tabs', { role: 'tablist' },
      tabBtn('buy', 'Buy'),
      tabBtn('sell', 'Sell')),
    pick ? picker(list.find((r) => r.item === pick.item), money) : listView(list, money),
  ]);
  if (!p.body.querySelector('.nav-focus')) {
    setFocus(p.body, p.body.querySelector(pick ? '[data-k=ok]' : '.item button') || p.body.querySelector('button'));
  }
}

function tabBtn(t, label) {
  return h('button', {
    type: 'button', role: 'tab', 'aria-selected': String(tab === t), 'data-k': 'tab-' + t,
    class: tab === t ? 'on' : '', on: { click: () => { tab = t; pick = null; render(lastMoney); } },
  }, label);
}

function listView(list, money) {
  if (!list.length) return h('p.muted', tab === 'buy' ? 'Nothing for sale.' : 'You have nothing the clerk will buy.');
  return h('div.list', list.map((r, i) => {
    const buy = tab === 'buy';
    const affordable = !buy || money >= r.price;
    return h('div.item', { class: buy && i === last.cursor ? 'sel' : '', 'data-item': r.item },
      itemIc(),
      h('span.nm', r.name, h('small', buy ? (r.desc || '') : `In bag: ${r.qty}`)),
      h('span.act',
        h('span.price', fmtMoney(r.price)),
        h('button', {
          type: 'button', 'data-k': (buy ? 'b' : 's') + r.item, disabled: !affordable,
          on: { click: () => { pick = { kind: tab, item: r.item, qty: 1 }; render(lastMoney); } },
        }, buy ? 'Buy' : 'Sell')));
  }));
}

function maxQty(r, money) {
  if (tab === 'sell') return Math.min(r.qty, MAX_QTY);
  return Math.max(1, Math.min(MAX_QTY, Math.floor(money / Math.max(1, r.price))));
}

function picker(r, money) {
  const max = maxQty(r, money);
  pick.qty = Math.max(1, Math.min(pick.qty, max));
  const total = pick.qty * r.price;
  const buy = tab === 'buy';
  const set = (q) => { pick.qty = Math.max(1, Math.min(max, q)); render(lastMoney); };
  return h('div.picker',
    h('div.item', itemIc(), h('span.nm', r.name, h('small', buy ? (r.desc || '') : `In bag: ${r.qty}`))),
    h('div.qtyrow', { 'aria-label': 'quantity' },
      h('button.step', { type: 'button', 'data-k': 'm10', disabled: pick.qty <= 1, on: { click: () => set(pick.qty - 10) } }, '−10'),
      h('button.step', { type: 'button', 'data-k': 'm1', 'aria-label': 'one less', disabled: pick.qty <= 1, on: { click: () => set(pick.qty - 1) } }, '−'),
      h('output.qtyval', { 'aria-live': 'polite' }, '×' + pick.qty),
      h('button.step', { type: 'button', 'data-k': 'p1', 'aria-label': 'one more', disabled: pick.qty >= max, on: { click: () => set(pick.qty + 1) } }, '+'),
      h('button.step', { type: 'button', 'data-k': 'p10', disabled: pick.qty >= max, on: { click: () => set(pick.qty + 10) } }, '+10')),
    h('div.row.totals',
      h('span.grow', buy ? 'Total' : 'You get'), h('span.price', fmtMoney(total))),
    buy ? h('div.row.totals.muted', h('span.grow', 'Money after'), h('span', fmtMoney(money - total))) : null,
    h('div.row',
      h('button.grow', { type: 'button', 'data-k': 'cancel', on: { click: () => { pick = null; render(lastMoney); } } }, 'Cancel'),
      h('button.go.grow', {
        type: 'button', 'data-k': 'ok', disabled: buy && total > money,
        on: { click: () => confirm(r) },
      }, buy ? `Buy ${pick.qty}` : `Sell ${pick.qty}`)));
}

function confirm(r) {
  app.send({ t: tab === 'buy' ? 'shop.buy' : 'shop.sell', item: r.item, qty: pick.qty });
  pick = null;
  render(lastMoney);
}

export const isOpen = () => !!last;
/** A on keyboard with nothing focused: open the picker on the server-cursor offer. */
export function confirmCursor() {
  if (!last || tab !== 'buy' || pick) return false;
  const o = (last.offers || [])[last.cursor];
  if (!o) return false;
  pick = { kind: 'buy', item: o.item, qty: 1 };
  render(lastMoney);
  return true;
}
