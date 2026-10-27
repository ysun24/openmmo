// Friends (online + where), blocks, trade history, player profiles, guild window, guild invite
// prompt and the tapped-player context menu (r3-social). State comes from the `social` section.
import { h, same, toast } from '../util.js';
import { panels, rerender, setFocus } from './panels.js';
import { monImg } from './common.js';
import * as chat from './chat.js';
import { openChallenge } from './battle.js';

let app, last = null, lastInvite = null, wantProfile = null, lastProfileSeq = 0, refreshTimer = 0;
let friendsTab = 'friends';
const RANKS = ['Recruit', 'Member', 'Officer', 'Commander', 'Executive', 'Leader'];

export function init(a) {
  app = a;
  app.openPlayer = openPlayer;
  // Our own stylesheet, so index.html stays untouched.
  if (!document.querySelector('link[data-social]')) {
    document.head.append(h('link', { rel: 'stylesheet', href: 'css/social.css', 'data-social': '1' }));
  }
}

/** "PalletTown_PlayersHouse_2F" -> "Pallet Town Players House 2F". */
export function placeName(loc) {
  if (!loc) return null;
  const n = loc.name || `${loc.region}:${loc.bank}:${loc.map}`;
  return n.replace(/_/g, ' ').replace(/([a-z])([A-Z0-9])/g, '$1 $2').replace(/\s+/g, ' ').trim();
}
const ago = (epoch) => {
  if (!epoch) return '';
  const d = Math.max(0, Date.now() / 1000 - epoch);
  return d < 90 ? 'just now' : d < 5400 ? `${Math.round(d / 60)} min ago` : d < 172800 ? `${Math.round(d / 3600)} h ago` : `${Math.round(d / 86400)} days ago`;
};
const date = (epoch) => (epoch ? new Date(epoch * 1000).toLocaleDateString() : '?');
const social = () => (app.net.state && app.net.state.social) || { friends: [], blocked: [], guild: { inGuild: false } };

export function update(state, changed) {
  const s = state.social;
  if (!s) return;
  // A guild invite pops up on its own.
  if (!same(s.invite, lastInvite)) {
    lastInvite = s.invite;
    if (s.invite) showInvite(s.invite);
    else panels.close('ginvite');
  }
  if (s.profileSeq !== lastProfileSeq) {
    lastProfileSeq = s.profileSeq;
    if (wantProfile && s.profile && s.profile.name.toLowerCase() === wantProfile.toLowerCase()) renderProfile(s.profile);
  }
  if (!changed.includes('social') && last) return;
  last = s;
  if (panels.isOpen('friends')) renderFriends();
  if (panels.isOpen('guild')) renderGuild();
  if (panels.isOpen('profile') && s.profile && wantProfile && s.profile.name.toLowerCase() === wantProfile.toLowerCase()) renderProfile(s.profile);
}

// ------------------------------------------------------------------ friends

export function openFriends(tab) {
  if (tab) friendsTab = tab;
  const p = panels.open('friends', 'Friends', {
    onBack: () => { clearInterval(refreshTimer); panels.close('friends'); },
  });
  p.onClose = () => clearInterval(refreshTimer);
  app.send({ t: 'social.refresh' });
  if (friendsTab === 'trades') app.send({ t: 'trade.history' });
  clearInterval(refreshTimer);
  // Locations change as friends move; refresh while the window is open.
  refreshTimer = setInterval(() => panels.isOpen('friends') ? app.send({ t: 'social.refresh' }) : clearInterval(refreshTimer), 10000);
  renderFriends();
  setFocus(p.body, p.body.querySelector('.tabs button.on'));
}

