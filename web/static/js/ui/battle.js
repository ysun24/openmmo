// Battle screen: GBA layout inside the game overlay (240x160 units of var(--u)).
//
// The server's battle section carries `fx`, an ordered effect stream (start, text, move, hp, miss,
// stat, faint, switch, ball, exp, level, end; see docs/web/PROTOCOL.md). New effects are queued
// and played one after another, so what the player sees follows the server's order even though
// state messages arrive coalesced. While effects play, the screen shows a presented copy of both
// monsters (`view`); once the queue drains it is reconciled with the authoritative state and the
// menu for the current phase appears. Every step is bounded by a timeout, text auto-advances, and
// A / a tap skips ahead, so input is never blocked for long.
import { h, $, hpClass, same } from '../util.js';
import { assets } from '../assets.js';
import { panels, rerender, setFocus } from './panels.js';
import { monImg } from './common.js';

let app;
let b = null; // last battle section
let menu = 'main'; // main | fight | party | bag
let dismissed = false;
let sk = null; // skeleton elements

// presentation
let lastSeq = 0;
let queue = [];
let playing = false;
let view = null; // {me, foe, meVis, foeVis}
let advance = null; // resolves the text step waiting for A
let typing = null; // {full, el, done()}
let learnConfirm = null; // {slot|null, text}
let timerSeen = { id: -1, at: 0 };
let ticker = 0;

const RESULT = {
  VICTORY: 'You won!', DEFEAT: 'You lost...', FLED: 'Got away safely!', CAUGHT: 'Gotcha!', ENDED: 'Battle over',
  WIN: 'You won!', LOSE: 'You lost...', RAN: 'Got away safely!', DRAW: 'Draw.', FORFEIT: 'Forfeited.',
};
export const TYPE_COLORS = {
  NORMAL: '#a8a878', FIRE: '#f08030', WATER: '#6890f0', GRASS: '#78c850', ELECTRIC: '#f8d030', ICE: '#98d8d8',
  FIGHTING: '#c03028', POISON: '#a040a0', GROUND: '#e0c068', FLYING: '#a890f0', PSYCHIC: '#f85888', BUG: '#a8b820',
  ROCK: '#b8a038', GHOST: '#705898', DRAGON: '#7038f8', DARK: '#705848', STEEL: '#b8b8d0',
};
// Where things sit, in GBA pixels.
const POS = {
  foe: { cx: 180, cy: 36, ball: [174, 40], from: [252, -6], peak: [214, -16] },
  me: { cx: 64, cy: 80, ball: [58, 94], from: [-14, 96], peak: [26, 44] },
};
const STEP_TIMEOUT = 6000;

export function init(a) {
  app = a;
}

export const isOpen = () => !!b && !dismissed;
export const root = () => $('#battle');
export const busy = () => playing || queue.length > 0;

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
    learnConfirm = null;
    build();
    startPresentation(nb);
  }
  const prevPhase = b && b.phase;
  const prevLearn = b && b.learn;
  b = nb;
  if (!same(prevLearn, b.learn)) learnConfirm = null;
  if (b.phase !== 'ACTION' && (menu === 'fight' || menu === 'bag')) menu = 'main';
  if (b.phase === 'FORCED_SWITCH') menu = 'party';
  else if (prevPhase === 'FORCED_SWITCH' && menu === 'party') menu = 'main';
  if (b.phase === 'ACTION' && prevPhase && prevPhase !== 'ACTION') menu = 'main';
  if (b.phase !== 'ENDED' || b.learn) dismissed = false;
  if (!fresh) enqueue(b.fx);
  if (b.timer && b.timer.id !== timerSeen.id) timerSeen = { id: b.timer.id, at: performance.now() };
  render(state);
  pump();
}

function close() {
  b = null;
  sk = null;
  queue = [];
  view = null;
  advance = null;
  typing = null;
  clearInterval(ticker);
  const r = root();
  r.classList.add('hidden');
  r.replaceChildren();
}

