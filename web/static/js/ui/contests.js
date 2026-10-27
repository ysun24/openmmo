// r5-contests: Hoenn contests, the Berry Blender, the POKéBLOCK CASE, berry trees and the museum
// paintings (PROTOCOL.md "r5-contests"). The server sends one JSON document per kind in
// state.contests.docs; this module draws them as full-screen overlays and sends `contest.*`,
// `blender.*`, `pokeblocks.*`, `berryChoose.*`, `painting.*` actions. Berry trees are drawn into the
// overworld by drawBerryTree(), called from the renderer for OBJ_EVENT_GFX_BERRY_TREE objects.
import { h, $ } from '../util.js';

let app = null;
let docs = {};
let root = null;
let open = null; // the kind shown
const CAT = ['COOL', 'BEAUTY', 'CUTE', 'SMART', 'TOUGH'];
const CAT_COLOR = ['#e04040', '#3870e8', '#f068b0', '#40b048', '#e8c020'];
const RANK = ['NORMAL', 'SUPER', 'HYPER', 'MASTER'];
const CONDITIONS = ['cool', 'beauty', 'cute', 'smart', 'tough'];
const BERRY_TREE_GFX = 60;

const send = (m) => app.send(m);
const pic = (dex, shiny) => app.assets.pokemon(dex, 'front', !!shiny);

export function init(a) {
  app = a;
  if (/[?&]debug=1/.test(location.search)) window.addEventListener('error', (e) => console.log('ERR', e.error && e.error.stack));
  root = h('div#r5c-root.hidden', { role: 'dialog', 'aria-modal': 'true' });
  document.body.append(root);
  const bar = $('#topbar');
  if (bar && !$('#btn-pokeblocks')) {
    const b = h('button#btn-pokeblocks.small', { type: 'button', title: 'POKéBLOCK CASE', on: { click: () => send({ t: 'pokeblocks.open' }) } }, '◆ Blocks');
    const menu = $('#btn-menu');
    if (menu && menu.parentNode) menu.parentNode.insertBefore(b, menu);
    else bar.append(b);
  }
}

export const isOpen = () => open !== null;

let lastMapKey = null;
let lastSeq = -1;
export function update(state, changed) {
  if (changed.includes('world')) {
    const m = state.world && state.world.map;
    const key = m ? `${m.region}:${m.bank}:${m.map}` : null;
    // Berry trees of the new map (SetBerryTreesSeen)
    if (key !== lastMapKey) {
      lastMapKey = key;
      if (m && m.region === 1) send({ t: 'berry.trees' });
    }
  }
  if (!changed.includes('contests') || !state.contests) return;
  if (state.contests.seq === lastSeq) return;
  lastSeq = state.contests.seq;
  const prev = docs;
  docs = state.contests.docs || {};
  // The overlay with the highest priority that has a document.
  const order = ['painting', 'berryChoose', 'contestEntry', 'contest', 'blender', 'pokeblocks'];
  const kind = order.find((k) => docs[k] && (k !== 'pokeblocks' || docs[k].open !== false)) || null;
  if (docs.pokeblocks && docs.pokeblocks.open === false && docs.pokeblocks.message && docs.pokeblocks !== prev.pokeblocks)
    import('../util.js').then((u) => u.toast(docs.pokeblocks.message));
  if (kind !== open) {
    stopAnim();
    open = kind;
    root.classList.toggle('hidden', !kind);
    document.body.classList.toggle('r5c-open', !!kind);
  }
  if (!kind) return (root.textContent = '');
  if (docs[kind] === prev[kind] && root.childElementCount) return;
  RENDER[kind](docs[kind], prev[kind]);
}

// ------------------------------------------------------------------ input
export function onPress(btn) {
  if (!open) return false;
  if (open === 'blender' && btn === 'a' && blend.playing) return blenderPress(), true;
  const btns = [...root.querySelectorAll('button:not([disabled])')];
  if (!btns.length) return true;
  let i = btns.indexOf(document.activeElement);
  if (btn === 'a') {
    (i >= 0 ? btns[i] : btns[0]).click();
    return true;
  }
  if (btn === 'b') {
    const back = root.querySelector('[data-back]');
    if (back) back.click();
    return true;
  }
  const d = btn === 'up' || btn === 'left' ? -1 : btn === 'down' || btn === 'right' ? 1 : 0;
  if (d) {
    i = i < 0 ? 0 : (i + d + btns.length) % btns.length;
    btns[i].focus();
  }
  return true;
}

