// OpenMMO web client entry point: login, layout, main loop, input routing.
import { $, h, fmtMoney, toast } from './util.js';
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
import * as progress from './ui/progress.js';
import * as field from './ui/field.js';
import * as social from './ui/social.js';
import * as typer from './ui/typewriter.js';
import { music } from './audio.js';
import { fonts, setFrameStyle } from './font.js';
import { settings, ACTIONS, keyLabel } from './settings.js';
import { KEYS } from './input.js';
import { sfx } from './sfx.js';

const DIRS = ['up', 'down', 'left', 'right'];
const net = new Net(defaultWsUrl());
const send = (m) => net.send(m);
const world = new World(send);
const renderer = new Renderer($('#screen'));
const input = new Input();
let creds = null; // in-memory only (password never persisted)
let inGame = false;
let reconnecting = false;

// remember-me session: the login server's token, never the password (localStorage openmmo.session)
const SESSION = 'openmmo.session';
const loadSession = () => {
  try {
    const s = JSON.parse(localStorage.getItem(SESSION) || 'null');
    return s && s.user && s.token ? s : null;
  } catch {
    return null;
  }
};
let session = loadSession();
const saveSession = (s) => {
  session = s;
  if (s) localStorage.setItem(SESSION, JSON.stringify(s));
  else localStorage.removeItem(SESSION);
};

/** Keyboard bindings from the settings (Settings → Controls) into input.js's key table. */
function applyKeys() {
  for (const k of Object.keys(KEYS)) delete KEYS[k];
  const map = settings.keys();
  for (const a of Object.keys(ACTIONS)) for (const code of map[a] || []) KEYS[code] = a;
}
applyKeys();
settings.on((k, v) => {
  if (k === 'keys') applyKeys();
  if (k === 'frame') setFrameStyle(v);
  if (k === 'sfxVolume') sfx.setVolume(v);
});
setFrameStyle(settings.get('frame'));

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
    setMuted: (m) => (music.setMuted(m), sfx.setMuted(m)),
    sfxVolume: () => settings.get('sfxVolume'),
    setSfxVolume: (v) => settings.set('sfxVolume', v),
    tags: () => renderer.tags,
    setTags: (v) => ((renderer.tags = v), saveDisplay()),
    selfTag: () => renderer.selfTag,
    setSelfTag: (v) => ((renderer.selfTag = v), saveDisplay()),
    store: settings,
  },
  region: () => (world.map ? world.map.region : 0),
  sfx,
  showHelp: (onboard = false) => showHelp(onboard),
  openChat: (pre) => chat.open(pre),
  setHighlight: (id) => (renderer.highlight = id),
  toggleFps: () => {
    app.showFps = !app.showFps;
    $('#fps').classList.toggle('hidden', !app.showFps);
  },
  logout: () => {
    creds = null;
    saveSession(null);
    inGame = false;
    stopReconnect();
    net.close();
    music.play(0, null);
    showLogin('Logged out.');
  },
};
const UIS = [chat, dialog, shop, battle, trade, market, misc, progress, field, social];
UIS.forEach((u) => u.init(app));
typer.init();

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
    cw = Math.floor(cw * dpr) / dpr;
    ch = Math.floor(ch * dpr) / dpr;
    k = s * dpr;
  } else if (touchDevice) {
    // landscape phone: centred between the side controls (they may overlap the edge a little)
    const availH = H - top;
    const side = Math.min(170, W * 0.2);
    const fit = Math.min(availH / VH, Math.max(VW, W - 2 * side) / VW);
    const ki = Math.floor(fit * dpr + 1e-6);
    const s = ki / dpr >= fit * 0.94 ? ki / dpr : fit;
    cw = Math.floor(VW * s * dpr) / dpr;
    ch = Math.floor(VH * s * dpr) / dpr;
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
  // HTML panels: ~2 CSS px per font pixel, snapped to whole device pixels (crisp glyphs)
  const pu = Math.max(1, Math.round((Math.min(W, H) < 360 ? 1.5 : 2) * dpr)) / dpr;
  document.documentElement.style.setProperty('--pu', pu + 'px');
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
  $('#conn').setAttribute('aria-label', 'Connection: ' + s);
  $('#btn-reconnect').classList.toggle('hidden', s !== 'offline' || !(creds || session) || inGame);
}
net.on('status', setConn);
net.on('rtt', (r) => ($('#rtt').textContent = Math.round(r) + ' ms'));
net.on('error', (m) => m && m.msg && console.warn('gateway error:', m.msg));
net.on('close', () => onDisconnect());

