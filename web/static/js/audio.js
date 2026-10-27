// Music: a small self-written Standard MIDI File player + WebAudio synth that approximates the GBA
// sound hardware (square / programmable wave / noise channels, and the DirectSound instruments with
// sample-free stand-ins). Songs and voice tables come from the decomps (PROTOCOL.md section 4,
// assets/music/<region>/index.json). Loops on the songs' "[" / "]" markers like the m4a engine.
import { assets } from './assets.js';

const LOOKAHEAD = 0.3; // s of events scheduled ahead
const TICK_MS = 60; // scheduler period
const STORE = 'openmmo.audio';

// ------------------------------------------------------------------ SMF parsing
/** Parses a Standard MIDI File into time-sorted events with absolute seconds + loop points. */
export function parseMidi(buf) {
  const d = new DataView(buf);
  const u8 = new Uint8Array(buf);
  const str = (p, n) => String.fromCharCode(...u8.subarray(p, p + n));
  if (str(0, 4) !== 'MThd') throw new Error('not a MIDI file');
  const ntr = d.getUint16(10);
  const div = d.getUint16(12);
  let p = 8 + d.getUint32(4);
  const ev = []; // [tick, order, kind, ch, a, b]
  let order = 0;
  for (let t = 0; t < ntr && p + 8 <= u8.length; t++) {
    if (str(p, 4) !== 'MTrk') break;
    const end = Math.min(u8.length, p + 8 + d.getUint32(p + 4));
    let q = p + 8;
    p = end;
    let tick = 0, rs = 0;
    const vlq = () => {
      let v = 0, b;
      do {
        b = u8[q++];
        v = (v << 7) | (b & 0x7f);
      } while (b & 0x80 && q < end);
      return v;
    };
    while (q < end) {
      tick += vlq();
      if (q >= end) break;
      let st = u8[q];
      if (st === 0xff) {
        const type = u8[q + 1];
        q += 2;
        const len = vlq();
        if (type === 0x51) ev.push([tick, order++, 'tempo', 0, (u8[q] << 16) | (u8[q + 1] << 8) | u8[q + 2], 0]);
        else if (type === 0x06 || type === 0x01) {
          const txt = str(q, len);
          if (txt === '[' || txt === ']') ev.push([tick, order++, txt, 0, 0, 0]);
        }
        q += len;
        continue;
      }
      if (st === 0xf0 || st === 0xf7) {
        q++;
        q += vlq();
        continue;
      }
      if (st & 0x80) {
        rs = st;
        q++;
      }
      const hi = rs & 0xf0, ch = rs & 15;
      if (hi === 0xc0 || hi === 0xd0) {
        if (hi === 0xc0) ev.push([tick, order++, 'prog', ch, u8[q], 0]);
        q += 1;
      } else {
        const a = u8[q], b = u8[q + 1];
        q += 2;
        if (hi === 0x90 && b > 0) ev.push([tick, order++, 'on', ch, a, b]);
        else if (hi === 0x80 || hi === 0x90) ev.push([tick, order++, 'off', ch, a, 0]);
        else if (hi === 0xb0) ev.push([tick, order++, 'cc', ch, a, b]);
        else if (hi === 0xe0) ev.push([tick, order++, 'bend', ch, ((b << 7) | a) - 8192, 0]);
      }
    }
  }
  // offs before ons at the same tick, otherwise file order
  const rank = (k) => (k === 'tempo' ? 0 : k === 'off' ? 1 : 2);
  ev.sort((x, y) => x[0] - y[0] || rank(x[2]) - rank(y[2]) || x[1] - y[1]);
  // ticks -> seconds through the tempo map
  let usPerQ = 500000, lastTick = 0, sec = 0, loopStart = null, loopEnd = null;
  const out = [];
  for (const e of ev) {
    sec += ((e[0] - lastTick) * usPerQ) / div / 1e6;
    lastTick = e[0];
    if (e[2] === 'tempo') {
      usPerQ = e[4];
      continue;
    }
    if (e[2] === '[') {
      if (loopStart === null) loopStart = { t: sec, i: out.length };
      continue;
    }
    if (e[2] === ']') {
      if (loopEnd === null) loopEnd = sec;
      continue;
    }
    out.push({ t: sec, k: e[2], ch: e[3], a: e[4], b: e[5] });
  }
  const length = out.length ? out[out.length - 1].t : 0;
  return { events: out, length, loopStart: loopStart && loopEnd !== null ? loopStart : null, loopEnd };
}