function screen(cls, ...kids) {
  root.textContent = '';
  const s = h(`div.r5c-screen.${cls}`, ...kids);
  root.append(s);
  const first = s.querySelector('button.primary:not([disabled])') || s.querySelector('button:not([disabled])');
  if (first) first.focus({ preventScroll: true });
  return s;
}

const hearts = (n, cls = '') => h(`span.r5c-hearts${cls}`, '♥'.repeat(Math.max(0, n)) || '·');
const gbaText = (t) => (t || '').split('\n').flatMap((l, i) => (i ? [h('br'), l] : [l]));
/** The party icon's first frame (the icon sheets are two 32x32 frames stacked). */
const icon = (dex) => h('span.r5c-icon', { style: `background-image:url(${app.assets.pokemon(dex, 'icon') || ''})` });

// ------------------------------------------------------------------ contest entry
let entry = { rank: 0, category: 0, slot: -1 };
function renderEntry(d) {
  const party = d.party || [];
  const elig = (m) => (m.eligible && m.eligible[entry.category] ? m.eligible[entry.category][entry.rank] : 0);
  const draw = () => {
    const pick = party[entry.slot];
    const ok = pick && elig(pick) >= 1 && elig(pick) <= 2;
    screen('r5c-entry',
      h('h2', 'POKéMON CONTEST RECEPTION'),
      h('p.r5c-hint', 'Which Rank, which CONTEST and which POKéMON would you like to enter?'),
      h('div.r5c-row', RANK.map((r, i) => h('button', { class: entry.rank === i ? 'on' : '', on: { click: () => ((entry.rank = i), draw()) } }, r))),
      h('div.r5c-row', CAT.map((c, i) => h('button', { class: entry.category === i ? 'on' : '', style: `--cat:${CAT_COLOR[i]}`, on: { click: () => ((entry.category = i), draw()) } }, c))),
      h('div.r5c-mons', party.map((m, i) => {
        const e = elig(m);
        const why = m.egg ? 'EGG' : e === 4 ? 'FAINTED' : e === 0 ? 'NOT QUALIFIED' : e === 2 ? 'WON BEFORE' : '';
        return h('button.r5c-mon', { class: entry.slot === i ? 'on' : '', on: { click: () => ((entry.slot = i), draw()) } },
          icon(m.dex),
          h('span.name', m.name), h('span.lv', 'Lv' + m.level),
          h('span.cond', CONDITIONS.map((c, k) => h('i', { style: `--cat:${CAT_COLOR[k]};--v:${m[c] / 255}`, title: `${c} ${m[c]}` }))),
          h('span.ribbon', m.rankRibbons ? '🎀'.repeat(m.rankRibbons[entry.category] || 0) : ''),
          why ? h('span.why', why) : null);
      })),
      h('div.r5c-row',
        h('button.primary', { disabled: !ok, on: { click: () => send({ t: 'contestEntry.choose', rank: entry.rank, category: entry.category, slot: entry.slot }) } }, 'ENTER'),
        h('button', { 'data-back': 1, on: { click: () => send({ t: 'contestEntry.cancel' }) } }, 'CANCEL')));
  };
  if (entry.slot < 0 || entry.slot >= party.length) entry.slot = party.findIndex((m) => !m.egg);
  draw();
}

// ------------------------------------------------------------------ the contest
let anim = null;
function stopAnim() {
  if (anim) clearTimeout(anim.timer);
  anim = null;
  stopBlender();
}

