// Battle screen: GBA layout inside the game overlay (240x160 units of var(--u)).
//
// The server's battle section carries `fx`, an ordered effect stream (start, text, move, hp, miss,
// stat, faint, switch, ball, exp, level, end, and from r3-battle status, ability, item, weather,
// volatile, revive; see docs/web/PROTOCOL.md). New effects are queued and played one after
// another, so what the player sees follows the server's order even though state messages arrive
// coalesced. While effects play, the screen shows a presented copy of every monster on the field
// (`view`, keyed me / me1 / foe / foe1 for the battle positions); once the queue drains it is
// reconciled with the authoritative state and the menu for the current phase appears. Every step
// is bounded by a timeout, text auto-advances, and A / a tap skips ahead, so input is never
// blocked for long.
//
// r3-battle additions: double battles (two positions per side, a target picker for single-target
// moves), status icons, the in-battle BAG with every usable item and its target, terrain
// backgrounds, trainer / player sprites in the intro, move animation sprites, ability and item
// banners, weather, and the evolution scene (which can also play outside a battle).
import { h, $, hpClass, same } from '../util.js';
import { assets } from '../assets.js';
import { panels, rerender, setFocus } from './panels.js';
import { monImg } from './common.js';
import { settings } from '../settings.js';

let app;
let b = null; // last battle section
let menu = 'main'; // main | fight | party | bag | target | bagparty | bagmove
let dismissed = false;
let sk = null; // skeleton elements

// presentation
let lastSeq = 0;
let queue = [];
let playing = false;
let view = null; // {me, me1, foe, foe1, meVis, me1Vis, foeVis, foe1Vis}
let advance = null; // resolves the text step waiting for A
let typing = null; // {full, el, done()}
let learnConfirm = null; // {slot|null, text}
let timerSeen = { id: -1, at: 0 };
let ticker = 0;
let pick = null; // {move} while choosing a target, {item} while choosing a bag target
let bagPocket = 'medicine';

// evolution scene
let evo = null; // last evolution section
let evoEl = null;
let evoState = null; // {seq, answered, stopped}

const RESULT = {
  VICTORY: 'You won!', DEFEAT: 'You lost...', FLED: 'Got away safely!', CAUGHT: 'Gotcha!', ENDED: 'Battle over',
  WIN: 'You won!', LOSE: 'You lost...', RAN: 'Got away safely!', DRAW: 'Draw.', FORFEIT: 'Forfeited.',
};
export const TYPE_COLORS = {
  NORMAL: '#a8a878', FIRE: '#f08030', WATER: '#6890f0', GRASS: '#78c850', ELECTRIC: '#f8d030', ICE: '#98d8d8',
  FIGHTING: '#c03028', POISON: '#a040a0', GROUND: '#e0c068', FLYING: '#a890f0', PSYCHIC: '#f85888', BUG: '#a8b820',
  ROCK: '#b8a038', GHOST: '#705898', DRAGON: '#7038f8', DARK: '#705848', STEEL: '#b8b8d0',
};
const STATUS = {
  slp: ['SLP', '#8c8c8c'], psn: ['PSN', '#a040a0'], tox: ['PSN', '#702878'], brn: ['BRN', '#e85830'],
  frz: ['FRZ', '#58b8e0'], par: ['PAR', '#d8b020'],
};
const KEYS = ['me', 'me1', 'foe', 'foe1'];
const keyFor = (side, pos) => (pos ? side + '1' : side);
const sideOfKey = (k) => (k.startsWith('foe') ? 'foe' : 'me');

// Where things sit, in GBA pixels: single battle, then the two positions of a double battle.
const POS1 = {
  foe: { cx: 180, cy: 36, ball: [174, 40], from: [252, -6], peak: [214, -16] },
  me: { cx: 64, cy: 80, ball: [58, 94], from: [-14, 96], peak: [26, 44] },
};
const POS2 = {
  foe: { cx: 156, cy: 32, ball: [150, 38], from: [252, -6], peak: [200, -16] },
  foe1: { cx: 204, cy: 42, ball: [198, 46], from: [252, -6], peak: [226, -16] },
  me: { cx: 36, cy: 78, ball: [30, 92], from: [-14, 96], peak: [10, 44] },
  me1: { cx: 88, cy: 82, ball: [82, 96], from: [-14, 96], peak: [46, 44] },
};
const dbl = () => !!b && b.format === 2;
const P = (k) => (dbl() ? POS2[k] : POS1[k] || POS1[sideOfKey(k)]) || POS1.me;
const STEP_TIMEOUT = 6000;

export function init(a) {
  app = a;
}

export const isOpen = () => (!!b && !dismissed) || !!evoEl;
export const root = () => evoEl || $('#battle');
export const busy = () => playing || queue.length > 0;

export function update(state, changed) {
  const nb = state.battle;
  // The evolution scene waits until the battle screen is gone, as on the GBA.
  const battleUp = !!nb && !(b && dismissed && nb.phase === 'ENDED');
  if (!battleUp || evoEl) updateEvolution(state.evolution || null);
  if (!nb) {
    if (b) close();
    return;
  }
  const fresh = !b;
  if (!fresh && same(nb, b) && !changed.includes('ui')) return;
  if (fresh) {
    menu = 'main';
    pick = null;
    dismissed = false;
    learnConfirm = null;
    b = nb;
    build();
    startPresentation(nb);
  }
  // The extended state (format, trainer sprite, background) can land after the battle opened:
  // rebuild the screen for it, keeping what is presented.
  if (!fresh && (nb.format !== b.format || (nb.trainer && nb.trainer.pic) !== (b.trainer && b.trainer.pic) || nb.terrain !== b.terrain)) {
    b = nb;
    const keepView = view || {};
    build();
    view = keepView;
    for (const k of keysInUse()) {
      if (!view[k]) view[k] = copyMon(stateMon(k));
      if (view[k + 'Vis'] === undefined) view[k + 'Vis'] = false;
    }
    if (busy() || queue.length) {
      // The intro is still playing: show the trainer and the player again.
      if (sk.trainer.dataset.has && !b.wild && !keysInUse().some((k) => sideOfKey(k) === 'foe' && view[k + 'Vis'])) sk.trainer.classList.remove('hidden');
      if (sk.back.dataset.has && !keysInUse().some((k) => sideOfKey(k) === 'me' && view[k + 'Vis'])) sk.back.classList.remove('hidden');
    }
    paintView();
  }
  const prevPhase = b && b.phase;
  const prevLearn = b && b.learn;
  const prevPos = b && b.choosingPos;
  b = nb;
  if (!same(prevLearn, b.learn)) learnConfirm = null;
  if (b.phase !== 'ACTION' && ['fight', 'bag', 'target', 'bagparty', 'bagmove'].includes(menu)) menu = 'main';
  if (b.phase === 'FORCED_SWITCH') menu = 'party';
  else if (prevPhase === 'FORCED_SWITCH' && menu === 'party') menu = 'main';
  if (b.phase === 'ACTION' && prevPhase && prevPhase !== 'ACTION') menu = 'main';
  // Doubles: the second position picks next.
  if (b.phase === 'ACTION' && prevPos !== b.choosingPos && !fresh) {
    menu = 'main';
    pick = null;
  }
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
  pick = null;
  clearInterval(ticker);
  const r = $('#battle');
  r.classList.add('hidden');
  r.classList.remove('dbl');
  r.replaceChildren();
}

