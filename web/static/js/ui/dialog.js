// Dialog box (GBA style, inside the game overlay) + choice menu.
import { h, $, same } from '../util.js';

let app, last = null;

export function init(a) {
  app = a;
}

export function update(state) {
  const d = state.ui && state.ui.dialog;
  if (same(d, last)) return;
  last = d;
  const root = $('#dialog');
  if (!d) {
    root.classList.add('hidden');
    root.replaceChildren();
    return;
  }
  root.classList.remove('hidden');
  const box = h('div.box.gbabox', { role: 'button', tabindex: -1, 'aria-label': 'Continue', on: { click: () => app.send({ t: 'a' }) } },
    d.speaker ? h('span.speaker', d.speaker) : null,
    (d.lines || []).map((l) => h('div', l)),
    !d.lastPage ? h('span.more', '▼') : null,
  );
  const kids = [box];
  if (d.choices && d.choices.length) {
    kids.push(h('div.choices.gbabox', d.choices.map((c, i) =>
      h('button', { type: 'button', class: i === d.cursor ? 'sel' : '', on: { click: (e) => { e.stopPropagation(); app.send({ t: 'choose', index: i }); } } }, c))));
  }
  root.replaceChildren(...kids);
}

export const isOpen = () => !!last;
export const hasChoices = () => !!(last && last.choices && last.choices.length);