// ------------------------------------------------------------------ skeleton
function build() {
  const r = root();
  const info = (side) => {
    const name = h('span.nm'), lv = h('span.lv'), bar = h('i'), num = h('div.hpnum');
    const exp = side === 'me' ? h('i') : null;
    const el = h('div.info.' + side,
      h('div.row', name, lv),
      h('div.hp', h('span', 'HP'), h('span.hpbar', bar)),
      side === 'me' ? num : null,
      side === 'me' ? h('div.expbar', h('span', 'EXP'), h('span.exptrack', exp)) : null);
    return { el, name, lv, bar, num, exp };
  };
  const wrap = (side) => h('div.monwrap.' + side, h('div.monclip', h('div.ph', '?')));
  sk = {
    foeWrap: wrap('foe'), meWrap: wrap('me'),
    foe: info('foe'), me: info('me'),
    text: h('div.text'), menu: h('div.menu.gbabox'), bottom: null, sheet: h('div.sheet.gbabox.hidden'),
    result: h('div.result.gbabox.hidden'), badge: h('div.bbadge.hidden'), fxLayer: h('div.fxlayer'),
    dexFoe: null, dexMe: null,
  };
  sk.bottom = h('div.bottom', sk.text, sk.menu);
  r.replaceChildren(h('div.plat.foe'), h('div.plat.me'), sk.foeWrap, sk.meWrap, sk.fxLayer, sk.foe.el, sk.me.el,
    sk.badge, sk.bottom, sk.sheet, sk.result);
  r.classList.remove('hidden');
  r.addEventListener('pointerdown', (e) => {
    if (busy() && !e.target.closest('button')) skip();
  });
  clearInterval(ticker);
  ticker = setInterval(tickBadge, 250);
}

function monEl(side) {
  return side === 'foe' ? sk.foeWrap : sk.meWrap;
}

function setSprite(side, dex) {
  const key = side === 'foe' ? 'dexFoe' : 'dexMe';
  if (sk[key] === dex) return;
  sk[key] = dex;
  const url = dex ? assets.pokemon(dex, side === 'foe' ? 'front' : 'back') : null;
  const n = url ? h('img.sprite', { src: url, alt: '', draggable: false }) : h('div.ph', '#' + (dex ?? '?'));
  monEl(side).querySelector('.monclip').replaceChildren(n);
}

// ------------------------------------------------------------------ presentation model
const copyMon = (m) => (m ? { ...m } : null);

function startPresentation(nb) {
  const fx = nb.fx || [];
  let start = -1;
  for (let i = fx.length - 1; i >= 0; i--) if (fx[i].k === 'start') { start = i; break; }
  queue = [];
  if (start >= 0) {
    // A battle that just began: play it from its start effect.
    lastSeq = fx[start].n - 1;
    view = { me: copyMon(fx[start].me || nb.me), foe: copyMon(fx[start].foe || nb.foe), meVis: false, foeVis: false };
    enqueue(fx);
  } else {
    // Joined mid-battle (reload): show the current state, no replay.
    lastSeq = fx.length ? fx[fx.length - 1].n : 0;
    view = { me: copyMon(nb.me), foe: copyMon(nb.foe), meVis: !!nb.me && nb.me.hp > 0, foeVis: !!nb.foe && nb.foe.hp > 0 };
  }
  paintView();
}

function enqueue(fx) {
  for (const f of fx || []) {
    if (f.n <= lastSeq) continue;
    lastSeq = f.n;
    queue.push(f);
  }
}

/** Plays queued effects one at a time; when done, reconciles with the server's state. */
function pump() {
  if (playing || !queue.length || !sk) return;
  playing = true;
  render(app.net.state);
  (async () => {
    while (queue.length && sk) {
      const f = queue.shift();
      try {
        await bounded(play(f), STEP_TIMEOUT);
      } catch (e) {
        console.warn('battle fx', f, e);
      }
    }
    playing = false;
    if (!sk) return;
    syncToState();
    render(app.net.state);
  })();
}

function syncToState() {
  if (!b || !view) return;
  if (b.me) {
    view.me = copyMon(b.me);
    view.meVis = b.me.hp > 0 || view.meVis;
    if (b.me.hp <= 0) view.meVis = false;
  }
  if (b.foe) {
    view.foe = copyMon(b.foe);
    if (b.foe.hp <= 0) view.foeVis = false;
    else if (b.result !== 'CAUGHT') view.foeVis = true;
  }
  if (b.me && b.me.hp > 0 && !view.meVis) view.meVis = true;
  paintView();
}

/** Puts the presented model on screen without animating. */
function paintView() {
  if (!sk || !view) return;
  if (view.foe) setSprite('foe', view.foe.dex);
  if (view.me) setSprite('me', view.me.dex);
  setVisible('foe', view.foeVis);
  setVisible('me', view.meVis);
  if (view.foe) setInfo(sk.foe, view.foe, false);
  if (view.me) setInfo(sk.me, view.me, true);
}

/** Shows or hides a monster (and, unless [withInfo] is false, its info box). */
function setVisible(side, vis, withInfo = true) {
  const el = monEl(side);
  // Finished animations hold their last frame over inline styles, so drop them first.
  for (const a of el.getAnimations({ subtree: true })) a.cancel();
  el.style.opacity = vis ? '1' : '0';
  el.style.transform = '';
  if (withInfo) (side === 'foe' ? sk.foe : sk.me).el.classList.toggle('gone', !vis);
}

function setInfo(i, m, mine) {
  i.name.textContent = m.name;
  i.lv.textContent = 'Lv' + m.level;
  setHp(i, m.hp, m.maxHp, mine);
  if (mine && i.exp && typeof m.exp === 'number') i.exp.style.transform = `scaleX(${m.exp})`;
}