// ------------------------------------------------------------------ skeleton
function region() {
  return (app.world && app.world.map && app.world.map.region) ?? 1;
}

function bgUrl() {
  const bgs = assets.manifest && assets.manifest.battle && assets.manifest.battle.backgrounds;
  if (!bgs || !b) return null;
  const r = bgs[String(region())] || bgs['1'] || {};
  const special = /^(elite|champion|team)/.test(b.terrain || '') ? r.stadium : null;
  return assets.url(r[b.terrain] || special || r.plain || r.tall_grass);
}

function build() {
  const r = $('#battle');
  r.classList.toggle('dbl', dbl());
  const info = (key) => {
    const mine = sideOfKey(key) === 'me';
    const name = h('span.nm'), lv = h('span.lv'), bar = h('i'), num = h('div.hpnum'), st = h('span.st.hidden');
    const exp = mine ? h('i') : null;
    const el = h('div.info.' + sideOfKey(key) + '.k-' + key,
      h('div.row', name, st, lv),
      h('div.hp', h('span', 'HP'), h('span.hpbar', bar)),
      mine ? num : null,
      mine && !dbl() ? h('div.expbar', h('span', 'EXP'), h('span.exptrack', exp)) : null);
    return { el, name, lv, bar, num, exp, st };
  };
  const wrap = (key) => h('div.monwrap.' + sideOfKey(key) + '.k-' + key, h('div.monclip', h('div.ph', '?')));
  const keys = dbl() ? KEYS : ['me', 'foe'];
  sk = {
    wraps: {}, infos: {}, dex: {},
    text: h('div.text'), menu: h('div.menu.gbabox'), bottom: null, sheet: h('div.sheet.gbabox.hidden'),
    result: h('div.result.gbabox.hidden'), badge: h('div.bbadge.hidden'), fxLayer: h('div.fxlayer'),
    bg: h('div.bgimg'), weather: h('div.weather.hidden'), trainer: h('div.trainerpic.hidden'), back: h('div.backpic.hidden'),
    banner: h('div.banners'),
  };
  for (const k of keys) {
    sk.wraps[k] = wrap(k);
    sk.infos[k] = info(k);
  }
  const url = bgUrl();
  if (url) {
    sk.bg.style.backgroundImage = `url("${url}")`;
    r.classList.add('hasbg');
  } else r.classList.remove('hasbg');
  sk.bottom = h('div.bottom', sk.text, sk.menu);
  r.replaceChildren(sk.bg, h('div.plat.foe'), h('div.plat.me'), sk.trainer, sk.back,
    ...keys.map((k) => sk.wraps[k]), sk.weather, sk.fxLayer, ...keys.map((k) => sk.infos[k].el),
    sk.banner, sk.badge, sk.bottom, sk.sheet, sk.result);
  r.classList.remove('hidden');
  r.addEventListener('pointerdown', (e) => {
    if (busy() && !e.target.closest('button')) skip();
  });
  clearInterval(ticker);
  ticker = setInterval(tickBadge, 250);
  setupIntroSprites();
}

/** The opposing trainer's front sprite and the player's back sprite for the intro. */
function setupIntroSprites() {
  const m = assets.manifest && assets.manifest.battle;
  if (!m || !b) return;
  const pic = b.trainer && b.trainer.pic && m.trainers && m.trainers[b.trainer.pic];
  if (pic) {
    sk.trainer.style.backgroundImage = `url("${assets.url(pic)}")`;
    sk.trainer.dataset.has = '1';
  }
  const name = region() === 0 ? 'red' : 'brendan';
  const back = m.backs && (m.backs[name] || m.backs.brendan);
  if (back) {
    sk.back.style.backgroundImage = `url("${assets.url(back.file)}")`;
    sk.back.style.backgroundSize = `100% ${back.frames * 100}%`;
    sk.back.dataset.frames = back.frames;
    sk.back.dataset.has = '1';
  }
}

const monEl = (k) => (sk && (sk.wraps[k] || sk.wraps[sideOfKey(k)])) || null;
const infoEl = (k) => (sk && (sk.infos[k] || sk.infos[sideOfKey(k)])) || null;

function setSprite(k, dex, shiny = false) {
  if (!sk || !sk.wraps[k]) return;
  if (sk.dex[k] === dex + (shiny ? 's' : '')) return;
  sk.dex[k] = dex + (shiny ? 's' : '');
  const url = dex ? assets.pokemon(dex, sideOfKey(k) === 'foe' ? 'front' : 'back', shiny) : null;
  const n = url ? h('img.sprite', { src: url, alt: '', draggable: false }) : h('div.ph', '#' + (dex ?? '?'));
  sk.wraps[k].querySelector('.monclip').replaceChildren(n);
}

// ------------------------------------------------------------------ presentation model
const copyMon = (m) => (m ? { ...m } : null);

/** The monster at key [k] in the server state. */
function stateMon(k) {
  if (!b) return null;
  const side = sideOfKey(k);
  const pos = k.endsWith('1') ? 1 : 0;
  const list = side === 'me' ? b.mine : b.foes;
  if (list && list.length) return list[pos] || null;
  return pos ? null : side === 'me' ? b.me : b.foe;
}

function keysInUse() {
  return dbl() ? KEYS : ['me', 'foe'];
}

/** Which presented position an effect is about: its monster id, else its side and position. */
function keyOf(f) {
  if (f.id != null) {
    const id = String(f.id);
    for (const k of keysInUse()) {
      const m = stateMon(k);
      if (m && m.id === id) return k;
    }
    for (const k of keysInUse()) if (view && view[k] && view[k].id === id) return k;
  }
  if (f.pos != null && f.side) return keyFor(f.side, f.pos);
  return f.side || null;
}

