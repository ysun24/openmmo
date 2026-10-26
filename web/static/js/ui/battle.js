// Battle screen: GBA layout inside the game overlay (240x160 units of var(--u)).
import { h, $, hpClass, same } from '../util.js';
import { assets } from '../assets.js';
import { monImg } from './common.js';

let app;
let b = null; // last battle section
let menu = 'main'; // main | fight | party | bag
let dismissed = false;
let sk = null; // skeleton elements

const RESULT = { WIN: 'You won!', LOSE: 'You lost...', RAN: 'Got away safely!', CAUGHT: 'Gotcha!', DRAW: 'Draw.', FORFEIT: 'Forfeited.' };

export function init(a) {
  app = a;
}

export const isOpen = () => !!b && !dismissed;
export const root = () => $('#battle');

export function update(state, changed) {
  const nb = state.battle;
  if (!nb) {
    if (b) close();
    return;
  }
  const fresh = !b;
  if (!fresh && same(nb, b) && !changed.includes('ui')) return;
  if (fresh) {
    menu = 'main';
    dismissed = false;
    build();
  }
  const prevPhase = b && b.phase;
  b = nb;
  if (b.phase !== 'ACTION' && (menu === 'fight' || menu === 'bag')) menu = 'main';
  if (b.phase === 'FORCED_SWITCH') menu = 'party';
  else if (prevPhase === 'FORCED_SWITCH' && menu === 'party') menu = 'main';
  if (b.phase === 'ACTION' && prevPhase && prevPhase !== 'ACTION') menu = 'main';
  if (b.phase !== 'ENDED' || b.learn) dismissed = false;
  render(state);
}

function close() {
  b = null;
  sk = null;
  const r = root();
  r.classList.add('hidden');
  r.replaceChildren();
}

function build() {
  const r = root();
  const info = (side) => {
    const name = h('span'), lv = h('span'), bar = h('i'), num = h('div.hpnum');
    const el = h('div.info.' + side, h('div.row', name, lv), h('div.hp', h('span', 'HP'), h('span.hpbar', bar)), side === 'me' ? num : null);
    return { el, name, lv, bar, num };
  };
  sk = {
    foeImg: h('div.mon.foe'), meImg: h('div.mon.me'),
    foe: info('foe'), me: info('me'),
    text: h('div.text'), menu: h('div.menu.gbabox'), bottom: null, sheet: h('div.sheet.gbabox.hidden'),
    result: h('div.result.gbabox.hidden'), dexFoe: null, dexMe: null,
  };
  sk.bottom = h('div.bottom', sk.text, sk.menu);
  r.replaceChildren(h('div.plat.foe'), h('div.plat.me'), sk.foeImg, sk.meImg, sk.foe.el, sk.me.el, sk.bottom, sk.sheet, sk.result);
  r.classList.remove('hidden');
}

function setMon(slot, dex, kind) {
  if (sk['dex' + slot] === dex) return;
  sk['dex' + slot] = dex;
  const box = slot === 'Foe' ? sk.foeImg : sk.meImg;
  const url = assets.pokemon(dex, kind);
  const cls = 'mon ' + (slot === 'Foe' ? 'foe' : 'me');
  const n = url ? h('img', { src: url, alt: '', draggable: false }) : h('div.ph', '#' + dex);
  n.className = cls + (url ? '' : ' ph');
  box.replaceWith(n);
  if (slot === 'Foe') sk.foeImg = n;
  else sk.meImg = n;
}

function setInfo(i, m, showNum) {
  i.name.textContent = m.name;
  i.lv.textContent = 'Lv' + m.level;
  const pct = m.maxHp > 0 ? (100 * Math.max(0, m.hp)) / m.maxHp : 0;
  i.bar.style.width = pct + '%';
  i.bar.className = hpClass(m.hp, m.maxHp);
  if (showNum) i.num.textContent = `${Math.max(0, m.hp)}/${m.maxHp}`;
}

const fill = (el, ...k) => el.replaceChildren(...k.flat(Infinity).filter((x) => x !== null && x !== undefined && x !== false));

const btn = (label, k, fn, disabled = false, extra = null) =>
  h('button', { type: 'button', 'data-k': k, disabled, on: { click: fn } }, label, extra);

