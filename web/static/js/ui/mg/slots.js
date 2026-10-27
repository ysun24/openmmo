// Slot machines (topic "slots"): FireRed's and Emerald's reels drawn from the decomp's reel icons.
// The server owns every outcome; the browser only animates the spinning reels between pushes.
import { h } from '../../util.js';
import { panels, setFocus, rerender } from '../panels.js';
import { assets } from '../../assets.js';

export const TOPICS = ['slots'];
let ctx = null;
let s = null; // last state
let spinT0 = 0; // when the reels were last reported spinning (performance.now)
let base = [0, 0, 0]; // positions at spinT0
let raf = 0;
let canvas = null;
const imgs = {};

const SYM = 32; // icon size (both games' reel graphics are 32 px)
const FRLG_NAMES = ['7', 'ROCKET', 'PIKACHU', 'PSYDUCK', 'CHERRY', 'MAGNEMITE', 'SHELLDER'];
const EM_NAMES = ['RED 7', 'BLUE 7', 'AZURILL', 'LOTAD', 'CHERRY', 'POWER', 'REPLAY'];

export function init(c) {
  ctx = c;
}

function img(rel) {
  if (!rel) return null;
  if (!imgs[rel]) {
    imgs[rel] = new Image();
    imgs[rel].src = 'assets/' + rel;
  }
  return imgs[rel];
}

function meta() {
  return (assets.manifest && assets.manifest.minigames && assets.manifest.minigames.slots) || {};
}

export function onTopic(_k, now, prev) {
  if (!now) {
    s = null;
    cancelAnimationFrame(raf);
    return panels.close('mgslots');
  }
  if (now.spinning && (!prev || !prev.spinning || now.current !== prev.current || !panels.isOpen('mgslots'))) {
    spinT0 = performance.now();
    base = now.reels.slice();
  }
  s = now;
  open();
}

function act(action, extra) {
  ctx.send('slots', action, extra);
}

function open() {
  const fresh = !panels.isOpen('mgslots');
  const p = panels.open('mgslots', s.game === 'frlg' ? 'SLOT MACHINE' : 'SLOTS', { onBack: () => act('quit') });
  p.el.classList.add('mg-slots', s.game);
  if (fresh) {
    canvas = h('canvas.mg-reels', { width: SYM * 3 + 16, height: SYM * 3 + 8 });
    p.body.replaceChildren();
    p.body.append(canvas, h('div.mg-slot-info'), h('div.mg-slot-btns'));
    p.onKey = (btn) => {
      if (btn === 'down') return act('bet', { n: (s ? s.bet : 0) + 1 }), true;
      if (btn === 'right' && !s.spinning) return act('bet', { n: 3 }), true;
      return false;
    };
    loop();
  }
  const info = p.body.querySelector('.mg-slot-info');
  const em = s.game === 'emerald';
  rerender(info, () => [
    h('div.mg-row', h('b', 'CREDIT'), h('span.mg-num', String(s.coins)), h('b', 'BET'), h('span.mg-num', String(s.bet)),
      h('b', 'PAYOUT'), h('span.mg-num', String(s.payout))),
    em ? h('div.mg-row', h('span', '⚡'.repeat(s.bolts) || 'PIKA POWER: —'), s.reelTime ? h('b.mg-rt', `REEL TIME ×${s.reelTime}`) : null) : null,
    h('p.mg-msg', { 'aria-live': 'polite' }, s.msg || (s.spinning ? 'Press A to stop the reel.' : 'Insert COINS: ↓ adds 1, → bets 3.')),
  ]);
  const btns = p.body.querySelector('.mg-slot-btns');
  const B = (label, k, fn, dis) => h('button', { type: 'button', 'data-k': k, disabled: !!dis, on: { click: fn } }, label);
  rerender(btns, () =>
    s.spinning
      ? [B(`STOP REEL ${s.current + 1}`, 'mg-stop', () => act('stop'))]
      : [
          B('BET 1', 'mg-bet1', () => act('bet', { n: s.bet + 1 }), s.bet >= 3 || s.coins === 0),
          B('BET 3', 'mg-bet3', () => act('bet', { n: 3 }), s.bet >= 3 || s.coins === 0),
          B('SPIN', 'mg-spin', () => act('spin'), s.bet === 0),
          B('QUIT', 'mg-quit', () => act('quit')),
        ]);
  setFocus(btns, btns.querySelector(s.spinning ? 'button' : s.bet > 0 ? '[data-k="mg-spin"]' : 'button:not(:disabled)'));
}