function setHp(i, hp, maxHp, mine) {
  const r = maxHp > 0 ? Math.max(0, hp) / maxHp : 0;
  i.bar.style.transform = `scaleX(${r})`;
  i.bar.className = hpClass(hp, maxHp);
  if (mine) i.num.textContent = `${Math.max(0, Math.round(hp))}/${maxHp}`;
}

// ------------------------------------------------------------------ effect players
const U = () => parseFloat(getComputedStyle($('#ov')).getPropertyValue('--u')) || 2;
const fast = () => queue.length > 10;
const dur = (ms) => (fast() ? ms * 0.35 : ms);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function bounded(p, ms) {
  return Promise.race([p, sleep(ms)]);
}
function anim(el, keyframes, opts) {
  if (!el || !el.animate) return Promise.resolve();
  const d = dur(opts.duration || 300);
  const a = el.animate(keyframes, { fill: 'forwards', easing: 'ease-out', ...opts, duration: d });
  return bounded(a.finished.catch(() => {}), d + 250).then(() => a);
}
const tr = (x, y, extra = '') => `translate(${x * U()}px, ${y * U()}px) ${extra}`;

async function play(f) {
  const side = f.side;
  switch (f.k) {
    case 'start': return playStart(f);
    case 'text': return playText(f.text);
    case 'move': return playMove(f);
    case 'hp': return playHp(f);
    case 'miss': return anim(monEl(side), [{ transform: tr(0, 0) }, { transform: tr(side === 'foe' ? 10 : -10, 0) }, { transform: tr(0, 0) }], { duration: 300 });
    case 'stat': return playStat(f);
    case 'faint': return playFaint(side);
    case 'switch': return playSwitch(f);
    case 'ball': return playBall(f);
    case 'exp': return playExp(f);
    case 'level': return playLevel(f);
    default: return undefined;
  }
}

async function playStart(f) {
  view = { me: copyMon(f.me || view.me), foe: copyMon(f.foe || view.foe), meVis: false, foeVis: false };
  paintView();
  if (f.wild) {
    // The wild monster slides in from the left as the scene opens.
    view.foeVis = true;
    setVisible('foe', true, false);
    await Promise.all([
      anim(monEl('foe'), [{ transform: tr(-200, 0), filter: 'brightness(0.3)' }, { transform: tr(0, 0), filter: 'brightness(1)' }], { duration: 800, easing: 'linear' }),
      anim(root().querySelector('.plat.foe'), [{ transform: tr(-200, 0) }, { transform: tr(0, 0) }], { duration: 800, easing: 'linear' }),
      anim(root().querySelector('.plat.me'), [{ transform: tr(200, 0) }, { transform: tr(0, 0) }], { duration: 800, easing: 'linear' }),
    ]);
    await showInfo('foe');
  } else {
    await Promise.all([
      anim(root().querySelector('.plat.foe'), [{ transform: tr(-200, 0) }, { transform: tr(0, 0) }], { duration: 600 }),
      anim(root().querySelector('.plat.me'), [{ transform: tr(200, 0) }, { transform: tr(0, 0) }], { duration: 600 }),
    ]);
  }
}

async function showInfo(side) {
  const i = side === 'foe' ? sk.foe : sk.me;
  i.el.classList.remove('gone');
  await anim(i.el, [{ transform: tr(side === 'foe' ? -120 : 120, 0) }, { transform: tr(0, 0) }], { duration: 250 });
}

/** Ball arcs onto the platform, pops, and the monster grows out of it. */
async function sendOut(side) {
  const p = POS[side];
  const ball = h('div.ball');
  place(ball, p.from[0], p.from[1]);
  sk.fxLayer.append(ball);
  await anim(ball, [
    { transform: tr(0, 0, 'rotate(0deg)') },
    { transform: tr(p.peak[0] - p.from[0], p.peak[1] - p.from[1], 'rotate(360deg)'), offset: 0.5, easing: 'ease-in' },
    { transform: tr(p.ball[0] - p.from[0], p.ball[1] - p.from[1], 'rotate(720deg)') },
  ], { duration: 520, easing: 'ease-out' });
  ball.remove();
  burst(p.ball[0] + 6, p.ball[1] + 6, '#ffffff', 6, 12);
  view[side + 'Vis'] = true;
  setVisible(side, true, false);
  await anim(monEl(side), [
    { transform: 'scale(0.1)', filter: 'brightness(3)', transformOrigin: '50% 100%' },
    { transform: 'scale(1)', filter: 'brightness(1)', transformOrigin: '50% 100%' },
  ], { duration: 300 });
  await showInfo(side);
}

function place(el, x, y) {
  el.style.left = `calc(var(--u) * ${x})`;
  el.style.top = `calc(var(--u) * ${y})`;
}