// ------------------------------------------------------------------ splash / login screens
function splash(msg, frac = null, err = false) {
  const sp = $('#splash');
  sp.classList.remove('hidden');
  sp.classList.toggle('err', err);
  if (msg !== null) $('#splash-status').textContent = msg;
  if (frac !== null) {
    $('#splash .sbar i').style.width = Math.round(frac * 100) + '%';
    $('#splash .sbar').setAttribute('aria-valuenow', String(Math.round(frac * 100)));
  }
}
const hideSplash = () => {
  $('#splash').classList.add('hidden');
  $('#splash').setAttribute('aria-busy', 'false');
};

function showLogin(msg, err = false) {
  if (!performance.getEntriesByName('openmmo:login-ready').length) performance.mark('openmmo:login-ready');
  hideSplash();
  $('#game').classList.add('hidden');
  $('#login').classList.remove('hidden');
  const st = $('#login-status');
  st.textContent = msg || '';
  st.classList.toggle('err', err);
  $('#login-btn').disabled = false;
  setTimeout(() => ($('#user').value ? $('#pass') : $('#user')).focus({ preventScroll: true }), 0);
}
function showGame() {
  hideSplash();
  $('#login').classList.add('hidden');
  $('#game').classList.remove('hidden');
  layout();
  $('#screen').focus({ preventScroll: true });
}

/** login message: the remember-me token when we have one (resume), else the typed password. */
let pendingLogin = null; // {viaToken, remember}
async function doLogin(viaToken) {
  await assetsReady; // map keys resolve through the manifest
  if (net.status !== 'online') await net.connect();
  net.resetState();
  world.reset();
  if (viaToken) {
    const s = session;
    pendingLogin = { viaToken: true, remember: true };
    send({ t: 'login', user: s.user, token: s.token, name: s.name, region: s.region });
  } else {
    pendingLogin = { viaToken: false, remember: !!creds.remember };
    send({ t: 'login', user: creds.user, pass: creds.pass, name: creds.name, region: creds.region, gender: creds.gender || 0, remember: !!creds.remember });
  }
}

// ------------------------------------------------------------------ connection lost -> auto-reconnect
let retryT = 0, retryN = 0, retryAt = 0, wasInGame = false;
function banner(text, ok = false) {
  const b = $('#connbanner');
  if (text === null) return b.classList.add('hidden');
  b.classList.remove('hidden');
  b.classList.toggle('ok', ok);
  $('#connbanner-text').textContent = text;
  $('#connbanner-retry').classList.toggle('hidden', ok);
}
function stopReconnect() {
  clearTimeout(retryT);
  retryT = 0;
  retryN = 0;
  reconnecting = false;
  document.body.classList.remove('offline');
  banner(null);
}
function onDisconnect() {
  if (!inGame && !wasInGame) return;
  if (inGame) {
    wasInGame = true;
    inGame = false;
    input.releaseAll();
  }
  if (!session && !creds) return showLogin('Disconnected.');
  document.body.classList.add('offline');
  scheduleRetry();
}
function scheduleRetry(now = false) {
  clearTimeout(retryT);
  // 1 s, 2 s, 4 s, 8 s, then every 15 s; immediately when the browser says it is back online
  const delay = now ? 0 : Math.min(15000, 1000 * 2 ** Math.min(retryN, 4));
  retryAt = performance.now() + delay;
  retryT = setTimeout(reconnect, delay);
  tickBanner();
}
function tickBanner() {
  if (!retryT || reconnecting) return;
  const s = Math.max(0, Math.ceil((retryAt - performance.now()) / 1000));
  banner(navigator.onLine === false ? `Connection lost (offline) — retrying in ${s} s` : `Connection lost — reconnecting in ${s} s`);
}
setInterval(tickBanner, 500);
async function reconnect() {
  if (reconnecting || inGame) return;
  retryT = 0;
  reconnecting = true;
  retryN++;
  banner('Reconnecting…');
  setConn('connecting');
  try {
    await doLogin(!!session);
  } catch {
    reconnecting = false;
    scheduleRetry();
  }
}
$('#btn-reconnect').addEventListener('click', () => reconnect());
$('#connbanner-retry').addEventListener('click', () => scheduleRetry(true));
window.addEventListener('online', () => wasInGame && !inGame && scheduleRetry(true));
window.addEventListener('offline', () => {
  if (!inGame) return;
  net.close();
  onDisconnect();
});
// heartbeat: pongs come every 5 s; a socket silent for 12 s is dead even if the browser has not noticed
setInterval(() => {
  if (inGame && net.status === 'online' && performance.now() - net.lastRx > 12000) {
    console.warn('connection silent for 12 s, reconnecting');
    net.close();
    onDisconnect();
  }
}, 2000);
$('#btn-menu').addEventListener('click', () => onPress('menu'));