function contestFrame(d, body) {
  const order = [...(d.contestants || [])].sort((a, b) => a.turn - b.turn);
  const boxes = h('div.r5c-boxes', order.map((c) => h(`div.r5c-box#r5c-box-${c.index}`, { class: c.you ? 'you' : '' },
    icon(c.dex),
    h('div.names', h('b', c.name), h('span', '/' + c.trainer)),
    h('div.r5c-round', hearts(0)),
    h('div.total', `♥${c.hearts}`),
    h('div.stars', '★'.repeat(c.condition)),
    h('div.r5c-status', statusIcon(c.status, c.attention)),
    h('div.turn', String(c.turn + 1)))));
  const meter = h('div.r5c-applause', [0, 1, 2, 3, 4].map((i) => h('i', { class: i < d.applause ? 'on' : '' })));
  return screen('r5c-contest', { style: `--cat:${CAT_COLOR[d.category]}` },
    h('div.r5c-top', h('span.title', `${RANK[d.rank]} RANK ${CAT[d.category]} CONTEST`), h('span.appeal', `APPEAL ${Math.min(5, d.appeal + 1)}/5`), meter),
    h('div.r5c-stage', h('div.r5c-judge', h('div.face', '🧑‍⚖️'), h('div.bubble.hidden')), h('img.r5c-mon-big.hidden', { alt: '' })),
    boxes,
    body);
}

function statusIcon(s, att) {
  return { shield: '🛡', nervous: '💦', skip: '⏸', attention: '👁' }[s] || (att ? '👁' : '');
}

function renderContest(d, prev) {
  if (d.phase === 'waiting') {
    return screen('r5c-wait', h('h2', `${RANK[d.rank]} RANK ${CAT[d.category]} CONTEST`),
      h('p', 'Waiting for other TRAINERS to enter…'), h('p.r5c-big', `${d.players} / 4`),
      h('p.r5c-hint', (d.names || []).join(', ')),
      h('p.r5c-hint', 'Empty places will be filled by local TRAINERS.'));
  }
  if (d.phase === 'intro') {
    const max = Math.max(1, ...d.contestants.map((c) => c.round1));
    return screen('r5c-intro', h('h2', d.title || 'CONTEST'), h('p.r5c-hint', 'Primary judging: the POKéMON\'s condition.'),
      h('div.r5c-intro-list', d.contestants.map((c) => h('div.r5c-intro-mon', { class: c.you ? 'you' : '' },
        h('img', { src: pic(c.dex, c.shiny) || '', alt: c.name }), h('b', c.name), h('span', c.trainer),
        h('div.bar', h('i', { style: `width:${(100 * c.round1) / max}%` })), h('span.pts', String(c.round1))))));
  }
  if (d.phase === 'select') {
    const me = d.contestants[d.you];
    let chosen = null;
    const draw = () => {
      const body = h('div.r5c-bottom',
        h('div.r5c-msg', gbaText(d.prompt)),
        d.canMove ? h('div.r5c-moves', d.moves.map((m) => h('button.r5c-move', {
          class: (chosen === m.slot ? 'on ' : '') + (m.combo ? 'combo ' : '') + (m.repeat ? 'repeat' : ''),
          style: `--cat:${CAT_COLOR[m.category]}`,
          disabled: chosen !== null,
          on: { click: () => { chosen = m.slot; send({ t: 'contest.move', slot: m.slot }); draw(); } },
        }, h('b', m.name), h('span.cat', CAT[m.category]), h('span.ap', '♥'.repeat(m.appeal)), h('span.jam', '♥'.repeat(m.jam)), h('small', m.description)))) : h('p', me && me.status === 'skip' ? '' : ''),
        chosen !== null ? h('p.r5c-hint', 'Waiting for the other TRAINERS…') : null);
      contestFrame(d, body);
    };
    return draw();
  }
  if (d.phase === 'appeals') return playAppeals(d);
  if (d.phase === 'results') return renderResults(d);
}

