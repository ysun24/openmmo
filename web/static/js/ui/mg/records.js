// Records (topic "records"): mix records with the other online players (TV shows, secret bases,
// Lilycove trainers, the Dewford trendy phrase); Easy Chat (topic "easychat"): the word picker of
// easy_chat.c over manifest.minigames.easyChat's groups.
import { h } from '../../util.js';
import { panels, setFocus, rerender } from '../panels.js';
import { assets } from '../../assets.js';

export const TOPICS = ['records', 'easychat'];
let ctx = null;
let s = null;

export function init(c) {
  ctx = c;
}

export function open() {
  ctx.send('records', 'open');
}

export function openProfile() {
  picker({ kind: 'profile', count: 4, current: null }, (words, text) => ctx.send('easychat', 'save', { kind: 'profile', words, text }));
}

export function onTopic(k, now) {
  if (k === 'easychat') {
    if (!now) return panels.close('mgeasychat');
    return picker(now, (words, text) => ctx.send('easychat', 'done', { words, text }), () => ctx.send('easychat', 'cancel'));
  }
  s = now;
  if (!now) return panels.close('mgrecords');
  const p = panels.open('mgrecords', 'RECORDS & TV', { onBack: () => (ctx.send('records', 'close'), panels.close('mgrecords')) });
  const m = s.mixed || {};
  const ec = s.easychat || {};
  rerender(p.body, () => [
    h('p', h('b', 'DEWFORD TREND: '), `“${(s.trend && s.trend.text) || ''}”`),
    h('p', h('b', 'YOUR PROFILE: '), ec.profile ? ec.profile.text : '—'),
    h('div.mg-row',
      h('button', { type: 'button', 'data-k': 'mg-mix', on: { click: () => ctx.send('records', 'mix') } }, 'MIX RECORDS'),
      h('button', { type: 'button', 'data-k': 'mg-trend', on: { click: () => picker({ kind: 'trend', count: 2 }, (words, text) => ctx.send('easychat', 'save', { kind: 'trend', words, text })) } }, 'SET TRENDY PHRASE'),
      h('button', { type: 'button', 'data-k': 'mg-prof', on: { click: openProfile } }, 'EDIT PROFILE')),
    m.at ? [
      h('h3', 'TRAINERS (LILYCOVE)'),
      (m.trainers || []).length ? h('ul.mg-players', m.trainers.map((t) => h('li', `${t.name} — ${(t.party || []).length} POKéMON ${t.profile ? '“' + t.profile.text + '”' : ''}`))) : h('p.muted', 'none'),
      h('h3', 'TV SHOWS'),
      (m.shows || []).length ? h('ul.mg-players', m.shows.map((t) => h('li', `${t.author}: “${t.text}”`))) : h('p.muted', 'none'),
      h('h3', 'SECRET BASES'),
      (m.bases || []).length ? h('ul.mg-players', m.bases.map((b) => h('li', `${b.owner}'s base (#${b.baseId}, ${b.decorations} decorations)`))) : h('p.muted', 'none'),
    ] : h('p.muted', 'Mix records to see other players’ TV shows, secret bases and trainers.'),
  ]);
  setFocus(p.body, p.body.querySelector('button'));
}

function picker(req, done, cancel) {
  const groups = ((assets.manifest && assets.manifest.minigames && assets.manifest.minigames.easyChat) || { groups: [] }).groups.filter((g) => g.words.length);
  const words = [];
  let gi = 0;
  const p = panels.open('mgeasychat', 'EASY CHAT', { onBack: () => (panels.close('mgeasychat'), cancel && cancel()) });
  const render = () => rerender(p.body, () => [
    h('p', `Choose ${req.count} word${req.count > 1 ? 's' : ''} (${req.kind.toUpperCase()}):`),
    h('div.mg-card', words.length ? words.map((w) => w.text).join(' ') : '…'),
    h('div.mg-row', h('select', { 'data-k': 'mg-ecg', on: { change: (e) => ((gi = +e.target.value), render()) } }, groups.map((g, i) => h('option', { value: i, selected: i === gi }, g.name)))),
    h('div.mg-words', (groups[gi] ? groups[gi].words : []).map((w, i) => h('button', { type: 'button', 'data-k': 'mg-w-' + i, disabled: words.length >= req.count,
      on: { click: () => (words.push({ id: (groups[gi].id << 9) | i, text: w }), render()) } }, w))),
    h('div.mg-row',
      h('button', { type: 'button', 'data-k': 'mg-ec-del', on: { click: () => (words.pop(), render()) } }, 'DEL'),
      h('button', { type: 'button', 'data-k': 'mg-ec-ok', disabled: !words.length, on: { click: () => (panels.close('mgeasychat'), done(words.map((w) => w.id), words.map((w) => w.text).join(' '))) } }, 'OK')),
  ]);
  render();
  setFocus(p.body, p.body.querySelector('button'));
}