function startPresentation(nb) {
  const fx = nb.fx || [];
  let start = -1;
  for (let i = fx.length - 1; i >= 0; i--) if (fx[i].k === 'start') { start = i; break; }
  queue = [];
  view = {};
  if (start >= 0) {
    // A battle that just began: play it from its start effect.
    lastSeq = fx[start].n - 1;
    for (const k of keysInUse()) {
      view[k] = copyMon(stateMon(k));
      view[k + 'Vis'] = false;
    }
    if (fx[start].me) view.me = { ...view.me, ...fx[start].me };
    if (fx[start].foe) view.foe = { ...view.foe, ...fx[start].foe };
    enqueue(fx);
  } else {
    // Joined mid-battle (reload): show the current state, no replay.
    lastSeq = fx.length ? fx[fx.length - 1].n : 0;
    for (const k of keysInUse()) {
      view[k] = copyMon(stateMon(k));
      view[k + 'Vis'] = !!view[k] && view[k].hp > 0;
    }
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
  for (const k of keysInUse()) {
    const m = stateMon(k);
    if (!m) {
      view[k + 'Vis'] = false;
      continue;
    }
    view[k] = copyMon(m);
    if (m.hp <= 0) view[k + 'Vis'] = false;
    else if (sideOfKey(k) === 'foe' && b.result === 'CAUGHT') view[k + 'Vis'] = false;
    else view[k + 'Vis'] = true;
  }
  hideIntro();
  paintView();
}

function hideIntro() {
  if (!sk) return;
  sk.trainer.classList.add('hidden');
  sk.back.classList.add('hidden');
}

/** Puts the presented model on screen without animating. */
function paintView() {
  if (!sk || !view) return;
  for (const k of keysInUse()) {
    if (view[k]) {
      setSprite(k, view[k].dex, view[k].shiny);
      setInfo(infoEl(k), view[k], sideOfKey(k) === 'me');
    }
    setVisible(k, !!view[k + 'Vis'] && !!view[k]);
  }
  paintWeather(b && b.weather);
}

/** Shows or hides a monster (and, unless [withInfo] is false, its info box). */
function setVisible(k, vis, withInfo = true) {
  const el = monEl(k);
  if (!el) return;
  // Finished animations hold their last frame over inline styles, so drop them first.
  for (const a of el.getAnimations({ subtree: true })) a.cancel();
  el.style.opacity = vis ? '1' : '0';
  el.style.transform = '';
  if (withInfo) infoEl(k).el.classList.toggle('gone', !vis);
  if (vis && view && view[k] && view[k].shiny) shinySparkle(k);
}

// Integration: the Gen 3 shiny sparkle (battle_anim_effects Anim_Shiny / gBattleAnimSpriteGfx_Sparkle)
// when a shiny monster comes out, once per monster per battle.
const sparkled = new Set();
function shinySparkle(k) {
  const id = String(view[k].id ?? k + ':' + view[k].dex);
  if (sparkled.has(id) || !sk) return;
  sparkled.add(id);
  const m = assets.manifest && assets.manifest.battle && assets.manifest.battle.anims;
  const s = m && m.sparkle;
  if (!s) return;
  const c = P(k);
  // Four stars around the monster, then one in the middle, like the GBA's sparkle cycle.
  const spots = [[-14, -18], [14, -10], [-10, 10], [16, 14], [0, -2]];
  spots.forEach(([dx, dy], i) => setTimeout(() => {
    if (!sk) return;
    const el = h('div.animspr.sparkle');
    const size = s.frame;
    el.style.width = el.style.height = `calc(var(--u) * ${size})`;
    el.style.backgroundImage = `url("${assets.url(s.file)}")`;
    el.style.backgroundSize = `100% ${s.frames * 100}%`;
    place(el, c.cx + dx - size / 2, c.cy + dy - size / 2);
    sk.fxLayer.append(el);
    let frame = 0;
    const n = Math.max(1, s.frames);
    const iv = setInterval(() => {
      frame++;
      if (frame >= n) { clearInterval(iv); el.remove(); return; }
      el.style.backgroundPositionY = `${(frame / (n - 1)) * 100}%`;
    }, 60);
  }, 300 + i * 130));
}

function setInfo(i, m, mine) {
  if (!i || !m) return;
  i.name.textContent = m.name;
  i.lv.textContent = 'Lv' + m.level;
  setStatus(i, m.status);
  setHp(i, m.hp, m.maxHp, mine);
  if (mine && i.exp && typeof m.exp === 'number') i.exp.style.transform = `scaleX(${m.exp})`;
}

function setStatus(i, code) {
  const s = code && STATUS[code];
  i.st.classList.toggle('hidden', !s);
  if (s) {
    i.st.textContent = s[0];
    i.st.style.background = s[1];
  }
}

function setHp(i, hp, maxHp, mine) {
  const r = maxHp > 0 ? Math.max(0, hp) / maxHp : 0;
  i.bar.style.transform = `scaleX(${r})`;
  i.bar.className = hpClass(hp, maxHp);
  if (mine) i.num.textContent = `${Math.max(0, Math.round(hp))}/${maxHp}`;
}

function paintWeather(w) {
  if (!sk) return;
  const on = w && w !== 'none';
  sk.weather.className = 'weather' + (on ? ' ' + w : ' hidden');
}

// ------------------------------------------------------------------ effect players
const U = () => parseFloat(getComputedStyle($('#ov')).getPropertyValue('--u')) || 2;
// settings (r3-present): battle animations off plays every step in the fast mode
const fast = () => queue.length > 10 || !settings.get('battleAnims');
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
  const k = keyOf(f);
  switch (f.k) {
    case 'start': return playStart(f);
    case 'text': return playText(f.text);
    case 'move': return playMove(f);
    case 'hp': return playHp(f, k);
    case 'miss': return k && anim(monEl(k), [{ transform: tr(0, 0) }, { transform: tr(sideOfKey(k) === 'foe' ? 10 : -10, 0) }, { transform: tr(0, 0) }], { duration: 300 });
    case 'stat': return playStat(k, f);
    case 'faint': return playFaint(k);
    case 'switch': return playSwitch(f);
    case 'ball': return playBall(f);
    case 'exp': return playExp(f);
    case 'level': return playLevel(f);
    case 'status': return playStatus(k, f);
    case 'ability': return banner(k, `${f.name || ''}'s ${f.ability}`);
    case 'item': return banner(k, `${f.name || ''}'s ${String(f.item || '').toUpperCase()}`);
    case 'weather': paintWeather(f.weather); return sleep(dur(200));
    case 'volatile': return f.kind === 'confused' && f.on ? playConfused(k) : undefined;
    case 'revive': return undefined;
    default: return undefined;
  }
}

