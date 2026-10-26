// Chat log + input. A leading "/" is sent as a server command (the gateway decides).
import { h, $ } from '../util.js';

let app, lastLen = 0, lastKey = '';

export function init(a) {
  app = a;
  const form = $('#chat-form');
  const input = $('#chat-input');
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = input.value.trim();
    if (text) app.send({ t: 'say', text });
    input.value = '';
    close();
  });
  input.addEventListener('focus', () => $('#chat').classList.remove('collapsed'));
  input.addEventListener('blur', () => {
    $('#chat').classList.add('collapsed');
    if (!document.body.classList.contains('touch')) document.body.classList.remove('chat-open');
  });
}

export function open(prefill) {
  document.body.classList.add('chat-open');
  $('#t-chat').classList.remove('unread');
  const input = $('#chat-input');
  if (typeof prefill === 'string') input.value = prefill;
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

export function update(state) {
  const lines = state.chat || [];
  const key = lines.length ? JSON.stringify(lines[lines.length - 1]) + lines.length : '';
  if (key === lastKey) return;
  const grew = lines.length !== lastLen || key !== lastKey;
  lastKey = key;
  lastLen = lines.length;
  const log = $('#chat-log');
  const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 30;
  log.replaceChildren(...lines.map((l) => h('p', { class: l.sender === '*' ? 'sys' : '' }, h('b', l.sender === '*' ? '★ ' : l.sender + ': '), l.text)));
  if (atBottom) log.scrollTop = log.scrollHeight;
  if (grew && document.body.classList.contains('touch') && !document.body.classList.contains('chat-open')) $('#t-chat').classList.add('unread');
}
