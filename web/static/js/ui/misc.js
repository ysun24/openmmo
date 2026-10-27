// Party (reorder, summary, item targets), bag by pocket, PC, notices, main menu, player actions, duel invite prompt, name prompt.
import { h, fmtMoney, hpBar, same, toast } from '../util.js';
import { panels, rerender, setFocus } from './panels.js';
import { monImg, itemIc } from './common.js';
import * as market from './market.js';
import { openChallenge, describeRules } from './battle.js';
import { frameStyles } from '../font.js';
import { ACTIONS, keyLabel } from '../settings.js';

let app, lastParty = null, lastInvite = null;

export function init(a) {
  app = a;
}

let lastNotice = null;

export function update(state, changed) {
  if (changed.includes('party') || changed.includes('ui') || changed.includes('pc')) {
    for (const name of ['party', 'bag', 'pc', 'summary']) if (panels.isOpen(name)) RENDER[name](state);
  }
  const n = state.ui && state.ui.notice;
  // Server notices (an item used, a refused move) pop up over the game.
  if (n && (!lastNotice || n.seq !== lastNotice.seq)) toast(n.text, 3500);
  lastNotice = n;
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

// ------------------------------------------------------------------ party
// When set, the party panel is choosing a target for this bag item.
let useItem = null;
let summaryId = null;

export function openParty(item = null) {
  useItem = item;
  const p = panels.open('party', item ? `Use ${item.name} on…` : 'POKéMON', {
    onBack: () => { useItem = null; panels.close('party'); },
  });
  p.title.textContent = item ? `Use ${item.name} on…` : 'POKéMON';
  lastParty = null;
  renderParty(app.net.state);
  setFocus(p.body, p.body.querySelector('.item button'));
}

function renderParty(state) {
  const p = panels.get('party');
  const key = JSON.stringify([state.party, useItem]);
  if (key === lastParty) return;
  lastParty = key;
  const party = state.party || [];
  const last = party.length - 1;
  rerender(p.body, () => [
    useItem ? h('p.muted', useItem.desc || '') : null,
    h('div.list', party.length ? party.map((m, i) => h('div.item.mon', { 'data-mon': m.id },
      // party menu icons bob slower when the monster is low on HP, and stop when it fainted
      monImg(m.dex, 'icon', '.ic', { still: m.hp <= 0, slow: m.hp > 0 && m.hp * 4 < m.maxHp }),
      h('span.nm', m.nick ? `${m.nick} (${m.name})` : m.name,
        h('small', `Lv${m.level} · HP ${m.hp}/${m.maxHp}${m.hp <= 0 ? ' · FNT' : ''}`), hpBar(m.hp, m.maxHp)),
      h('span.act', useItem
        ? h('button.go', { type: 'button', 'data-k': 'use' + i, on: { click: () => use(useItem, m) } }, 'Use')
        : [
            h('button', { type: 'button', 'data-k': 'sum' + i, on: { click: () => openSummary(m.id) } }, 'Info'),
            h('button.step', { type: 'button', 'data-k': 'up' + i, 'aria-label': 'move up', disabled: i === 0, on: { click: () => app.send({ t: 'party.move', from: i, to: i - 1 }) } }, '▲'),
            h('button.step', { type: 'button', 'data-k': 'dn' + i, 'aria-label': 'move down', disabled: i === last, on: { click: () => app.send({ t: 'party.move', from: i, to: i + 1 }) } }, '▼'),
          ]))) : h('p.muted', 'No POKéMON.')),
  ]);
}

function use(item, mon) {
  app.send({ t: 'item.use', item: item.item, mon: mon ? mon.id : null });
  if (mon) {
    // Stay on the party so the HP change shows; drop the target mode once the item runs out.
    lastParty = null;
  }
}

function openSummary(id) {
  summaryId = id;
  panels.open('summary', 'Summary');
  renderSummary(app.net.state);
}

const STAT_LABELS = { hp: 'HP', atk: 'Attack', def: 'Defense', spa: 'Sp. Atk', spd: 'Sp. Def', spe: 'Speed' };

function renderSummary(state) {
  const p = panels.get('summary');
  const m = (state.party || []).find((x) => x.id === summaryId);
  if (!m) return panels.close('summary');
  p.title.textContent = m.nick || m.name;
  rerender(p.body, () => [
    h('div.row.sumhead', monImg(m.dex, 'front', '.big', { shiny: m.shiny }),
      h('div.grow',
        h('div', h('b', m.nick ? `${m.nick} (${m.name})` : m.name), m.shiny ? h('span.shinystar', { title: 'shiny', 'aria-label': 'shiny' }, ' ★') : ''),
        h('div.muted', `Lv${m.level} · #${m.dex}${m.types ? ' · ' + m.types.join('/') : ''}`),
        h('div', `HP ${m.hp}/${m.maxHp}`), hpBar(m.hp, m.maxHp),
        h('div.muted', `OT ${m.ot || '?'} · EXP ${m.xp ?? 0}`))),
    m.nature ? h('div.row', h('span.chip', 'Nature: ' + m.nature), h('span.chip', 'Ability: ' + m.ability)) : null,
    m.stats ? h('div.stats', Object.entries(m.stats).map(([k, v]) => h('div.stat', h('span', STAT_LABELS[k] || k), h('b', String(v))))) : null,
    h('h3', 'Moves'),
    h('div.list', (m.moves || []).length ? m.moves.map((mv) => h('div.item.move', h('span.nm', mv.name), h('span.act', `PP ${mv.pp}/${mv.maxPp}`))) : h('p.muted', 'No moves.')),
  ]);
}

// ------------------------------------------------------------------ bag
const POCKETS = [['items', 'Items'], ['balls', 'Balls'], ['tms', 'TMs'], ['berries', 'Berries'], ['key', 'Key']];
let pocket = 'items', lastBag = null;

export function openBag() {
  const p = panels.open('bag', 'Bag');
  lastBag = null;
  renderBag(app.net.state);
  setFocus(p.body, p.body.querySelector('.tabs button.on'));
}

function renderBag(state) {
  const p = panels.get('bag');
  const bag = (state.ui && state.ui.bag) || [];
  const key = JSON.stringify([bag, pocket, state.ui && state.ui.money]);
  if (key === lastBag) return;
  lastBag = key;
  const inPocket = bag.filter((b) => (b.pocket || 'items') === pocket);
  rerender(p.body, () => [
    h('div.row', h('b', 'Money: '), h('span.price', fmtMoney(state.ui ? state.ui.money : 0))),
    h('div.tabs.pockets', { role: 'tablist' }, POCKETS.map(([k, label]) => {
      const n = bag.filter((b) => (b.pocket || 'items') === k).length;
      return h('button', { type: 'button', role: 'tab', 'aria-selected': String(pocket === k), class: pocket === k ? 'on' : '', 'data-k': 'pk-' + k,
        on: { click: () => { pocket = k; lastBag = null; renderBag(app.net.state); } } }, n ? `${label} ${n}` : label);
    })),
    h('div.list', inPocket.length ? inPocket.map((b) => h('div.item', { 'data-item': b.item },
      itemIc(),
      h('span.nm', b.name, h('small', b.desc || '')),
      h('span.act', h('span', '×' + b.qty),
        b.use ? h('button.go', { type: 'button', 'data-k': 'use' + b.item,
          on: { click: () => (b.use === 'mon' ? openParty(b) : use(b, null)) } }, 'Use') : null))) : h('p.muted', 'Nothing in this pocket.')),
  ]);
}

// ------------------------------------------------------------------ PC
let lastPc = null;

export function openPc() {
  // SE_PC_ON then the log-in beep, SE_PC_OFF on the way out (as the PC menu does)
  app.sfx.play('pcOn', app.region()).then(() => app.sfx.play('pcLogin', app.region()));
  const p = panels.open('pc', 'PC');
  p.onClose = () => app.sfx.play('pcOff', app.region());
  lastPc = null;
  renderPc(app.net.state);
  setFocus(p.body, p.body.querySelector('.item button'));
}

function renderPc(state) {
  const p = panels.get('pc');
  const pc = state.pc || { here: false, mons: [] };
  const key = JSON.stringify([state.party, pc]);
  if (key === lastPc) return;
  lastPc = key;
  const party = state.party || [];
  if (!pc.here) {
    rerender(p.body, () => [h('p.muted', 'The PC is at every POKéMON CENTER. Go to one to deposit or withdraw POKéMON.')]);
    return;
  }
  const row = (m, btn) => h('div.item', monImg(m.dex),
    h('span.nm', m.nick ? `${m.nick} (${m.name})` : m.name, h('small', `Lv${m.level} · HP ${m.hp}/${m.maxHp}`)), h('span.act', btn));
  rerender(p.body, () => [
    h('h3', `Party (${party.length}/6)`),
    h('div.list', party.map((m, i) => row(m, h('button', { type: 'button', 'data-k': 'dep' + i, disabled: party.length <= 1,
      on: { click: () => app.send({ t: 'pc.deposit', slot: i }) } }, 'Deposit')))),
    h('h3', `Boxes (${pc.mons.length})`),
    h('div.list', pc.mons.length ? pc.mons.map((m) => row(m, h('button', { type: 'button', 'data-k': 'wd' + m.slot, disabled: party.length >= 6,
      on: { click: () => app.send({ t: 'pc.withdraw', slot: m.slot }) } }, 'Withdraw'))) : h('p.muted', 'The boxes are empty.')),
  ]);
}

const RENDER = {
  party: (s) => renderParty(s),
  bag: (s) => renderBag(s),
  pc: (s) => renderPc(s),
  summary: (s) => renderSummary(s),
};

export function openMenu() {
  const p = panels.open('menu', 'Menu');
  const item = (label, k, fn) => h('button', { type: 'button', 'data-k': k, on: { click: () => { panels.close('menu'); fn(); } } }, label);
  const pcHere = app.net.state.pc && app.net.state.pc.here;
  rerender(p.body, () => [
    item('POKéMON', 'party', () => openParty()),
    item('Bag', 'bag', openBag),
    pcHere ? item('PC', 'pc', openPc) : null,
    item('Market', 'market', () => market.open()),
    item('Trade with…', 'trade', () => askName('Trade with', (n) => app.send({ t: 'trade.request', name: n }))),
    item('Duel…', 'duel', () => askName('Duel', (n) => openChallenge(n))),
    item('Chat', 'chat', () => app.openChat()),
    item('Settings', 'sound', openSettings),
    item('Help', 'help', () => app.showHelp(false)),
    item(app.showFps ? 'Hide FPS' : 'Show FPS', 'fps', () => app.toggleFps()),
    item('Log out', 'logout', () => app.logout()),
  ]);
  p.body.style.gridTemplateColumns = '1fr 1fr';
  setFocus(p.body, p.body.querySelector('button'));
}

/**
 * Settings: sound (music / effects volume, mute), text speed, battle animations, window frame,
 * name tags, keyboard controls (remap) and help. Stored in localStorage by their owners.
 */
export function openSettings() {
  const p = panels.open('settings', 'Settings');
  const s = app.settings;
  const st = s.store;
  const slider = (label, k, get, set) => {
    const pct = h('span.val', Math.round(get() * 100) + '%');
    const range = h('input', {
      type: 'range', min: 0, max: 100, step: 5, value: Math.round(get() * 100), 'aria-label': label + ' volume', 'data-k': k,
      on: {
        input: (e) => { set(e.target.value / 100); pct.textContent = e.target.value + '%'; },
        change: () => k === 'sfx' && app.sfx.play('select', app.region(), 0),
      },
    });
    return h('div.setrow', h('label', label), range, pct);
  };
  const toggle = (label, k, get, set) => {
    const b = h('button', { type: 'button', 'data-k': k, 'aria-pressed': String(get()) }, get() ? 'ON' : 'OFF');
    b.addEventListener('click', () => {
      set(!get());
      b.textContent = get() ? 'ON' : 'OFF';
      b.setAttribute('aria-pressed', String(get()));
    });
    return h('div.setrow', h('span', label), b, h('span'));
  };
  const seg = (label, k, options, get, set) => {
    const btns = options.map(([v, l]) => {
      const b = h('button', { type: 'button', 'data-k': k + '-' + v, 'aria-pressed': String(get() === v) }, l);
      b.addEventListener('click', () => {
        set(v);
        for (const x of btns) x.setAttribute('aria-pressed', String(x === b));
      });
      return b;
    });
    return h('div.setrow', h('span', { id: 'lbl-' + k }, label), h('div.seg', { role: 'group', 'aria-labelledby': 'lbl-' + k }, btns), h('span'));
  };
  const frames = frameStyles(app.region());
  const frameBtn = (d) => h('button', { type: 'button', 'data-k': 'frame' + (d < 0 ? 'prev' : 'next'), 'aria-label': d < 0 ? 'previous frame' : 'next frame',
    on: { click: () => { st.set('frame', (st.get('frame') + d + frames) % Math.max(1, frames)); frameNo.textContent = 'Type ' + (st.get('frame') + 1); } } }, d < 0 ? '◀' : '▶');
  const frameNo = h('span.val', 'Type ' + (st.get('frame') + 1));
  rerender(p.body, () => [
    h('h3', 'Sound'),
    slider('Music', 'vol', s.volume, s.setVolume),
    slider('Effects', 'sfx', s.sfxVolume, s.setSfxVolume),
    toggle('Mute all', 'mute', s.muted, s.setMuted),
    h('h3', 'Game'),
    seg('Text speed', 'ts', [['slow', 'Slow'], ['mid', 'Mid'], ['fast', 'Fast'], ['instant', 'Max']], () => st.get('textSpeed'), (v) => st.set('textSpeed', v)),
    toggle('Battle animations', 'banim', () => st.get('battleAnims'), (v) => st.set('battleAnims', v)),
    frames ? h('div.setrow', h('span', 'Window frame'), h('div.row', frameBtn(-1), h('span.frameprev', { 'aria-hidden': 'true' }), frameBtn(1)), frameNo) : null,
    toggle('Name tags', 'tags', s.tags, s.setTags),
    toggle('My name tag', 'selftag', s.selfTag, s.setSelfTag),
    h('h3', 'Controls'),
    h('div.row', h('button', { type: 'button', 'data-k': 'keys', on: { click: openKeys } }, 'Keyboard keys…'),
      h('button', { type: 'button', 'data-k': 'help', on: { click: () => { panels.close('settings'); app.showHelp(false); } } }, 'Show controls help')),
  ]);
  setFocus(p.body, p.body.querySelector('[data-k=vol]'));
}

/** Keyboard remap: click an action, press the new key (Esc cancels); two keys per action. */
function openKeys() {
  const p = panels.open('keys', 'Keyboard keys');
  const st = app.settings.store;
  let listening = null; // {action, slot, btn}
  const onKey = (e) => {
    if (!listening) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    const { action, slot } = listening;
    listening = null;
    window.removeEventListener('keydown', onKey, true);
    if (e.code !== 'Escape') {
      const map = st.keys();
      // a key does one thing: take it away from other actions
      for (const a of Object.keys(map)) map[a] = map[a].filter((c) => c !== e.code);
      const cur = [...(map[action] || [])];
      cur[slot] = e.code;
      for (const [a, codes] of Object.entries(map)) if (a !== action) st.bind(a, codes);
      st.bind(action, cur.filter(Boolean));
    }
    draw();
  };
  const draw = () => {
    const map = st.keys();
    rerender(p.body, () => [
      h('p.muted', 'Select a key slot, then press the new key. Esc cancels.'),
      h('div.keys', Object.entries(ACTIONS).map(([a, [label]]) => [
        h('span', label),
        ...[0, 1].map((slot) => h('button', {
          type: 'button', 'data-k': `k-${a}-${slot}`, 'aria-label': `${label} key ${slot + 1}: ${keyLabel((map[a] || [])[slot])}`,
          on: { click: (e) => {
            listening = { action: a, slot, btn: e.currentTarget };
            e.currentTarget.textContent = 'press a key…';
            e.currentTarget.classList.add('listening');
            window.addEventListener('keydown', onKey, true);
          } },
        }, keyLabel((map[a] || [])[slot]))),
      ])),
      h('div.row', h('button.danger', { type: 'button', 'data-k': 'reset', on: { click: () => { st.resetKeys(); draw(); } } }, 'Reset to defaults')),
    ]);
  };
  p.onClose = () => window.removeEventListener('keydown', onKey, true);
  draw();
  setFocus(p.body, p.body.querySelector('[data-k^=k-]'));
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