async function playStart(f) {
  sparkled.clear();
  for (const k of keysInUse()) {
    view[k] = copyMon(stateMon(k)) || view[k];
    view[k + 'Vis'] = false;
  }
  if (f.me) view.me = { ...view.me, ...f.me };
  if (f.foe) view.foe = { ...view.foe, ...f.foe };
  paintView();
  const plats = [
    anim(root().querySelector('.plat.foe'), [{ transform: tr(-200, 0) }, { transform: tr(0, 0) }], { duration: 800, easing: 'linear' }),
    anim(root().querySelector('.plat.me'), [{ transform: tr(200, 0) }, { transform: tr(0, 0) }], { duration: 800, easing: 'linear' }),
    anim(sk.bg, [{ opacity: 0 }, { opacity: 1 }], { duration: 400 }),
  ];
  // The player's back sprite slides in with the platforms.
  if (sk.back.dataset.has) {
    sk.back.classList.remove('hidden');
    sk.back.style.backgroundPositionY = '0%';
    plats.push(anim(sk.back, [{ transform: tr(200, 0) }, { transform: tr(0, 0) }], { duration: 800, easing: 'linear' }));
  }
  if (f.wild) {
    // The wild monsters slide in from the left as the scene opens.
    const foes = keysInUse().filter((k) => sideOfKey(k) === 'foe' && view[k]);
    for (const k of foes) {
      view[k + 'Vis'] = true;
      setVisible(k, true, false);
      plats.push(anim(monEl(k), [{ transform: tr(-200, 0), filter: 'brightness(0.3)' }, { transform: tr(0, 0), filter: 'brightness(1)' }], { duration: 800, easing: 'linear' }));
    }
    await Promise.all(plats);
    for (const k of foes) await showInfo(k);
  } else {
    // A trainer slides in with the platforms and steps aside when it sends its monsters out.
    if (sk.trainer.dataset.has) {
      sk.trainer.classList.remove('hidden');
      plats.push(anim(sk.trainer, [{ transform: tr(-200, 0) }, { transform: tr(0, 0) }], { duration: 800, easing: 'linear' }));
    }
    await Promise.all(plats);
  }
}

async function showInfo(k) {
  const i = infoEl(k);
  if (!i) return;
  i.el.classList.remove('gone');
  await anim(i.el, [{ transform: tr(sideOfKey(k) === 'foe' ? -120 : 120, 0) }, { transform: tr(0, 0) }], { duration: 250 });
}

/** Ball arcs onto the platform, pops, and the monster grows out of it. */
async function sendOut(k) {
  const p = P(k);
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
  view[k + 'Vis'] = true;
  setVisible(k, true, false);
  await anim(monEl(k), [
    { transform: 'scale(0.1)', filter: 'brightness(3)', transformOrigin: '50% 100%' },
    { transform: 'scale(1)', filter: 'brightness(1)', transformOrigin: '50% 100%' },
  ], { duration: 300 });
  await showInfo(k);
}

