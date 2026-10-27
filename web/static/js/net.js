// WebSocket transport (PROTOCOL.md section 1): JSON text frames, 5 s ping/pong RTT, reconnect.
import { Emitter } from './util.js';

// r3-battle: 'evolution' (the evolution scene, also outside battles). r3-items: dex..learn.
export const SECTIONS = ['world', 'chat', 'ui', 'party', 'pc', 'battle', 'invite', 'evolution', 'trade', 'market',
  'dex', 'trainer', 'daycare', 'decor', 'learn'];

export function defaultWsUrl() {
  const q = new URLSearchParams(location.search).get('ws');
  if (q) return q;
  return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
}

export class Net extends Emitter {
  constructor(url) {
    super();
    this.url = url;
    this.ws = null;
    this.status = 'offline'; // offline | connecting | online
    this.rtt = null;
    this.pingN = 0;
    this.pingSent = new Map();
    this.pingTimer = 0;
    /** Latest value of every state section (null = absent). */
    this.state = Object.fromEntries(SECTIONS.map((s) => [s, null]));
    this.stateSeq = 0;
  }

  setStatus(s) {
    this.status = s;
    this.emit('status', s);
  }

  connect() {
    this.close();
    this.setStatus('connecting');
    let ws;
    try {
      ws = new WebSocket(this.url);
    } catch (e) {
      this.setStatus('offline');
      this.emit('error', String(e));
      return Promise.reject(e);
    }
    this.ws = ws;
    return new Promise((resolve, reject) => {
      ws.onopen = () => {
        this.setStatus('online');
        this.pingTimer = setInterval(() => this.ping(), 5000);
        this.ping();
        resolve();
      };
      ws.onerror = () => reject(new Error('connection failed'));
      ws.onclose = (ev) => {
        if (this.ws !== ws) return;
        clearInterval(this.pingTimer);
        this.ws = null;
        this.setStatus('offline');
        this.emit('close', ev);
        reject(new Error('closed'));
      };
      ws.onmessage = (ev) => {
        let m;
        try {
          m = JSON.parse(ev.data);
        } catch {
          return;
        }
        this.onMessage(m);
      };
    });
  }

  close() {
    clearInterval(this.pingTimer);
    if (this.ws) {
      const ws = this.ws;
      this.ws = null;
      ws.onclose = null;
      try {
        ws.close();
      } catch {}
    }
    this.setStatus('offline');
  }

  send(obj) {
    if (!this.ws || this.ws.readyState !== 1) return false;
    this.ws.send(JSON.stringify(obj));
    return true;
  }

  ping() {
    const n = ++this.pingN;
    this.pingSent.set(n, performance.now());
    if (this.pingSent.size > 8) this.pingSent.delete(this.pingSent.keys().next().value);
    this.send({ t: 'ping', n });
  }

  resetState() {
    for (const s of SECTIONS) this.state[s] = null;
  }

  onMessage(m) {
    switch (m.t) {
      case 'pong': {
        const t0 = this.pingSent.get(m.n);
        if (t0 !== undefined) {
          this.pingSent.delete(m.n);
          const r = performance.now() - t0;
          this.rtt = this.rtt === null ? r : this.rtt * 0.7 + r * 0.3;
          this.emit('rtt', this.rtt);
        }
        break;
      }
      case 'state': {
        const changed = [];
        for (const s of SECTIONS) {
          if (s in m) {
            this.state[s] = m[s];
            changed.push(s);
          }
        }
        this.stateSeq = m.seq;
        if (changed.length) this.emit('state', this.state, changed);
        break;
      }
      default:
        this.emit(m.t, m);
    }
  }
}