/** Plays the round's events: hearts fill, the judge reacts, texts print. */
function playAppeals(d) {
  const bottom = h('div.r5c-bottom', h('div.r5c-msg'), h('button.r5c-skip', { on: { click: () => finishAnim() } }, 'SKIP ▶'));
  contestFrame(d, bottom);
  const msg = root.querySelector('.r5c-msg');
  const big = root.querySelector('.r5c-mon-big');
  const bubble = root.querySelector('.r5c-judge .bubble');
  const round = {};
  const total = {};
  for (const c of d.contestants) { round[c.index] = 0; total[c.index] = c.hearts; }
  // the totals the boxes show are after the round; show them from before
  for (const e of d.events) if (e.k === 'hearts') total[e.who] -= e.b;
  const setBox = (who) => {
    const b = root.querySelector(`#r5c-box-${who}`);
    if (!b) return;
    b.querySelector('.r5c-round').replaceChildren(heartsSigned(Math.trunc(round[who] / 10)));
    b.querySelector('.total').textContent = `♥${total[who]}`;
  };
  for (const c of d.contestants) setBox(c.index);
  const events = d.events.slice();
  let i = 0;
  const step = () => {
    if (!anim) return;
    if (i >= events.length) return finishAnim();
    const e = events[i++];
    let wait = 120;
    const box = e.who >= 0 ? root.querySelector(`#r5c-box-${e.who}`) : null;
    switch (e.k) {
      case 'enter': {
        const c = d.contestants[e.who];
        big.src = pic(c.dex, c.shiny) || '';
        big.classList.remove('hidden', 'out');
        big.classList.add('in');
        root.querySelectorAll('.r5c-box').forEach((b) => b.classList.remove('active'));
        if (box) box.classList.add('active');
        wait = 450;
        break;
      }
      case 'leave': big.classList.add('out'); wait = 300; break;
      case 'appeal': case 'text': case 'skip': case 'round':
        if (e.text) { msg.replaceChildren(...gbaText(e.text)); wait = 1100; }
        if (e.k === 'appeal' && box) box.classList.add('active');
        if (e.k === 'appeal' && e.move) appealAnim(e.moveType);
        break;
      case 'hearts':
        round[e.who] += e.b;
        total[e.who] += e.b;
        setBox(e.who);
        if (box) { box.classList.remove('flash-up', 'flash-down'); void box.offsetWidth; box.classList.add(e.b >= 0 ? 'flash-up' : 'flash-down'); }
        wait = 500;
        break;
      case 'judge':
        bubble.textContent = ['🌀', '', '❗', '‼', '', '①', '④', '❓', '⭐'][e.a] || '❗';
        bubble.classList.remove('hidden');
        setTimeout(() => bubble.classList.add('hidden'), 900);
        wait = 700;
        break;
      case 'stars': if (box) box.querySelector('.stars').textContent = '★'.repeat(e.a); wait = 250; break;
      case 'status': if (box) box.querySelector('.r5c-status').textContent = statusIcon(e.text); break;
      case 'crowd': {
        const m = root.querySelectorAll('.r5c-applause i');
        m.forEach((x, k) => x.classList.toggle('on', k < e.a));
        if (e.text) { msg.replaceChildren(...gbaText(e.text)); wait = 1000; }
        root.querySelector('.r5c-applause').classList.toggle('cheer', e.b > 0);
        break;
      }
      case 'order': wait = 300; break;
      case 'end': wait = 400; break;
    }
    anim.timer = setTimeout(step, wait);
  };
  anim = { timer: setTimeout(step, 300) };
}

/**
 * The appeal's move on the stage: the battle animation sprite of the move's type (the exported
 * graphics/battle_anims sheets, manifest.battle.anims), bursting from the monster towards the
 * judge, frames stepping like the battle screen's.
 */
function appealAnim(type) {
  const m = app.assets.manifest && app.assets.manifest.battle && app.assets.manifest.battle.anims;
  const s = m && (m[type] || m.NORMAL);
  const stage = root.querySelector('.r5c-stage');
  if (!s || !stage) return;
  const el = h('div.r5c-anim');
  el.style.backgroundImage = `url("${app.assets.url(s.file)}")`;
  el.style.backgroundSize = `100% ${Math.max(1, s.frames) * 100}%`;
  el.style.setProperty('--sz', String(s.frame));
  stage.append(el);
  const n = Math.max(1, s.frames);
  let frame = 0;
  const iv = setInterval(() => {
    frame = (frame + 1) % n;
    el.style.backgroundPositionY = n > 1 ? `${(frame / (n - 1)) * 100}%` : '0%';
  }, 70);
  const done = () => { clearInterval(iv); el.remove(); };
  if (!el.animate) return setTimeout(done, 900);
  el.animate([
    { transform: 'translate(0, 0) scale(0.6)', opacity: 0 },
    { transform: 'translate(-30%, -10%) scale(1.3)', opacity: 1, offset: 0.4 },
    { transform: 'translate(-80%, -30%) scale(1)', opacity: 0 },
  ], { duration: 900, easing: 'ease-out' }).finished.then(done, done);
}

