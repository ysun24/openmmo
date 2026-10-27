// OpenMMO web client entry point: login, layout, main loop, input routing.
import { $, fmtMoney, toast } from './util.js';
import { Net, defaultWsUrl } from './net.js';
import { assets } from './assets.js';
import { World } from './world.js';
import { Renderer, VW, VH } from './render.js';
import { Input } from './input.js';
import { panels, navMove, navActivate } from './ui/panels.js';
import * as chat from './ui/chat.js';
import * as dialog from './ui/dialog.js';
import * as shop from './ui/shop.js';
import * as battle from './ui/battle.js';
import * as trade from './ui/trade.js';
import * as market from './ui/market.js';
import * as misc from './ui/misc.js';
import { music } from './audio.js';

const DIRS = ['up', 'down', 'left', 'right'];
const net = new Net(defaultWsUrl());
const send = (m) => net.send(m);
const world = new World(send);
const renderer = new Renderer($('#screen'));
const input = new Input();
let creds = null; // in-memory only (password never persisted)
let inGame = false;
let reconnecting = false;

// display settings (name tags), remembered like the audio ones
const DISPLAY = 'openmmo.display';
(() => {
  try {
    const d = JSON.parse(localStorage.getItem(DISPLAY) || '{}');
    if (typeof d.tags === 'boolean') renderer.tags = d.tags;
    if (typeof d.selfTag === 'boolean') renderer.selfTag = d.selfTag;
  } catch {}
})();
const saveDisplay = () => localStorage.setItem(DISPLAY, JSON.stringify({ tags: renderer.tags, selfTag: renderer.selfTag }));
music.init();
const assetsReady = assets.load();

const app = {
  net, send, world, assets, showFps: false,
  settings: {
    volume: () => music.volume,
    setVolume: (v) => music.setVolume(v),
    muted: () => music.muted,
    setMuted: (m) => music.setMuted(m),
    tags: () => renderer.tags,
    setTags: (v) => ((renderer.tags = v), saveDisplay()),
    selfTag: () => renderer.selfTag,
    setSelfTag: (v) => ((renderer.selfTag = v), saveDisplay()),
  },
  openChat: (pre) => chat.open(pre),
  setHighlight: (id) => (renderer.highlight = id),
  toggleFps: () => {
    app.showFps = !app.showFps;
    $('#fps').classList.toggle('hidden', !app.showFps);
  },
  logout: () => {
    creds = null;
    inGame = false;
    net.close();
    music.play(0, null);
    showLogin('Logged out.');
  },
};
const UIS = [chat, dialog, shop, battle, trade, market, misc];
UIS.forEach((u) => u.init(app));

// ------------------------------------------------------------------ device / layout
const touchDevice = matchMedia('(pointer: coarse)').matches || 'ontouchstart' in window || /[?&]touch=1/.test(location.search);
if (touchDevice) {
  document.body.classList.add('touch');
  $('#touch').classList.remove('hidden');
  input.bindTouch($('#touch'));
}