/** Where reel r is now (fractional), predicted while it spins. */
function pos(r) {
  if (!s.spinning || r < s.current) return s.reels[r];
  const frames = ((performance.now() - spinT0) * 60) / 1000;
  return base[r] - (frames * (s.speed || 8)) / 24;
}

function drawIcon(g, sym, x, y) {
  const m = meta();
  if (s.game === 'frlg') {
    const im = img(m.frlgIcons);
    if (im && im.complete && im.naturalWidth) return g.drawImage(im, 0, sym * SYM, SYM, SYM, x, y, SYM, SYM);
  } else {
    const im = img((m.emeraldSymbols || [])[sym]);
    if (im && im.complete && im.naturalWidth) return g.drawImage(im, x, y, SYM, SYM);
  }
  g.fillStyle = '#333';
  g.font = '9px monospace';
  g.fillText((s.game === 'frlg' ? FRLG_NAMES : EM_NAMES)[sym] || '?', x + 2, y + 18);
}

function loop() {
  cancelAnimationFrame(raf);
  const tick = () => {
    if (!s || !canvas || !canvas.isConnected) return;
    const g = canvas.getContext('2d');
    g.imageSmoothingEnabled = false;
    g.fillStyle = s.game === 'frlg' ? '#f8f0d8' : '#e8f0f8';
    g.fillRect(0, 0, canvas.width, canvas.height);
    for (let r = 0; r < 3; r++) {
      const strip = s.strips[r];
      const p = pos(r);
      const top = Math.floor(p);
      const frac = p - top;
      const x = 4 + r * (SYM + 4);
      g.save();
      g.beginPath();
      g.rect(x, 4, SYM, SYM * 3);
      g.clip();
      // Rows 1..3 show strip[pos+1..pos+3]; the reel scrolls downwards as the position falls.
      for (let k = 0; k <= 4; k++) {
        const sym = strip[(((top + k) % 21) + 21) % 21];
        drawIcon(g, sym, x, 4 + (k - 1 - frac) * SYM);
      }
      g.restore();
    }
    // the bet's lines
    g.strokeStyle = 'rgba(220,40,40,.8)';
    g.lineWidth = 2;
    const lines = new Set(s.lines || []);
    const mid = 4 + SYM * 1.5;
    const L = (on, x1, y1, x2, y2) => {
      if (!on) return;
      g.beginPath();
      g.moveTo(x1, y1);
      g.lineTo(x2, y2);
      g.stroke();
    };
    const W = canvas.width;
    if (!s.spinning) {
      if (s.game === 'frlg') {
        // sRowAttributes order: tl-br, top, middle, bottom, bl-tr
        L(lines.has(0), 2, 6, W - 2, SYM * 3 + 2);
        L(lines.has(1), 2, 4 + SYM / 2, W - 2, 4 + SYM / 2);
        L(lines.has(2), 2, mid, W - 2, mid);
        L(lines.has(3), 2, 4 + SYM * 2.5, W - 2, 4 + SYM * 2.5);
        L(lines.has(4), 2, SYM * 3 + 2, W - 2, 6);
      } else {
        L(lines.has(0), 2, mid, W - 2, mid);
        L(lines.has(1), 2, 4 + SYM / 2, W - 2, 4 + SYM / 2);
        L(lines.has(2), 2, 4 + SYM * 2.5, W - 2, 4 + SYM * 2.5);
        L(lines.has(3), 2, 6, W - 2, SYM * 3 + 2);
        L(lines.has(4), 2, SYM * 3 + 2, W - 2, 6);
      }
    }
    raf = requestAnimationFrame(tick);
  };
  raf = requestAnimationFrame(tick);
}
