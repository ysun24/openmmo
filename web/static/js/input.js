// Keyboard + touch input. Produces a held direction / run flag for movement and discrete
// "press" events (with auto-repeat for directions) for menus.
import { Emitter } from './util.js';

export const KEYS = {
  ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
  KeyW: 'up', KeyS: 'down', KeyA: 'left', KeyD: 'right',
  KeyZ: 'a', Space: 'a', KeyX: 'b', Escape: 'b', Enter: 'chat', Tab: 'menu',
  // r3-field: field moves menu and the bike (the GBA's registered-item SELECT).
  KeyF: 'field', KeyC: 'bike',
};
const DIRS = ['up', 'down', 'left', 'right'];
const REPEAT_DELAY = 360;
const REPEAT_RATE = 110;

export class Input extends Emitter {
  constructor() {
    super();
    this.held = []; // stack of held directions, last = active
    this.sources = new Map(); // source -> dir (keyboard key code / 'pad')
    this.shift = false;
    this.runToggle = false;
    this.tapDir = null; // a press shorter than a frame still counts once
    this.repeatT = 0;
    this.repeatDir = null;
    this.bindKeys();
  }

  get dir() { return this.held.length ? this.held[this.held.length - 1] : this.tapDir; }
  get run() { return this.shift !== this.runToggle; }
  consumeTap() { this.tapDir = null; }

  dirDown(src, dir) {
    const prev = this.sources.get(src);
    if (prev === dir) return;
    if (prev) this.dirUp(src);
    this.sources.set(src, dir);
    this.held.push(dir);
    this.tapDir = dir;
    this.emit('press', dir);
    clearTimeout(this.repeatT);
    this.repeatDir = dir;
    const rep = () => {
      if (this.dir !== dir || this.repeatDir !== dir) return;
      this.emit('repeat', dir);
      this.repeatT = setTimeout(rep, REPEAT_RATE);
    };
    this.repeatT = setTimeout(rep, REPEAT_DELAY);
  }
  dirUp(src) {
    const d = this.sources.get(src);
    if (!d) return;
    this.sources.delete(src);
    const i = this.held.lastIndexOf(d);
    if (i >= 0) this.held.splice(i, 1);
    if (this.repeatDir === d) this.repeatDir = null;
  }
  releaseAll() {
    this.sources.clear();
    this.held.length = 0;
    this.shift = false;
    this.repeatDir = null;
  }

  bindKeys() {
    const typing = (e) => {
      const t = e.target;
      return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable);
    };
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Shift') this.shift = true;
      if (typing(e)) {
        if (e.code === 'Escape') {
          e.target.blur();
          this.emit('press', 'blur');
        }
        return;
      }
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const k = KEYS[e.code];
      if (!k) return;
      e.preventDefault();
      if (k === 'run') return void (this.shift = true); // remappable run key (settings)
      if (DIRS.includes(k)) {
        if (!e.repeat) this.dirDown('k:' + e.code, k);
      } else if (!e.repeat) this.emit('press', k);
    });
    window.addEventListener('keyup', (e) => {
      if (e.key === 'Shift') this.shift = false;
      const k = KEYS[e.code];
      if (k === 'run') this.shift = false;
      if (k && DIRS.includes(k)) this.dirUp('k:' + e.code);
    });
    window.addEventListener('blur', () => this.releaseAll());
    document.addEventListener('visibilitychange', () => document.hidden && this.releaseAll());
  }

  /** On-screen controls (multi-touch via pointer events). */
  bindTouch(root) {
    const pad = root.querySelector('#dpad');
    const segs = { up: pad.querySelector('.u'), down: pad.querySelector('.d'), left: pad.querySelector('.l'), right: pad.querySelector('.r') };
    let padPointer = null;
    const light = (d) => {
      for (const [k, el] of Object.entries(segs)) el.classList.toggle('on', k === d);
    };
    const padDir = (e) => {
      const r = pad.getBoundingClientRect();
      const dx = e.clientX - (r.left + r.width / 2);
      const dy = e.clientY - (r.top + r.height / 2);
      if (dx * dx + dy * dy < 12 * 12) return null;
      return Math.abs(dx) > Math.abs(dy) ? (dx < 0 ? 'left' : 'right') : dy < 0 ? 'up' : 'down';
    };
    pad.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      padPointer = e.pointerId;
      try {
        pad.setPointerCapture(e.pointerId);
      } catch {}
      const d = padDir(e);
      if (d) this.dirDown('pad', d);
      light(d);
    });
    pad.addEventListener('pointermove', (e) => {
      if (e.pointerId !== padPointer) return;
      const d = padDir(e);
      if (d) this.dirDown('pad', d);
      else this.dirUp('pad');
      light(d);
    });
    const end = (e) => {
      if (e.pointerId !== padPointer) return;
      padPointer = null;
      this.dirUp('pad');
      light(null);
    };
    pad.addEventListener('pointerup', end);
    pad.addEventListener('pointercancel', end);
    pad.addEventListener('lostpointercapture', end);

    const btn = (sel, name) => {
      const el = root.querySelector(sel);
      el.addEventListener('pointerdown', (e) => {
        e.preventDefault();
        el.classList.add('on');
        this.emit('press', name);
      });
      const off = () => el.classList.remove('on');
      el.addEventListener('pointerup', off);
      el.addEventListener('pointercancel', off);
      el.addEventListener('pointerleave', off);
      el.addEventListener('contextmenu', (e) => e.preventDefault());
    };
    btn('#t-a', 'a');
    btn('#t-b', 'b');
    btn('#t-chat', 'chat');
    btn('#t-menu', 'menu');
    const run = root.querySelector('#t-run');
    run.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      this.runToggle = !this.runToggle;
      run.classList.toggle('on', this.runToggle);
    });
    // no long-press menus / selection / pinch on the controls
    root.addEventListener('touchstart', (e) => e.preventDefault(), { passive: false });
    root.addEventListener('contextmenu', (e) => e.preventDefault());
  }
}
