// FireRed's FAME CHECKER (topic "fame"): the people and their six flavor texts from
// fame_checker.c / data/text/fame_checker.inc (manifest.minigames.fameChecker), unlocked by the
// server's pick states and flavor-text flags.
import { h } from '../../util.js';
import { panels, setFocus, rerender } from '../panels.js';
import { assets } from '../../assets.js';

export const TOPICS = ['fame'];
let ctx = null;
let s = null;
let who = 0, text = -1;

export function init(c) {
  ctx = c;
}

export function open() {
  ctx.send('fame', 'open');
}

export function onTopic(_k, now) {
  s = now;
  if (!now) return panels.close('mgfame');
  render();
}

function render() {
  const data = (assets.manifest && assets.manifest.minigames && assets.manifest.minigames.fameChecker) || { people: [] };
  const p = panels.open('mgfame', 'FAME CHECKER', { onBack: () => (ctx.send('fame', 'close'), panels.close('mgfame')) });
  if (s.error) return rerender(p.body, () => h('p', s.error));
  const shown = data.people.filter((x) => s.pick[x.id] > 0);
  if (!shown.find((x) => x.id === who) && shown.length) who = shown[0].id;
  const person = data.people.find((x) => x.id === who);
  const flags = s.flags[who] || 0;
  const name = (x) => (s.pick[x.id] === 2 ? x.name : '?????');
  const player = (ctx.app.net.state.world && ctx.app.net.state.world.name) || 'PLAYER';
  const fill = (t) => t.replace(/\{PLAYER\}/g, player).replace(/\{RIVAL\}/g, 'GREEN');
  rerender(p.body, () => h('div.mg-fame',
    h('ul', shown.map((x) => h('li', h('button', { type: 'button', 'data-k': 'mg-f-' + x.id, class: x.id === who ? 'sel' : '', on: { click: () => ((who = x.id), (text = -1), render()) } }, name(x))))),
    person ? h('div',
      h('h3', s.pick[who] === 2 ? person.title : '?????'),
      h('div.mg-dots', person.texts.map((t, i) => h('button', { type: 'button', 'data-k': 'mg-ft-' + i, disabled: !(flags & (1 << i)), class: flags & (1 << i) ? '' : 'mg-locked',
        on: { click: () => ((text = i), render()) } }, flags & (1 << i) ? `${person.origins[i]} · ${person.objects[i]}` : '?'))),
      h('div.mg-text', text >= 0 ? fill(person.texts[text]) : s.pick[who] === 2 ? fill(person.quote || '') : 'Who is this person?')) : h('p', 'Nobody yet.')));
  setFocus(p.body, p.body.querySelector(`[data-k="mg-f-${who}"]`));
}
