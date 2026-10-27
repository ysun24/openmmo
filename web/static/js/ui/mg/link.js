// The wireless minigames (topics "lobby", "berrycrush", "dodrio", "pokejump"): a lobby to host or
// join a group (NPC players fill the empty slots), then Berry Crush, Dodrio Berry Picking and
// Pokémon Jump drawn from the server's 60 fps state.
import { h } from '../../util.js';
import { panels, setFocus, rerender } from '../panels.js';
import { monImg } from '../common.js';

export const TOPICS = ['lobby', 'berrycrush', 'dodrio', 'pokejump'];
let ctx = null;
let lobby = null;
let berry = null;

export function init(c) {
  ctx = c;
}

export function openLobbyList() {
  ctx.send('lobby', 'open');
  renderLobby();
}

export function onTopic(k, now) {
  if (k === 'lobby') {
    lobby = now;
    if (!now) return panels.close('mglobby');
    return renderLobby();
  }
  if (!now) return panels.close('mg' + k);
  if (k === 'berrycrush') return renderCrush(now);
  if (k === 'dodrio') return renderDodrio(now);
  if (k === 'pokejump') return renderJump(now);
}

function renderLobby() {
  const p = panels.open('mglobby', 'WIRELESS MINIGAMES', { onBack: () => (ctx.send('lobby', 'close'), panels.close('mglobby')) });
  const L = lobby || { groups: [], games: [], berries: [] };
  const B = (label, k, fn, dis) => h('button', { type: 'button', 'data-k': k, disabled: !!dis, on: { click: fn } }, label);
  const mine = L.mine;
  rerender(p.body, () => [
    mine
      ? [
          h('h3', `${mine.title} — ${mine.players.length}/${mine.max}`),
          h('ul.mg-players', mine.players.map((n) => h('li', '• ' + n))),
          mine.players.length < mine.min ? h('p.muted', `NPC players will fill up to ${mine.min}.`) : null,
          mine.game === 'berrycrush'
            ? h('div.mg-row', h('b', 'BERRY:'),
                (L.berries || []).length
                  ? h('select', { 'data-k': 'mg-berry', on: { change: (e) => ((berry = +e.target.value), ctx.send('lobby', 'pick', { value: berry })) } },
                      h('option', { value: '' }, '—'), L.berries.map((b) => h('option', { value: b.berry, selected: b.berry === berry }, `${b.name} ×${b.qty}`)))
                  : h('span.muted', 'You have no BERRIES.'))
            : null,
          mine.results ? results(mine.results) : null,
          mine.leader ? B(mine.running ? 'PLAYING…' : 'START', 'mg-start', () => ctx.send('lobby', 'start'), mine.running)
            : B('READY — WAITING FOR THE LEADER', 'mg-ready', () => {}),
          B('LEAVE GROUP', 'mg-leave', () => ctx.send('lobby', 'leave')),
        ]
      : [
          h('p.muted', 'BECOME LEADER of a group, or JOIN one:'),
          h('div.mg-row', (L.games || []).map((g) => B('HOST ' + g.title, 'mg-host-' + g.id, () => ctx.send('lobby', 'host', { game: g.id })))),
          L.groups.length ? h('ul.mg-lobby', L.groups.map((g) =>
            h('li', B(`JOIN ${g.title} (${g.leader}, ${g.players.length}/${g.max})`, 'mg-join-' + g.id, () => ctx.send('lobby', 'join', { id: g.id }))))) : h('p.muted', 'No groups are open right now.'),
        ],
  ]);
  setFocus(p.body, p.body.querySelector('button:not(:disabled)'));
}

function results(r) {
  if (r.game === 'berrycrush') {
    return h('div.mg-card', h('h3', 'BERRY CRUSH RESULTS'),
      h('p', `Time ${(r.time / 60).toFixed(2)} s · ${r.speed.toFixed(2)} presses/s · silkiness ${r.silkiness}% · ${r.powder} BERRY POWDER`),
      h('table.mg-results', h('tr', h('th', 'NAME'), h('th', 'PRESSES'), h('th', 'SYNC'), h('th', 'NEAT')),
        r.rows.map((x) => h('tr', h('td', x.name), h('td', String(x.presses)), h('td', String(x.sync)), h('td', String(x.neat))))));
  }
  if (r.game === 'dodrio') {
    return h('div.mg-card', h('h3', 'DODRIO BERRY PICKING'), r.prize ? h('p', `Everyone won a ${r.prize}!`) : h('p.muted', 'No prize (best score under 3,000).'),
      h('table.mg-results', h('tr', h('th', 'NAME'), h('th', 'SCORE'), h('th', '🔵'), h('th', '🟢'), h('th', '🟡'), h('th', 'MISS')),
        r.rows.map((x) => h('tr', h('td', x.name), h('td', String(x.score)), h('td', String(x.blue)), h('td', String(x.green)), h('td', String(x.gold)), h('td', String(x.missed))))));
  }
  return h('div.mg-card', h('h3', 'POKéMON JUMP'),
    h('p', `${r.jumps} jumps in a row · ${r.score} points · best EXCELLENT streak ${r.excellents}`),
    r.tripped ? h('p.muted', `${r.tripped} got caught by the vine.`) : null,
    r.prize ? h('p', `Prize: ${r.prize} ×${r.qty}`) : h('p.muted', 'No prize (5,000 points needed).'));
}

