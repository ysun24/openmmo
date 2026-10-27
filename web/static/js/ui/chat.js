// Chat log + input with channel tabs (r3-social): All, Local, Global, Trade, Guild, Whisper.
// The selected tab filters the log and is the channel a plain line goes to ("All" sends locally).
// Shortcuts in the input: "/w Name text" or "@Name text" whispers, "/l", "/g", "/t", "/gu" pick a
// channel for one line; any other leading "/" is a server command (the gateway decides).
import { h, $, toast } from '../util.js';

const TABS = [
  ['all', 'All'], ['local', 'Local'], ['global', 'Global'], ['trade', 'Trade'], ['guild', 'Guild'], ['whisper', 'Whisper'],
];
const TAG = { local: '', global: '[G] ', trade: '[T] ', guild: '[Guild] ', whisper: '' };

let app, lastKey = '', tab = 'all', whisperTo = null, tabsEl = null, toEl = null, lastLine = null;
const unread = new Set();

export function init(a) {
  app = a;
  const form = $('#chat-form');
  const input = $('#chat-input');
  tabsEl = h('div#chat-tabs', { role: 'tablist' }, TABS.map(([k, label]) =>
    h('button', { type: 'button', role: 'tab', 'data-tab': k, on: { mousedown: (e) => e.preventDefault(), click: () => setTab(k) } }, label)));
  toEl = h('span#chat-to.hidden');
  $('#chat').prepend(tabsEl);
  form.prepend(toEl);
  setTab('all');
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = input.value.trim();
    if (text) send(text);
    input.value = '';
    close();
  });
  input.addEventListener('focus', () => $('#chat').classList.remove('collapsed'));
  input.addEventListener('blur', () => {
    $('#chat').classList.add('collapsed');
    if (!document.body.classList.contains('touch')) document.body.classList.remove('chat-open');
  });
}

/** Parses the shortcuts and sends the line on the right channel. */
export function send(text) {
  let m;
  if ((m = /^(?:\/w(?:hisper)?\s+|@)(\S+)\s+([\s\S]+)$/i.exec(text))) {
    whisperTo = m[1];
    return app.send({ t: 'say', channel: 'whisper', to: m[1], text: m[2] });
  }
  if ((m = /^\/(l|g|t|gu)\s+([\s\S]+)$/i.exec(text))) {
    const ch = { l: 'local', g: 'global', t: 'trade', gu: 'guild' }[m[1].toLowerCase()];
    return app.send({ t: 'say', channel: ch, text: m[2] });
  }
  if (text.startsWith('/')) return app.send({ t: 'say', text });
  if (tab === 'whisper') {
    if (!whisperTo) return toast('Whisper to whom? Type "/w Name message".');
    return app.send({ t: 'say', channel: 'whisper', to: whisperTo, text });
  }
  return app.send({ t: 'say', channel: tab === 'all' ? 'local' : tab, text });
}

function setTab(k) {
  tab = k;
  unread.delete(k);
  for (const b of tabsEl.children) {
    b.classList.toggle('on', b.dataset.tab === k);
    b.classList.toggle('unread', unread.has(b.dataset.tab));
    b.setAttribute('aria-selected', String(b.dataset.tab === k));
  }
  toEl.textContent = k === 'whisper' && whisperTo ? `→ ${whisperTo}` : '';
  toEl.classList.toggle('hidden', !(k === 'whisper' && whisperTo));
  $('#chat-input').placeholder = k === 'whisper' ? (whisperTo ? `Whisper to ${whisperTo}` : '/w Name message')
    : k === 'all' || k === 'local' ? 'Enter to chat, / for commands' : `Chat on ${k}`;
  lastKey = '';
  if (app && app.net) update(app.net.state);
}

/** Opens the chat on the whisper tab, addressed to [name]. */
export function whisper(name) {
  whisperTo = name;
  setTab('whisper');
  open();
}

export function open(prefill) {
  document.body.classList.add('chat-open');
  $('#t-chat').classList.remove('unread');
  const input = $('#chat-input');
  if (typeof prefill === 'string') {
    const m = /^@(\S+)\s*$/.exec(prefill);
    if (m) return whisper(m[1]);
    input.value = prefill;
  }
  input.focus();
  const log = $('#chat-log');
  log.scrollTop = log.scrollHeight;
}
export function close() {
  document.body.classList.remove('chat-open');
  $('#chat-input').blur();
}
export function toggle() {
  if (document.activeElement === $('#chat-input')) close();
  else open();
}

const chanOf = (l) => l.channel || 'system';
// Server notices and channel errors show on every tab.
const shown = (l) => tab === 'all' || chanOf(l) === tab || chanOf(l) === 'system';

function line(l, me) {
  const ch = chanOf(l);
  if (ch === 'system') return h('p.sys', h('b', '★ '), l.text);
  if (ch === 'whisper') {
    const out = l.sender === me;
    return h('p.ch-whisper', h('b', { on: { click: () => whisper(out ? l.to : l.sender) }, title: 'reply' },
      out ? `→ ${l.to}: ` : `${l.sender} ⇢ you: `), l.text);
  }
  return h('p.ch-' + ch, h('span.tag', TAG[ch] || ''),
    h('b', { on: { click: () => app.openPlayer && app.openPlayer({ name: l.sender }) } }, l.sender + ': '), l.text);
}

export function update(state) {
  const lines = state.chat || [];
  const key = lines.length ? JSON.stringify(lines[lines.length - 1]) + lines.length + tab : tab;
  if (key === lastKey) return;
  // Unread markers: lines after the last one seen, on channels other than the shown tab.
  const lastIdx = lastLine === null ? lines.length - 1 : lines.map((l) => JSON.stringify(l)).lastIndexOf(lastLine);
  for (const l of lines.slice(lastIdx + 1)) {
    const ch = chanOf(l);
    if (tab !== 'all' && ch !== tab && TABS.some(([k]) => k === ch)) unread.add(ch);
  }
  for (const b of tabsEl.children) b.classList.toggle('unread', unread.has(b.dataset.tab));
  lastLine = lines.length ? JSON.stringify(lines[lines.length - 1]) : null;
  lastKey = key;
  const log = $('#chat-log');
  const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 30;
  const me = state.world && (state.world.entities || []).find((e) => e.id === state.world.self);
  const myName = me ? me.name : null;
  log.replaceChildren(...lines.filter(shown).map((l) => line(l, myName)));
  if (atBottom) log.scrollTop = log.scrollHeight;
  if (document.body.classList.contains('touch') && !document.body.classList.contains('chat-open')) $('#t-chat').classList.add('unread');
}
