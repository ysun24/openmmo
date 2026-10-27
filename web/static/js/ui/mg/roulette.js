// Mauville ROULETTE (topic "roulette"): the 3x4 grid of colors x Pokémon, the six-ball round and
// the multipliers of roulette.c's GetMultiplier, all decided by the server.
import { h } from '../../util.js';
import { panels, setFocus, rerender } from '../panels.js';
import { assets } from '../../assets.js';

export const TOPICS = ['roulette'];
let ctx = null;
let s = null;
let sel = 6; // SQU_ORANGE_WYNAUT

const POKES = ['WYNAUT', 'AZURILL', 'SKITTY', 'MAKUHITA'];
const COLORS = ['ORANGE', 'GREEN', 'PURPLE'];
const CSS = ['#f0a040', '#58c060', '#a070d0'];

export function init(c) {
  ctx = c;
}

export function onTopic(_k, now) {
  if (!now) {
    s = null;
    return panels.close('mgroulette');
  }
  s = now;
  render();
}

const label = (id) => {
  const col = id % 5, row = Math.floor(id / 5);
  if (row === 0) return POKES[col - 1];
  if (col === 0) return COLORS[row - 1];
  return COLORS[row - 1] + ' ' + POKES[col - 1];
};

function icon(name) {
  const r = assets.manifest && assets.manifest.minigames && assets.manifest.minigames.roulette;
  const rel = r && r[name.toLowerCase()];
  return rel ? h('i.mg-ricon', { style: `background-image:url(assets/${rel})` }) : h('i.mg-ricon', name[0]);
}

function render() {
  const p = panels.open('mgroulette', 'ROULETTE', { onBack: () => ctx.send('roulette', 'quit') });
  p.el.classList.add('mg-roulette');
  const hits = new Set(s.hits || []);
  const cell = (id, content, extra = '') => {
    const m = s.multipliers[id];
    return h('button.mg-cell' + extra, {
      type: 'button', 'data-k': 'mg-r' + id, disabled: m === 0 || s.ball >= s.balls,
      'aria-label': `${label(id)} x${m}`,
      class: (hits.has(id) ? 'hit ' : '') + (sel === id ? 'sel' : ''),
      on: { click: () => { sel = id; ctx.send('roulette', 'bet', { selection: id }); } },
    }, content, h('small', m ? '×' + m : '—'));
  };
  const grid = [h('span.mg-corner', `${s.minBet} COIN${s.minBet > 1 ? 'S' : ''}/BALL`)];
  for (let c = 1; c <= 4; c++) grid.push(cell(c, icon(POKES[c - 1]), '.head'));
  for (let r = 1; r <= 3; r++) {
    grid.push(cell(r * 5, h('b', { style: `color:${CSS[r - 1]}` }, COLORS[r - 1]), '.head'));
    for (let c = 1; c <= 4; c++) {
      const id = r * 5 + c;
      grid.push(cell(id, h('span.mg-sq', { style: `background:${CSS[r - 1]}` }, icon(POKES[c - 1])), hits.has(id) ? '.ball' : ''));
    }
  }
  const last = s.last;
  rerender(p.body, () => [
    h('div.mg-row', h('b', 'COINS'), h('span.mg-num', String(s.coins)), h('b', 'BALL'), h('span.mg-num', `${Math.min(s.ball + 1, s.balls)}/${s.balls}`),
      s.special ? h('b.mg-rt', 'SPECIAL RATE') : null),
    h('div.mg-grid', grid),
    h('div.mg-wheel', s.slots.map((sq, i) => h('span.mg-wslot' + (last && last.slot === i ? '.land' : '') + (hits.has(sq) ? '.taken' : ''),
      { style: `background:${CSS[Math.floor(sq / 5) - 1]}`, title: label(sq) }, POKES[(sq % 5) - 1][0]))),
    h('p.mg-msg', { 'aria-live': 'polite' }, s.msg || 'Pick a bet: a square pays ×12, a Pokémon or color less.'),
    s.ball >= s.balls ? h('button', { type: 'button', 'data-k': 'mg-newround', on: { click: () => ctx.send('roulette', 'newround') } }, 'NEW ROUND') : null,
    h('button', { type: 'button', 'data-k': 'mg-rquit', on: { click: () => ctx.send('roulette', 'quit') } }, 'QUIT'),
  ]);
  setFocus(p.body, p.body.querySelector(`[data-k="mg-r${sel}"]:not(:disabled)`) || p.body.querySelector('button:not(:disabled)'));
}