/** Small particles flying out from (x, y), cleaned up on their own. */
function burst(x, y, color, n = 8, radius = 22, size = 4) {
  for (let i = 0; i < n; i++) {
    const a = (Math.PI * 2 * i) / n + Math.random() * 0.5;
    const r = radius * (0.6 + Math.random() * 0.5);
    const d = h('div.particle');
    d.style.background = color;
    d.style.width = d.style.height = `calc(var(--u) * ${size})`;
    place(d, x - size / 2, y - size / 2);
    sk.fxLayer.append(d);
    anim(d, [{ transform: 'translate(0,0) scale(1)', opacity: 1 }, { transform: tr(Math.cos(a) * r, Math.sin(a) * r, 'scale(0.3)'), opacity: 0 }],
      { duration: 380 + Math.random() * 120 }).then(() => d.remove());
  }
}

function flash(side, color, times = 1) {
  const f = h('div.flash');
  f.style.background = color;
  monEl(side).append(f);
  const frames = [];
  for (let i = 0; i < times; i++) frames.push({ opacity: 0 }, { opacity: 0.75 });
  frames.push({ opacity: 0 });
  return anim(f, frames, { duration: 160 * times + 120, easing: 'linear' }).then(() => f.remove());
}

async function playMove(f) {
  const side = f.side || 'me';
  const target = f.target && f.target !== side ? f.target : side === 'me' ? 'foe' : 'me';
  const color = TYPE_COLORS[f.type] || '#ffffff';
  if (!view[side + 'Vis']) return;
  const dx = side === 'me' ? 14 : -14, dy = side === 'me' ? -6 : 6;
  await anim(monEl(side), [{ transform: tr(0, 0) }, { transform: tr(dx, dy) }, { transform: tr(0, 0) }], { duration: 260 });
  if (f.power > 0) {
    // Damaging move: a type-coloured burst on the target.
    const t = POS[target];
    burst(t.cx, t.cy, color, 10, 26, 5);
    burst(t.cx, t.cy, '#ffffff', 5, 12, 3);
    if (view[target + 'Vis']) await flash(target, color, 1);
  } else {
    // Status move: rings of its colour around whoever it aims at.
    const who = f.target || target;
    const ring = h('div.ring');
    ring.style.borderColor = color;
    place(ring, POS[who].cx - 16, POS[who].cy - 16);
    sk.fxLayer.append(ring);
    await anim(ring, [{ transform: 'scale(0.2)', opacity: 1 }, { transform: 'scale(1.6)', opacity: 0 }], { duration: 450 });
    ring.remove();
  }
}

async function playHp(f) {
  const side = f.side;
  if (!side || !view[side]) return;
  const m = view[side];
  const maxHp = f.maxHp || m.maxHp;
  const from = typeof f.from === 'number' ? f.from : m.hp;
  const to = f.hp;
  const info = side === 'me' ? sk.me : sk.foe;
  if (to < from && view[side + 'Vis']) {
    // Hit: the sprite blinks and shakes while the box jolts.
    await Promise.all([
      anim(monEl(side), [{ opacity: 1 }, { opacity: 0 }, { opacity: 1 }, { opacity: 0 }, { opacity: 1 }, { opacity: 0 }, { opacity: 1 }], { duration: 480, easing: 'steps(1)' }),
      anim(info.el, [{ transform: tr(0, 0) }, { transform: tr(-2, 0) }, { transform: tr(2, 0) }, { transform: tr(0, 0) }], { duration: 240 }),
    ]);
  }
  const ms = dur(Math.max(300, Math.min(1200, (Math.abs(from - to) / Math.max(1, maxHp)) * 1400)));
  const t0 = performance.now();
  await bounded(new Promise((res) => {
    const stepFn = (now) => {
      if (!sk) return res();
      const k = Math.min(1, (now - t0) / ms);
      const hp = from + (to - from) * k;
      setHp(info, side === 'me' ? Math.round(hp) : hp, maxHp, side === 'me');
      if (k < 1) requestAnimationFrame(stepFn);
      else res();
    };
    requestAnimationFrame(stepFn);
  }), ms + 400);
  m.hp = to;
  m.maxHp = maxHp;
  setHp(info, to, maxHp, side === 'me');
}

async function playStat(f) {
  const side = f.side;
  if (!side || !view[side + 'Vis']) return;
  const up = f.delta > 0;
  const o = h('div.statfx.' + (up ? 'up' : 'down'));
  monEl(side).append(o);
  await anim(o, [{ backgroundPositionY: '0px', opacity: 0.85 }, { backgroundPositionY: (up ? -1 : 1) * 64 * U() + 'px', opacity: 0.85, offset: 0.85 }, { opacity: 0 }],
    { duration: 700, easing: 'linear' });
  o.remove();
}

