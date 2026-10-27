// Client preferences (localStorage `openmmo.settings`): text speed, battle animations, music / sfx
// volume, window frame style, keyboard bindings, help seen. Modules read them through `settings`
// and subscribe with `settings.on(fn)`.
import { Emitter } from './util.js';

const KEY = 'openmmo.settings';
const reduce = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Game actions that can be bound to keys; [label, default key codes]. */
export const ACTIONS = {
  up: ['Up', ['ArrowUp', 'KeyW']],
  down: ['Down', ['ArrowDown', 'KeyS']],
  left: ['Left', ['ArrowLeft', 'KeyA']],
  right: ['Right', ['ArrowRight', 'KeyD']],
  a: ['A (talk / confirm)', ['KeyZ', 'Space']],
  b: ['B (back / cancel)', ['KeyX', 'Escape']],
  run: ['Run (hold)', ['ShiftLeft', 'ShiftRight']],
  chat: ['Chat', ['Enter']],
  menu: ['Menu', ['Tab']],
};

/** GBA text speeds: frames per character (sTextSpeedFrameDelays: SLOW 8, MID 4, FAST 1). */
export const TEXT_SPEEDS = { slow: 8, mid: 4, fast: 1, instant: 0 };

const DEFAULTS = {
  textSpeed: 'mid',
  battleAnims: !reduce,
  musicVolume: 0.6,
  sfxVolume: 0.7,
  muted: false,
  frame: 0,
  keys: null, // null = ACTIONS defaults; else {action: [codes]}
  helpSeen: false,
};

class Settings extends Emitter {
  constructor() {
    super();
    this.v = { ...DEFAULTS };
    try {
      Object.assign(this.v, JSON.parse(localStorage.getItem(KEY) || '{}'));
    } catch {}
  }
  get(k) { return this.v[k]; }
  set(k, val) {
    this.v[k] = val;
    try {
      localStorage.setItem(KEY, JSON.stringify(this.v));
    } catch {}
    this.emit('change', k, val);
  }
  on(fn) { return super.on('change', fn); }
  /** action -> key codes */
  keys() {
    const out = {};
    for (const [a, [, def]] of Object.entries(ACTIONS)) out[a] = (this.v.keys && this.v.keys[a]) || def;
    return out;
  }
  bind(action, codes) {
    const k = { ...(this.v.keys || {}) };
    k[action] = codes;
    this.set('keys', k);
  }
  resetKeys() { this.set('keys', null); }
  /** ms per character step in battle text (the battle screen's own pacing at MID). */
  battleCharMs() { return { slow: 55, mid: 22, fast: 8, instant: 0 }[this.v.textSpeed] ?? 22; }
  /** ms per character for the dialog typewriter. */
  charMs() { return (TEXT_SPEEDS[this.v.textSpeed] ?? 4) * (1000 / 59.7275); }
}

export const settings = new Settings();

/** "KeyZ" -> "Z", "ArrowUp" -> "↑" ... for the remap UI. */
export function keyLabel(code) {
  if (!code) return '—';
  const m = { ArrowUp: '↑', ArrowDown: '↓', ArrowLeft: '←', ArrowRight: '→', Space: 'Space', Escape: 'Esc', Enter: 'Enter', Tab: 'Tab',
    ShiftLeft: 'L-Shift', ShiftRight: 'R-Shift', ControlLeft: 'L-Ctrl', ControlRight: 'R-Ctrl', Backspace: 'Bksp' };
  if (m[code]) return m[code];
  if (/^Key[A-Z]$/.test(code)) return code.slice(3);
  if (/^Digit\d$/.test(code)) return code.slice(5);
  if (/^Numpad/.test(code)) return 'Num' + code.slice(6);
  return code;
}