/** The trainer (or the player) steps out of the way before the first send-out. */
async function introExit(side) {
  const el = side === 'foe' ? sk.trainer : sk.back;
  if (el.classList.contains('hidden')) return;
  if (side === 'me' && el.dataset.frames > 1) {
    // The throw: step through the back sprite's frames.
    const n = +el.dataset.frames;
    for (let i = 1; i < n; i++) {
      el.style.backgroundPositionY = `${(i / (n - 1)) * 100}%`;
      await sleep(dur(70));
    }
  }
  await anim(el, [{ transform: tr(0, 0), opacity: 1 }, { transform: tr(side === 'foe' ? 90 : -110, 0), opacity: 0.2 }], { duration: 380, easing: 'ease-in' });
  el.classList.add('hidden');
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

function flash(k, color, times = 1) {
  const el = monEl(k);
  if (!el) return Promise.resolve();
  const f = h('div.flash');
  f.style.background = color;
  el.append(f);
  const frames = [];
  for (let i = 0; i < times; i++) frames.push({ opacity: 0 }, { opacity: 0.75 });
  frames.push({ opacity: 0 });
  return anim(f, frames, { duration: 160 * times + 120, easing: 'linear' }).then(() => f.remove());
}

/** A battle animation sprite sheet (graphics/battle_anims) flying from [a] to [t], frames stepping. */
async function animSprite(key, a, t) {
  const m = assets.manifest && assets.manifest.battle && assets.manifest.battle.anims;
  const s = m && (m[key] || m.hit);
  if (!s) return;
  const size = s.frame;
  const el = h('div.animspr');
  el.style.width = el.style.height = `calc(var(--u) * ${size})`;
  el.style.backgroundImage = `url("${assets.url(s.file)}")`;
  el.style.backgroundSize = `100% ${s.frames * 100}%`;
  place(el, a.cx - size / 2, a.cy - size / 2);
  sk.fxLayer.append(el);
  const n = Math.max(1, s.frames);
  let frame = 0;
  const iv = setInterval(() => {
    frame = (frame + 1) % n;
    el.style.backgroundPositionY = n > 1 ? `${(frame / (n - 1)) * 100}%` : '0%';
  }, 70);
  await anim(el, [{ transform: tr(0, 0, 'scale(0.6)') }, { transform: tr(t.cx - a.cx, t.cy - a.cy, 'scale(1.2)') }], { duration: 420, easing: 'ease-in' });
  await sleep(dur(120));
  clearInterval(iv);
  el.remove();
}

async function playMove(f) {
  const k = keyOf({ id: f.id, side: f.side || 'me' }) || f.side || 'me';
  const side = sideOfKey(k);
  let tk = f.targetId != null ? keyOf({ id: f.targetId }) : null;
  if (!tk || tk === k) tk = f.target && f.target !== side ? f.target : side === 'me' ? 'foe' : 'me';
  const color = TYPE_COLORS[f.type] || '#ffffff';
  if (!view[k + 'Vis']) return;
  const dx = side === 'me' ? 14 : -14, dy = side === 'me' ? -6 : 6;
  await anim(monEl(k), [{ transform: tr(0, 0) }, { transform: tr(dx, dy) }, { transform: tr(0, 0) }], { duration: 260 });
  if (f.power > 0) {
    // Damaging move: the type's animation sprite flies over, then a burst on every target.
    const targets = (f.targets || [f.targetId]).map((id) => keyOf({ id })).filter((x) => x && x !== k);
    const list = targets.length ? targets : [tk];
    await animSprite(f.type, P(k), P(list[0]));
    for (const t of list) {
      burst(P(t).cx, P(t).cy, color, 10, 26, 5);
      burst(P(t).cx, P(t).cy, '#ffffff', 5, 12, 3);
    }
    await Promise.all(list.map((t) => (view[t + 'Vis'] ? flash(t, color, 1) : null)));
  } else {
    // Status move: rings of its colour around whoever it aims at.
    const who = f.target === side ? k : tk;
    const ring = h('div.ring');
    ring.style.borderColor = color;
    place(ring, P(who).cx - 16, P(who).cy - 16);
    sk.fxLayer.append(ring);
    await anim(ring, [{ transform: 'scale(0.2)', opacity: 1 }, { transform: 'scale(1.6)', opacity: 0 }], { duration: 450 });
    ring.remove();
  }
}

async function playHp(f, k) {
  if (!k || !view[k]) return;
  const m = view[k];
  const mine = sideOfKey(k) === 'me';
  const maxHp = f.maxHp || m.maxHp;
  const from = typeof f.from === 'number' ? f.from : m.hp;
  const to = f.hp;
  const info = infoEl(k);
  if (to < from && view[k + 'Vis']) {
    // Hit: the sprite blinks and shakes while the box jolts.
    await Promise.all([
      anim(monEl(k), [{ opacity: 1 }, { opacity: 0 }, { opacity: 1 }, { opacity: 0 }, { opacity: 1 }, { opacity: 0 }, { opacity: 1 }], { duration: 480, easing: 'steps(1)' }),
      anim(info.el, [{ transform: tr(0, 0) }, { transform: tr(-2, 0) }, { transform: tr(2, 0) }, { transform: tr(0, 0) }], { duration: 240 }),
    ]);
  } else if (to > from && view[k + 'Vis']) {
    burst(P(k).cx, P(k).cy, '#98f8a8', 8, 18, 3);
  }
  const ms = dur(Math.max(300, Math.min(1200, (Math.abs(from - to) / Math.max(1, maxHp)) * 1400)));
  const t0 = performance.now();
  await bounded(new Promise((res) => {
    const stepFn = (now) => {
      if (!sk) return res();
      const q = Math.min(1, (now - t0) / ms);
      const hp = from + (to - from) * q;
      setHp(info, mine ? Math.round(hp) : hp, maxHp, mine);
      if (q < 1) requestAnimationFrame(stepFn);
      else res();
    };
    requestAnimationFrame(stepFn);
  }), ms + 400);
  m.hp = to;
  m.maxHp = maxHp;
  setHp(info, to, maxHp, mine);
}

async function playStat(k, f) {
  if (!k || !view[k + 'Vis']) return;
  const up = f.delta > 0;
  const o = h('div.statfx.' + (up ? 'up' : 'down'));
  monEl(k).append(o);
  await anim(o, [{ backgroundPositionY: '0px', opacity: 0.85 }, { backgroundPositionY: (up ? -1 : 1) * 64 * U() + 'px', opacity: 0.85, offset: 0.85 }, { opacity: 0 }],
    { duration: 700, easing: 'linear' });
  o.remove();
}

/** A major status lands (or is cured): the info box icon updates and the monster glows its colour. */
async function playStatus(k, f) {
  if (!k || !view[k]) return;
  view[k].status = f.status || null;
  setStatus(infoEl(k), f.status);
  if (!f.status || !view[k + 'Vis']) return;
  const color = (STATUS[f.status] || [0, '#fff'])[1];
  if (f.status === 'slp') await animSprite('sleep', { cx: P(k).cx, cy: P(k).cy }, { cx: P(k).cx + 10, cy: P(k).cy - 18 });
  await flash(k, color, 2);
}

async function playConfused(k) {
  if (!k || !view[k + 'Vis']) return;
  const ring = h('div.ring.dizzy');
  place(ring, P(k).cx - 16, P(k).cy - 34);
  sk.fxLayer.append(ring);
  await anim(ring, [{ transform: 'rotate(0deg) scaleY(0.4)', opacity: 1 }, { transform: 'rotate(720deg) scaleY(0.4)', opacity: 0 }], { duration: 700, easing: 'linear' });
  ring.remove();
}

/** An ability or held item announces itself next to its owner's info box. */
async function banner(k, text) {
  if (!sk) return;
  const el = h('div.banner.' + (k ? sideOfKey(k) : 'me'), text);
  sk.banner.append(el);
  await anim(el, [{ transform: tr(k && sideOfKey(k) === 'foe' ? -80 : 80, 0), opacity: 0 }, { transform: tr(0, 0), opacity: 1, offset: 0.25 }, { opacity: 1, offset: 0.85 }, { opacity: 0 }],
    { duration: 1100, easing: 'linear' });
  el.remove();
}

async function playFaint(k) {
  if (!k || !view[k + 'Vis']) return;
  const clip = monEl(k).querySelector('.monclip > *');
  await anim(clip, [{ transform: 'translateY(0)' }, { transform: tr(0, 64) }], { duration: 450, easing: 'ease-in' });
  view[k + 'Vis'] = false;
  setVisible(k, false);
  if (view[k]) view[k].hp = 0;
}

async function playSwitch(f) {
  const side = f.side;
  if (!side || !f.mon) return;
  const k = keyFor(side, f.pos || 0);
  if (view[k + 'Vis']) {
    // Called back: a red flash and the monster shrinks into its ball.
    await flash(k, '#f04040', 1);
    await anim(monEl(k), [{ transform: 'scale(1)', opacity: 1, transformOrigin: '50% 100%' }, { transform: 'scale(0.1)', opacity: 0, transformOrigin: '50% 100%' }], { duration: 260 });
    view[k + 'Vis'] = false;
    setVisible(k, false);
  }
  const live = stateMon(k);
  view[k] = { ...view[k], ...f.mon, id: live && live.name === f.mon.name ? live.id : view[k] && view[k].id,
    exp: side === 'me' ? (live && live.name === f.mon.name ? live.exp : undefined) : undefined };
  setSprite(k, f.mon.dex, f.mon.shiny);
  setInfo(infoEl(k), view[k], side === 'me');
  setVisible(k, false);
}

async function playBall(f) {
  const target = keysInUse().find((k) => sideOfKey(k) === 'foe' && view[k + 'Vis']) || 'foe';
  const p = P(target);
  const ball = h('div.ball' + (f.ball ? '.b-' + f.ball.toLowerCase().replace(/[^a-z]/g, '') : ''));
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
  await anim(monEl(target), [{ transform: 'scale(1)', filter: 'brightness(1)' }, { transform: 'scale(0.05)', filter: 'brightness(4)', offset: 1 }], { duration: 350 });
  view[target + 'Vis'] = false;
  setVisible(target, false, false);
  // The ball drops to the ground and bounces.
  const dropY = p.cy + 8 - land[1];
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
    // The ball breaks open and the monster bursts back out.
    ball.classList.add('open');
    burst(land[0] + 6, land[1] + dropY + 6, '#ffffff', 12, 22);
    await anim(ball, [{ transform: 'scale(1)', opacity: 1 }, { transform: 'scale(1.6)', opacity: 0 }], { duration: 220 });
    ball.remove();
    view[target + 'Vis'] = true;
    setVisible(target, true, false);
    await anim(monEl(target), [{ transform: 'scale(0.1)', filter: 'brightness(4)' }, { transform: 'scale(1)', filter: 'brightness(1)' }], { duration: 300 });
  }
}