function heartsSigned(n) {
  if (n >= 0) return hearts(n);
  return h('span.r5c-hearts.neg', '♥'.repeat(-n));
}

function finishAnim() {
  if (anim) clearTimeout(anim.timer);
  anim = null;
  send({ t: 'contest.ready' });
  const s = root.querySelector('.r5c-skip');
  if (s) { s.disabled = true; s.textContent = 'Waiting…'; }
}

function renderResults(d) {
  const rows = [...d.results].sort((a, b) => a.place - b.place);
  const max = Math.max(1, ...rows.map((r) => r.total));
  screen('r5c-results', h('h2', 'RESULTS'),
    h('p.r5c-big', d.text),
    h('div.r5c-res', rows.map((r) => {
      const c = d.contestants[r.index];
      return h('div.r5c-res-row', { class: (c.you ? 'you ' : '') + (r.place === 0 ? 'win' : '') },
        h('span.place', ['1st', '2nd', '3rd', '4th'][r.place]),
        h('img', { src: pic(c.dex, c.shiny) || '', alt: '' }),
        h('div.who', h('b', c.name), h('span', c.trainer)),
        h('div.bars', h('i.r1', { style: `width:${(100 * r.round1) / max}%` }), h('i.r2', { style: `width:${(100 * Math.max(0, r.round2)) / max}%` })),
        h('span.pts', `${r.round1} + ${r.round2} = ${r.total}`));
    })),
    h('button.primary', { on: { click: (ev) => { ev.target.disabled = true; send({ t: 'contest.ready' }); } } }, 'OK'));
}

// ------------------------------------------------------------------ blender
const blend = { playing: false, raf: 0, doc: null, at: 0, hits: [], seats: [] };
function stopBlender() {
  if (blend.raf) cancelAnimationFrame(blend.raf);
  blend.raf = 0;
  blend.playing = false;
}