function renderFriends() {
  const p = panels.get('friends');
  if (!p) return;
  const s = social();
  const online = s.friends.filter((f) => f.online).length;
  p.title.textContent = `Friends (${online}/${s.friends.length} online)`;
  const name = h('input', { placeholder: 'trainer name', maxlength: 12, autocapitalize: 'off', 'aria-label': 'trainer name', 'data-k': 'fname' });
  const tabBtn = (k, label) => h('button', { type: 'button', class: friendsTab === k ? 'on' : '', 'data-k': 'ft-' + k,
    on: { click: () => { friendsTab = k; if (k === 'trades') app.send({ t: 'trade.history' }); renderFriends(); } } }, label);
  rerender(p.body, () => [
    h('div.tabs', tabBtn('friends', 'Friends'), tabBtn('blocked', `Blocked ${s.blocked.length || ''}`), tabBtn('trades', 'Trade log')),
    friendsTab === 'friends' ? [
      h('form.row', { on: { submit: (e) => { e.preventDefault(); const n = name.value.trim(); if (n) { app.send({ t: 'friends.add', name: n }); name.value = ''; } } } },
        name, h('button.go', { type: 'submit', 'data-k': 'fadd' }, 'Add friend')),
      // Integration: friend requests wait for an answer; only mutual friends see each other.
      (s.requests || []).length ? h('div.list.requests', h('p.muted', 'Friend requests'), s.requests.map((n) => h('div.item.friend.req',
        h('span.nm', n, h('small', 'wants to be your friend')),
        h('span.act',
          h('button.go', { type: 'button', 'data-k': 'fa' + n, on: { click: () => app.send({ t: 'friends.accept', name: n }) } }, 'Accept'),
          h('button.danger', { type: 'button', 'data-k': 'fd' + n, on: { click: () => app.send({ t: 'friends.decline', name: n }) } }, 'Decline'))))) : null,
      h('div.list', s.friends.length ? s.friends.map((f) => h('div.item.friend', { class: f.online ? 'on' : 'off' },
        h('span.dot', { title: f.online ? 'online' : 'offline' }),
        h('span.nm', f.name, f.mutual ? h('span.badge', '⇄') : null,
          h('small', f.mutual ? (f.online ? (placeName(f.location) || 'online') : 'offline') : 'request sent')),
        h('span.act',
          h('button', { type: 'button', 'data-k': 'fw' + f.name, title: 'Whisper', disabled: !f.online, on: { click: () => { panels.close('friends'); chat.whisper(f.name); } } }, '✉'),
          h('button', { type: 'button', 'data-k': 'fp' + f.name, title: 'Profile', on: { click: () => openProfile(f.name) } }, 'Profile'),
          h('button', { type: 'button', 'data-k': 'ft' + f.name, title: 'Trade', disabled: !f.online, on: { click: () => { panels.close('friends'); app.send({ t: 'trade.request', name: f.name }); } } }, '⇄'),
          h('button.danger', { type: 'button', 'data-k': 'fr' + f.name, title: 'Remove', on: { click: () => app.send({ t: 'friends.remove', name: f.name }) } }, '✕')))) :
        h('p.muted', 'No friends yet. Add someone by name, or tap a player on the map.')),
    ] : null,
    friendsTab === 'blocked' ? h('div.list', s.blocked.length ? s.blocked.map((n) => h('div.item',
      h('span.nm', n, h('small', 'their messages, whispers, trade and guild requests are hidden')),
      h('span.act', h('button', { type: 'button', 'data-k': 'ub' + n, on: { click: () => app.send({ t: 'unblock', name: n }) } }, 'Unblock')))) :
      h('p.muted', 'Nobody is blocked.')) : null,
    friendsTab === 'trades' ? h('div.list', !s.tradeHistory ? h('p.muted', 'Loading…') : s.tradeHistory.length ?
      s.tradeHistory.map((t) => h('div.item.trade-rec',
        h('span.nm', `with ${t.partner}`, h('small', `${date(t.at)} · ${ago(t.at)}`)),
        h('span.gave', h('small', 'gave'), t.gave), h('span.got', h('small', 'got'), t.got))) :
      h('p.muted', 'No trades yet.')) : null,
  ]);
}

// ------------------------------------------------------------------ profile

export function openProfile(name) {
  wantProfile = name;
  const p = panels.open('profile', name);
  p.title.textContent = name;
  p.onClose = () => { wantProfile = null; };
  rerender(p.body, () => h('p.muted', 'Loading…'));
  app.send({ t: 'profile', name });
}

function renderProfile(pr) {
  const p = panels.get('profile');
  if (!p) return;
  p.title.textContent = pr.name;
  const act = (label, k, fn, cls = '') => h('button' + cls, { type: 'button', 'data-k': k, on: { click: fn } }, label);
  rerender(p.body, () => [
    h('div.row.prof-head',
      h('span.dot', { class: pr.online ? 'on' : 'off' }),
      h('div.grow',
        h('b', pr.name), pr.tag ? h('span.gtag', `[${pr.tag}]`) : null,
        h('div.muted', pr.online ? `Online · ${placeName(pr.location) || ''}` : 'Offline'),
        h('div.muted', `${pr.guild ? 'Guild ' + pr.guild + ' · ' : ''}Trainer since ${date(pr.created)} · ${pr.trades} trade${pr.trades === 1 ? '' : 's'}`))),
    h('h3', 'Party'),
    h('div.prof-party', pr.party.length ? pr.party.map((m) => h('div.pmon', monImg(m.dex, 'icon', ''), h('small', `${m.name} Lv${m.level}${m.shiny ? ' ★' : ''}`))) : h('p.muted', 'Hidden while offline.')),
    h('div.row',
      pr.friend ? act('Remove friend', 'pfr', () => app.send({ t: 'friends.remove', name: pr.name }), '.grow')
        : act('＋ Add friend', 'pfa', () => app.send({ t: 'friends.add', name: pr.name }), '.go.grow'),
      act('✉ Whisper', 'pfw', () => { panels.close('profile'); chat.whisper(pr.name); }, '.grow'),
      act('⇄ Trade', 'pft', () => { panels.close('profile'); app.send({ t: 'trade.request', name: pr.name }); }, '.grow'),
      act('⚔ Duel', 'pfd', () => { panels.close('profile'); openChallenge(pr.name); }, '.grow'),
      pr.blocked ? act('Unblock', 'pfu', () => app.send({ t: 'unblock', name: pr.name }), '.grow')
        : act('Block', 'pfb', () => app.send({ t: 'block', name: pr.name }), '.danger.grow')),
  ]);
  // The block/friend toggles change the profile; ask again so it shows the new state.
  for (const k of ['pfr', 'pfa', 'pfu', 'pfb']) {
    const b = p.body.querySelector(`[data-k=${k}]`);
    if (b) b.addEventListener('click', () => setTimeout(() => app.send({ t: 'profile', name: pr.name }), 250));
  }
}