async function playExp(f) {
  const i = infoEl('me');
  if (!i || !i.exp) return;
  const from = Math.max(0, Math.min(1, f.from ?? 0)), to = Math.max(0, Math.min(1, f.to ?? 0));
  await anim(i.exp, [{ transform: `scaleX(${from})` }, { transform: `scaleX(${to})` }], { duration: Math.max(250, 900 * Math.abs(to - from)), easing: 'linear' });
  i.exp.getAnimations().forEach((a) => a.cancel());
  i.exp.style.transform = `scaleX(${to})`;
  if (view.me) view.me.exp = to;
}

async function playLevel(f) {
  const i = infoEl('me');
  if (view.me) view.me.level = f.level;
  i.lv.textContent = 'Lv' + f.level;
  i.el.classList.add('lvup');
  burst(P('me').cx, P('me').cy, '#f8e060', 10, 28, 4);
  await anim(i.el, [{ boxShadow: '0 0 0 0 #f8e060' }, { boxShadow: `0 0 ${8 * U()}px ${3 * U()}px #f8e060` }, { boxShadow: '0 0 0 0 #f8e060' }], { duration: 700 });
  i.el.classList.remove('lvup');
}

/** Types the line out, then waits for A / a tap or a short auto delay. */
async function playText(text) {
  if (!sk) return;
  // Send-outs ride on their announcement, as on the GBA.
  const hiddenOf = (side) => keysInUse().filter((k) => sideOfKey(k) === side && !view[k + 'Vis'] && stateMon(k) && stateMon(k).hp > 0);
  const sendSide = /^Go! /.test(text) && hiddenOf('me').length ? 'me'
    : / sent out /.test(text) && !/^Go! /.test(text) && hiddenOf('foe').length ? 'foe' : null;
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
      typing.t = setTimeout(tick, settings.battleCharMs());
    };
    typing = { t: 0, finish: () => { clearTimeout(typing && typing.t); el.textContent = text; res(); } };
    tick();
  });
  const out = sendSide
    ? (async () => {
      await introExit(sendSide);
      // In a double battle both monsters come out on one line.
      for (const k of (dbl() ? hiddenOf(sendSide) : hiddenOf(sendSide).slice(0, 1))) {
        view[k] = copyMon(stateMon(k)) || view[k];
        setSprite(k, view[k].dex, view[k].shiny);
        setInfo(infoEl(k), view[k], sendSide === 'me');
        await sendOut(k);
      }
    })()
    : Promise.resolve();
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
const statusChip = (code) => (code && STATUS[code] ? h('span.st', { style: `background:${STATUS[code][1]}` }, STATUS[code][0]) : null);

/** The monster picking an action now (the next position in a double battle). */
function actor() {
  if (!b) return null;
  if (dbl() && typeof b.choosingPos === 'number' && b.mine) return b.mine[b.choosingPos] || b.me;
  return b.me;
}
const actorPos = () => (dbl() && typeof b.choosingPos === 'number' ? b.choosingPos : null);

function sendFight(i, target) {
  const pos = actorPos();
  if (pos === null && !target) return app.send({ t: 'battle.fight', move: i });
  const msg = { t: 'battle.fight', move: i };
  if (pos !== null) msg.pos = pos;
  if (target) msg.target = target;
  pick = null;
  menu = 'main';
  app.send(msg);
}

function chooseMove(i, m) {
  // In a double battle a single-target move asks who to aim at.
  if (dbl() && (!m.target || m.target === 'SELECTED')) {
    pick = { move: i, name: m.name };
    return setMenu('target');
  }
  sendFight(i, null);
}