// ------------------------------------------------------------------ synth helpers
const DUTIES = [0.125, 0.25, 0.5, 0.75];

function pulseWave(ctx, duty) {
  const n = 48, re = new Float32Array(n), im = new Float32Array(n);
  for (let k = 1; k < n; k++) re[k] = (2 / (k * Math.PI)) * Math.sin(k * Math.PI * duty);
  return ctx.createPeriodicWave(re, im);
}
function sampleWave(ctx, samples) {
  const N = samples.length, n = 16, re = new Float32Array(n), im = new Float32Array(n);
  const mean = samples.reduce((a, b) => a + b, 0) / N;
  for (let k = 1; k < n; k++) {
    let r = 0, i = 0;
    for (let j = 0; j < N; j++) {
      const v = (samples[j] - mean) / 7.5;
      r += v * Math.cos((2 * Math.PI * k * j) / N);
      i += v * Math.sin((2 * Math.PI * k * j) / N);
    }
    re[k] = r / N;
    im[k] = i / N;
  }
  return ctx.createPeriodicWave(re, im);
}
function noiseBuffer(ctx, short) {
  const len = ctx.sampleRate; // 1 s
  const b = ctx.createBuffer(1, len, ctx.sampleRate);
  const d = b.getChannelData(0);
  if (!short) {
    for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
  } else {
    // GB 7-bit LFSR: metallic periodic noise
    let lfsr = 0x7f, v = 1;
    const step = Math.max(1, Math.round(ctx.sampleRate / 16384));
    for (let i = 0; i < len; i++) {
      if (i % step === 0) {
        const bit = (lfsr ^ (lfsr >> 1)) & 1;
        lfsr = (lfsr >> 1) | (bit << 6);
        v = lfsr & 1 ? 1 : -1;
      }
      d[i] = v;
    }
  }
  return b;
}

/** Sample-free stand-in for a DirectSound instrument, from its sample's name. */
function timbre(name = '') {
  const n = name.toLowerCase();
  if (/kick|bass_drum|bassdrum/.test(n)) return 'kick';
  if (/snare|rimshot|rim/.test(n)) return 'snare';
  if (/hat|hihat|cymbal|crash|ride|splash|tambourine|shaker|maracas|cabasa/.test(n)) return 'hat';
  if (/clap|snap/.test(n)) return 'clap';
  if (/tom|timpani|conga|bongo|tabla|taiko/.test(n)) return 'tom';
  if (/triangle|bell|chime|glock|celesta|music_box|xylo|marimba|vibra|steel/.test(n)) return 'bell';
  if (/piano|ep\d|_ep|harpsi|harp|guitar|banjo|koto|sitar|pizz|clav|mute/.test(n)) return 'pluck';
  if (/bass|tuba|cello|contrabass/.test(n)) return 'bass';
  if (/organ|accordion|harmonica/.test(n)) return 'organ';
  if (/flute|whistle|ocarina|recorder|pan_?flute|piccolo|choir|voice|aah|ooh/.test(n)) return 'flute';
  if (/string|violin|viola|ensemble|pad|synth_?str/.test(n)) return 'strings';
  if (/brass|trumpet|horn|trombone|sax|oboe|clarinet|bassoon|fanfare/.test(n)) return 'brass';
  return 'lead';
}

