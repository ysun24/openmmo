// The UNION ROOM (topic "union"): every player in the room or in a Pokémon Center 2F, with TRADE,
// BATTLE, CHAT and minigame offers. An accepted trade / battle runs through the normal trade and
// duel windows (the asking browser sends trade.request / duel).
import { h } from '../../util.js';
import { panels, setFocus, rerender } from '../panels.js';

export const TOPICS = ['union'];
let ctx = null;
let s = null;
let target = null;

export function init(c) {
  ctx = c;
}

export function open() {
  ctx.send('union', 'enter');
}

const WHAT = [['trade', 'TRADE'], ['battle', 'BATTLE'], ['chat', 'CHAT'], ['game:berrycrush', 'BERRY CRUSH'], ['game:pokejump', 'POKéMON JUMP'], ['game:dodrio', 'DODRIO BERRY PICKING']];

export function onTopic(_k, now) {
  s = now;
  if (!now) return panels.close('mgunion');
  const acc = now.accepted;
  if (acc) {
    // The other player said yes: start the real trade / battle with them.
    if (acc.what === 'trade') ctx.app.send({ t: 'trade.request', name: acc.name });
    else if (acc.what === 'battle') ctx.app.send({ t: 'duel', name: acc.name });
  }
  render();
}

function render() {
  const p = panels.open('mgunion', 'UNION ROOM', { onBack: () => (ctx.send('union', 'leave'), panels.close('mgunion')) });
  const B = (label, k, fn) => h('button', { type: 'button', 'data-k': k, on: { click: fn } }, label);
  rerender(p.body, () => [
    s.invite ? h('div.mg-card', h('p', `${s.invite.from} wants to ${(WHAT.find((w) => w[0] === s.invite.what) || [0, s.invite.what])[1]} with you.`),
      h('div.mg-row', B('YES', 'mg-u-yes', () => ctx.send('union', 'answer', { answer: 'yes' })), B('NO', 'mg-u-no', () => ctx.send('union', 'answer', { answer: 'no' })))) : null,
    s.players.length ? h('ul.mg-players', s.players.map((pl) => h('li', B(`${pl.name}${pl.busy ? ' (busy)' : ''}`, 'mg-u-p-' + pl.name, () => ((target = pl.name), render())),
      h('small.muted', pl.where.replace(/_/g, ' '))))) : h('p.muted', 'Nobody else is here yet. Players in any POKéMON CENTER 2F appear here.'),
    target ? h('div.mg-card', h('p', `Ask ${target} to…`), h('div.mg-row', WHAT.map(([w, label]) => B(label, 'mg-u-' + w, () => {
      ctx.send('union', 'invite', { name: target, what: w });
      target = null;
    })))) : null,
    h('div.mg-chat', (s.chat || []).map((c) => h('div', h('b', c.name + ': '), c.text))),
    h('div.mg-row', h('input', { 'data-k': 'mg-u-text', maxlength: 60, placeholder: 'Say something' }),
      B('SEND', 'mg-u-send', () => {
        const i = p.body.querySelector('[data-k="mg-u-text"]');
        if (i.value.trim()) ctx.send('union', 'chat', { text: i.value.trim() });
        i.value = '';
      })),
  ]);
  setFocus(p.body, p.body.querySelector('button'));
}