function renderBlender(d) {
  if (d.phase === 'message' || d.phase === 'record') {
    stopBlender();
    return screen('r5c-blender', h('h2', 'BERRY BLENDER'), h('p.r5c-big', gbaText(d.text)),
      h('button.primary', { 'data-back': 1, on: { click: () => send({ t: 'blender.close' }) } }, 'OK'));
  }
  if (d.phase === 'choose') {
    stopBlender();
    return screen('r5c-blender', h('h2', 'BERRY BLENDER'), h('p.r5c-hint', 'Choose a BERRY to put in the BERRY BLENDER.'),
      h('div.r5c-list', d.berries.map((b) => h('button.r5c-berry', { on: { click: () => send({ t: 'blender.berry', berry: b.berry }) } },
        h('b', b.name + ' BERRY'), h('span', '×' + b.qty)))),
      h('button', { 'data-back': 1, on: { click: () => send({ t: 'blender.cancel' }) } }, 'CANCEL'));
  }
  if (d.phase === 'wait') {
    return screen('r5c-blender', h('h2', 'BERRY BLENDER'), h('p', 'Communication standby… Waiting for other TRAINERS.'),
      h('p.r5c-big', (d.players || []).join(', ')),
      h('button.primary', { on: { click: () => send({ t: 'blender.start' }) } }, 'START NOW'));
  }
  if (d.phase === 'start' || d.phase === 'play') {
    if (d.seats) blend.seats = d.seats;
    blend.you = d.you;
    if (!blend.canvas || !root.contains(blend.canvas)) {
      blend.canvas = h('canvas.r5c-blender-canvas', { width: 240, height: 160 });
      blend.canvas.addEventListener('pointerdown', (e) => { e.preventDefault(); blenderPress(); });
      screen('r5c-blender.r5c-blend-play', blend.canvas,
        h('div.r5c-blend-info', h('span.rpm', '0.00 RPM'), h('div.r5c-progress', h('i'))),
        h('p.r5c-hint', 'Press A (or tap) when the arrow is on your mark!'));
    }
    if (d.phase === 'play') {
      blend.doc = d;
      blend.at = performance.now();
      for (const x of d.hits || []) blend.hits.push({ ...x, t: performance.now() });
      root.querySelector('.rpm').textContent = `${(d.rpm / 100).toFixed(2)} RPM`;
      root.querySelector('.r5c-progress i').style.width = `${d.progress / 10}%`;
    } else blend.doc = null;
    blend.playing = d.phase === 'play';
    if (!blend.raf) blend.raf = requestAnimationFrame(drawBlender);
    return;
  }
  if (d.phase === 'result') {
    stopBlender();
    const b = d.block;
    return screen('r5c-blender', h('h2', 'RESULTS'),
      h('div.r5c-res', d.ranking.map((r, i) => h('div.r5c-res-row', { class: r.seat === d.you ? 'you' : '' },
        h('span.place', `${i + 1}.`), h('b', r.name), h('span', `BEST ${r.best}  GOOD ${r.good}  MISS ${r.miss}`)))),
      h('p', `MAXIMUM SPEED  ${(d.maxRpm / 100).toFixed(2)} RPM${d.newRecord ? '  — NEW RECORD!' : ''}`),
      h('div.r5c-block', h('i.swatch', { style: `background:${BLOCK_COLORS[b.color]}` }), h('b', b.name),
        h('span', `LV ${b.level}  FEEL ${b.feel}`), h('span.fl', `SPICY ${b.spicy} DRY ${b.dry} SWEET ${b.sweet} BITTER ${b.bitter} SOUR ${b.sour}`)),
      h('p', d.received ? 'The POKéBLOCK was put in the POKéBLOCK CASE.' : 'Your POKéBLOCK CASE is full.'),
      h('p.r5c-hint', 'Would you like to blend another BERRY?'),
      h('div.r5c-row', h('button.primary', { on: { click: () => send({ t: 'blender.again' }) } }, 'YES'),
        h('button', { 'data-back': 1, on: { click: () => send({ t: 'blender.close' }) } }, 'NO')));
  }
}

/** The arrow position now, extrapolated from the last server frame (60 frames per second). */
function arrowNow() {
  const d = blend.doc;
  if (!d) return 0;
  const frames = ((performance.now() - blend.at) * 60) / 1000;
  return Math.floor(d.arrow + d.speed * Math.min(frames, 30)) & 0xffff;
}

function blenderPress() {
  if (!blend.playing) return;
  send({ t: 'blender.press', pos: arrowNow() });
}

