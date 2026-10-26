#!/usr/bin/env node
// Dev-only MOCK of web.gateway (docs/web/PROTOCOL.md sections 1-3). Serves web/static and /ws on
// 127.0.0.1 and runs a scripted single-player world per socket so the browser client can be
// developed and screenshotted without the Kotlin gateway / game servers.
//
//   node mock-gateway.js [--port 8381] [--latency 80] [--assets <dir>]
//
// Latency is the simulated round trip (half applied each way). Assets: web/static/assets if it has
// a manifest.json, else web/dev/placeholder-assets (run gen-placeholder-assets.js first).
//
// Chat commands (in addition to plain chat):
//   /mock battle | trainer | learn | invite | trade | tradereq | market | dialog | shop | warp | home
//   /mock latency <ms> | /mock friend  (Friend walks next to you)
//   /testbattle (= /mock battle)
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const arg = (k, d) => {
  const i = process.argv.indexOf('--' + k);
  return i < 0 ? d : process.argv[i + 1];
};
const PORT = +arg('port', process.env.PORT || 8381);
let LATENCY = +arg('latency', 80);
const STATIC = path.resolve(__dirname, '../static');
const ASSETS = (() => {
  const a = arg('assets', null);
  if (a) return path.resolve(a);
  const real = path.join(STATIC, 'assets');
  if (fs.existsSync(path.join(real, 'manifest.json'))) return real;
  return path.join(__dirname, 'placeholder-assets');
})();
const manifest = JSON.parse(fs.readFileSync(path.join(ASSETS, 'manifest.json'), 'utf8'));
const cols = {};
function mapInfo(key) {
  const m = manifest.maps[key];
  if (!m) return null;
  if (!cols[key]) cols[key] = fs.readFileSync(path.join(ASSETS, m.collision));
  return m;
}
function blocked(key, x, y) {
  const m = mapInfo(key);
  if (!m || x < 0 || y < 0 || x >= m.width || y >= m.height) return true;
  return (cols[key][y * m.width + x] & 1) !== 0;
}
const keyObj = (k) => {
  const [region, bank, map] = k.split(':').map(Number);
  return { region, bank, map };
};

// ------------------------------------------------------------------ static HTTP
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.png': 'image/png', '.bin': 'application/octet-stream', '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon', '.webmanifest': 'application/manifest+json',
};
const server = http.createServer((req, res) => {
  let p;
  try {
    p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  } catch {
    res.writeHead(400).end();
    return;
  }
  if (p === '/') p = '/index.html';
  let root = STATIC;
  let rel = p;
  if (p.startsWith('/assets/')) {
    root = ASSETS;
    rel = p.slice('/assets'.length);
  }
  const file = path.resolve(root, '.' + rel);
  if (!file.startsWith(root + path.sep)) {
    res.writeHead(403).end();
    return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' }).end('not found');
      return;
    }
    const ext = path.extname(file);
    res.writeHead(200, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Cache-Control': root === ASSETS ? 'public, max-age=86400' : 'no-cache',
    });
    res.end(data);
  });
});

// ------------------------------------------------------------------ game data
const ITEMS = { 4: 'POKE BALL', 13: 'POTION', 14: 'ANTIDOTE', 17: 'PARLYZ HEAL', 20: 'SUPER POTION', 21: 'REVIVE' };
const NAMES = { 1: 'BULBASAUR', 4: 'CHARMANDER', 7: 'SQUIRTLE', 16: 'PIDGEY', 19: 'RATTATA', 25: 'PIKACHU', 10: 'CATERPIE', 133: 'EEVEE', 129: 'MAGIKARP', 63: 'ABRA' };
const MOVES = { 4: ['SCRATCH', 'GROWL', 'EMBER', 'SMOKESCREEN'], 1: ['TACKLE', 'GROWL', 'VINE WHIP'], 7: ['TACKLE', 'TAIL WHIP', 'BUBBLE'], 25: ['THUNDERSHOCK', 'GROWL', 'QUICK ATTACK'], 16: ['TACKLE', 'SAND-ATTACK'] };
const mon = (slot, dex, level, nick = null) => {
  const maxHp = Math.floor(level * 2.4 + 10);
  return { slot, dex, name: NAMES[dex] || `MON${dex}`, nick, level, hp: maxHp, maxHp,
    moves: (MOVES[dex] || ['TACKLE', 'GROWL']).map((n) => ({ name: n, pp: 30, maxPp: 35 })) };
};
const pubMon = (m) => ({ slot: m.slot, dex: m.dex, name: m.name, nick: m.nick, level: m.level, hp: m.hp, maxHp: m.maxHp });