async function playFaint(side) {
  if (!side || !view[side + 'Vis']) return;
  const clip = monEl(side).querySelector('.monclip > *');
  await anim(clip, [{ transform: 'translateY(0)' }, { transform: tr(0, 64) }], { duration: 450, easing: 'ease-in' });
  view[side + 'Vis'] = false;
  setVisible(side, false);
  if (view[side]) view[side].hp = 0;
}

async function playSwitch(f) {
  const side = f.side;
  if (!side || !f.mon) return;
  if (view[side + 'Vis']) {
    // Called back: a red flash and the monster shrinks into its ball.
    await flash(side, '#f04040', 1);
    await anim(monEl(side), [{ transform: 'scale(1)', opacity: 1, transformOrigin: '50% 100%' }, { transform: 'scale(0.1)', opacity: 0, transformOrigin: '50% 100%' }], { duration: 260 });
    view[side + 'Vis'] = false;
    setVisible(side, false);
  }
  view[side] = { ...view[side], ...f.mon, exp: side === 'me' ? (b && b.me && b.me.name === f.mon.name ? b.me.exp : undefined) : undefined };
  setSprite(side, f.mon.dex);
  setInfo(side === 'me' ? sk.me : sk.foe, view[side], side === 'me');
  setVisible(side, false);
}

async function playBall(f) {
  const p = POS.foe;
  const ball = h('div.ball');
  const start = [16, 108], peak = [100, -6], land = [p.cx - 6, p.cy - 10];
  place(ball, start[0], start[1]);
  sk.fxLayer.append(ball);
  await anim(ball, [
    { transform: tr(0, 0, 'rotate(0deg)') },
    { transform: tr(peak[0] - start[0], peak[1] - start[1], 'rotate(540deg)'), offset: 0.55, easing: 'ease-in' },
    { transform: tr(land[0] - start[0], land[1] - start[1], 'rotate(900deg)') },
  ], { duration: 700, easing: 'ease-out' });
  // The monster turns to light and is pulled into the ball.
  burst(p.cx, p.cy, '#ffffff', 8, 18);
  await anim(monEl('foe'), [{ transform: 'scale(1)', filter: 'brightness(1)' }, { transform: 'scale(0.05)', filter: 'brightness(4)', offset: 1 }], { duration: 350 });
  view.foeVis = false;
  setVisible('foe', false, false);
  // The ball drops to the ground and bounces.
  const dropY = 44 - land[1];
  ball.getAnimations().forEach((a) => a.cancel());
  place(ball, land[0], land[1]);
  await anim(ball, [{ transform: tr(0, 0) }, { transform: tr(0, dropY), offset: 0.5, easing: 'ease-in' }, { transform: tr(0, dropY - 6), offset: 0.75 }, { transform: tr(0, dropY) }], { duration: 500 });
  place(ball, land[0], land[1] + dropY);
  ball.getAnimations().forEach((a) => a.cancel());
  const shakes = Math.max(0, Math.min(3, f.shakes ?? 3));
  for (let i = 0; i < shakes; i++) {
    await sleep(dur(350));
    await anim(ball, [{ transform: 'rotate(0)' }, { transform: 'rotate(-28deg)' }, { transform: 'rotate(22deg)' }, { transform: 'rotate(0)' }], { duration: 420, easing: 'ease-in-out' });
  }
  await sleep(dur(300));
  if (f.caught) {
    ball.classList.add('caught');
    for (let i = 0; i < 3; i++) burst(land[0] + 6 + (i - 1) * 6, land[1] + dropY, '#f8e060', 4, 14, 3);
    await sleep(dur(500));
    // The ball stays where it landed until the battle closes.
  } else {
    burst(land[0] + 6, land[1] + dropY + 6, '#ffffff', 10, 18);
    ball.remove();
    view.foeVis = true;
    setVisible('foe', true, false);
    await anim(monEl('foe'), [{ transform: 'scale(0.1)', filter: 'brightness(4)' }, { transform: 'scale(1)', filter: 'brightness(1)' }], { duration: 300 });
  }
}

async function playExp(f) {
  if (!sk.me.exp) return;
  const from = Math.max(0, Math.min(1, f.from ?? 0)), to = Math.max(0, Math.min(1, f.to ?? 0));
  await anim(sk.me.exp, [{ transform: `scaleX(${from})` }, { transform: `scaleX(${to})` }], { duration: Math.max(250, 900 * Math.abs(to - from)), easing: 'linear' });
  sk.me.exp.getAnimations().forEach((a) => a.cancel());
  sk.me.exp.style.transform = `scaleX(${to})`;
  if (view.me) view.me.exp = to;
}