// GBA envelopes -> seconds. DirectSound: per-frame (60 Hz) linear attack (+a/255) and
// multiplicative decay/release (x d/256). CGB channels: 0-7 step lengths, 0-15 sustain.
function envOf(v) {
  const [a, d, s, r] = v.env || [255, 0, 255, 0];
  if (v.cgb) {
    // one envelope step per `x` frames, 15 steps
    return {
      att: a ? a * 0.1 : 0.002,
      tauD: d ? d * 0.1 : 0,
      sus: d ? s / 15 : 1,
      tauR: r ? r * 0.06 : 0.015,
    };
  }
  const tau = (x) => (x <= 0 ? 0.004 : x >= 255 ? 4 : -1 / (60 * Math.log(x / 256)));
  return { att: a >= 255 ? 0.002 : (255 / Math.max(1, a)) / 60, tauD: d ? tau(d) : s < 255 ? 0.003 : 0, sus: s / 255, tauR: tau(r) };
}

class Song {
  constructor(engine, name, info, data, waves) {
    this.e = engine;
    this.name = name;
    this.info = info;
    this.data = data;
    this.waves = waves;
    this.out = engine.ctx.createGain();
    this.out.gain.value = 0;
    this.out.connect(engine.master);
    this.ch = [];
    for (let i = 0; i < 16; i++) {
      const g = engine.ctx.createGain();
      const pan = engine.ctx.createStereoPanner ? engine.ctx.createStereoPanner() : null;
      if (pan) g.connect(pan).connect(this.out);
      else g.connect(this.out);
      g.gain.value = 100 / 127;
      this.ch.push({ g, pan, prog: 0, vol: 100, expr: 127, bend: 0, range: 2, notes: new Map() });
    }
    this.i = 0;
    this.t0 = 0;
    this.stopped = false;
  }

  start(at, fadeIn) {
    this.t0 = at;
    const v = (this.info.vol ?? 127) / 127;
    this.out.gain.setValueAtTime(fadeIn > 0 ? 0 : v, at);
    if (fadeIn > 0) this.out.gain.linearRampToValueAtTime(v, at + fadeIn);
  }

  stop(at, fade) {
    if (this.stopped) return;
    this.stopped = true;
    const g = this.out.gain;
    g.cancelScheduledValues(at);
    g.setValueAtTime(g.value, at);
    g.linearRampToValueAtTime(0, at + Math.max(0.01, fade));
    setTimeout(() => {
      for (const c of this.ch) for (const n of c.notes.values()) this.kill(n);
      this.out.disconnect();
    }, (fade + 0.3) * 1000 + LOOKAHEAD * 1000);
  }

  /** Schedules every event up to `until` (AudioContext time). Returns false once finished. */
  pump(until) {
    if (this.stopped) return false;
    const ev = this.data.events;
    while (true) {
      if (this.i >= ev.length || (this.data.loopEnd !== null && this.data.loopStart && ev[this.i].t >= this.data.loopEnd)) {
        if (this.data.loopStart) {
          const ls = this.data.loopStart, le = this.data.loopEnd ?? this.data.length;
          if (le - ls.t < 0.05) return false;
          const at = this.t0 + le;
          for (const c of this.ch) for (const n of [...c.notes.values()]) this.release(c, n, at);
          this.t0 += le - ls.t;
          this.i = ls.i;
          continue;
        }
        return this.t0 + this.data.length + 2 > this.e.ctx.currentTime;
      }
      const e = ev[this.i];
      const at = this.t0 + e.t;
      if (at > until) return true;
      this.i++;
      this.apply(e, Math.max(at, this.e.ctx.currentTime));
    }
  }