net.on('login', (m) => {
  const p = pendingLogin || {};
  pendingLogin = null;
  if (m.ok) {
    const resumed = wasInGame;
    inGame = true;
    wasInGame = false;
    reconnecting = false;
    clearTimeout(retryT);
    retryT = 0;
    retryN = 0;
    document.body.classList.remove('offline');
    if (m.token && p.remember) {
      const who = p.viaToken ? session : creds;
      saveSession({ user: who.user, name: who.name, region: who.region, token: m.token });
    }
    if (creds) creds.pass = p.viaToken ? creds.pass : null; // drop the password once logged in
    showGame();
    if (resumed) {
      banner('Reconnected', true);
      setTimeout(() => banner(null), 1800);
    } else toast(`Welcome, ${m.name}!`);
    maybeOnboard();
  } else {
    inGame = false;
    reconnecting = false;
    const tokenRejected = p.viaToken && /INVALID_SAVED|TOKEN|BAD_CREDENTIALS/.test(m.error || '');
    if (tokenRejected) saveSession(null);
    if (wasInGame && !tokenRejected && (session || (creds && creds.pass))) return scheduleRetry();
    wasInGame = false;
    stopReconnect();
    showLogin(tokenRejected ? 'Your saved login expired — please log in again.' : 'Login failed: ' + (m.error || 'unknown error'), true);
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
  const remember = $('#remember').checked;
  const gender = +$('#gender').value;
  creds = { user, pass, name, region, remember, gender };
  saveSession(null);
  localStorage.setItem('openmmo.login', JSON.stringify({ user, name, region }));
  $('#login-btn').disabled = true;
  const st = $('#login-status');
  st.classList.remove('err');
  st.textContent = 'Connecting…';
  try {
    await doLogin(false);
    st.textContent = 'Logging in…';
  } catch {
    showLogin('Cannot reach the gateway.', true);
  }
  $('#pass').value = '';
});

// first load: assets + fonts behind the loading screen, then resume a remembered session or log in
async function boot() {
  splash('Loading game data…', 0.02);
  const prog = setInterval(() => splash(null, Math.min(0.9, 0.05 + assets.progress.manifest * 0.8)), 50);
  await assetsReady;
  clearInterval(prog);
  splash('Loading fonts…', 0.9);
  await Promise.race([fonts.load(), new Promise((r) => setTimeout(r, 4000))]);
  splash(null, 1);
  $('#splash').classList.add('ready');
  if (!assets.ok) return splash('Could not load the game data. Reload to try again.', 1, true);
  if (session) {
    splash(`Resuming as ${session.name || session.user}…`, 1);
    try {
      await doLogin(true);
    } catch {
      showLogin('Cannot reach the gateway.', true);
    }
  } else showLogin('');
}

// ------------------------------------------------------------------ state fan-out
net.on('state', (state, changed) => {
  const now = performance.now();
  if (changed.includes('world')) {
    const prevKey = world.mapKey, prevInfo = world.res && world.res.info;
    world.onServer(state.world, now);
    if (state.world) world.self.female = !!state.world.female;
    // warp sounds: into a building through a door (SE_DOOR), back out over the mat (SE_EXIT)
    if (prevKey && world.mapKey !== prevKey && world.fade === 1 && prevInfo) {
      const info = assets.manifest && assets.manifest.maps[world.mapKey];
      const outdoor = (i) => !!(i && i.connections && i.connections.length);
      if (outdoor(prevInfo) && info && !outdoor(info)) sfx.play('door', app.region());
      else if (!outdoor(prevInfo) && outdoor(info)) sfx.play('exit', app.region());
    }
    const m = state.world && state.world.map;
    $('#mapname').textContent = m ? m.name : '';
    if (m) document.body.classList.toggle('region-1', m.region === 1);
  }
  if (changed.includes('ui') && state.ui) $('#money').textContent = fmtMoney(state.ui.money);
  for (const u of UIS) u.update(state, changed);
  stateSounds(state, changed);
  document.body.classList.toggle('busy', battle.isOpen() || dialog.isOpen());
});

// effects that follow the state: level up / shiny in battles, item get and healing fanfares
let lastFx = -1, battleWasOpen = false, lastDialogKey = '', partyFull = null;
function stateSounds(state, changed) {
  const r = app.region();
  if (changed.includes('battle')) {
    const b = state.battle;
    if (b && !battleWasOpen && b.foe && b.foe.shiny) setTimeout(() => sfx.play('shiny', r), 900);
    battleWasOpen = !!b;
    for (const f of (b && b.fx) || []) {
      if (f.n <= lastFx) continue;
      lastFx = f.n;
      if (f.k === 'level' && f.side !== 'foe') sfx.play('levelUp', r, 1000);
      if (f.k === 'switch' && f.side === 'foe' && f.mon && f.mon.shiny) setTimeout(() => sfx.play('shiny', r), 500);
    }
  }
  if (changed.includes('ui')) {
    const d = state.ui && state.ui.dialog;
    const text = d ? (d.lines || []).join(' ') : '';
    if (text !== lastDialogKey) {
      lastDialogKey = text;
      // "<PLAYER> obtained a POTION!", "received", "found one ..." (the field item fanfare)
      if (/\b(obtained|received|found)\b.+[!.]$/i.test(text) && !/\b(didn't|did not|nothing)\b/i.test(text)) sfx.play('itemGet', r, 1500);
    }
  }
  if (changed.includes('party') && !state.battle) {
    const p = state.party || [];
    const full = p.length > 0 && p.every((m) => m.hp >= m.maxHp && (m.moves || []).every((mv) => mv.pp >= mv.maxPp));
    // Nurse Joy (and the PC heal): the whole party back to full while she talks -> MUS_HEAL
    if (partyFull === false && full && state.ui && state.ui.dialog) sfx.play('heal', r, 3000);
    partyFull = full;
  }
}

// ------------------------------------------------------------------ input routing
function canMove() {
  const s = net.state;
  return inGame && !panels.any() && !dialog.isOpen() && !battle.isOpen() && !(s.ui && (s.ui.locked || s.ui.shop))
    && !(document.activeElement && document.activeElement.id === 'chat-input');
}

/** SE_SELECT when the menu cursor actually moved (GBA menus beep on every cursor step). */
function cursorSound(root, move) {
  const before = root.querySelector('.nav-focus');
  move();
  if (root.querySelector('.nav-focus') !== before) sfx.play('select', app.region());
}

function onPress(btn, repeat = false) {
  if (!inGame) return;
  if (helpOpen()) return btn === 'a' || btn === 'b' || btn === 'menu' ? closeHelp() : undefined;
  if (btn === 'chat') return repeat ? null : chat.toggle();
  if (btn === 'menu') {
    if (panels.isOpen('menu')) return panels.close('menu');
    sfx.play('winOpen', app.region());
    return misc.openMenu();
  }
  if (btn === 'blur') {
    if (document.body.classList.contains('chat-open')) chat.close();
    return;
  }
  const isDir = DIRS.includes(btn);
  if (repeat && !isDir) return;
  const top = panels.top();
  if (top) {
    // The shop's B steps back out of the quantity picker before it closes the shop.
    if (top.name === 'shop' && btn === 'b') return shop.back();
    if (isDir) return cursorSound(top.body, () => navMove(top.body, btn));
    if (btn === 'a') return sfx.play('select', app.region()), navActivate(top.body);
    if (btn === 'b') return panels.back();
    return;
  }
  if (battle.isOpen()) {
    if (isDir) return cursorSound(battle.root(), () => navMove(battle.root(), btn));
    if (btn === 'a') return sfx.play('select', app.region()), navActivate(battle.root());
    if (btn === 'b') return battle.back();
    return;
  }
  if (dialog.isOpen()) {
    // A / B while the page is still printing completes it (GBA), they do not advance yet
    if ((btn === 'a' || btn === 'b') && typer.busy()) return typer.finish();
    if (isDir && dialog.hasChoices()) return sfx.play('select', app.region()), send({ t: 'cursor', delta: btn === 'up' || btn === 'left' ? -1 : 1 });
    if (btn === 'a') return dialog.hasChoices() && sfx.play('select', app.region()), send({ t: 'a' });
    if (btn === 'b') return send({ t: 'b' });
    return;
  }
  if (btn === 'a' && !repeat) {
    const [x, y] = world.facingTile();
    const e = world.entityAt(x, y);
    if (e && e.kind === 'player') return misc.openPlayer(e);
    return send({ t: 'a' });
  }
  if (!repeat && field.onPress(btn)) return;
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

$('#dialog').addEventListener('click', (e) => {
  if (!typer.busy()) return;
  e.stopPropagation();
  typer.finish();
}, true);

// ------------------------------------------------------------------ accessibility
// Panels: real DOM focus follows the panel stack (screen readers announce them), Tab moves inside
// them instead of toggling the menu, closing returns focus to the game.
new MutationObserver((recs) => {
  for (const r of recs) {
    for (const n of r.addedNodes) {
      if (!(n instanceof HTMLElement) || !n.classList.contains('panel')) continue;
      n.setAttribute('aria-modal', 'true');
      const h2 = n.querySelector('header h2');
      if (h2) {
        h2.id = h2.id || 'ph-' + n.dataset.panel;
        n.setAttribute('aria-labelledby', h2.id);
      }
      requestAnimationFrame(() => {
        if (!n.isConnected || n.contains(document.activeElement)) return;
        const f = n.querySelector('.nav-focus') || n.querySelector('.body button:not(:disabled), .body input, .body select') || n.querySelector('button');
        if (f) f.focus({ preventScroll: true });
      });
    }
    if (r.removedNodes.length && !$('#panels').children.length && inGame && !document.activeElement.closest('#chat')) {
      $('#screen').focus({ preventScroll: true });
    } else if (r.removedNodes.length) {
      const top = $('#panels').lastElementChild;
      if (top && !top.contains(document.activeElement)) {
        const f = top.querySelector('.nav-focus') || top.querySelector('button');
        if (f) f.focus({ preventScroll: true });
      }
    }
  }
}).observe($('#panels'), { childList: true });
// keyboard focus and the arrow-key cursor (.nav-focus) stay the same element
document.addEventListener('focusin', (e) => {
  const p = e.target.closest && e.target.closest('.panel .body, #battle');
  if (p && e.target.matches('button, input, select')) {
    p.querySelectorAll('.nav-focus').forEach((x) => x !== e.target && x.classList.remove('nav-focus'));
    e.target.classList.add('nav-focus');
  }
});
window.addEventListener('keydown', (e) => {
  // Esc on a slider / checkbox closes the panel like everywhere else (they are not text fields)
  if (e.code === 'Escape' && document.activeElement && document.activeElement.matches('input[type=range], input[type=checkbox]') &&
      document.activeElement.closest('#panels')) {
    e.stopImmediatePropagation();
    e.preventDefault();
    panels.back();
    return;
  }
  // Enter on a focused control in a panel activates it (native button behaviour), not the chat
  if (e.code === 'Enter' && document.activeElement && document.activeElement.matches('button, input[type=range], select') &&
      document.activeElement.closest('#panels, #help, #login, #connbanner, #battle, #dialog')) {
    e.stopImmediatePropagation();
    return;
  }
  if (e.code === 'Tab' && document.activeElement && document.activeElement.closest('#panels, #help, #login, #connbanner, #battle')) {
    e.stopImmediatePropagation();
    // keep focus inside the topmost panel (modal)
    const top = document.activeElement.closest('.panel, #help');
    if (top) {
      const f = [...top.querySelectorAll('button:not(:disabled), input, select, [tabindex="0"]')].filter((x) => x.offsetParent !== null);
      if (f.length) {
        const i = f.indexOf(document.activeElement);
        const j = e.shiftKey ? (i <= 0 ? f.length - 1 : i - 1) : (i === f.length - 1 ? 0 : i + 1);
        e.preventDefault();
        f[j].focus();
      }
    }
  }
}, true);
$('#region').addEventListener('change', (e) => document.body.classList.toggle('region-1', e.target.value === '1'));
document.body.classList.toggle('region-1', $('#region').value === '1' || (session && session.region === 1));

// ------------------------------------------------------------------ help + mobile onboarding
const helpOpen = () => !$('#help').classList.contains('hidden');
let helpReturn = null;
function closeHelp() {
  $('#help').classList.add('hidden');
  $('#help').replaceChildren();
  settings.set('helpSeen', true);
  (helpReturn && helpReturn.isConnected ? helpReturn : $('#screen')).focus({ preventScroll: true });
}
function showHelp(onboard) {
  const el = $('#help');
  helpReturn = document.activeElement;
  const keys = settings.keys();
  const kl = (a) => (keys[a] || []).map((c) => keyLabel(c)).join(' / ');
  const card = h('div.card',
    h('h2#help-title', touchDevice ? 'Touch controls' : 'Controls'),
    touchDevice && onboard ? h('p', 'Welcome! The labels show what each button does. Tap a player to duel, trade or chat.') : touchDevice ? h('dl',
      h('dt', 'D-pad'), h('dd', 'Walk. Slide your thumb to turn.'),
      h('dt', 'A'), h('dd', 'Talk, read signs, confirm.'),
      h('dt', 'B'), h('dd', 'Back, cancel. Speeds up text.'),
      h('dt', 'RUN'), h('dd', 'Toggle running shoes.'),
      h('dt', 'MENU'), h('dd', 'POKéMON, Bag, Market, Settings…'),
      h('dt', 'CHAT'), h('dd', 'Talk to other players.'),
      h('dt', 'Tap a player'), h('dd', 'Duel, trade or chat with them.'))
      : h('dl',
        h('dt', kl('up') + ' ' + kl('left') + ' ' + kl('down') + ' ' + kl('right')), h('dd', 'Walk'),
        h('dt', kl('a')), h('dd', 'A — talk, confirm'),
        h('dt', kl('b')), h('dd', 'B — back, cancel'),
        h('dt', kl('run')), h('dd', 'Run (hold)'),
        h('dt', kl('menu')), h('dd', 'Menu'),
        h('dt', kl('chat')), h('dd', 'Chat ( / for commands)'),
        h('dt', 'Click a player'), h('dd', 'Duel, trade or chat')),
    onboard ? null : h('p.muted', 'You can change keys, text speed and sound in Menu → Settings.'),
    h('button.primary', { type: 'button', on: { click: closeHelp } }, 'Got it!'));
  el.replaceChildren(card);
  el.classList.toggle('onboard', !!onboard);
  el.classList.remove('hidden');
  $('#toast').classList.add('hidden');
  if (onboard && touchDevice) {
    // callouts over the real controls
    const at = (sel, label) => {
      const t = $(sel);
      if (!t || !t.offsetParent) return;
      const r = t.getBoundingClientRect();
      const c = h('span.callout', { style: `left:${r.left + r.width / 2}px;top:${r.top - 4}px` }, label);
      el.append(c);
      const half = c.offsetWidth / 2 + 4;
      c.style.left = Math.max(half, Math.min(innerWidth - half, r.left + r.width / 2)) + 'px';
    };
    at('#dpad', 'Walk');
    at('#t-a', 'A: talk / OK');
    at('#t-b', 'B: back');
    at('#t-run', 'Run');
    at('#t-menu', 'Menu');
    at('#t-chat', 'Chat');
  }
  setTimeout(() => card.querySelector('button').focus({ preventScroll: true }), 0);
}
function maybeOnboard() {
  if (settings.get('helpSeen')) return;
  if (!touchDevice) return void setTimeout(() => toast('Tip: Menu → Help shows the controls.', 4000), 1500);
  setTimeout(() => inGame && !helpOpen() && showHelp(true), 900);
}

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
window.__game = { world, net, input, renderer, assets, music, sfx, settings, fonts };

let last = performance.now();
let worldPainted = false;
function frame(now) {
  const dt = Math.min(100, now - last);
  last = now;
  if (inGame) {
    const considered = !world.self.moving && world.turnWait <= 0;
    world.update(dt, { dir: input.dir, run: input.run, enabled: canMove() }, now);
    if (considered) input.consumeTap();
    // SE_WALL_HIT while walking into something (GBA: once per collision bump, ~every 16 frames)
    if (input.dir && world.hasSelf && !world.self.moving && world.self.facing === input.dir && canMove()) {
      const [bx, by] = world.facingTile();
      if (world.res && world.res.col && world.blocked(bx, by) && !world.isWarp(bx, by) && !world.isWarp(world.self.x, world.self.y)) sfx.play('bump', app.region(), 270);
    }
    if (!battle.isOpen()) renderer.draw(world, now);
    // first frame that shows the world (performance budget: first meaningful paint)
    if (!worldPainted && world.hasSelf && world.res && world.res.ready && world.fade < 1) {
      worldPainted = true;
      performance.mark('openmmo:world-ready');
    }
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
boot();
if (/[?&]fps=1/.test(location.search)) app.toggleFps();
