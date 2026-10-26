// Small DOM + misc helpers shared by all modules.

/** h('div.cls#id', {attrs, on:{click}}, ...children) -> Element. Text children are escaped. */
export function h(tag, props, ...kids) {
  const m = /^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i.exec(tag);
  const el = document.createElement(m[1] || 'div');
  for (const part of m[2].match(/[.#][\w-]+/g) || []) {
    if (part[0] === '.') el.classList.add(part.slice(1));
    else el.id = part.slice(1);
  }
  if (props && (typeof props !== 'object' || props instanceof Node || Array.isArray(props))) {
    kids.unshift(props);
    props = null;
  }
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v === undefined || v === null || v === false) continue;
      if (k === 'on') for (const [ev, fn] of Object.entries(v)) el.addEventListener(ev, fn);
      else if (k === 'class') el.className += ' ' + v;
      else if (k === 'style') el.setAttribute('style', v);
      else if (k in el && k !== 'list') el[k] = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  append(el, kids);
  return el;
}
function append(el, kids) {
  for (const k of kids) {
    if (k === null || k === undefined || k === false) continue;
    if (Array.isArray(k)) append(el, k);
    else el.append(k instanceof Node ? k : document.createTextNode(String(k)));
  }
}
export const $ = (sel, root = document) => root.querySelector(sel);
export const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

/** Tiny event emitter. */
export class Emitter {
  constructor() { this.l = new Map(); }
  on(ev, fn) {
    if (!this.l.has(ev)) this.l.set(ev, new Set());
    this.l.get(ev).add(fn);
    return () => this.l.get(ev).delete(fn);
  }
  emit(ev, ...a) {
    const s = this.l.get(ev);
    if (s) for (const fn of s) fn(...a);
  }
}

export function fmtMoney(n) { return '₽' + (n ?? 0).toLocaleString('en-US'); }

export function hpClass(hp, max) {
  const r = max > 0 ? hp / max : 0;
  return r > 0.5 ? '' : r > 0.2 ? 'mid' : 'low';
}
export function hpBar(hp, max) {
  const pct = max > 0 ? Math.round((100 * Math.max(0, hp)) / max) : 0;
  return h('span.hpbar', h('i', { class: hpClass(hp, max), style: `width:${pct}%` }));
}

let toastT = 0;
export function toast(msg, ms = 2500) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toastT);
  toastT = setTimeout(() => t.classList.add('hidden'), ms);
}

/** True if `a` and `b` serialise to the same JSON (sections are small). */
export const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