function drawBlender() {
  blend.raf = 0;
  const c = blend.canvas;
  if (!c || !root.contains(c)) return;
  const g = c.getContext('2d');
  g.fillStyle = '#2c3a5c';
  g.fillRect(0, 0, 240, 160);
  const cx = 120, cy = 80, R = 62;
  // the blender bowl
  g.fillStyle = '#d8d8e0';
  g.beginPath(); g.arc(cx, cy, R + 6, 0, Math.PI * 2); g.fill();
  g.fillStyle = '#f4f0e8';
  g.beginPath(); g.arc(cx, cy, R, 0, Math.PI * 2); g.fill();
  // arrow position units: pos 0 is the top, increasing counter-clockwise; a mark spans p in
  // [start, start + 48) with p = pos / 256 + 24, its centre (Best) at pos = start * 256.
  const ang = (pos) => -Math.PI / 2 - (pos / 65536) * Math.PI * 2;
  const corners = [[48, 20], [192, 20], [48, 140], [192, 140]];
  blend.seats.forEach((s, seat) => {
    const start = s.hitStart;
    const a0 = ang((start - 24) * 256), a1 = ang((start + 24) * 256);
    g.strokeStyle = seat === blend.you ? '#f8c030' : '#78a0f0';
    g.lineWidth = 9;
    g.beginPath(); g.arc(cx, cy, R - 6, a1, a0); g.stroke();
    const b0 = ang((start - 4) * 256), b1 = ang((start + 4) * 256);
    g.strokeStyle = '#e04848';
    g.beginPath(); g.arc(cx, cy, R - 6, b1, b0); g.stroke();
    const [x, y] = corners[s.arrow];
    g.fillStyle = seat === blend.you ? '#f8c030' : '#ffffff';
    g.font = '10px sans-serif';
    g.textAlign = 'center';
    g.fillText(`${s.name}`, x, y);
    g.fillStyle = '#c0c8e0';
    g.fillText(`${s.berryName}`, x, y + 11);
  });
  // the arrow
  const pos = arrowNow();
  const a = ang(pos);
  g.strokeStyle = '#303030';
  g.lineWidth = 4;
  g.beginPath(); g.moveTo(cx, cy); g.lineTo(cx + Math.cos(a) * (R - 2), cy + Math.sin(a) * (R - 2)); g.stroke();
  g.fillStyle = '#505868';
  g.beginPath(); g.arc(cx, cy, 10, 0, Math.PI * 2); g.fill();
  // hit symbols
  const now = performance.now();
  blend.hits = blend.hits.filter((x) => now - x.t < 700);
  for (const x of blend.hits) {
    const s = blend.seats[x.seat];
    if (!s) continue;
    const [px, py] = corners[s.arrow];
    g.fillStyle = x.score === 'best' ? '#ff5050' : x.score === 'good' ? '#40c040' : '#808080';
    g.font = 'bold 12px sans-serif';
    g.fillText(x.score === 'best' ? '★BEST' : x.score === 'good' ? '○GOOD' : '×MISS', px, py + (s.arrow < 2 ? 36 : -14) - (now - x.t) / 40);
  }
  blend.raf = requestAnimationFrame(drawBlender);
}

// sPokeblockColors-like swatches for the case (PBLOCK_CLR_*)
const BLOCK_COLORS = ['#000', '#e83838', '#3868e8', '#f080c0', '#38b048', '#f0d030', '#a048d0', '#3838a0', '#a06838', '#70c8f0', '#90a038', '#a0a0a0', '#303030', '#f8f8f8', '#e8b820'];

// ------------------------------------------------------------------ pokeblock case
let caseSel = -1;
function renderPokeblocks(d) {
  const blocks = d.blocks || [];
  if (!blocks.some((b) => b.slot === caseSel)) caseSel = blocks.length ? blocks[0].slot : -1;
  const sel = blocks.find((b) => b.slot === caseSel);
  screen('r5c-case', h('h2', 'POKéBLOCK CASE'),
    d.message ? h('p.r5c-msgline', gbaText(d.message)) : null,
    h('div.r5c-case-cols',
      h('div.r5c-list', blocks.length ? blocks.map((b) => h('button.r5c-blockrow', { class: b.slot === caseSel ? 'on' : '', on: { click: () => ((caseSel = b.slot), renderPokeblocks(d)) } },
        h('i.swatch', { style: `background:${BLOCK_COLORS[b.color]}` }), h('b', b.name), h('span', `LV${b.level}`))) : h('p.r5c-hint', 'The case is empty.')),
      h('div.r5c-detail', sel ? [
        h('div.r5c-flavors', ['SPICY', 'DRY', 'SWEET', 'BITTER', 'SOUR'].map((f, i) => h('div', h('span', f), h('i', { style: `--cat:${CAT_COLOR[i]};width:${Math.min(100, ([sel.spicy, sel.dry, sel.sweet, sel.bitter, sel.sour][i] * 100) / 64)}%` }), h('b', String([sel.spicy, sel.dry, sel.sweet, sel.bitter, sel.sour][i]))))),
        h('p', `FEEL ${sel.feel}`),
        h('p.r5c-hint', 'Feed it to which POKéMON?'),
        h('div.r5c-party', (d.party || []).map((m) => h('button.r5c-mon', { disabled: m.egg || m.sheen >= 255, on: { click: () => send({ t: 'pokeblocks.feed', slot: sel.slot, mon: m.id }) } },
          icon(m.dex), h('span.name', m.name),
          h('span.cond', CONDITIONS.map((c, k) => h('i', { style: `--cat:${CAT_COLOR[k]};--v:${m[c] / 255}`, title: `${c} ${m[c]}` }))),
          h('span.sheen', `✦${m.sheen}`)))),
        h('button', { on: { click: () => send({ t: 'pokeblocks.toss', slot: sel.slot }) } }, 'TOSS'),
        h('button', { on: { click: () => send({ t: 'pokeblocks.give', slot: sel.slot }) } }, 'GIVE'),
      ] : null)),
    h('button', { 'data-back': 1, on: { click: () => send({ t: 'pokeblocks.close' }) } }, 'CLOSE'));
}