  apply(e, at) {
    const c = this.ch[e.ch];
    switch (e.k) {
      case 'prog': c.prog = e.a; break;
      case 'cc':
        if (e.a === 7) c.vol = e.b;
        else if (e.a === 11) c.expr = e.b;
        else if (e.a === 10 && c.pan) c.pan.pan.setValueAtTime(Math.max(-1, Math.min(1, (e.b - 64) / 64)), at);
        else if (e.a === 20) c.range = e.b || 2; // m4a BENDR
        else if (e.a === 120 || e.a === 123) for (const n of [...c.notes.values()]) this.release(c, n, at);
        if (e.a === 7 || e.a === 11) c.g.gain.setTargetAtTime(((c.vol / 127) * c.expr) / 127, at, 0.01);
        break;
      case 'bend': {
        c.bend = (e.a / 8192) * c.range;
        for (const n of c.notes.values()) if (!n.fixed) n.src.detune.setValueAtTime(c.bend * 100, at);
        break;
      }
      case 'on': this.noteOn(c, e.a, e.b, at); break;
      case 'off': {
        const n = c.notes.get(e.a);
        if (n) this.release(c, n, at);
        break;
      }
    }
  }

  voiceFor(c, note) {
    const vs = this.info.voices || {};
    let v = vs[c.prog];
    if (v && v.keys) v = v.keys[note] || Object.values(v.keys)[0];
    return v || { t: 'sq', duty: 2, env: [0, 0, 15, 2], cgb: true };
  }

  noteOn(c, note, vel, at) {
    const old = c.notes.get(note);
    if (old) this.release(c, old, at);
    const ctx = this.e.ctx;
    const v = this.voiceFor(c, note);
    const env = envOf(v);
    const g = ctx.createGain();
    g.gain.value = 0;
    let src, peak = (vel / 127) * 0.32, fixed = false, oneShot = 0;
    const freq = 440 * Math.pow(2, (note - 69) / 12);
    const tb = v.t === 'ds' ? timbre(v.name) : null;
    const drum = v.t === 'ds' && (v.drum || v.fixed) && ['kick', 'snare', 'hat', 'clap', 'tom'].includes(tb);
    let out = g;
    if (v.t === 'noise' || (drum && tb !== 'kick' && tb !== 'tom')) {
      src = ctx.createBufferSource();
      src.buffer = v.t === 'noise' && v.period ? this.e.noiseShort : this.e.noise;
      src.loop = true;
      const f = ctx.createBiquadFilter();
      if (v.t === 'noise') {
        src.playbackRate.value = Math.min(4, Math.max(0.1, Math.pow(2, (note - 60) / 12)));
        f.type = 'lowpass';
        f.frequency.value = 9000;
      } else {
        fixed = true;
        f.type = tb === 'snare' ? 'bandpass' : 'highpass';
        f.frequency.value = tb === 'snare' ? 1800 : tb === 'clap' ? 1200 : 7000;
        oneShot = tb === 'snare' ? 0.16 : tb === 'clap' ? 0.12 : /crash|cymbal/.test(v.name || '') ? 0.7 : 0.06;
        peak *= tb === 'hat' ? 0.55 : 0.9;
      }
      src.connect(f).connect(g);
      fixed = fixed || v.t !== 'noise';
    } else {
      src = ctx.createOscillator();
      if (v.t === 'sq') src.setPeriodicWave(this.e.pulses[v.duty ?? 2]);
      else if (v.t === 'wave') src.setPeriodicWave(this.waveOf(v.wave));
      else if (drum) {
        // kick / tom: a sine with a falling pitch
        fixed = tb === 'kick';
        src.type = 'sine';
        const f0 = tb === 'kick' ? 160 : Math.max(80, freq);
        src.frequency.setValueAtTime(f0, at);
        src.frequency.exponentialRampToValueAtTime(f0 * 0.35, at + 0.18);
        oneShot = tb === 'kick' ? 0.2 : 0.28;
        peak *= 1.4;
      } else {
        // DirectSound melodic stand-ins
        const type = { pluck: 'triangle', bell: 'sine', bass: 'triangle', organ: 'square', flute: 'sine', strings: 'sawtooth', brass: 'sawtooth', lead: 'triangle' }[tb] || 'triangle';
        src.type = type;
        if (type === 'sawtooth' || type === 'square') {
          const f = ctx.createBiquadFilter();
          f.type = 'lowpass';
          f.frequency.value = tb === 'brass' ? 2600 : 1800;
          out = f;
          f.connect(g);
          peak *= 0.6;
        }
        if (tb === 'pluck' || tb === 'bell') {
          env.tauD = env.tauD || (tb === 'bell' ? 0.5 : 0.35);
          env.sus = Math.min(env.sus, 0.08);
        }
      }
      if (!fixed && !(drum && tb === 'tom')) src.frequency.setValueAtTime(freq, at);
      if (v.t === 'sq' || v.t === 'wave') peak *= v.t === 'wave' ? 0.9 : 0.55;
      src.connect(out === g ? g : out);
    }
    if (!fixed) src.detune.setValueAtTime(c.bend * 100, at);
    // envelope
    const gp = g.gain;
    gp.setValueAtTime(0, at);
    if (oneShot) {
      gp.linearRampToValueAtTime(peak, at + 0.002);
      gp.setTargetAtTime(0, at + 0.004, oneShot / 3);
    } else {
      gp.linearRampToValueAtTime(peak, at + env.att);
      if (env.tauD) gp.setTargetAtTime(peak * env.sus, at + env.att, env.tauD);
    }
    g.connect(c.g);
    src.start(at);
    const n = { src, g, env, note, fixed, peak, end: oneShot ? at + oneShot * 2.5 : Infinity, t0: at };
    if (oneShot) {
      src.stop(n.end);
      src.onended = () => this.kill(n);
    } else c.notes.set(note, n);
  }

