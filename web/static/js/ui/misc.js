// Party view, main menu, player actions, duel invite prompt, name prompt.
import { h, fmtMoney, hpBar, same } from '../util.js';
import { panels, rerender, setFocus } from './panels.js';
import { monImg } from './common.js';
import * as market from './market.js';
import { openChallenge, describeRules } from './battle.js';

let app, lastParty = null, lastInvite = null;

export function init(a) {
  app = a;
}

export function update(state, changed) {
  if (panels.isOpen('party') && (changed.includes('party') || changed.includes('ui'))) renderParty(state);
  if (changed.includes('invite')) {
    const inv = state.invite;
    if (!same(inv, lastInvite)) {
      lastInvite = inv;
      if (inv) {
        const p = panels.open('invite', 'Duel challenge', { onBack: () => app.send({ t: 'duel.answer', accept: false }) });
        rerender(p.body, () => [h('p.big', `${inv.from} challenges you to a duel!`), h('p.muted', describeRules(inv.rules)),
          h('div.row', h('button.go.grow', { type: 'button', 'data-k': 'acc', on: { click: () => app.send({ t: 'duel.answer', accept: true }) } }, 'Accept'),
            h('button.danger.grow', { type: 'button', 'data-k': 'dec', on: { click: () => app.send({ t: 'duel.answer', accept: false }) } }, 'Decline'))]);
        setFocus(p.body, p.body.querySelector('[data-k=acc]'));
      } else panels.close('invite');
    }
  }
}

export function openParty() {
  panels.open('party', 'Party & Bag');
  lastParty = null;
  renderParty(app.net.state);
}
function renderParty(state) {
  const p = panels.get('party');
  const key = JSON.stringify([state.party, state.ui && state.ui.bag, state.ui && state.ui.money]);
  if (key === lastParty) return;
  lastParty = key;
  const party = state.party || [];
  const bag = (state.ui && state.ui.bag) || [];
  rerender(p.body, () => [
    h('div.row', h('b', 'Money: '), h('span.price', fmtMoney(state.ui ? state.ui.money : 0))),
    h('h3', `POKéMON (${party.length}/6)`),
    h('div.list', party.length ? party.map((m) => h('div.item', monImg(m.dex),
      h('span.nm', m.nick ? `${m.nick} (${m.name})` : m.name, h('small', `Lv${m.level} · HP ${m.hp}/${m.maxHp}`)),
      h('span.act', hpBar(m.hp, m.maxHp)))) : h('p.muted', 'No POKéMON.')),
    h('h3', 'Bag'),
    h('div.list', bag.length ? bag.map((b) => h('div.item', h('span.ic.ph', '◆'), h('span.nm', b.name), h('span.act', 'x' + b.qty))) : h('p.muted', 'Empty.')),
  ]);
}

export function openMenu() {
  const p = panels.open('menu', 'Menu');
  const item = (label, k, fn) => h('button', { type: 'button', 'data-k': k, on: { click: () => { panels.close('menu'); fn(); } } }, label);
  rerender(p.body, () => [
    item('POKéMON & Bag', 'party', openParty),
    item('Market', 'market', () => market.open()),
    item('Trade with…', 'trade', () => askName('Trade with', (n) => app.send({ t: 'trade.request', name: n }))),
    item('Duel…', 'duel', () => askName('Duel', (n) => openChallenge(n))),
    item('Chat', 'chat', () => app.openChat()),
    item(app.showFps ? 'Hide FPS' : 'Show FPS', 'fps', () => app.toggleFps()),
    item('Log out', 'logout', () => app.logout()),
  ]);
  p.body.style.gridTemplateColumns = '1fr 1fr';
  setFocus(p.body, p.body.querySelector('button'));
}

export function askName(title, fn, initial = '') {
  const p = panels.open('ask', title);
  const input = h('input', { value: initial, maxlength: 12, placeholder: 'player name', autocapitalize: 'off', 'aria-label': 'player name' });
  const submit = (e) => {
    e && e.preventDefault();
    const n = input.value.trim();
    if (!n) return;
    panels.close('ask');
    fn(n);
  };
  p.body.replaceChildren(h('form.row', { on: { submit } }, input, h('button.go', { type: 'submit' }, 'OK')));
  setTimeout(() => input.focus(), 0);
}

/** Actions for a tapped / faced player. */
export function openPlayer(ent) {
  const p = panels.open('player', ent.name || 'Player');
  p.title.textContent = ent.name || 'Player';
  const act = (label, k, fn, cls = '') => h('button' + cls, { type: 'button', 'data-k': k, on: { click: () => { panels.close('player'); fn(); } } }, label);
  rerender(p.body, () => [h('div.row',
    act('⚔ Duel', 'duel', () => openChallenge(ent.name), '.grow'),
    act('⇄ Trade', 'trade', () => app.send({ t: 'trade.request', name: ent.name }), '.grow'),
    act('✉ Chat', 'chat', () => app.openChat(`@${ent.name} `), '.grow'))]);
  p.onClose = () => app.setHighlight(null);
  app.setHighlight(ent.id);
  setFocus(p.body, p.body.querySelector('button'));
}
