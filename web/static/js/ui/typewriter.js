// Dialog typewriter: the GBA prints message box text one character at a time at the player's text
// speed (sTextSpeedFrameDelays: SLOW 8, MID 4, FAST 1 frames per character); A or B finishes the
// page at once, the "more" arrow and the choice menu only show when the page is complete.
// Works on whatever ui/dialog.js renders (observes #dialog), so the dialog code stays unchanged.
import { $ } from '../util.js';
import { settings } from '../settings.js';

let root = null, full = [], shown = 0, total = 0, timer = 0, key = '', lines = [];

export function init() {
  root = $('#dialog');
  new MutationObserver(check).observe(root, { childList: true });
}

function check() {
  const box = root.querySelector('.box');
  if (!box) {
    stop();
    key = '';
    return;
  }
  lines = [...box.children].filter((e) => e.tagName === 'DIV');
  // the GBA prints the speaker in the text ("MOM: ..."); drop the extra tag when it does
  const sp = box.querySelector('.speaker');
  if (sp && lines[0]) sp.classList.toggle('dup', lines[0].textContent.toUpperCase().startsWith(sp.textContent.trim().toUpperCase()));
  const texts = lines.map((l) => l.textContent);
  const k = texts.join('\n');
  if (k === key) return apply(); // same page re-rendered (cursor move, state echo): keep progress
  key = k;
  full = texts;
  total = texts.reduce((n, t) => n + [...t].length, 0);
  shown = 0;
  const ms = settings.charMs();
  if (!ms || !total) return finish();
  root.classList.add('typing');
  clearInterval(timer);
  // whole GBA frames per character; several characters per tick when the tab lags
  let last = performance.now();
  timer = setInterval(() => {
    const now = performance.now();
    const n = Math.floor((now - last) / ms);
    if (n <= 0) return;
    last += n * ms;
    shown = Math.min(total, shown + n);
    apply();
    if (shown >= total) finish();
  }, Math.max(8, Math.min(ms, 60)));
  apply();
}

function apply() {
  let left = shown;
  lines.forEach((el, i) => {
    const chars = [...(full[i] || '')];
    const n = Math.max(0, Math.min(chars.length, left));
    left -= chars.length;
    const t = n >= chars.length ? full[i] : chars.slice(0, n).join('');
    if (el.textContent !== t) el.textContent = t;
  });
}

function stop() {
  clearInterval(timer);
  timer = 0;
  if (root) root.classList.remove('typing');
}

export function finish() {
  shown = total;
  apply();
  stop();
}

export const busy = () => !!timer;
