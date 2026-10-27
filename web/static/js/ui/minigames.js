// r5-minigames (PROTOCOL.md section "r5-minigames"): the browser side of the Game Corner, the link
// minigames, the Union Room, Mystery Gift, the Fame Checker, records and Easy Chat. The server
// pushes one JSON object per topic in `state.minigames.<topic>`; actions go back as
// `{t: 'minigame', topic, action, ...}`. Each topic's window lives in ui/mg/<topic>.js.
import { h } from '../util.js';
import { panels, setFocus, rerender } from './panels.js';
import * as menu from './mg/menu.js';
import * as slots from './mg/slots.js';
import * as roulette from './mg/roulette.js';
import * as link from './mg/link.js';
import * as fame from './mg/fame.js';
import * as union from './mg/union.js';
import * as gift from './mg/gift.js';
import * as records from './mg/records.js';

const PARTS = [menu, slots, roulette, link, fame, union, gift, records];
let app = null;
let last = {};

export function init(a) {
  app = a;
  const ctx = { app, send, topic: (k) => (app.net.state.minigames || {})[k] || null };
  PARTS.forEach((p) => p.init && p.init(ctx));
  // A LINK button in the top bar opens the hub (coin case, Fame Checker, Union Room, gifts...).
  const bar = document.getElementById('topbar');
  const menuBtn = document.getElementById('btn-menu');
  if (bar && menuBtn && !document.getElementById('btn-link')) {
    const b = h('button#btn-link.small', { type: 'button', 'aria-label': 'Open link menu', on: { click: openHub } }, '⇄', h('span.lbl', ' Link'));
    bar.insertBefore(b, menuBtn);
  }
  const coins = h('span#coins.hidden', { title: 'COIN CASE' }, '');
  if (bar && menuBtn) bar.insertBefore(coins, document.getElementById('money'));
}

export function send(topic, action, extra = {}) {
  app.send({ t: 'minigame', topic, action, ...extra });
}

export function update(state, changed) {
  if (!changed.includes('minigames')) return;
  const mg = state.minigames || {};
  for (const p of PARTS) {
    for (const k of p.TOPICS || []) {
      const now = mg[k] || null;
      if (JSON.stringify(now) !== JSON.stringify(last[k] || null)) p.onTopic(k, now, last[k] || null);
    }
  }
  const c = mg.coins;
  const el = document.getElementById('coins');
  if (el) {
    el.classList.toggle('hidden', !c || !c.hasCase);
    if (c) el.textContent = '🪙 ' + c.coins.toLocaleString('en-US');
  }
  last = JSON.parse(JSON.stringify(mg));
}

function openHub() {
  if (panels.isOpen('mghub')) return panels.close('mghub');
  send('coins', 'get');
  const p = panels.open('mghub', 'LINK & GAMES');
  const b = (label, k, fn) => h('button', { type: 'button', 'data-k': k, on: { click: () => (panels.close('mghub'), fn()) } }, label);
  rerender(p.body, () => [
    h('p.muted', 'Online features that replace the GBA link cable and wireless adapter.'),
    b('UNION ROOM', 'mg-union', () => union.open()),
    b('MINIGAMES', 'mg-link', () => link.openLobbyList()),
    b('FAME CHECKER', 'mg-fame', () => fame.open()),
    b('MYSTERY GIFT', 'mg-gift', () => gift.open()),
    b('RECORDS & TV', 'mg-records', () => records.open()),
    b('EASY CHAT PROFILE', 'mg-easychat', () => records.openProfile()),
  ]);
  setFocus(p.body, p.body.querySelector('button'));
}