let nextListing = 100;
const MARKET = [];
(() => {
  const sellers = ['Misty', 'Brock', 'Gary', 'Erika', 'Lt.Surge'];
  const dexes = [25, 133, 129, 63, 16, 19, 10, 1, 7, 4];
  for (let i = 0; i < 37; i++) {
    const isMon = i % 3 !== 2;
    const dex = dexes[i % dexes.length];
    const item = [13, 4, 20, 21][i % 4];
    MARKET.push({ id: String(nextListing++), kind: isMon ? 'mon' : 'item', dex: isMon ? dex : null, item: isMon ? null : item,
      name: isMon ? NAMES[dex] : ITEMS[item], level: isMon ? 5 + ((i * 7) % 30) : null, qty: isMon ? 1 : 1 + (i % 5),
      price: 200 + ((i * 1373) % 9000), expiresAt: Math.floor(Date.now() / 1000) + 86400 * (1 + (i % 6)), mine: false,
      seller: sellers[i % sellers.length] });
  }
})();

// ------------------------------------------------------------------ session
const PALLET = '0:3:0';
const SELF = '1000000000001';
const FRIEND = '1000000000002';

class Session {
  constructor(ws) {
    this.ws = ws;
    this.loggedIn = false;
    this.last = {};
    this.dirty = new Set();
    this.seq = 0;
    this.timers = [];
    this.loginTimes = [];
    this.lastSeen = Date.now();
    this.flushTimer = null;
  }
  later(ms, fn) {
    const t = setTimeout(() => {
      this.timers = this.timers.filter((x) => x !== t);
      if (this.ws.readyState === 1) fn();
    }, ms);
    this.timers.push(t);
  }
  every(ms, fn) {
    const t = setInterval(() => this.ws.readyState === 1 && fn(), ms);
    this.timers.push(t);
  }
  close() {
    this.timers.forEach((t) => (clearTimeout(t), clearInterval(t)));
    clearInterval(this.flushTimer);
  }
  send(obj) {
    const s = JSON.stringify(obj);
    setTimeout(() => this.ws.readyState === 1 && this.ws.send(s), LATENCY / 2);
  }
  mark(...secs) {
    secs.forEach((s) => this.dirty.add(s));
  }
  flush() {
    if (!this.loggedIn || this.dirty.size === 0) return;
    const out = { t: 'state', seq: ++this.seq };
    let any = false;
    for (const sec of this.dirty) {
      const v = this.section(sec);
      const js = JSON.stringify(v);
      if (this.last[sec] !== js) {
        this.last[sec] = js;
        out[sec] = v;
        any = true;
      }
    }
    this.dirty.clear();
    if (any) this.send(out);
  }
  section(sec) {
    switch (sec) {
      case 'world': {
        const m = manifest.maps[this.map];
        return { map: m ? { ...keyObj(this.map), name: m.name, width: m.width, height: m.height } : null, self: SELF,
          inWorld: true, ackSeq: this.ackSeq,
          entities: this.entities.filter((e) => e.map === this.map).map((e) => ({ id: e.id, kind: e.kind, name: e.name,
            gfx: e.gfx, x: e.x, y: e.y, facing: e.facing, moving: e.moving, map: keyObj(e.map) })) };
      }
      case 'chat': return this.chat.slice(-50);
      case 'ui': return { dialog: this.dialog ? this.dialogView() : null, shop: this.shop, money: this.money,
        bag: this.bag.filter((b) => b.qty > 0), locked: !!this.locked };
      case 'party': return this.party.map(pubMon);
      case 'battle': return this.battle ? this.battleView() : null;
      case 'invite': return this.invite;
      case 'trade': return this.trade;
      case 'market': return this.market;
      default: return null;
    }
  }