function layout() {
  const W = window.innerWidth, H = window.innerHeight;
  const dpr = window.devicePixelRatio || 1;
  const top = $('#topbar').offsetHeight || 34;
  const portrait = touchDevice && H >= W;
  document.body.classList.toggle('portrait', portrait);
  document.body.classList.toggle('landscape', touchDevice && !portrait);
  let cw, ch, k;
  if (portrait) {
    // full width; integer device-pixel scale when it is (nearly) as wide, else crisp fractional
    const minCtl = 200; // px kept for the controls below
    const fit = Math.min(W / VW, Math.max(160, H - top - minCtl) / VH);
    const ki = Math.floor((fit * dpr) + 1e-6);
    const s = ki / dpr >= fit * 0.94 ? ki / dpr : fit;
    cw = VW * s;
    ch = VH * s;
    // snap the CSS size to whole device pixels
    cw = Math.round(cw * dpr) / dpr;
    ch = Math.round(ch * dpr) / dpr;
    k = s * dpr;
  } else {
    const availH = H - top;
    // integer scale in DEVICE pixels -> crisp pixels at any devicePixelRatio
    k = Math.max(1, Math.floor(Math.min((W * dpr) / VW, (availH * dpr) / VH)));
    cw = (VW * k) / dpr;
    ch = (VH * k) / dpr;
  }
  const stage = $('#stage');
  const sh = portrait ? ch : H - top;
  stage.style.bottom = portrait ? 'auto' : '0';
  stage.style.height = sh + 'px';
  const left = Math.round(((W - cw) / 2) * dpr) / dpr;
  const topOff = portrait ? 0 : Math.round(((sh - ch) / 2) * dpr) / dpr;
  for (const el of [$('#screen'), $('#ov')]) {
    el.style.width = cw + 'px';
    el.style.height = ch + 'px';
    el.style.left = left + 'px';
    el.style.top = topOff + 'px';
  }
  $('#ov').style.setProperty('--u', k / dpr + 'px');
  if (portrait) $('#touch').style.top = top + ch + 'px';
  else $('#touch').style.top = '';
  app.layout = { k, dpr, cw, ch, portrait };
}
window.addEventListener('resize', layout);
window.addEventListener('orientationchange', () => setTimeout(layout, 120));
// block pinch / double-tap zoom on the game surfaces
for (const ev of ['gesturestart', 'gesturechange']) document.addEventListener(ev, (e) => e.preventDefault());
$('#stage').addEventListener('touchmove', (e) => e.preventDefault(), { passive: false });
document.addEventListener('dblclick', (e) => e.preventDefault(), { passive: false });
{
  // iOS ignores user-scalable=no: swallow the second tap of a quick double tap outside text fields
  let lastTap = 0;
  document.addEventListener('touchend', (e) => {
    const t = e.target;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT')) return;
    const now = e.timeStamp;
    if (now - lastTap < 320 && e.cancelable) e.preventDefault();
    lastTap = now;
  }, { passive: false });
}

// ------------------------------------------------------------------ connection + login
function setConn(s) {
  const c = $('#conn');
  c.classList.toggle('on', s === 'online');
  c.classList.toggle('wait', s === 'connecting');
  $('#conn-text').textContent = s;
  $('#btn-reconnect').classList.toggle('hidden', s !== 'offline' || !creds);
}
net.on('status', setConn);
net.on('rtt', (r) => ($('#rtt').textContent = Math.round(r) + ' ms'));
net.on('error', (m) => m && m.msg && console.warn('gateway error:', m.msg));
net.on('close', () => {
  if (inGame && creds && !reconnecting) {
    toast('Disconnected. Reconnecting…');
    setTimeout(() => reconnect(), 1500);
  }
});

function showLogin(msg, err = false) {
  $('#game').classList.add('hidden');
  $('#login').classList.remove('hidden');
  const st = $('#login-status');
  st.textContent = msg || '';
  st.classList.toggle('err', err);
  $('#login-btn').disabled = false;
}
function showGame() {
  $('#login').classList.add('hidden');
  $('#game').classList.remove('hidden');
  layout();
}

async function doLogin() {
  await assetsReady; // map keys resolve through the manifest
  if (net.status !== 'online') await net.connect();
  net.resetState();
  world.reset();
  send({ t: 'login', ...creds });
}

async function reconnect() {
  if (!creds || reconnecting) return;
  reconnecting = true;
  setConn('connecting');
  try {
    await doLogin();
  } catch {
    toast('Reconnect failed — tap Reconnect to retry.');
  } finally {
    reconnecting = false;
  }
}
$('#btn-reconnect').addEventListener('click', () => reconnect());

net.on('login', (m) => {
  if (m.ok) {
    inGame = true;
    showGame();
    toast(`Welcome, ${m.name}!`);
  } else {
    inGame = false;
    showLogin('Login failed: ' + (m.error || 'unknown error'), true);
  }
});