// ------------------------------------------------------------------ berry choice (Bag_ChooseBerry)
function renderBerryChoose(d) {
  screen('r5c-berries', h('h2', 'BERRIES POCKET'), h('p.r5c-hint', 'Choose the BERRY to plant.'),
    h('div.r5c-list', d.berries.map((b) => h('button.r5c-berry', { on: { click: () => send({ t: 'berryChoose.pick', item: b.item }) } },
      h('b', b.name + ' BERRY'), h('span', '×' + b.qty), h('small', b.description)))),
    h('button', { 'data-back': 1, on: { click: () => send({ t: 'berryChoose.cancel' }) } }, 'CANCEL'));
}

// ------------------------------------------------------------------ painting
// contest_painting.c draws the winner with a per-category image effect; CSS filters stand in.
const PAINT_FILTER = ['saturate(1.6) contrast(1.25)', 'blur(0.4px) brightness(1.08) saturate(1.2)', 'saturate(1.3) hue-rotate(-12deg) brightness(1.1)', 'grayscale(0.4) sepia(0.3) contrast(1.3)', 'contrast(1.6) saturate(0.8)'];
const FRAME = ['#c03030', '#3060c0', '#e070a8', '#309040', '#d0a020'];
function renderPainting(d) {
  const src = pic(d.dex, d.shiny) || '';
  const close = () => send({ t: 'painting.close' });
  const cat = d.category || 0;
  screen('r5c-painting',
    h('div.r5c-frame', { style: `--frame:${d.fossil ? '#806040' : FRAME[cat]}`, on: { click: close } },
      h('div.r5c-canvas', h('img', { src, alt: d.nickname || '', style: `filter:${d.fossil ? 'sepia(0.8) contrast(1.2)' : PAINT_FILTER[cat]}` }))),
    d.caption ? h('p.r5c-caption', gbaText(d.caption)) : null,
    d.fossil ? null : h('button.primary', { 'data-back': 1, on: { click: close } }, 'OK'));
}

const RENDER = { contestEntry: renderEntry, contest: renderContest, blender: renderBlender, pokeblocks: renderPokeblocks, berryChoose: renderBerryChoose, painting: renderPainting };

// ------------------------------------------------------------------ berry trees in the world
/**
 * Draws a berry tree object (OBJ_EVENT_GFX_BERRY_TREE) at its screen position by its growth stage
 * (sAnim_BerryTreeStage0-4). Returns true when the entity was a tree (drawn or bare soil).
 */
export function drawBerryTree(ctx, e, bx, by, now, region) {
  if (region !== 1 || e.kind !== 'npc' || e.gfx !== BERRY_TREE_GFX) return false;
  const d = docs.berryTrees;
  const m = app.world.map;
  if (!d || !m || d.bank !== m.bank || d.mapId !== m.map) return true;
  const t = d.trees.find((x) => x.x === e.x && x.y === e.y);
  if (!t || !t.stage || !t.berry) return true;
  const man = app.assets.manifest && app.assets.manifest.contest;
  if (!man) return true;
  const img = app.assets.image(man.berryTrees[String(t.berry)]);
  if (!img || !img.ready) return true;
  const frames = man.stageAnims[String(Math.min(5, t.stage))] || [[0, 32]];
  const total = frames.reduce((s, f) => s + f[1], 0);
  let tick = Math.floor((now * 59.7275) / 1000) % total;
  let frame = frames[0][0];
  for (const [f, dur] of frames) { if (tick < dur) { frame = f; break; } tick -= dur; }
  ctx.drawImage(img.img, frame * 16, 0, 16, 32, bx, by - 16, 16, 32);
  return true;
}