function gameWindow(name, title, s, body, onKey) {
  const p = panels.open('mg' + name, title, { onBack: () => panels.close('mg' + name) });
  p.onKey = onKey;
  rerender(p.body, () => [
    s.countdown ? h('p.mg-big', { style: 'text-align:center;font-size:2em' }, String(s.countdown)) : null,
    s.results ? null : body,
    s.results ? [results(s.results), h('button', { type: 'button', 'data-k': 'mg-done', on: { click: () => (panels.close('mg' + name), ctx.send('lobby', 'open')) } }, 'OK')] : null,
  ]);
  if (s.results) setFocus(p.body, p.body.querySelector('[data-k="mg-done"]'));
  else setFocus(p.body, p.body.querySelector('.mg-big') || p.body.querySelector('button'));
  return p;
}

function renderCrush(s) {
  const press = () => ctx.send('berrycrush', 'press');
  gameWindow('berrycrush', 'BERRY CRUSH', s, h('div.mg-crush',
    h('div.mg-row', s.players.map((pl, i) => h('span', monImg(pl.dex), ` ${pl.name}: ${s.presses ? s.presses[i] : 0}`))),
    h('div.mg-bowl', h('div.mg-fill', { style: `height:${((s.depth || 0) / 32) * 100}%` })),
    h('p.muted', `${s.berries.join(', ')} · ${s.total || 0}/${s.target} presses`),
    !s.results ? h('button.mg-big', { type: 'button', 'data-k': 'mg-crush', on: { pointerdown: (e) => (e.preventDefault(), press()) } }, 'A: CRUSH!') : null),
    (btn) => (btn === 'a' && !s.results ? (press(), true) : false));
}

const DIRS = { left: 'left', up: 'up', right: 'right' };
function renderDodrio(s) {
  const pick = (dir) => ctx.send('dodrio', 'pick', { dir });
  const cols = s.columns;
  const field = h('div.mg-field');
  for (let c = 0; c < cols; c++) field.append(h('div.mg-col', { style: `left:${(c / cols) * 100}%` }));
  (s.berries || []).forEach((b, c) => {
    if (!b) return;
    const cls = b.t === 2 ? '.gold' : '';
    field.append(h('div.mg-berry' + cls, { style: `left:${((c + 0.5) / cols) * 100}%;top:${8 + b.d * 7}%;background:${['#4070e0', '#40b050', '#f0c020'][b.t]}` }));
  });
  s.players.forEach((pl, i) => {
    const pk = s.picks ? s.picks[i] : -1;
    field.append(h('div.mg-mon', { style: `left:${((i * 2 + 1.5) / cols) * 100}%` }, monImg(85),
      h('div', (pk === 0 ? '↖' : pk === 1 ? '↑' : pk === 2 ? '↗' : '·') + ' ' + pl.name), h('div', String(s.scores ? s.scores[i] : 0))));
  });
  gameWindow('dodrio', 'DODRIO BERRY PICKING', s, [
    h('p', 'Misses: ' + '■'.repeat(s.gray || 0) + '□'.repeat(10 - (s.gray || 0))),
    field,
    !s.results ? h('div.mg-row', ['left', 'up', 'right'].map((d) => h('button', { type: 'button', 'data-k': 'mg-d-' + d, on: { pointerdown: (e) => (e.preventDefault(), pick(d)) } }, d === 'left' ? '← LEFT' : d === 'up' ? '↑ MIDDLE' : 'RIGHT →'))) : null,
  ], (btn) => (!s.results && DIRS[btn] ? (pick(DIRS[btn]), true) : false));
}

function renderJump(s) {
  const jump = () => ctx.send('pokejump', 'jump');
  const field = h('div.mg-field');
  // The vine swings round: drawn as a rope whose height follows the swing (0.5 = at the feet).
  const ph = s.phase || 0;
  const y = 50 + 40 * Math.cos(ph * 2 * Math.PI);
  field.append(h('div.mg-rope', { style: `top:${y}%` }));
  s.players.forEach((pl, i) => {
    const air = s.air && s.air[i];
    field.append(h('div.mg-mon', { style: `left:${((i + 0.5) / s.players.length) * 100}%;bottom:${air ? 40 : 12}%` }, monImg(pl.dex), h('div', pl.name)));
  });
  gameWindow('pokejump', 'POKéMON JUMP', s, [
    h('div.mg-row', h('b', 'JUMPS'), h('span.mg-num', String(s.jumps || 0)), h('b', 'POINTS'), h('span.mg-num', String(s.score || 0)),
      s.excellents ? h('b.mg-rt', 'EXCELLENT ×' + s.excellents) : null),
    field,
    !s.results ? h('button.mg-big', { type: 'button', 'data-k': 'mg-jump', on: { pointerdown: (e) => (e.preventDefault(), jump()) } }, 'A: JUMP!') : null,
  ], (btn) => (btn === 'a' && !s.results ? (jump(), true) : false));
}