async function playLevel(f) {
  if (view.me) view.me.level = f.level;
  sk.me.lv.textContent = 'Lv' + f.level;
  sk.me.el.classList.add('lvup');
  burst(POS.me.cx, POS.me.cy, '#f8e060', 10, 28, 4);
  await anim(sk.me.el, [{ boxShadow: '0 0 0 0 #f8e060' }, { boxShadow: `0 0 ${8 * U()}px ${3 * U()}px #f8e060` }, { boxShadow: '0 0 0 0 #f8e060' }], { duration: 700 });
  sk.me.el.classList.remove('lvup');
}

/** Types the line out, then waits for A / a tap or a short auto delay. */
async function playText(text) {
  if (!sk) return;
  // Send-outs ride on their announcement, as on the GBA.
  const sendSide =
    /^Go! /.test(text) && !view.meVis ? 'me'
    : / sent out /.test(text) && !view.foeVis && !/^Go! /.test(text) ? 'foe' : null;
  const el = h('span');
  const adv = h('button.adv', { type: 'button', 'data-k': 'adv', 'aria-label': 'Next', on: { click: (e) => { e.stopPropagation(); skip(); } } }, '▼');
  sk.text.replaceChildren(h('div.line', el), adv);
  sk.menu.classList.add('hidden');
  setFocus(root(), adv);
  const typed = new Promise((res) => {
    let i = 0;
    const speed = fast() ? 4 : 1;
    const tick = () => {
      if (!sk || !typing) return res();
      i = Math.min(text.length, i + speed);
      el.textContent = text.slice(0, i);
      if (i >= text.length) return res();
      typing.t = setTimeout(tick, 22);
    };
    typing = { t: 0, finish: () => { clearTimeout(typing && typing.t); el.textContent = text; res(); } };
    tick();
  });
  const out = sendSide ? sendOut(sendSide) : Promise.resolve();
  await bounded(typed, 4000);
  typing = null;
  el.textContent = text;
  await out;
  const hold = fast() ? 150 : 700 + Math.min(900, text.length * 18);
  await new Promise((res) => {
    const t = setTimeout(res, hold);
    advance = () => { clearTimeout(t); res(); };
  });
  advance = null;
}

/** A / tap during playback: finish the typing, or go to the next step. */
export function skip() {
  if (typing) typing.finish();
  else if (advance) advance();
}

// ------------------------------------------------------------------ menus
const fill = (el, ...k) => el.replaceChildren(...k.flat(Infinity).filter((x) => x !== null && x !== undefined && x !== false));

const btn = (label, k, fn, disabled = false, extra = null) =>
  h('button', { type: 'button', 'data-k': k, disabled, on: { click: fn } }, label, extra);

const typeChip = (t) => (t ? h('span.tchip', { style: `background:${TYPE_COLORS[t] || '#888'}` }, t.slice(0, 3)) : null);