// ------------------------------------------------------------------ player context menu

/** Actions for a tapped / faced player (or a name clicked in the chat). */
export function openPlayer(ent) {
  if (!ent || !ent.name) return;
  const me = app.net.state.world && (app.net.state.world.entities || []).find((e) => e.id === app.net.state.world.self);
  if (me && me.name === ent.name) return;
  const p = panels.open('player', ent.name);
  p.title.textContent = ent.name;
  const s = social();
  const friend = s.friends.some((f) => f.name === ent.name);
  const act = (label, k, fn, cls = '') => h('button' + cls, { type: 'button', 'data-k': k, on: { click: () => { panels.close('player'); fn(); } } }, label);
  rerender(p.body, () => [h('div.ctx',
    act('👤 Profile', 'profile', () => openProfile(ent.name)),
    act('✉ Whisper', 'whisper', () => chat.whisper(ent.name)),
    act('⇄ Trade', 'trade', () => app.send({ t: 'trade.request', name: ent.name })),
    act('⚔ Duel', 'duel', () => openChallenge(ent.name)),
    friend ? act('✓ Friend (remove)', 'unfriend', () => app.send({ t: 'friends.remove', name: ent.name }))
      : act('＋ Add friend', 'friend', () => app.send({ t: 'friends.add', name: ent.name }), '.go'),
    s.guild && s.guild.inGuild && s.guild.myRank >= 2 && !(s.guild.members || []).some((m) => m.name === ent.name)
      ? act('⚑ Invite to guild', 'ginvite', () => app.send({ t: 'guild.invite', name: ent.name })) : null)]);
  p.onClose = () => app.setHighlight(null);
  if (ent.id) app.setHighlight(ent.id);
  setFocus(p.body, p.body.querySelector('button'));
}

// ------------------------------------------------------------------ guild

export function openGuild() {
  const p = panels.open('guild', 'Guild');
  app.send({ t: 'social.refresh' });
  renderGuild();
  setFocus(p.body, p.body.querySelector('button, input'));
}

