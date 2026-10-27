// MYSTERY GIFT / WONDER CARDS (topic "gift"): take an admin-defined card here, then the
// deliveryman in any Pokémon Center 2F hands over what it holds.
import { h } from '../../util.js';
import { panels, setFocus, rerender } from '../panels.js';

export const TOPICS = ['gift'];
let ctx = null;
let s = null;

export function init(c) {
  ctx = c;
}

export function open() {
  ctx.send('gift', 'open');
}

const KIND = { item: '🎁 ITEM', mon: '🥚 POKéMON', ticket: '🎫 TICKET' };

export function onTopic(_k, now) {
  s = now;
  if (!now) return panels.close('mggift');
  const p = panels.open('mggift', 'MYSTERY GIFT', { onBack: () => (ctx.send('gift', 'close'), panels.close('mggift')) });
  const card = (g, btn) => h('div.mg-card', h('h3', `${KIND[g.kind] || g.kind} — ${g.title}`), g.body ? h('p', g.body) : null, btn);
  rerender(p.body, () => [
    h('h3', 'NEW WONDER CARDS'),
    s.available.length ? s.available.map((g) => card(g, h('button', { type: 'button', 'data-k': 'mg-g-' + g.id, on: { click: () => ctx.send('gift', 'receive', { id: g.id }) } }, 'RECEIVE'))) : h('p.muted', 'No new MYSTERY GIFTS right now.'),
    s.cards.length ? [h('h3', 'SAVED CARDS'), s.cards.map((g) => card(g, h('p.muted', 'Visit the deliveryman at a POKéMON CENTER 2F.')))] : null,
    s.tickets.length ? h('p', 'Event tickets: ' + s.tickets.join(', ').toUpperCase()) : null,
  ]);
  setFocus(p.body, p.body.querySelector('button'));
}