function render(state) {
  if (!sk || !b) return;
  const r = $('#battle');
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
      if (!evoEl) setFocus(r, adv);
    }
    return;
  }
  paintView();

  const log = (b.log || []).map((l) => l.replace(/ \((learn N \/ skip|accept \/ decline)\)$/, ''));
  const lastLines = log.slice(-2);
  const keep = r.querySelector('.nav-focus');
  const keepK = keep && keep.dataset.k !== 'adv' ? keep.dataset.k : null;
  sk.menu.className = 'menu gbabox';
  sk.menu.classList.remove('hidden');
  sk.sheet.classList.add('hidden');
  sk.sheet.classList.remove('learn');
  sk.result.classList.add('hidden');
  const me = actor();

  if (b.phase === 'ACTION' && menu === 'fight' && me) {
    const moves = me.moves || [];
    sk.menu.classList.add('wide');
    const cells = [0, 1, 2, 3].map((i) => {
      const m = moves[i];
      if (!m) return btn('-', 'mv' + i, null, true);
      const cell = btn(m.name, 'mv' + i, () => chooseMove(i, m), m.pp <= 0 && moves.some((x) => x.pp > 0),
        h('span.pp', `${m.pp}/${m.maxPp}`));
      if (m.type) cell.style.borderLeft = `calc(var(--u) * 2) solid ${TYPE_COLORS[m.type] || '#888'}`;
      return cell;
    });
    sk.menu.replaceChildren(...cells);
    sk.text.replaceChildren(btn('◀ BACK', 'back', () => setMenu('main')));
    sk.text.style.flex = '0 0 calc(var(--u) * 54)';
  } else if (b.phase === 'ACTION' && menu === 'target' && pick) {
    sk.menu.classList.add('wide');
    const foes = b.foes || [b.foe];
    const pos = actorPos() ?? 0;
    const ally = (b.mine || [])[1 - pos];
    sk.menu.replaceChildren(
      ...foes.map((f, i) => btn(f ? f.name : '-', 'tg' + i, () => sendFight(pick.move, { side: 'foe', pos: i }), !f || f.hp <= 0, h('span.pp', 'FOE'))),
      btn(ally ? ally.name : '-', 'tga', () => sendFight(pick.move, { side: 'me', pos: 1 - pos }), !ally || ally.hp <= 0, h('span.pp', 'ALLY')),
      btn('◀ BACK', 'back', () => setMenu('fight')),
    );
    sk.text.replaceChildren(h('div', `${pick.name}:`), h('div', 'which target?'));
    sk.text.style.flex = '0 0 calc(var(--u) * 70)';
  } else {
    sk.text.style.flex = '';
    if (b.phase === 'ACTION' && me) {
      sk.text.replaceChildren(h('div', `What will ${me.name} do?`));
      sk.menu.replaceChildren(
        btn('FIGHT', 'fight', () => setMenu('fight')),
        btn('BAG', 'bag', () => setMenu('bag'), !!b.rules),
        btn('POKéMON', 'pkmn', () => setMenu('party')),
        btn(b.rules ? 'FORFEIT' : 'RUN', 'run', () => app.send({ t: 'battle.run' }), !b.wild && !b.rules),
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
    const out = b.actives || [b.activeSlot];
    sk.sheet.classList.remove('hidden');
    fill(sk.sheet,
      h('div.title', forced ? 'Choose the next POKéMON.' : 'Switch to which POKéMON?'),
      (b.party || []).map((p, i) => {
        const active = out.includes(i);
        return h('button', { type: 'button', 'data-k': 'p' + i, disabled: p.hp <= 0 || active, on: { click: () => app.send({ t: 'battle.switch', slot: i }) } },
          monImg(p.dex, 'icon', ''), h('span', `${p.name} Lv${p.level}${active ? ' (in battle)' : ''}`, statusChip(p.status)), h('span', `${Math.max(0, p.hp)}/${p.maxHp}`));
      }),
      forced ? null : btn('◀ BACK', 'pback', () => setMenu('main')),
    );
  } else if (menu === 'bag' && b.phase === 'ACTION') {
    renderBag();
  } else if (menu === 'bagparty' && b.phase === 'ACTION' && pick && pick.item) {
    const it = pick.item;
    sk.sheet.classList.remove('hidden');
    fill(sk.sheet,
      h('div.title', `Use ${it.name} on which POKéMON?`),
      (b.party || []).map((p, i) => h('button', { type: 'button', 'data-k': 'bp' + i, on: { click: () => {
        if (it.perMove) {
          pick = { ...pick, mon: p };
          return setMenu('bagmove');
        }
        sendItem(it, p.id, null);
      } } }, monImg(p.dex, 'icon', ''), h('span', `${p.name} Lv${p.level}`, statusChip(p.status)), h('span', `${Math.max(0, p.hp)}/${p.maxHp}`))),
      btn('◀ BACK', 'bpback', () => setMenu('bag')),
    );
  } else if (menu === 'bagmove' && b.phase === 'ACTION' && pick && pick.mon) {
    const mon = (b.mine || []).find((m) => m && m.id === pick.mon.id) || (b.me && b.me.id === pick.mon.id ? b.me : null);
    const moves = (mon && mon.moves) || [];
    sk.sheet.classList.remove('hidden');
    fill(sk.sheet,
      h('div.title', `Restore which move's PP?`),
      moves.length ? moves.map((m, i) => h('button', { type: 'button', 'data-k': 'bm' + i, on: { click: () => sendItem(pick.item, pick.mon.id, i) } },
        h('span', ''), h('span', m.name), h('span', `${m.pp}/${m.maxPp}`))) : h('div.title', 'Only a monster in battle can be picked here.'),
      btn('◀ BACK', 'bmback', () => setMenu('bagparty')),
    );
  }

  if (b.learn) renderLearn();
  else if (b.phase === 'ENDED') {
    sk.result.classList.remove('hidden');
    fill(sk.result, h('div', RESULT[b.result] || b.result || 'Battle over'),
      !b.wild && b.opponentName ? h('div', { style: 'font-size:calc(var(--u)*7)' }, 'vs ' + b.opponentName) : null,
      btn('Continue', 'cont', () => {
        dismissed = true;
        $('#battle').classList.add('hidden');
        updateEvolution(app.net.state.evolution || null);
      }));
  }

  if (evoEl) return;
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

const POCKETS = [['medicine', 'ITEMS'], ['balls', 'BALLS'], ['battle', 'BATTLE']];

/** The in-battle BAG: pockets of every usable item, each with what it needs a target for. */
function renderBag() {
  const bag = b.bag || [];
  if (!bag.some((x) => x.pocket === bagPocket)) bagPocket = (bag[0] && bag[0].pocket) || 'medicine';
  const items = bag.filter((x) => x.pocket === bagPocket);
  sk.sheet.classList.remove('hidden');
  fill(sk.sheet,
    h('div.tabs', POCKETS.map(([k, label]) => h('button.tab' + (k === bagPocket ? '.on' : ''), { type: 'button', 'data-k': 'pk' + k,
      disabled: !bag.some((x) => x.pocket === k), on: { click: () => { bagPocket = k; render(app.net.state); } } }, label))),
    items.length ? items.map((it) => h('button', { type: 'button', 'data-k': 'it' + it.id, on: { click: () => useBagItem(it) } },
      h('span', it.pocket === 'balls' ? '◓' : '◆'), h('span', it.name), h('span', `x${it.count}`)))
      : h('div.title', b.wild ? 'Nothing usable here.' : b.rules ? "Items can't be used against a player." : 'Nothing usable here.'),
    !b.wild && !b.rules ? h('div.title.note', "Balls can't be used in a trainer battle.") : null,
    btn('◀ BACK', 'bback', () => setMenu('main')),
  );
}

function useBagItem(it) {
  if (it.target === 'foe') return app.send({ t: 'battle.ball', item: it.id });
  if (it.target === 'party') {
    pick = { item: it };
    return setMenu('bagparty');
  }
  const msg = { t: 'battle.item', item: it.id };
  if (actorPos() !== null) msg.pos = actorPos();
  menu = 'main';
  app.send(msg);
}

function sendItem(it, monId, moveSlot) {
  const msg = { t: 'battle.item', item: it.id, mon: monId };
  if (moveSlot !== null && moveSlot !== undefined) msg.moveSlot = moveSlot;
  if (actorPos() !== null) msg.pos = actorPos();
  pick = null;
  menu = 'main';
  app.send(msg);
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
  const r = $('#battle');
  r.querySelectorAll('.nav-focus').forEach((e) => e.classList.remove('nav-focus'));
  render(app.net.state);
  if (r.querySelector('.nav-focus')) return;
  const first = r.querySelector('.sheet:not(.hidden) button:not(:disabled), .menu:not(.hidden) button:not(:disabled)');
  if (first) first.classList.add('nav-focus');
}

/** B in battle: back out of a sub menu, or of a learn confirmation; B in the evolution scene stops it. */
export function back() {
  if (evoEl) return evoBack();
  if (!b) return;
  if (busy()) return skip();
  if (learnConfirm) {
    learnConfirm = null;
    return render(app.net.state);
  }
  const up = { target: 'fight', bagparty: 'bag', bagmove: 'bagparty' }[menu];
  if (up) return setMenu(up);
  if (menu !== 'main' && b.phase !== 'FORCED_SWITCH') setMenu('main');
}

// ------------------------------------------------------------------ rules badge + turn clock
export function describeRules(r) {
  if (!r) return 'Standard rules';
  return [r.levelCap ? `Lv. cap ${r.levelCap}` : 'No level cap', r.doubles ? `${r.party} vs ${r.party} double` : `${r.party} vs ${r.party}`, r.timer ? `${r.timer}s per turn` : 'No timer'].join(' · ');
}

function renderBadge() {
  if (!sk) return;
  const show = !!b && (b.turn > 0 || b.rules);
  sk.badge.classList.toggle('hidden', !show || b.phase === 'ENDED');
  if (!show) return;
  const rules = b.rules;
  const parts = [h('span.turn', `TURN ${Math.max(1, b.turn || 1)}`)];
  if (dbl()) parts.push(h('span', '2v2'));
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

// ------------------------------------------------------------------ evolution scene
// The GBA scene (src/evolution_scene.c): "What? X is evolving!", the monster flashes between its
// old and new silhouettes faster and faster, B stops it ("Huh? X stopped evolving!"), otherwise
// the new form appears and "Congratulations! Your X evolved into Y!".
function updateEvolution(e) {
  if (!e) {
    if (evoEl) closeEvolution();
    evo = null;
    return;
  }
  const prev = evo;
  evo = e;
  if (e.phase === 'start' && (!prev || prev.seq !== e.seq || !evoEl)) return openEvolution(e);
  if (!evoEl) openEvolution(e);
  if (prev && prev.phase !== e.phase) evoPhase(e);
}

function openEvolution(e) {
  evoState = { seq: e.seq, answered: false, stopped: false, done: false };
  if (!evoEl) {
    evoEl = h('div#evo');
    $('#ov').append(evoEl);
  }
  const oldImg = monImg(e.fromDex, 'front', '.evomon.old');
  const newImg = monImg(e.toDex, 'front', '.evomon.new');
  const text = h('div.evotext.gbabox', h('span', `What? ${e.name} is evolving!`));
  // A does nothing until the scene is over (then it closes); B / the Stop button stops it.
  const ok = h('button.evook', { type: 'button', 'data-k': 'evook', on: { click: () => {
    if (evo && (evo.phase === 'done' || evo.phase === 'cancelled')) app.send({ t: 'evolution.close' });
  } } }, 'A');
  const stop = h('button.evostop', { type: 'button', 'data-k': 'evostop', on: { click: (ev) => { ev.stopPropagation(); evoBack(); } } }, 'B: Stop');
  evoEl.replaceChildren(h('div.evobg'), h('div.evostage', oldImg, newImg), text, stop, ok);
  evoEl.querySelectorAll('.nav-focus').forEach((x) => x.classList.remove('nav-focus'));
  ok.classList.add('nav-focus');
  if (e.phase === 'start') runEvolution(e, oldImg, newImg, text);
  else evoPhase(e);
}

async function runEvolution(e, oldImg, newImg, text) {
  const seq = e.seq;
  await sleep(1200);
  // Flash between the two silhouettes, faster each round.
  let period = 520;
  for (let i = 0; i < 12 && evoState && evoState.seq === seq && !evoState.stopped; i++) {
    oldImg.style.opacity = '1';
    newImg.style.opacity = '0';
    await sleep(period / 2);
    if (!evoState || evoState.stopped) break;
    oldImg.style.opacity = '0';
    newImg.style.opacity = '1';
    await sleep(period / 2);
    period = Math.max(90, period * 0.82);
  }
  if (!evoState || evoState.seq !== seq || evoState.stopped || evoState.answered) return;
  evoState.answered = true;
  app.send({ t: 'evolution.answer', proceed: true });
  text.replaceChildren(h('span', '...'));
}

function evoBack() {
  if (!evoEl || !evo) return;
  if (evoState && !evoState.answered && evo.phase === 'start') {
    evoState.answered = true;
    evoState.stopped = true;
    app.send({ t: 'evolution.answer', proceed: false });
    return;
  }
  if (evo.phase === 'done' || evo.phase === 'cancelled') app.send({ t: 'evolution.close' });
}

function evoPhase(e) {
  if (!evoEl) return;
  const oldImg = evoEl.querySelector('.evomon.old');
  const newImg = evoEl.querySelector('.evomon.new');
  const text = evoEl.querySelector('.evotext');
  const stop = evoEl.querySelector('.evostop');
  if (e.phase === 'done') {
    if (oldImg) oldImg.style.opacity = '0';
    if (newImg) {
      newImg.style.opacity = '1';
      newImg.classList.add('arrived');
    }
    const learned = (e.learned || []).map((m) => h('div', `${e.toName} learned ${m}!`));
    text.replaceChildren(h('div', `Congratulations! Your ${e.name || 'POKéMON'} evolved into ${e.toName || 'a new form'}!`), ...learned);
  } else if (e.phase === 'cancelled') {
    if (oldImg) oldImg.style.opacity = '1';
    if (newImg) newImg.style.opacity = '0';
    text.replaceChildren(h('div', `Huh? ${e.name || 'It'} stopped evolving!`));
  } else return;
  if (stop) stop.classList.add('hidden');
  const ok = evoEl.querySelector('.evook');
  if (ok) {
    ok.textContent = 'OK';
    ok.classList.add('shown', 'nav-focus');
  }
}

function closeEvolution() {
  if (evoEl) evoEl.remove();
  evoEl = null;
  evoState = null;
}

// ------------------------------------------------------------------ duel challenge dialog
const RULE_CHOICES = {
  levelCap: [[0, 'Off'], [50, '50'], [100, '100']],
  party: [[1, '1'], [3, '3'], [6, '6']],
  timer: [[0, 'Off'], [30, '30s'], [60, '60s'], [90, '90s']],
  doubles: [[false, '1v1'], [true, '2v2 double']],
};
let challengeRules = { levelCap: 0, party: 6, timer: 0, doubles: false };

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
    group('doubles', 'Format'),
    h('p.muted', describeRules(challengeRules) + '. Monsters above the cap fight at the cap; nothing carries over after the duel.'),
    h('div.row',
      h('button.go.grow', { type: 'button', 'data-k': 'send', on: { click: () => {
        panels.close('duelrules');
        const msg = { t: 'duel', name, levelCap: challengeRules.levelCap, party: challengeRules.party, timer: challengeRules.timer };
        if (challengeRules.doubles) msg.doubles = true;
        app.send(msg);
      } } }, '⚔ Challenge'),
      h('button.grow', { type: 'button', 'data-k': 'cancel', on: { click: () => panels.close('duelrules') } }, 'Cancel')),
  ]);
  draw();
  setFocus(p.body, p.body.querySelector('[data-k=send]'));
}