(() => {
  const saved = JSON.parse(localStorage.getItem('openmmo.login') || '{}');
  if (saved.user) $('#user').value = saved.user;
  if (saved.name) $('#cname').value = saved.name;
  if (saved.region !== undefined) $('#region').value = String(saved.region);
})();
$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const user = $('#user').value.trim();
  const pass = $('#pass').value;
  const name = $('#cname').value.trim() || user;
  const region = +$('#region').value;
  creds = { user, pass, name, region };
  localStorage.setItem('openmmo.login', JSON.stringify({ user, name, region }));
  $('#login-btn').disabled = true;
  const st = $('#login-status');
  st.classList.remove('err');
  st.textContent = 'Connecting…';
  try {
    await doLogin();
    st.textContent = 'Logging in…';
  } catch {
    showLogin('Cannot reach the gateway.', true);
  }
});

// ------------------------------------------------------------------ state fan-out
net.on('state', (state, changed) => {
  const now = performance.now();
  if (changed.includes('world')) {
    world.onServer(state.world, now);
    const m = state.world && state.world.map;
    $('#mapname').textContent = m ? m.name : '';
  }
  if (changed.includes('ui') && state.ui) $('#money').textContent = fmtMoney(state.ui.money);
  for (const u of UIS) u.update(state, changed);
  document.body.classList.toggle('busy', battle.isOpen() || dialog.isOpen());
});

// ------------------------------------------------------------------ input routing
function canMove() {
  const s = net.state;
  return inGame && !panels.any() && !dialog.isOpen() && !battle.isOpen() && !(s.ui && (s.ui.locked || s.ui.shop))
    && !(document.activeElement && document.activeElement.id === 'chat-input');
}

function onPress(btn, repeat = false) {
  if (!inGame) return;
  if (btn === 'chat') return repeat ? null : chat.toggle();
  if (btn === 'menu') return panels.isOpen('menu') ? panels.close('menu') : misc.openMenu();
  if (btn === 'blur') {
    if (document.body.classList.contains('chat-open')) chat.close();
    return;
  }
  const isDir = DIRS.includes(btn);
  if (repeat && !isDir) return;
  const top = panels.top();
  if (top) {
    if (top.name === 'shop') {
      if (btn === 'up' || btn === 'down') return send({ t: 'cursor', delta: btn === 'up' ? -1 : 1 });
      if (btn === 'a') return shop.confirmCursor();
      if (btn === 'b') return send({ t: 'shop.close' });
    }
    if (isDir) return navMove(top.body, btn);
    if (btn === 'a') return navActivate(top.body);
    if (btn === 'b') return panels.back();
    return;
  }
  if (battle.isOpen()) {
    if (isDir) return navMove(battle.root(), btn);
    if (btn === 'a') return navActivate(battle.root());
    if (btn === 'b') return battle.back();
    return;
  }
  if (dialog.isOpen()) {
    if (isDir && dialog.hasChoices()) return send({ t: 'cursor', delta: btn === 'up' || btn === 'left' ? -1 : 1 });
    if (btn === 'a') return send({ t: 'a' });
    if (btn === 'b') return send({ t: 'b' });
    return;
  }
  if (btn === 'a' && !repeat) {
    const [x, y] = world.facingTile();
    const e = world.entityAt(x, y);
    if (e && e.kind === 'player') return misc.openPlayer(e);
    return send({ t: 'a' });
  }
  if (btn === 'b' && document.body.classList.contains('chat-open')) chat.close();
}
input.on('press', (b) => onPress(b));
input.on('repeat', (b) => onPress(b, true));

// tap / click on the world: players -> action menu, adjacent NPC -> talk
$('#screen').addEventListener('pointerdown', (e) => {
  if (!canMove() || !world.hasSelf) return;
  const { wx, wy } = renderer.pickTile(e.clientX, e.clientY);
  let hit = null;
  for (const ent of world.drawList) {
    if (ent === world.self) continue;
    if (wx >= ent.px && wx < ent.px + 16 && wy >= ent.py - 16 && wy < ent.py + 16) hit = ent;
  }
  if (!hit) return;
  if (hit.kind === 'player') return misc.openPlayer(hit);
  const dx = hit.x - world.self.x, dy = hit.y - world.self.y;
  if (Math.abs(dx) + Math.abs(dy) === 1 && !world.self.moving) {
    const dir = dx === 1 ? 'right' : dx === -1 ? 'left' : dy === 1 ? 'down' : 'up';
    if (world.self.facing !== dir) {
      world.self.facing = dir;
      send({ t: 'face', dir });
    }
    send({ t: 'a' });
  }
});