  // ---------------------------------------------------------------- login / world
  login(m) {
    const now = Date.now();
    this.loginTimes = this.loginTimes.filter((t) => now - t < 60000);
    if (this.loginTimes.length >= 5) return this.send({ t: 'login', ok: false, error: 'RATE_LIMITED' });
    this.loginTimes.push(now);
    if (!m.user || m.pass === 'wrong') return this.send({ t: 'login', ok: false, error: 'INVALID_PASSWORD' });
    if (this.loggedIn) return this.send({ t: 'error', msg: 'already logged in' });
    this.name = String(m.name || m.user).slice(0, 12);
    this.loggedIn = true;
    this.map = PALLET;
    this.ackSeq = 0;
    this.money = 3000;
    this.bag = [{ item: 13, name: 'POTION', qty: 3 }, { item: 4, name: 'POKE BALL', qty: 5 }, { item: 14, name: 'ANTIDOTE', qty: 1 }];
    this.party = [mon(0, 4, 15), mon(1, 1, 12), mon(2, 25, 9, 'SPARKY')];
    this.chat = [{ sender: '*', text: 'Welcome to OpenMMO! (mock gateway)' }, { sender: '*', text: 'Try /mock battle, /mock trade, /mock market, /mock shop' }];
    this.entities = [
      { id: SELF, kind: 'player', name: this.name, gfx: 0, x: 6, y: 8, facing: 'down', moving: false, map: PALLET },
      { id: FRIEND, kind: 'player', name: 'Friend', gfx: 7, x: 6, y: 15, facing: 'right', moving: false, map: PALLET, path: 0 },
      { id: '2001', kind: 'npc', name: 'WOMAN', gfx: 23, x: 3, y: 10, facing: 'down', moving: false, map: PALLET, home: [3, 10], range: [1, 4],
        talk: ['You can type and save', 'your own messages', 'on a computer!'] },
      { id: '2002', kind: 'npc', name: 'FAT MAN', gfx: 27, x: 13, y: 17, facing: 'down', moving: false, map: PALLET, home: [13, 17], range: [6, 2],
        talk: ['Technology is incredible!', 'You can now store and recall items', 'and POKeMON as data via PC!'] },
      { id: '2003', kind: 'npc', name: 'OAK', gfx: 71, x: 10, y: 8, facing: 'down', moving: false, map: PALLET, oak: true },
      { id: '2004', kind: 'npc', name: 'CLERK', gfx: 68, x: 20, y: 8, facing: 'down', moving: false, map: PALLET, clerk: true },
    ];
    this.self = this.entities[0];
    this.send({ t: 'login', ok: true, selfId: SELF, name: this.name });
    this.mark('world', 'chat', 'ui', 'party', 'battle', 'invite', 'trade', 'market');
    this.flushTimer = setInterval(() => this.flush(), 33);
    this.every(700, () => this.tickNpcs());
    this.every(260, () => this.tickFriend());
    this.every(23000, () => this.say('Friend', ['anyone up for a duel?', 'trading a PIKACHU, dm me', 'route 1 has so many PIDGEY'][Math.floor(Math.random() * 3)]));
  }
  say(sender, text) {
    this.chat.push({ sender, text });
    if (this.chat.length > 50) this.chat.shift();
    this.mark('chat');
  }
  occupied(map, x, y, except) {
    return this.entities.some((e) => e !== except && e.map === map && e.x === x && e.y === y);
  }
  stepEntity(e, dir) {
    const [dx, dy] = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] }[dir];
    e.facing = dir;
    let nx = e.x + dx, ny = e.y + dy, nmap = e.map;
    const m = mapInfo(e.map);
    if (nx < 0 || ny < 0 || nx >= m.width || ny >= m.height) {
      const c = (m.connections || []).find((c) => c.dir === dir);
      const b = c && mapInfo(c.map);
      if (!b) return false;
      if (dir === 'up') (nx -= c.offset), (ny = b.height - 1);
      if (dir === 'down') (nx -= c.offset), (ny = 0);
      if (dir === 'left') (nx = b.width - 1), (ny -= c.offset);
      if (dir === 'right') (nx = 0), (ny -= c.offset);
      nmap = c.map;
    }
    if (blocked(nmap, nx, ny) || this.occupied(nmap, nx, ny, e)) return false;
    e.x = nx;
    e.y = ny;
    e.map = nmap;
    e.moving = true;
    clearTimeout(e.stopT);
    e.stopT = setTimeout(() => {
      e.moving = false;
      this.mark('world');
    }, 260);
    return true;
  }
  tickNpcs() {
    if (this.map !== PALLET) return;
    for (const e of this.entities) {
      if (e.kind !== 'npc' || !e.range || this.dialog) continue;
      if (Math.random() > 0.3) continue;
      const dir = ['up', 'down', 'left', 'right'][Math.floor(Math.random() * 4)];
      const [dx, dy] = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] }[dir];
      if (Math.abs(e.x + dx - e.home[0]) > e.range[0] || Math.abs(e.y + dy - e.home[1]) > e.range[1]) {
        e.facing = dir;
      } else this.stepEntity(e, dir);
      this.mark('world');
    }
  }
  tickFriend() {
    const f = this.entities[1];
    if (f.follow) {
      return;
    }
    // walks a rectangle (6..11, 15..18)
    const loop = ['right', 'right', 'right', 'right', 'right', 'down', 'down', 'down', 'left', 'left', 'left', 'left', 'left', 'up', 'up', 'up'];
    if (f.pause > 0) {
      f.pause--;
      return;
    }
    if (this.stepEntity(f, loop[f.path % loop.length])) f.path++;
    else f.pause = 3;
    if (f.path % 16 === 0) f.pause = 8;
    this.mark('world');
  }
  onStep(m) {
    const seq = m.seq | 0;
    if (!['up', 'down', 'left', 'right'].includes(m.dir)) return this.send({ t: 'error', msg: 'bad dir' });
    if (!this.dialog && !this.shop && !this.battle && !this.locked) {
      const before = this.map;
      this.stepEntity(this.self, m.dir);
      if (this.self.map !== before) {
        this.map = this.self.map;
        this.say('*', `Entered ${manifest.maps[this.map].name}`);
      }
      if (this.map === PALLET && Math.random() < 0) this.startBattle(true);
    }
    this.ackSeq = Math.max(this.ackSeq, seq);
    this.mark('world');
  }
  facingTile() {
    const [dx, dy] = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] }[this.self.facing];
    return [this.self.x + dx, this.self.y + dy];
  }
  onA() {
    if (this.dialog) return this.advanceDialog();
    if (this.shop || this.battle) return;
    const [x, y] = this.facingTile();
    const e = this.entities.find((n) => n.map === this.map && n.x === x && n.y === y && n.kind === 'npc');
    if (!e) return;
    e.facing = { up: 'down', down: 'up', left: 'right', right: 'left' }[this.self.facing];
    this.mark('world');
    if (e.clerk) return this.openDialog('CLERK', ['Hi there!', 'May I help you?'], ['BUY', 'SELL', 'SEE YA!'], (i) => {
      if (i === 0 || i === 1) this.openShop();
    });
    if (e.oak) return this.openDialog('OAK', ["Hello there! Welcome to the", 'world of POKeMON!', 'Would you like to battle?'], ['YES', 'NO'], (i) => {
      if (i === 0) this.startBattle(false, 'PROF. OAK');
      else this.openDialog('OAK', ['Come back any time!']);
    });
    this.openDialog(e.name, e.talk);
  }
  openDialog(speaker, lines, choices = null, onChoice = null) {
    // two lines per page
    this.dialog = { speaker, pages: [], page: 0, choices, cursor: 0, onChoice };
    for (let i = 0; i < lines.length; i += 2) this.dialog.pages.push(lines.slice(i, i + 2));
    this.mark('ui');
  }
  dialogView() {
    const d = this.dialog;
    const last = d.page >= d.pages.length - 1;
    return { speaker: d.speaker, lines: d.pages[d.page], lastPage: last, choices: last ? d.choices : null, cursor: d.cursor };
  }
  advanceDialog() {
    const d = this.dialog;
    if (d.page < d.pages.length - 1) d.page++;
    else {
      this.dialog = null;
      if (d.choices && d.onChoice) d.onChoice(d.cursor);
    }
    this.mark('ui');
  }
  openShop() {
    this.shop = { offers: [{ item: 4, name: 'POKE BALL', price: 200 }, { item: 13, name: 'POTION', price: 300 }, { item: 14, name: 'ANTIDOTE', price: 100 },
      { item: 17, name: 'PARLYZ HEAL', price: 200 }, { item: 20, name: 'SUPER POTION', price: 700 }, { item: 21, name: 'REVIVE', price: 1500 }], cursor: 0, status: '' };
    this.mark('ui');
  }
  shopBuy(m) {
    const o = this.shop && this.shop.offers.find((x) => x.item === m.item);
    const qty = Math.max(1, Math.min(99, m.qty | 0));
    if (!o) return this.send({ t: 'error', msg: 'no such offer' });
    if (o.price * qty > this.money) this.shop.status = "You don't have enough money.";
    else {
      this.money -= o.price * qty;
      this.addItem(o.item, qty);
      this.shop.status = `Bought ${qty}x ${o.name} for ${o.price * qty}.`;
    }
    this.mark('ui');
  }
  shopSell(m) {
    const b = this.bag.find((x) => x.item === m.item);
    const qty = Math.max(1, m.qty | 0);
    if (!this.shop || !b || b.qty < qty) return this.send({ t: 'error', msg: 'cannot sell' });
    b.qty -= qty;
    const price = 50 * qty;
    this.money += price;
    this.shop.status = `Sold ${qty}x ${b.name} for ${price}.`;
    this.mark('ui');
  }
  addItem(item, qty) {
    const b = this.bag.find((x) => x.item === item);
    if (b) b.qty += qty;
    else this.bag.push({ item, name: ITEMS[item] || `ITEM${item}`, qty });
  }

  // ---------------------------------------------------------------- battle
  startBattle(wild, opponentName = '') {
    if (this.battle) return;
    this.dialog = null;
    const foe = wild ? mon(0, [16, 19, 10][Math.floor(Math.random() * 3)], 3 + Math.floor(Math.random() * 4)) : mon(0, 133, 14);
    this.battle = { wild, opponentName, phase: 'ACTION', turn: 1, active: this.party.findIndex((p) => p.hp > 0), foe,
      log: [wild ? `Wild ${foe.name} appeared!` : `${opponentName} wants to battle!`, `Go! ${this.party[0].name}!`], result: null, learn: null };
    this.mark('battle', 'ui');
  }
  battleView() {
    const b = this.battle;
    const me = this.party[b.active];
    return { wild: b.wild, opponentName: b.opponentName, phase: b.phase, turn: b.turn,
      me: { dex: me.dex, name: me.nick || me.name, level: me.level, hp: me.hp, maxHp: me.maxHp, moves: me.moves },
      foe: { dex: b.foe.dex, name: b.foe.name, level: b.foe.level, hp: b.foe.hp, maxHp: b.foe.maxHp },
      party: this.party.map((p) => ({ dex: p.dex, name: p.nick || p.name, level: p.level, hp: p.hp, maxHp: p.maxHp })),
      log: b.log.slice(-6), result: b.result, learn: b.learn };
  }
  battleLog(...l) {
    this.battle.log.push(...l);
  }
  endBattle(result) {
    const b = this.battle;
    b.phase = 'ENDED';
    b.result = result;
    this.mark('battle');
    this.later(3500, () => {
      if (this.battle === b && !b.learn) {
        this.battle = null;
        this.mark('battle', 'party');
      }
    });
  }
  resolveTurn(myAction) {
    const b = this.battle;
    if (!b || b.phase !== 'ACTION') return;
    b.phase = 'WAITING';
    this.mark('battle');
    this.later(500, () => {
      const me = this.party[b.active];
      myAction(me);
      if (b.phase === 'ENDED') return this.mark('battle', 'party');
      if (b.foe.hp <= 0) {
        this.battleLog(`${b.wild ? 'Wild ' : 'Foe '}${b.foe.name} fainted!`, `${me.name} gained 120 EXP. Points!`);
        if (b.learnOnWin) {
          b.learn = { move: 'FLAMETHROWER', current: me.moves.map((m) => m.name) };
          b.phase = 'ENDED';
          this.battleLog(`${me.name} is trying to learn FLAMETHROWER.`);
          return this.mark('battle', 'party');
        }
        return this.endBattle('WIN');
      }
      const dmg = 2 + Math.floor(Math.random() * 5);
      me.hp = Math.max(0, me.hp - dmg);
      this.battleLog(`${b.wild ? 'Wild ' : 'Foe '}${b.foe.name} used TACKLE!`);
      if (me.hp <= 0) {
        this.battleLog(`${me.name} fainted!`);
        if (this.party.some((p) => p.hp > 0)) b.phase = 'FORCED_SWITCH';
        else return this.endBattle('LOSE');
      } else b.phase = 'ACTION';
      b.turn++;
      this.mark('battle', 'party');
    });
  }
  onFight(m) {
    const i = m.move | 0;
    this.resolveTurn((me) => {
      const mv = me.moves[i];
      if (!mv || mv.pp <= 0) return this.battleLog('But there was no PP left!');
      mv.pp--;
      const dmg = 3 + Math.floor(Math.random() * 6);
      this.battle.foe.hp = Math.max(0, this.battle.foe.hp - dmg);
      this.battleLog(`${me.nick || me.name} used ${mv.name}!`);
      if (dmg > 7) this.battleLog("It's super effective!");
    });
  }
  onSwitch(m) {
    const b = this.battle;
    const s = m.slot | 0;
    if (!b || !this.party[s] || this.party[s].hp <= 0 || s === b.active) return this.send({ t: 'error', msg: 'cannot switch' });
    if (b.phase === 'FORCED_SWITCH') {
      b.active = s;
      b.phase = 'ACTION';
      this.battleLog(`Go! ${this.party[s].name}!`);
      return this.mark('battle');
    }
    this.resolveTurn(() => {
      b.active = s;
      this.battleLog(`Come back! Go! ${this.party[s].name}!`);
    });
  }
  onRun() {
    if (!this.battle) return;
    if (!this.battle.wild) {
      this.battleLog("No! There's no running from a trainer battle!");
      return this.mark('battle');
    }
    this.resolveTurn(() => {
      this.battleLog('Got away safely!');
      this.endBattle('RAN');
    });
  }
  onBall() {
    const b = this.battle;
    if (!b) return;
    const ball = this.bag.find((x) => x.item === 4 && x.qty > 0);
    if (!b.wild || !ball) {
      this.battleLog(!ball ? 'You have no POKE BALLS!' : "The trainer blocked the BALL!");
      return this.mark('battle');
    }
    this.resolveTurn(() => {
      ball.qty--;
      this.battleLog(`${this.name} used POKE BALL!`);
      if (Math.random() < 0.6 || b.foe.hp < b.foe.maxHp / 2) {
        this.battleLog(`Gotcha! ${b.foe.name} was caught!`);
        if (this.party.length < 6) this.party.push({ ...b.foe, slot: this.party.length });
        this.mark('ui');
        this.endBattle('CAUGHT');
      } else this.battleLog('Oh no! The POKeMON broke free!');
    });
  }
  onLearn(m) {
    const b = this.battle;
    if (!b || !b.learn) return;
    const me = this.party[b.active];
    if (m.slot === null || m.slot === undefined) this.battleLog(`${me.name} did not learn ${b.learn.move}.`);
    else {
      const old = me.moves[m.slot | 0];
      this.battleLog(`${me.name} forgot ${old ? old.name : '-'} and learned ${b.learn.move}!`);
      me.moves[m.slot | 0] = { name: b.learn.move, pp: 15, maxPp: 15 };
    }
    b.learn = null;
    this.endBattle('WIN');
  }

  // ---------------------------------------------------------------- trade
  tradeSide() {
    return { stage: 'NONE', mons: [], items: [], money: 0 };
  }
  tradeOpen(partner, phase) {
    this.trade = { phase, partner, message: '', mine: this.tradeSide(), theirs: this.tradeSide() };
    this.mark('trade');
  }
  tradePartnerOffers() {
    const t = this.trade;
    if (!t || t.phase !== 'OPEN') return;
    t.theirs.mons = [{ slot: 0, dex: 25, name: 'PIKACHU', level: 18 }];
    t.theirs.items = [{ item: 20, name: 'SUPER POTION', qty: 2 }];
    t.theirs.money = 250;
    this.mark('trade');
  }
  tradeChanged() {
    const t = this.trade;
    t.mine.stage = 'NONE';
    t.theirs.stage = 'NONE';
    t.message = 'Offer changed.';
    this.mark('trade');
  }
  onTrade(type, m) {
    const t = this.trade;
    switch (type) {
      case 'trade.request':
        if (t) return this.send({ t: 'error', msg: 'already trading' });
        this.tradeOpen(String(m.name || ''), 'REQUEST_SENT');
        this.trade.message = `Waiting for ${m.name}...`;
        if (m.name !== 'Friend') {
          return this.later(1200, () => {
            if (this.trade) (this.trade.phase = 'CLOSED'), (this.trade.message = `${m.name} is not online.`), this.mark('trade'), this.later(2500, () => ((this.trade = null), this.mark('trade')));
          });
        }
        return this.later(1200, () => {
          if (!this.trade) return;
          this.trade.phase = 'OPEN';
          this.trade.message = 'Friend accepted.';
          this.mark('trade');
          this.later(900, () => this.tradePartnerOffers());
        });
      case 'trade.accept':
        if (!t || t.phase !== 'REQUEST_RECEIVED') return;
        t.phase = 'OPEN';
        t.message = '';
        this.mark('trade');
        return this.later(900, () => this.tradePartnerOffers());
      case 'trade.decline':
      case 'trade.cancel':
        if (!t) return;
        t.phase = 'CLOSED';
        t.message = type === 'trade.decline' ? 'Declined.' : 'Trade cancelled.';
        this.mark('trade');
        return this.later(1500, () => ((this.trade = null), this.mark('trade')));
      case 'trade.mon': {
        if (!t || t.phase !== 'OPEN') return;
        const p = this.party[m.slot | 0];
        if (!p) return this.send({ t: 'error', msg: 'no such slot' });
        const i = t.mine.mons.findIndex((x) => x.slot === p.slot);
        if (i >= 0) t.mine.mons.splice(i, 1);
        else t.mine.mons.push({ slot: p.slot, dex: p.dex, name: p.nick || p.name, level: p.level });
        return this.tradeChanged();
      }
      case 'trade.item': {
        if (!t || t.phase !== 'OPEN') return;
        const b = this.bag.find((x) => x.item === m.item);
        const qty = Math.max(0, Math.min(b ? b.qty : 0, m.qty | 0));
        t.mine.items = t.mine.items.filter((x) => x.item !== m.item);
        if (qty > 0 && b) t.mine.items.push({ item: b.item, name: b.name, qty });
        return this.tradeChanged();
      }
      case 'trade.money':
        if (!t || t.phase !== 'OPEN') return;
        t.mine.money = Math.max(0, Math.min(this.money, m.amount | 0));
        return this.tradeChanged();
      case 'trade.ready':
        if (!t || t.phase !== 'OPEN') return;
        t.mine.stage = 'READY';
        t.message = '';
        this.mark('trade');
        return this.later(1000, () => {
          if (this.trade === t && t.mine.stage !== 'NONE') (t.theirs.stage = 'READY'), this.mark('trade');
        });
      case 'trade.confirm':
        if (!t || t.mine.stage !== 'READY' || t.theirs.stage === 'NONE') return;
        t.mine.stage = 'CONFIRMED';
        this.mark('trade');
        return this.later(1000, () => {
          if (this.trade !== t) return;
          t.theirs.stage = 'CONFIRMED';
          t.phase = 'COMPLETED';
          t.message = 'Trade completed!';
          const gave = new Set(t.mine.mons.map((x) => x.slot));
          this.party = this.party.filter((p) => !gave.has(p.slot));
          t.theirs.mons.forEach((x) => this.party.push(mon(0, x.dex, x.level)));
          this.party.forEach((p, i) => (p.slot = i));
          t.mine.items.forEach((x) => this.addItem(x.item, -x.qty));
          t.theirs.items.forEach((x) => this.addItem(x.item, x.qty));
          this.money += t.theirs.money - t.mine.money;
          this.mark('trade', 'party', 'ui');
          this.later(2500, () => ((this.trade = null), this.mark('trade')));
        });
      default:
    }
  }

  // ---------------------------------------------------------------- market
  ensureMarket() {
    if (!this.market) this.market = { money: this.money, browse: [], browseTotal: 0, page: 0, mine: [], inventory: null, log: [] };
    this.market.money = this.money;
  }
  marketLog(s) {
    this.market.log.push(s);
    this.market.log = this.market.log.slice(-10);
  }
  onMarket(type, m) {
    this.ensureMarket();
    const mk = this.market;
    const view = (l) => ({ id: l.id, kind: l.kind, dex: l.dex, item: l.item, name: l.name, level: l.level, qty: l.qty, price: l.price,
      expiresAt: l.expiresAt, mine: !!l.mine });
    switch (type) {
      case 'market.search': {
        this.lastSearch = m;
        let r = MARKET.filter((l) => !l.mine);
        if (m.kind) r = r.filter((l) => l.kind === m.kind);
        if (m.id) r = r.filter((l) => (l.kind === 'mon' ? l.dex : l.item) === +m.id);
        if (m.min) r = r.filter((l) => l.price >= +m.min);
        if (m.max) r = r.filter((l) => l.price <= +m.max);
        if (m.sort === 'price') r.sort((a, b) => a.price - b.price);
        else if (m.sort === 'price-desc') r.sort((a, b) => b.price - a.price);
        else r.sort((a, b) => +b.id - +a.id);
        const page = Math.max(0, m.page | 0);
        mk.browse = r.slice(page * 10, page * 10 + 10).map(view);
        mk.browseTotal = r.length;
        mk.page = page;
        break;
      }
      case 'market.mine':
        mk.mine = MARKET.filter((l) => l.mine).map(view);
        break;
      case 'market.inventory':
        mk.inventory = { party: this.party.map(pubMon), bag: this.bag.filter((b) => b.qty > 0) };
        break;
      case 'market.listMon': {
        const p = this.party[m.slot | 0];
        const price = m.price | 0;
        if (!p || price <= 0) return this.marketLog('LIST failed: bad slot or price'), this.mark('market');
        if (this.party.length <= 1) return this.marketLog('LIST failed: cannot list your last POKeMON'), this.mark('market');
        this.party.splice(this.party.indexOf(p), 1);
        this.party.forEach((x, i) => (x.slot = i));
        MARKET.push({ id: String(nextListing++), kind: 'mon', dex: p.dex, item: null, name: p.name, level: p.level, qty: 1, price,
          expiresAt: Math.floor(Date.now() / 1000) + 7 * 86400, mine: true });
        this.marketLog(`LIST ok: ${p.name} for ${price}.`);
        mk.mine = MARKET.filter((l) => l.mine).map(view);
        mk.inventory = { party: this.party.map(pubMon), bag: this.bag.filter((b) => b.qty > 0) };
        this.mark('party');
        break;
      }
      case 'market.listItem': {
        const b = this.bag.find((x) => x.item === m.item);
        const qty = m.qty | 0, price = m.price | 0;
        if (!b || qty <= 0 || qty > b.qty || price <= 0) return this.marketLog('LIST failed: bad item/qty/price'), this.mark('market');
        b.qty -= qty;
        MARKET.push({ id: String(nextListing++), kind: 'item', dex: null, item: b.item, name: b.name, level: null, qty, price,
          expiresAt: Math.floor(Date.now() / 1000) + 7 * 86400, mine: true });
        this.marketLog(`LIST ok: ${qty}x ${b.name} for ${price}.`);
        mk.mine = MARKET.filter((l) => l.mine).map(view);
        mk.inventory = { party: this.party.map(pubMon), bag: this.bag.filter((b) => b.qty > 0) };
        this.mark('ui');
        break;
      }
      case 'market.buy': {
        const i = MARKET.findIndex((l) => l.id === String(m.id) && !l.mine);
        if (i < 0) return this.marketLog('BUY failed: listing gone'), this.mark('market');
        const l = MARKET[i];
        if (l.price > this.money) return this.marketLog('BUY failed: not enough money'), this.mark('market');
        this.money -= l.price;
        MARKET.splice(i, 1);
        if (l.kind === 'mon') this.party.length < 6 && this.party.push(mon(this.party.length, l.dex, l.level));
        else this.addItem(l.item, l.qty);
        this.marketLog(`BUY ok: Bought ${l.qty}x ${l.name} for ${l.price}.`);
        mk.browse = mk.browse.filter((x) => x.id !== l.id);
        mk.browseTotal--;
        this.mark('ui', 'party');
        break;
      }
      case 'market.cancel': {
        const i = MARKET.findIndex((l) => l.id === String(m.id) && l.mine);
        if (i < 0) return this.marketLog('CANCEL failed'), this.mark('market');
        const [l] = MARKET.splice(i, 1);
        if (l.kind === 'mon') this.party.push(mon(this.party.length, l.dex, l.level));
        else this.addItem(l.item, l.qty);
        this.marketLog(`CANCEL ok: ${l.name} returned.`);
        mk.mine = MARKET.filter((x) => x.mine).map(view);
        this.mark('ui', 'party');
        break;
      }
      default:
    }
    mk.money = this.money;
    this.mark('market');
  }

  // ---------------------------------------------------------------- commands
  command(text) {
    const [cmd, sub, arg1] = text.slice(1).trim().split(/\s+/);
    const c = cmd === 'testbattle' ? 'battle' : cmd === 'mock' ? sub : null;
    switch (c) {
      case 'battle': return this.startBattle(true);
      case 'trainer': return this.startBattle(false, 'YOUNGSTER BEN');
      case 'learn': this.startBattle(true); this.battle && (this.battle.learnOnWin = true); return;
      case 'invite': this.invite = { from: 'Friend' }; return this.mark('invite');
      case 'trade': return this.onTrade('trade.request', { name: 'Friend' });
      case 'tradereq': this.tradeOpen('Friend', 'REQUEST_RECEIVED'); this.trade.message = 'Friend wants to trade.'; return;
      case 'market': return this.onMarket('market.search', { page: 0 });
      case 'dialog': return this.openDialog('OAK', ['Hey! Wait! Don\'t go out!', 'It\'s unsafe! Wild POKeMON live', 'in tall grass!', 'You need your own POKeMON for', 'your protection.', 'Do you understand?'], ['YES', 'NO'], (i) => this.say('*', `You chose ${i === 0 ? 'YES' : 'NO'}`));
      case 'shop': return this.openShop();
      case 'warp': this.self.map = this.map = '0:3:1'; this.self.x = 22; this.self.y = 20; this.mark('world'); return this.say('*', 'Warped to Viridian City');
      case 'home': this.self.map = this.map = PALLET; this.self.x = 6; this.self.y = 8; this.mark('world'); return;
      case 'latency': LATENCY = Math.max(0, +arg1 || 0); return this.say('*', `latency ${LATENCY} ms`);
      case 'friend': {
        const f = this.entities[1];
        f.map = this.map;
        const [x, y] = this.facingTile();
        f.x = x; f.y = y; f.follow = true; f.facing = 'left';
        this.mark('world');
        return;
      }
      default:
        if (cmd === 'givemoney') { this.money += +sub || 0; this.mark('ui'); return; }
        return this.say('*', `Unknown command: ${text}`);
    }
  }

  handle(m) {
    if (m.t === 'ping') return this.send({ t: 'pong', n: m.n });
    if (m.t === 'login') return this.login(m);
    if (!this.loggedIn) return this.send({ t: 'error', msg: 'not logged in' });
    switch (m.t) {
      case 'step': return this.onStep(m);
      case 'face':
        if (['up', 'down', 'left', 'right'].includes(m.dir)) (this.self.facing = m.dir), this.mark('world');
        return;
      case 'say': {
        const text = String(m.text || '').slice(0, 200);
        if (!text) return;
        if (text.startsWith('/')) return this.command(text);
        this.say(this.name, text);
        if (/hi|hello/i.test(text)) this.later(1200, () => this.say('Friend', `hey ${this.name}!`));
        return;
      }
      case 'a': return this.onA();
      case 'b':
        if (this.dialog) {
          const d = this.dialog;
          this.dialog = null;
          if (d.choices && d.onChoice) d.onChoice(d.choices.length - 1);
        } else if (this.shop) this.shop = null;
        return this.mark('ui');
      case 'cursor':
        if (this.dialog && this.dialog.choices) {
          const n = this.dialog.choices.length;
          this.dialog.cursor = (this.dialog.cursor + (m.delta | 0) + n) % n;
        } else if (this.shop) {
          const n = this.shop.offers.length;
          this.shop.cursor = (this.shop.cursor + (m.delta | 0) + n) % n;
        }
        return this.mark('ui');
      case 'choose':
        if (this.dialog && this.dialog.choices) {
          this.dialog.cursor = Math.max(0, Math.min(this.dialog.choices.length - 1, m.index | 0));
          this.dialog.page = this.dialog.pages.length - 1;
          this.advanceDialog();
        }
        return;
      case 'shop.buy': return this.shopBuy(m);
      case 'shop.sell': return this.shopSell(m);
      case 'shop.close': this.shop = null; return this.mark('ui');
      case 'battle.fight': return this.onFight(m);
      case 'battle.switch': return this.onSwitch(m);
      case 'battle.run': return this.onRun();
      case 'battle.ball': return this.onBall();
      case 'battle.learn': return this.onLearn(m);
      case 'duel':
        this.say('*', `Challenged ${m.name} to a duel...`);
        return this.later(1200, () => {
          if (m.name === 'Friend') this.startBattle(false, 'Friend');
          else this.say('*', `${m.name} declined.`);
        });
      case 'duel.answer':
        if (!this.invite) return;
        if (m.accept) this.startBattle(false, this.invite.from);
        this.invite = null;
        return this.mark('invite');
      default:
        if (m.t.startsWith('trade.')) return this.onTrade(m.t, m);
        if (m.t.startsWith('market.')) return this.onMarket(m.t, m);
        return this.send({ t: 'error', msg: `unknown type ${m.t}` });
    }
  }
}

const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });
server.on('upgrade', (req, sock, head) => {
  if (new URL(req.url, 'http://x').pathname !== '/ws') return sock.destroy();
  const origin = req.headers.origin;
  if (origin && new URL(origin).host !== req.headers.host) return sock.destroy();
  wss.handleUpgrade(req, sock, head, (ws) => {
    const s = new Session(ws);
    const idle = setInterval(() => Date.now() - s.lastSeen > 30000 && ws.close(4000, 'idle'), 5000);
    ws.on('message', (data) => {
      s.lastSeen = Date.now();
      let m;
      try {
        m = JSON.parse(data.toString());
      } catch {
        return s.send({ t: 'error', msg: 'malformed json' });
      }
      if (!m || typeof m.t !== 'string') return s.send({ t: 'error', msg: 'missing t' });
      setTimeout(() => {
        try {
          s.handle(m);
        } catch (e) {
          s.send({ t: 'error', msg: String(e.message) });
        }
      }, LATENCY / 2);
    });
    ws.on('close', () => {
      clearInterval(idle);
      s.close();
    });
  });
});
server.listen(PORT, '127.0.0.1', () => console.log(`mock gateway http://127.0.0.1:${PORT}/ (assets: ${ASSETS}, latency ${LATENCY} ms)`));