function render(state) {
  const r = root();
  r.classList.toggle('hidden', dismissed);
  setMon('Foe', b.foe.dex, 'front');
  setMon('Me', b.me.dex, 'back');
  sk.foeImg.classList.toggle('fainted', b.foe.hp <= 0);
  sk.meImg.classList.toggle('fainted', b.me.hp <= 0);
  setInfo(sk.foe, b.foe, false);
  setInfo(sk.me, b.me, true);

  const log = b.log || [];
  const lastLines = log.slice(-2);
  const keep = r.querySelector('.nav-focus');
  const keepK = keep && keep.dataset.k;
  sk.menu.className = 'menu gbabox';
  sk.menu.classList.remove('hidden');
  sk.sheet.classList.add('hidden');
  sk.result.classList.add('hidden');

  if (b.phase === 'ACTION' && menu === 'fight') {
    const moves = b.me.moves || [];
    sk.menu.classList.add('wide');
    const cells = [0, 1, 2, 3].map((i) => {
      const m = moves[i];
      if (!m) return btn('-', 'mv' + i, null, true);
      return btn(m.name, 'mv' + i, () => app.send({ t: 'battle.fight', move: i }), m.pp <= 0, h('span.pp', `${m.pp}/${m.maxPp}`));
    });
    sk.menu.replaceChildren(...cells);
    sk.text.replaceChildren(btn('◀ BACK', 'back', () => setMenu('main')));
    sk.text.style.flex = '0 0 calc(var(--u) * 54)';
  } else {
    sk.text.style.flex = '';
    if (b.phase === 'ACTION') {
      sk.text.replaceChildren(...(lastLines.length && b.turn > 1 ? [h('div', lastLines[lastLines.length - 1])] : []), h('div', `What will ${b.me.name} do?`));
      sk.menu.replaceChildren(
        btn('FIGHT', 'fight', () => setMenu('fight')),
        btn('BAG', 'bag', () => setMenu('bag')),
        btn('POKéMON', 'pkmn', () => setMenu('party')),
        btn('RUN', 'run', () => app.send({ t: 'battle.run' })),
      );
    } else {
      sk.menu.classList.add('hidden');
      sk.text.replaceChildren(...lastLines.map((l) => h('div', l)));
      if (b.phase === 'WAITING' && !lastLines.length) sk.text.replaceChildren(h('div', 'Waiting...'));
    }
  }

  if (menu === 'party' && (b.phase === 'ACTION' || b.phase === 'FORCED_SWITCH')) {
    const forced = b.phase === 'FORCED_SWITCH';
    sk.sheet.classList.remove('hidden');
    fill(sk.sheet,
      h('div.title', forced ? 'Choose the next POKéMON.' : 'Switch to which POKéMON?'),
      (b.party || []).map((p, i) => {
        const active = p.name === b.me.name && p.level === b.me.level && p.hp === b.me.hp;
        return h('button', { type: 'button', 'data-k': 'p' + i, disabled: p.hp <= 0 || active, on: { click: () => app.send({ t: 'battle.switch', slot: i }) } },
          monImg(p.dex, 'icon', ''), h('span', `${p.name} Lv${p.level}`), h('span', `${Math.max(0, p.hp)}/${p.maxHp}`));
      }),
      forced ? null : btn('◀ BACK', 'pback', () => setMenu('main')),
    );
  } else if (menu === 'bag' && b.phase === 'ACTION') {
    const bag = (state.ui && state.ui.bag) || [];
    const balls = bag.filter((x) => /BALL/i.test(x.name)).reduce((s, x) => s + x.qty, 0);
    sk.sheet.classList.remove('hidden');
    fill(sk.sheet,
      h('div.title', 'BAG'),
      h('button', { type: 'button', 'data-k': 'ball', disabled: !b.wild || balls <= 0, on: { click: () => app.send({ t: 'battle.ball' }) } },
        h('span', '◓'), h('span', 'POKé BALL'), h('span', `x${balls}`)),
      !b.wild ? h('div.title', "Can't use balls in a trainer battle.") : null,
      btn('◀ BACK', 'bback', () => setMenu('main')),
    );
  }

  if (b.learn) {
    sk.sheet.classList.remove('hidden');
    fill(sk.sheet,
      h('div.title', `${b.me.name} wants to learn ${b.learn.move}. Forget which move?`),
      b.learn.current.map((m, i) => h('button', { type: 'button', 'data-k': 'l' + i, on: { click: () => app.send({ t: 'battle.learn', slot: i }) } }, h('span', i + 1), h('span', m), h('span', ''))),
      h('button', { type: 'button', 'data-k': 'lskip', on: { click: () => app.send({ t: 'battle.learn', slot: null }) } }, h('span', '✕'), h('span', `Don't learn ${b.learn.move}`), h('span', '')),
    );
  } else if (b.phase === 'ENDED') {
    sk.result.classList.remove('hidden');
    fill(sk.result, h('div', RESULT[b.result] || b.result || 'Battle over'),
      !b.wild && b.opponentName ? h('div', { style: 'font-size:calc(var(--u)*7)' }, 'vs ' + b.opponentName) : null,
      btn('Continue', 'cont', () => {
        dismissed = true;
        root().classList.add('hidden');
      }));
  }

  let n = keepK && r.querySelector(`[data-k="${keepK}"]`);
  if (!n || n.disabled || !n.offsetParent) n = r.querySelector('.sheet:not(.hidden) button:not(:disabled), .result:not(.hidden) button, .menu:not(.hidden) button:not(:disabled)');
  if (n) n.classList.add('nav-focus');
}

function setMenu(m) {
  menu = m;
  root().querySelectorAll('.nav-focus').forEach((e) => e.classList.remove('nav-focus'));
  render(app.net.state);
  const first = root().querySelector('.sheet:not(.hidden) button:not(:disabled), .menu:not(.hidden) button:not(:disabled)');
  if (first) first.classList.add('nav-focus');
}

/** B in battle: back out of a sub menu. */
export function back() {
  if (!b) return;
  if (menu !== 'main' && b.phase !== 'FORCED_SWITCH') setMenu('main');
}