// ------------------------------------------------------------------ music + loading bar
let musicCheck = 0;
/** The song the GBA would play now: battle theme / victory jingle in battles, else the map's. */
function wantedSong() {
  if (!inGame) return [0, null, 0.8];
  const s = net.state;
  const region = world.map ? world.map.region : 0;
  const bm = (assets.manifest && assets.manifest.battleMusic && assets.manifest.battleMusic[region]) || {};
  const b = s.battle;
  if (b) {
    if (b.phase === 'ENDED') {
      if (b.result === 'VICTORY') return [region, b.wild ? bm.victoryWild : bm.victoryTrainer, 0.2];
      if (b.result === 'CAUGHT') return [region, bm.caught || bm.victoryWild, 0.2];
      return [region, null, 0.6];
    }
    return [region, b.wild ? bm.wild : bm.trainer, 0.15];
  }
  const info = world.res && world.res.info;
  return [region, info ? info.music : null, 0.9];
}
function updateMusic(now) {
  if (now - musicCheck < 200) return;
  musicCheck = now;
  const [r, name, fade] = wantedSong();
  music.play(r, name || null, fade);
}

let barShown = false;
function updateLoadBar() {
  let f = assets.progress.manifest < 1 ? assets.progress.manifest * 0.5 : null;
  if (f === null) {
    const waiting = !inGame ? false : !world.hasSelf || !!world.fadeWaiting || !(world.res && world.res.ready);
    const lf = assets.loadFraction();
    f = waiting ? (lf === null ? 0.9 : 0.5 + lf * 0.5) : null;
  }
  const show = f !== null;
  if (show !== barShown) {
    barShown = show;
    $('#loadbar').classList.toggle('hidden', !show);
  }
  if (show) $('#loadbar i').style.width = Math.round(Math.max(0.04, f) * 100) + '%';
}

// ------------------------------------------------------------------ main loop + fps probe
const fpsBuf = new Float32Array(120);
const workBuf = new Float32Array(120);
let fpsI = 0, fpsN = 0, fpsShown = 0;
const fps = { fps: 0, avgMs: 0, maxMs: 0, workAvgMs: 0, workMaxMs: 0, frames: 0 };
window.__fps = fps;
window.__game = { world, net, input, renderer, assets, music };

let last = performance.now();
function frame(now) {
  const dt = Math.min(100, now - last);
  last = now;
  if (inGame) {
    const considered = !world.self.moving && world.turnWait <= 0;
    world.update(dt, { dir: input.dir, run: input.run, enabled: canMove() }, now);
    if (considered) input.consumeTap();
    if (!battle.isOpen()) renderer.draw(world, now);
  }
  updateMusic(now);
  updateLoadBar();
  const work = performance.now() - now;
  fpsBuf[fpsI] = dt;
  workBuf[fpsI] = work;
  fpsI = (fpsI + 1) % fpsBuf.length;
  fpsN = Math.min(fpsBuf.length, fpsN + 1);
  fps.frames++;
  if (now - fpsShown > 500) {
    fpsShown = now;
    let s = 0, m = 0, ws = 0, wm = 0;
    for (let i = 0; i < fpsN; i++) {
      s += fpsBuf[i];
      m = Math.max(m, fpsBuf[i]);
      ws += workBuf[i];
      wm = Math.max(wm, workBuf[i]);
    }
    fps.avgMs = s / fpsN;
    fps.fps = 1000 / fps.avgMs;
    fps.maxMs = m;
    fps.workAvgMs = ws / fpsN;
    fps.workMaxMs = wm;
    if (app.showFps) $('#fps').textContent = `${fps.fps.toFixed(0)} fps · ${fps.workAvgMs.toFixed(2)} ms`;
  }
  requestAnimationFrame(frame);
}

assetsReady.then(() => {
  layout();
});
requestAnimationFrame(frame);
layout();
if (/[?&]fps=1/.test(location.search)) app.toggleFps();
