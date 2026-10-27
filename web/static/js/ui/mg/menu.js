// The decomp's multichoice boxes (topic "menu"): a server-driven list the script waits on.
import { h } from '../../util.js';
import { panels, setFocus, rerender } from '../panels.js';

export const TOPICS = ['menu'];
let ctx = null;
let seq = -1;

export function init(c) {
  ctx = c;
}

export function onTopic(_k, m) {
  if (!m) {
    seq = -1;
    return panels.close('mgmenu');
  }
  if (m.seq === seq && panels.isOpen('mgmenu')) return;
  seq = m.seq;
  const pick = (i) => {
    panels.close('mgmenu');
    ctx.send('menu', 'choose', { choice: i });
  };
  const p = panels.open('mgmenu', m.title || 'CHOOSE', { onBack: () => m.cancel !== false && pick(-1) });
  p.el.classList.add('mg-menu');
  rerender(p.body, () =>
    m.options.map((o, i) =>
      h('button.mg-opt', { type: 'button', 'data-k': 'mg-opt-' + i, on: { click: () => pick(i) } },
        h('span', o), m.detail && m.detail[i] ? h('span.mg-price', m.detail[i]) : null)));
  setFocus(p.body, p.body.querySelector('button'));
}
