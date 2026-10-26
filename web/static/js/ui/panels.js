// HTML overlay panel stack + spatial (arrow / D-pad) navigation inside panels.
import { h, $ } from '../util.js';

const stack = []; // [{name, el, onBack}]

export const panels = {
  /** Open (or return) panel `name`. Returns {el, body, header}. */
  open(name, title, { onBack } = {}) {
    let p = stack.find((x) => x.name === name);
    if (p) {
      // bring to front
      stack.splice(stack.indexOf(p), 1);
      stack.push(p);
      $('#panels').append(p.el);
      return p;
    }
    const body = h('div.body');
    const closeBtn = h('button.close', { type: 'button', 'aria-label': 'Close', on: { click: () => (p.onBack ? p.onBack() : panels.close(name)) } }, '✕');
    const header = h('header', h('h2', title), closeBtn);
    const el = h('section.panel', { 'data-panel': name, role: 'dialog', 'aria-label': title }, header, body);
    p = { name, el, body, header, onBack, title: header.querySelector('h2') };
    stack.push(p);
    $('#panels').append(el);
    return p;
  },
  get(name) { return stack.find((x) => x.name === name) || null; },
  isOpen(name) { return !!this.get(name); },
  close(name) {
    const i = stack.findIndex((x) => x.name === name);
    if (i < 0) return;
    const [p] = stack.splice(i, 1);
    p.el.remove();
    if (p.onClose) p.onClose();
  },
  top() { return stack[stack.length - 1] || null; },
  any() { return stack.length > 0; },
  back() {
    const p = this.top();
    if (!p) return false;
    if (p.onBack) p.onBack();
    else this.close(p.name);
    return true;
  },
};

// ------------------------------------------------------------------ spatial navigation
function focusables(root) {
  return [...root.querySelectorAll('button:not(:disabled), input, select')].filter((e) => e.offsetParent !== null);
}
export function navMove(root, dir) {
  const all = focusables(root);
  if (!all.length) return;
  let cur = root.querySelector('.nav-focus');
  if (!cur || !all.includes(cur)) {
    setFocus(root, all[0]);
    return;
  }
  const r0 = cur.getBoundingClientRect();
  const cx = r0.left + r0.width / 2, cy = r0.top + r0.height / 2;
  let best = null, bestScore = Infinity;
  for (const c of all) {
    if (c === cur) continue;
    const r = c.getBoundingClientRect();
    const dx = r.left + r.width / 2 - cx, dy = r.top + r.height / 2 - cy;
    let main, cross;
    if (dir === 'down') (main = dy), (cross = dx);
    else if (dir === 'up') (main = -dy), (cross = dx);
    else if (dir === 'right') (main = dx), (cross = dy);
    else (main = -dx), (cross = dy);
    if (main < 4) continue;
    const s = main + Math.abs(cross) * 2.5;
    if (s < bestScore) (bestScore = s), (best = c);
  }
  if (best) setFocus(root, best);
}
export function setFocus(root, el) {
  root.querySelectorAll('.nav-focus').forEach((e) => e.classList.remove('nav-focus'));
  if (!el) return;
  el.classList.add('nav-focus');
  el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}
export function navActivate(root) {
  let cur = root.querySelector('.nav-focus');
  if (!cur) {
    cur = focusables(root)[0];
    if (cur) setFocus(root, cur);
    return;
  }
  if (cur.tagName === 'INPUT' || cur.tagName === 'SELECT') cur.focus();
  else cur.click();
}
/** Re-render `container` via `fn` while keeping the nav focus on the element with the same data-k. */
export function rerender(container, fn) {
  const f = container.querySelector('.nav-focus');
  const k = f && f.dataset.k;
  const vals = new Map();
  for (const i of container.querySelectorAll('input[data-k], select[data-k]')) vals.set(i.dataset.k, i.value);
  const act = document.activeElement && container.contains(document.activeElement) ? document.activeElement.dataset.k : null;
  const scroll = container.scrollTop;
  container.replaceChildren(...[fn()].flat(Infinity).filter((x) => x !== null && x !== undefined && x !== false));
  for (const i of container.querySelectorAll('input[data-k], select[data-k]')) if (vals.has(i.dataset.k)) i.value = vals.get(i.dataset.k);
  const find = (key) => container.querySelector(`[data-k="${CSS.escape(key)}"]`);
  if (k) {
    const n = find(k);
    if (n) n.classList.add('nav-focus');
  }
  if (act) {
    const n = find(act);
    if (n) n.focus({ preventScroll: true });
  }
  container.scrollTop = scroll;
}