function render(state) {
  if (!sk || !b) return;
  const r = root();
  r.classList.toggle('hidden', dismissed);
  renderBadge();
  if (busy()) {
    // Effects are playing: only the text box (driven by playText) is up.
    sk.menu.classList.add('hidden');
    sk.sheet.classList.add('hidden');
    sk.result.classList.add('hidden');
    sk.text.style.flex = '';
    if (!sk.text.querySelector('.adv')) {
      const adv = h('button.adv', { type: 'button', 'data-k': 'adv', on: { click: () => skip() } }, '▼');
      sk.text.replaceChildren(h('div.line', ''), adv);
      setFocus(r, adv);
    }
    return;
  }
  paintView();

  const log = b.log || [];
  const lastLines = log.slice(-2);
  const keep = r.querySelector('.nav-focus');
  const keepK = keep && keep.dataset.k !== 'adv' ? keep.dataset.k : null;
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
      const cell = btn(m.name, 'mv' + i, () => app.send({ t: 'battle.fight', move: i }), m.pp <= 0 && moves.some((x) => x.pp > 0),
        h('span.pp', `${m.pp}/${m.maxPp}`));
      if (m.type) cell.style.borderLeft = `calc(var(--u) * 2) solid ${TYPE_COLORS[m.type] || '#888'}`;
      return cell;
    });
    sk.menu.replaceChildren(...cells);
    sk.text.replaceChildren(btn('◀ BACK', 'back', () => setMenu('main')));
    sk.text.style.flex = '0 0 calc(var(--u) * 54)';
  } else {
    sk.text.style.flex = '';
    if (b.phase === 'ACTION') {
      sk.text.replaceChildren(h('div', `What will ${b.me.name} do?`));
      sk.menu.replaceChildren(
        btn('FIGHT', 'fight', () => setMenu('fight')),
        btn('BAG', 'bag', () => setMenu('bag')),
        btn('POKéMON', 'pkmn', () => setMenu('party')),
        btn(b.rules ? 'FORFEIT' : 'RUN', 'run', () => app.send({ t: 'battle.run' })),
      );
    } else {
      sk.menu.classList.add('hidden');
      const waiting = b.phase === 'WAITING' && b.timer && !b.timer.mustAct ? 'Waiting for the opponent...' : null;
      sk.text.replaceChildren(...(waiting ? [h('div', waiting)] : lastLines.map((l) => h('div', l))));
      if (b.phase === 'WAITING' && !lastLines.length) sk.text.replaceChildren(h('div', 'Waiting...'));
    }
  }

  if (menu === 'party' && (b.phase === 'ACTION' || b.phase === 'FORCED_SWITCH')) {
    const forced = b.phase === 'FORCED_SWITCH';
    const activeSlot = typeof b.activeSlot === 'number' ? b.activeSlot : -1;
    sk.sheet.classList.remove('hidden');
    fill(sk.sheet,
      h('div.title', forced ? 'Choose the next POKéMON.' : 'Switch to which POKéMON?'),
      (b.party || []).map((p, i) => {
        const active = i === activeSlot && !forced;
        return h('button', { type: 'button', 'data-k': 'p' + i, disabled: p.hp <= 0 || i === activeSlot, on: { click: () => app.send({ t: 'battle.switch', slot: i }) } },
          monImg(p.dex, 'icon', ''), h('span', `${p.name} Lv${p.level}${active ? ' (in battle)' : ''}`), h('span', `${Math.max(0, p.hp)}/${p.maxHp}`));
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
      !b.wild ? h('div.title', b.rules ? "Items can't be used against a player." : "Can't use balls in a trainer battle.") : null,
      btn('◀ BACK', 'bback', () => setMenu('main')),
    );
  }

  if (b.learn) renderLearn();
  else if (b.phase === 'ENDED') {
    sk.result.classList.remove('hidden');
    fill(sk.result, h('div', RESULT[b.result] || b.result || 'Battle over'),
      !b.wild && b.opponentName ? h('div', { style: 'font-size:calc(var(--u)*7)' }, 'vs ' + b.opponentName) : null,
      btn('Continue', 'cont', () => {
        dismissed = true;
        root().classList.add('hidden');
      }));
  }

  let n = keepK && r.querySelector(`[data-k="${keepK}"]`);
  // An open sheet or result box owns the focus.
  const modal = r.querySelector('.sheet:not(.hidden), .result:not(.hidden)');
  if (n && modal && !modal.contains(n)) n = null;
  if (!n || n.disabled || !n.offsetParent) {
    n = (modal && modal.querySelector('button:not(:disabled)')) || r.querySelector('.menu:not(.hidden) button:not(:disabled)');
  }
  r.querySelectorAll('.nav-focus').forEach((e) => e.classList.remove('nav-focus'));
  if (n) n.classList.add('nav-focus');
}

/** The learn-move menu: the four known moves plus the new one, then a confirmation. */
function renderLearn() {
  const L = b.learn;
  const offer = L.offer || { name: L.move };
  const known = L.moves && L.moves.length ? L.moves : (L.current || []).map((name) => ({ name }));
  const row = (m, k, fn, cls = '') =>
    h('button.mvrow' + cls, { type: 'button', 'data-k': k, on: { click: fn } },
      h('span.mvname', m.name), typeChip(m.type),
      h('span.mvstat', m.power ? `POW ${m.power}` : 'POW –'),
      h('span.mvstat', m.maxPp ? `PP ${m.pp ?? m.maxPp}/${m.maxPp}` : ''));
  sk.sheet.classList.remove('hidden');
  sk.sheet.classList.add('learn');
  if (learnConfirm) {
    fill(sk.sheet,
      h('div.title', learnConfirm.text),
      h('div.confirm',
        btn('YES', 'ly', () => {
          const slot = learnConfirm.slot;
          learnConfirm = null;
          app.send({ t: 'battle.learn', slot });
        }),
        btn('NO', 'ln', () => {
          learnConfirm = null;
          render(app.net.state);
        })),
    );
    return;
  }
  const who = L.mon || (b.me && b.me.name) || 'Your POKéMON';
  fill(sk.sheet,
    h('div.title', `${who} wants to learn ${offer.name}. Forget which move?`),
    h('div.newmove', h('span.tag', 'NEW'), h('span.mvname', offer.name), typeChip(offer.type),
      h('span.mvstat', offer.power ? `POW ${offer.power}` : 'POW –'), h('span.mvstat', offer.maxPp ? `PP ${offer.maxPp}` : '')),
    known.map((m, i) => row(m, 'l' + i, () => {
      learnConfirm = { slot: i, text: `Forget ${m.name} and learn ${offer.name}?` };
      render(app.net.state);
    })),
    h('button.mvrow.skip', { type: 'button', 'data-k': 'lskip', on: { click: () => {
      learnConfirm = { slot: null, text: `Stop learning ${offer.name}?` };
      render(app.net.state);
    } } }, h('span.mvname', `Don't learn ${offer.name}`)),
  );
}

function setMenu(m) {
  menu = m;
  root().querySelectorAll('.nav-focus').forEach((e) => e.classList.remove('nav-focus'));
  render(app.net.state);
  if (root().querySelector('.nav-focus')) return;
  const first = root().querySelector('.sheet:not(.hidden) button:not(:disabled), .menu:not(.hidden) button:not(:disabled)');
  if (first) first.classList.add('nav-focus');
}

/** B in battle: back out of a sub menu, or of a learn confirmation. */
export function back() {
  if (!b) return;
  if (busy()) return skip();
  if (learnConfirm) {
    learnConfirm = null;
    return render(app.net.state);
  }
  if (menu !== 'main' && b.phase !== 'FORCED_SWITCH') setMenu('main');
}

// ------------------------------------------------------------------ rules badge + turn clock
export function describeRules(r) {
  if (!r) return 'Standard rules';
  return [r.levelCap ? `Lv. cap ${r.levelCap}` : 'No level cap', `${r.party} vs ${r.party}`, r.timer ? `${r.timer}s per turn` : 'No timer'].join(' · ');
}

function renderBadge() {
  if (!sk) return;
  const show = !!b && (b.turn > 0 || b.rules);
  sk.badge.classList.toggle('hidden', !show || b.phase === 'ENDED');
  if (!show) return;
  const rules = b.rules;
  const parts = [h('span.turn', `TURN ${Math.max(1, b.turn || 1)}`)];
  if (rules) {
    if (rules.levelCap) parts.push(h('span', `Lv${rules.levelCap}`));
    parts.push(h('span', `${rules.party}v${rules.party}`));
  }
  const clock = h('span.clock');
  if (rules && rules.timer) parts.push(clock);
  if (b.timer) {
    const warn = (n) => (n ? '⚠' + n + '/' + (b.timer.maxTimeouts || 3) : null);
    const mine = warn(b.timer.timeouts), theirs = warn(b.timer.rivalTimeouts);
    if (mine) parts.push(h('span.warn', 'you ' + mine));
    if (theirs) parts.push(h('span.warn.rival', 'foe ' + theirs));
  }
  sk.badge.replaceChildren(...parts);
  sk.clock = clock;
  tickBadge();
}

function tickBadge() {
  if (!sk || !b || !sk.clock) return;
  const t = b.timer;
  if (!t || !b.rules || !b.rules.timer || b.phase === 'ENDED') {
    sk.clock.textContent = b.rules && b.rules.timer ? `⏱ ${b.rules.timer}s` : '';
    sk.clock.classList.remove('low');
    return;
  }
  const left = Math.max(0, Math.ceil(t.seconds - (performance.now() - timerSeen.at) / 1000));
  sk.clock.textContent = (t.mustAct ? '⏱ ' : '⏳ foe ') + left + 's';
  sk.clock.classList.toggle('low', t.mustAct && left <= 10);
}

// ------------------------------------------------------------------ duel challenge dialog
const RULE_CHOICES = {
  levelCap: [[0, 'Off'], [50, '50'], [100, '100']],
  party: [[1, '1'], [3, '3'], [6, '6']],
  timer: [[0, 'Off'], [30, '30s'], [60, '60s'], [90, '90s']],
};
let challengeRules = { levelCap: 0, party: 6, timer: 0 };

/** Lets the challenger pick the rules, then sends the challenge. */
export function openChallenge(name) {
  const p = panels.open('duelrules', 'Duel ' + name);
  p.title.textContent = 'Duel ' + name;
  const group = (key, label) =>
    h('div.rulegroup', h('span.rulelabel', label),
      h('div.row', RULE_CHOICES[key].map(([v, text]) =>
        h('button.grow' + (challengeRules[key] === v ? '.go' : ''), {
          type: 'button', 'data-k': key + v, 'aria-pressed': String(challengeRules[key] === v),
          on: { click: () => { challengeRules = { ...challengeRules, [key]: v }; draw(); } },
        }, text))));
  const draw = () => rerender(p.body, () => [
    group('levelCap', 'Level cap'),
    group('party', 'Party size'),
    group('timer', 'Turn timer'),
    h('p.muted', describeRules(challengeRules) + '. Monsters above the cap fight at the cap; nothing carries over after the duel.'),
    h('div.row',
      h('button.go.grow', { type: 'button', 'data-k': 'send', on: { click: () => {
        panels.close('duelrules');
        app.send({ t: 'duel', name, ...challengeRules });
      } } }, '⚔ Challenge'),
      h('button.grow', { type: 'button', 'data-k': 'cancel', on: { click: () => panels.close('duelrules') } }, 'Cancel')),
  ]);
  draw();
  setFocus(p.body, p.body.querySelector('[data-k=send]'));
}
