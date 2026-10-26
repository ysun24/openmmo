// Trade window: request/accept/decline, both offers, toggle party mons, items, money,
// ready -> confirm -> completed.
import { h, fmtMoney, same } from '../util.js';
import { panels, rerender } from './panels.js';
import { monImg } from './common.js';

let app, last = null, p = null, lastInv = null;

export function init(a) {
  app = a;
}

export function request(name) {
  if (name) app.send({ t: 'trade.request', name });
}

export function update(state) {
  const t = state.trade;
  if (!t) {
    if (p) panels.close('trade');
    p = null;
    last = null;
    return;
  }
  const inv = { party: state.party, bag: state.ui && state.ui.bag, money: state.ui && state.ui.money };
  if (same(t, last) && same(inv, lastInv) && p) return;
  last = t;
  lastInv = inv;
  if (!p) p = panels.open('trade', 'Trade', { onBack: () => onBack() });
  p.title.textContent = `Trade with ${t.partner || '?'}`;
  rerender(p.body, () => view(t, state));
}

function onBack() {
  if (!last) return panels.close('trade');
  if (last.phase === 'COMPLETED' || last.phase === 'CLOSED') {
    panels.close('trade');
    p = null;
  } else if (last.phase === 'REQUEST_RECEIVED') app.send({ t: 'trade.decline' });
  else app.send({ t: 'trade.cancel' });
}

function offerView(title, o) {
  return h('div.offer',
    h('h4', h('span', title), h('span.stage.' + o.stage, o.stage)),
    o.mons.length || o.items.length || o.money ? null : h('div.muted', 'Nothing offered yet'),
    o.mons.map((m) => h('div.entry', monImg(m.dex, 'icon', ''), `${m.name} Lv${m.level}`)),
    o.items.map((i) => h('div.entry', '◆', `${i.name} x${i.qty}`)),
    o.money ? h('div.entry', fmtMoney(o.money)) : null);
}

function view(t, state) {
  const msg = t.message ? h('p.muted.big', t.message) : null;
  if (t.phase === 'REQUEST_RECEIVED') {
    return [h('p.big', `${t.partner} wants to trade with you.`), msg,
      h('div.row', h('button.go.grow', { type: 'button', 'data-k': 'acc', on: { click: () => app.send({ t: 'trade.accept' }) } }, 'Accept'),
        h('button.danger.grow', { type: 'button', 'data-k': 'dec', on: { click: () => app.send({ t: 'trade.decline' }) } }, 'Decline'))];
  }
  if (t.phase === 'REQUEST_SENT') {
    return [h('p.big', `Waiting for ${t.partner} to accept...`), msg,
      h('button.danger', { type: 'button', 'data-k': 'cancel', on: { click: () => app.send({ t: 'trade.cancel' }) } }, 'Cancel request')];
  }
  if (t.phase === 'COMPLETED' || t.phase === 'CLOSED') {
    return [h('p.big', t.phase === 'COMPLETED' ? 'Trade complete!' : 'Trade closed.'), msg,
      h('div.offer-cols', offerView('You gave', t.mine), offerView(`${t.partner} gave`, t.theirs)),
      h('button', { type: 'button', 'data-k': 'close', on: { click: () => { panels.close('trade'); p = null; } } }, 'Close')];
  }
  // OPEN
  const mine = t.mine, theirs = t.theirs;
  const offered = new Set(mine.mons.map((m) => m.slot));
  const locked = mine.stage !== 'NONE';
  const party = state.party || [];
  const bag = (state.ui && state.ui.bag) || [];
  const money = h('input', { type: 'number', min: 0, max: state.ui ? state.ui.money : 0, value: mine.money || 0, inputmode: 'numeric', 'aria-label': 'money', 'data-k': 'money' });
  return [
    h('div.offer-cols', offerView('Your offer', mine), offerView(`${t.partner}'s offer`, theirs)),
    msg,
    h('h3', 'Your POKéMON (tap to offer)'),
    h('div.chips', party.map((m) => h('button', { type: 'button', 'data-k': 'm' + m.slot, class: offered.has(m.slot) ? 'on' : '', disabled: locked,
      on: { click: () => app.send({ t: 'trade.mon', slot: m.slot }) } }, monImg(m.dex, 'icon', ''), `${m.nick || m.name} Lv${m.level}`))),
    h('h3', 'Items'),
    bag.length ? h('div.list', bag.map((b) => {
      const off = mine.items.find((x) => x.item === b.item);
      const q = h('input.qty', { type: 'number', min: 0, max: b.qty, value: off ? off.qty : 0, inputmode: 'numeric', 'aria-label': 'quantity', 'data-k': 'iq' + b.item });
      return h('div.item', { class: off ? 'on' : '' }, h('span.ic.ph', '◆'), h('span.nm', b.name, h('small', `have ${b.qty}`)),
        h('span.act', q, h('button', { type: 'button', 'data-k': 'is' + b.item, disabled: locked, on: { click: () => app.send({ t: 'trade.item', item: b.item, qty: Math.max(0, Math.min(b.qty, q.value | 0)) }) } }, 'Set')));
    })) : h('p.muted', 'No items.'),
    h('h3', 'Money'),
    h('div.row', money, h('button', { type: 'button', 'data-k': 'ms', disabled: locked, on: { click: () => app.send({ t: 'trade.money', amount: Math.max(0, money.value | 0) }) } }, 'Set money'),
      h('span.muted', 'you have ' + fmtMoney(state.ui ? state.ui.money : 0))),
    h('div.row',
      h('button.grow', { type: 'button', 'data-k': 'ready', disabled: mine.stage !== 'NONE', on: { click: () => app.send({ t: 'trade.ready' }) } }, 'Ready'),
      h('button.go.grow', { type: 'button', 'data-k': 'confirm', disabled: !(mine.stage === 'READY' && theirs.stage !== 'NONE'), on: { click: () => app.send({ t: 'trade.confirm' }) } }, 'Confirm'),
      h('button.danger.grow', { type: 'button', 'data-k': 'tcancel', on: { click: () => app.send({ t: 'trade.cancel' }) } }, 'Cancel')),
    h('p.muted', mine.stage === 'NONE' ? 'Changing an offer resets both sides to NONE.' : theirs.stage === 'NONE' ? 'Waiting for partner to be ready…' : mine.stage === 'CONFIRMED' ? 'Waiting for partner to confirm…' : 'Both ready — confirm to trade.'),
  ];
}