  release(c, n, at) {
    c.notes.delete(n.note);
    const gp = n.g.gain;
    const t = Math.max(at, n.t0 + 0.005);
    if (t < n.t0 + n.env.att) {
      gp.cancelScheduledValues(t);
      gp.setValueAtTime(n.peak * 0.5, t);
    }
    gp.setTargetAtTime(0, t, n.env.tauR);
    const end = t + Math.min(3, n.env.tauR * 6 + 0.02);
    try {
      n.src.stop(end);
    } catch {}
    n.src.onended = () => this.kill(n);
  }

  kill(n) {
    try {
      n.src.disconnect();
      n.g.disconnect();
    } catch {}
  }

  waveOf(i) {
    if (!this.e.waveCache.has(i)) {
      const s = this.waves[i] || Array.from({ length: 32 }, (_, k) => (k < 16 ? 15 : 0));
      this.e.waveCache.set(i, sampleWave(this.e.ctx, s));
    }
    return this.e.waveCache.get(i);
  }
}

// ------------------------------------------------------------------ music manager
export const music = {
  ctx: null,
  master: null,
  song: null, // playing Song
  want: null, // {region, name}
  current: null, // name of what plays / should play
  volume: 0.6,
  muted: false,
  unlocked: false,
  timer: 0,
  indexCache: new Map(),
  midiCache: new Map(),
  pending: 0,
  done: null,

  init() {
    try {
      const s = JSON.parse(localStorage.getItem(STORE) || '{}');
      if (typeof s.volume === 'number') this.volume = Math.max(0, Math.min(1, s.volume));
      this.muted = !!s.muted;
    } catch {}
    const unlock = () => this.unlock();
    for (const ev of ['pointerdown', 'keydown', 'touchend']) window.addEventListener(ev, unlock, { capture: true, passive: true });
    document.addEventListener('visibilitychange', () => {
      if (!this.ctx) return;
      if (document.hidden) this.ctx.suspend();
      else if (this.unlocked) this.ctx.resume();
    });
  },

  save() {
    localStorage.setItem(STORE, JSON.stringify({ volume: this.volume, muted: this.muted }));
  },

  setVolume(v) {
    this.volume = Math.max(0, Math.min(1, v));
    this.save();
    this.applyGain();
  },
  setMuted(m) {
    this.muted = !!m;
    this.save();
    this.applyGain();
  },
  applyGain() {
    if (!this.master) return;
    const v = this.muted ? 0 : this.volume * this.volume * 2.2; // perceptual curve, GBA-ish loudness
    this.master.gain.setTargetAtTime(v, this.ctx.currentTime, 0.05);
  },

  /** Autoplay rules: the AudioContext starts on the first user gesture. */
  unlock() {
    if (this.unlocked) return;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    if (!this.ctx) {
      this.ctx = new AC({ latencyHint: 'playback' });
      this.master = this.ctx.createGain();
      this.master.gain.value = 0;
      // gentle limiter: many voices summing must not clip
      const comp = this.ctx.createDynamicsCompressor();
      comp.threshold.value = -10;
      comp.ratio.value = 6;
      this.master.connect(comp).connect(this.ctx.destination);
      this.pulses = DUTIES.map((d) => pulseWave(this.ctx, d));
      this.noise = noiseBuffer(this.ctx, false);
      this.noiseShort = noiseBuffer(this.ctx, true);
      this.waveCache = new Map();
    }
    this.ctx.resume().then(() => {
      this.unlocked = this.ctx.state === 'running';
      if (!this.unlocked) return;
      this.applyGain();
      if (this.want) this.start(this.want.region, this.want.name, this.want.fade);
    });
  },

  async loadIndex(region) {
    if (!this.indexCache.has(region)) {
      const path = assets.manifest && assets.manifest.music && assets.manifest.music[region];
      this.indexCache.set(region, path ? fetch(assets.url(path)).then((r) => r.json()).catch(() => null) : Promise.resolve(null));
    }
    return this.indexCache.get(region);
  },

  async loadMidi(path) {
    if (!this.midiCache.has(path)) {
      this.midiCache.set(path, fetch(assets.url(path)).then((r) => r.arrayBuffer()).then(parseMidi).catch((e) => (console.warn('midi', path, e), null)));
    }
    return this.midiCache.get(path);
  },

  /** Plays the song constant `name` (e.g. "MUS_PALLET") of `region`; null stops the music. */
  play(region, name, fade = 0.8) {
    if (name === 'MUS_NONE' || name === 'MUS_DUMMY') name = null;
    const key = name ? region + ':' + name : null;
    if (key === this.current) return;
    if (key && key === this.done) return; // a jingle that already finished stays silent
    this.done = null;
    this.current = key;
    this.want = name ? { region, name, fade } : null;
    if (!this.unlocked) return;
    this.start(region, name, fade);
  },

  async start(region, name, fade) {
    const token = ++this.pending;
    const now = this.ctx.currentTime;
    if (this.song) {
      this.song.stop(now, fade);
      this.song = null;
    }
    if (!name) return this.idle();
    const idx = await this.loadIndex(region);
    const info = idx && idx.songs && idx.songs[name];
    if (!info) return;
    const data = await this.loadMidi(info.mid);
    if (!data || token !== this.pending) return;
    const s = new Song(this, name, info, data, idx.waves || []);
    const at = Math.max(this.ctx.currentTime + 0.05, now + fade * 0.6);
    s.start(at, Math.min(0.4, fade));
    this.song = s;
    if (!this.timer) this.timer = setInterval(() => this.pump(), TICK_MS);
    this.pump();
  },

  pump() {
    if (!this.song) return this.idle();
    if (!this.song.pump(this.ctx.currentTime + LOOKAHEAD)) {
      this.song = null;
      this.done = this.current; // finished (a jingle without loop)
      this.current = null;
      this.idle();
    }
  },
  idle() {
    if (this.song) return;
    clearInterval(this.timer);
    this.timer = 0;
  },

  /** For tests / the debug handle. */
  status() {
    return { state: this.ctx ? this.ctx.state : 'none', song: this.song ? this.song.name : null, want: this.want && this.want.name, volume: this.volume, muted: this.muted };
  },
};