function renderGuild() {
  const p = panels.get('guild');
  if (!p) return;
  const g = social().guild || { inGuild: false };
  if (!g.inGuild) {
    p.title.textContent = 'Guild';
    const name = h('input', { placeholder: 'guild name (3-24)', maxlength: 24, 'aria-label': 'guild name', 'data-k': 'gname' });
    const tag = h('input', { placeholder: 'TAG', maxlength: 5, 'aria-label': 'guild tag', 'data-k': 'gtag', style: 'width:6em;text-transform:uppercase' });
    rerender(p.body, () => [
      h('p', 'You are not in a guild. Ask an officer for an invite, or found your own.'),
      h('form.row', { on: { submit: (e) => { e.preventDefault(); if (name.value.trim() && tag.value.trim()) app.send({ t: 'guild.create', name: name.value.trim(), tag: tag.value.trim().toUpperCase() }); } } },
        name, tag, h('button.go', { type: 'submit', 'data-k': 'gcreate' }, 'Found guild')),
      h('p.muted', 'Founding a guild costs ₽15,000.'),
    ]);
    return;
  }
  p.title.textContent = `${g.name} [${g.tag}]`;
  const mine = g.myRank;
  const officer = mine >= 2;
  const motd = h('input', { value: g.motd, maxlength: 200, placeholder: 'message of the day', 'aria-label': 'message of the day', 'data-k': 'gmotd' });
  const invite = h('input', { placeholder: 'invite trainer', maxlength: 12, 'aria-label': 'invite trainer', 'data-k': 'ginv' });
  const online = g.members.filter((m) => m.online).length;
  rerender(p.body, () => [
    h('div.motd', g.motd ? ['📌 ', g.motd] : h('span.muted', 'No message of the day.')),
    officer ? h('form.row', { on: { submit: (e) => { e.preventDefault(); app.send({ t: 'guild.motd', text: motd.value }); } } },
      motd, h('button', { type: 'submit', 'data-k': 'gsetmotd' }, 'Set')) : null,
    h('h3', `Members (${online}/${g.members.length} online) · you are ${g.myRankName}`),
    h('div.list', g.members.map((m) => {
      const me = m.rank === mine && m.name === (myName() || '');
      const canKick = officer && m.rank < mine && !me;
      const canPromote = officer && m.rank < mine && m.rank + 1 < mine && !me;
      const canDemote = officer && m.rank < mine && m.rank > 0 && !me;
      return h('div.item.member', { class: m.online ? 'on' : 'off' },
        h('span.dot'),
        h('span.nm', m.name, h('span.rank.r' + m.rank, m.rankName), h('small', m.online ? (placeName(m.location) || 'online') : 'offline')),
        h('span.act',
          canPromote ? h('button', { type: 'button', 'data-k': 'gp' + m.name, title: `Promote to ${RANKS[m.rank + 1]}`, on: { click: () => app.send({ t: 'guild.rank', name: m.name, rank: m.rank + 1 }) } }, '▲') : null,
          canDemote ? h('button', { type: 'button', 'data-k': 'gd' + m.name, title: `Demote to ${RANKS[m.rank - 1]}`, on: { click: () => app.send({ t: 'guild.rank', name: m.name, rank: m.rank - 1 }) } }, '▼') : null,
          mine === 5 && !me ? h('button', { type: 'button', 'data-k': 'gl' + m.name, title: 'Make leader', on: { click: () => confirm(`Hand the leadership of ${g.name} to ${m.name}?`) && app.send({ t: 'guild.rank', name: m.name, rank: 5 }) } }, '♛') : null,
          canKick ? h('button.danger', { type: 'button', 'data-k': 'gk' + m.name, title: 'Kick', on: { click: () => confirm(`Remove ${m.name} from the guild?`) && app.send({ t: 'guild.kick', name: m.name }) } }, '✕') : null,
          !me && m.online ? h('button', { type: 'button', 'data-k': 'gw' + m.name, title: 'Whisper', on: { click: () => { panels.close('guild'); chat.whisper(m.name); } } }, '✉') : null));
    })),
    officer ? h('form.row', { on: { submit: (e) => { e.preventDefault(); const n = invite.value.trim(); if (n) { app.send({ t: 'guild.invite', name: n }); invite.value = ''; } } } },
      invite, h('button.go', { type: 'submit', 'data-k': 'ginvite' }, 'Invite')) : null,
    h('div.row',
      h('button.grow', { type: 'button', 'data-k': 'gchat', on: { click: () => { panels.close('guild'); chat.open('/gu '); } } }, 'Guild chat'),
      mine === 5 ? h('button.danger.grow', { type: 'button', 'data-k': 'gdisband', on: { click: () => confirm(`Disband ${g.name}? This cannot be undone.`) && app.send({ t: 'guild.disband' }) } }, 'Disband')
        : h('button.danger.grow', { type: 'button', 'data-k': 'gleave', on: { click: () => confirm(`Leave ${g.name}?`) && app.send({ t: 'guild.leave' }) } }, 'Leave')),
    h('h3', 'Activity'),
    h('div.glog', g.log.length ? g.log.map((l) => h('div', h('small', ago(l.at) + ' '), l.text)) : h('span.muted', 'Nothing yet.')),
  ]);
}

function myName() {
  const w = app.net.state.world;
  const me = w && (w.entities || []).find((e) => e.id === w.self);
  return me && me.name;
}

function showInvite(inv) {
  const p = panels.open('ginvite', 'Guild invite', { onBack: () => app.send({ t: 'guild.answer', accept: false }) });
  const left = () => Math.max(0, Math.round((inv.expiresAt - Date.now()) / 1000));
  const timer = h('span.muted', `${left()} s`);
  const iv = setInterval(() => {
    if (!panels.isOpen('ginvite')) return clearInterval(iv);
    timer.textContent = `${left()} s`;
    if (left() <= 0) { clearInterval(iv); panels.close('ginvite'); toast('The guild invite expired.'); }
  }, 1000);
  rerender(p.body, () => [h('p.big', `${inv.from} invites you to ${inv.guild} [${inv.tag}].`), h('p', 'Answer within ', timer),
    h('div.row', h('button.go.grow', { type: 'button', 'data-k': 'gacc', on: { click: () => app.send({ t: 'guild.answer', accept: true }) } }, 'Join'),
      h('button.danger.grow', { type: 'button', 'data-k': 'gdec', on: { click: () => app.send({ t: 'guild.answer', accept: false }) } }, 'Decline'))]);
  setFocus(p.body, p.body.querySelector('[data-k=gacc]'));
}
