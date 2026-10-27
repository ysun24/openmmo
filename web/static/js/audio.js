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

/** GBA frame rate: the m4a engine updates envelopes, LFOs and sweeps once per frame. */
const FRAME = 1 / 59.7275;

class Song {
  constructor(engine, name, info, data, waves, samples = {}, bus = null) {
    this.e = engine;
    this.name = name;
    this.info = info;
    this.data = data;
    this.waves = waves;
    this.samples = samples || {};
    const ctx = engine.ctx;
    this.out = ctx.createGain();
    this.out.gain.value = 0;
    this.out.connect(bus || engine.master);
    // DirectSound voices mix into their own bus, which carries the m4a reverb (-R in midi.cfg):
    // each new frame of the PCM ring starts as (L+R of the frame 7 frames back + of the one 6
    // frames back) * rev / 512 (SoundMainRAM_Reverb), a mono feedback comb on the DirectSound mix.
    // The CGB channels (square / wave / noise) are separate hardware and stay dry.
    this.ds = ctx.createGain();
    this.ds.connect(this.out);
    const rev = info.rev || 0;
    if (rev > 0 && engine.reverbOn) {
      const mono = ctx.createGain();
      mono.channelCount = 1;
      mono.channelCountMode = 'explicit';
      mono.channelInterpretation = 'speakers';
      mono.gain.value = (2 * rev) / 512;
      const d6 = ctx.createDelay(0.2), d7 = ctx.createDelay(0.2);
      // 224 samples per frame at 13379 Hz, a 7-frame ring (PCM_DMA_BUF_SIZE 1584)
      d6.delayTime.value = 6 * FRAME;
      d7.delayTime.value = 7 * FRAME;
      this.ds.connect(d6).connect(mono);
      this.ds.connect(d7).connect(mono);
      mono.connect(this.ds);
      this.rv = [d6, d7, mono];
    }
    this.ch = [];
    for (let i = 0; i < 16; i++) {
      const mk = (dst) => {
        const g = ctx.createGain();
        const pan = ctx.createStereoPanner ? ctx.createStereoPanner() : null;
        if (pan) g.connect(pan).connect(dst);
        else g.connect(dst);
        g.gain.value = 100 / 127;
        return [g, pan];
      };
      const [g, pan] = mk(this.out);
      const [gd, pand] = mk(this.ds);
      this.ch.push({
        g, pan, gd, pand, prog: 0, vol: 100, expr: 127, bend: 0, range: 2, tune: 0, notes: new Map(),
        // m4a LFO: MOD depth (CC1), LFOS speed (CC21, default 22), MODT target (CC22: 0 pitch,
        // 1 volume, 2 pan), LFODL delay in frames (CC26)
        mod: 0, lfos: 22, modt: 0, lfodl: 0, lfo: null, lfoGain: null,
      });
    }
    this.i = 0;
    this.t0 = 0;
    this.stopped = false;
  }

  /** The channel's LFO: a triangle like the m4a's (256 steps per cycle, `lfos` steps per frame). */
  lfoOf(c, at) {
    const ctx = this.e.ctx;
    if (!c.lfo) {
      c.lfo = ctx.createOscillator();
      c.lfo.type = 'triangle';
      c.lfo.start(at);
    }
    c.lfo.frequency.setValueAtTime((c.lfos * 59.7275) / 256, at);
    return c.lfo;
  }

  /** Re-targets the channel's volume / pan LFO (MODT 1 / 2) after MOD / LFOS / MODT changes. */
  updateTremolo(c, at) {
    if (c.lfoGain) {
      try {
        c.lfoGain.disconnect();
      } catch {}
      c.lfoGain = null;
    }
    if (!c.mod || c.modt === 0) return;
    const ctx = this.e.ctx;
    const g = ctx.createGain();
    if (c.modt === 1) {
      // vol x (modM + 128) / 128
      g.gain.value = (c.mod / 128) * (((c.vol / 127) * c.expr) / 127);
      this.lfoOf(c, at).connect(g);
      g.connect(c.g.gain);
      g.connect(c.gd.gain);
    } else if (c.pan) {
      g.gain.value = c.mod / 64;
      this.lfoOf(c, at).connect(g);
      g.connect(c.pan.pan);
      g.connect(c.pand.pan);
    }
    c.lfoGain = g;
  }

  /** Vibrato (MODT 0) for one note: the channel LFO into its detune, after LFODL frames. */
  vibrato(c, n, at) {
    if (!c.mod || c.modt !== 0 || !n.src.detune || n.fixed) return;
    const ctx = this.e.ctx;
    const g = ctx.createGain();
    // modM = +-MOD, pitch += 16 * modM / 256 semitones -> MOD * 6.25 cents
    const depth = c.mod * 6.25;
    if (c.lfodl > 0) {
      g.gain.setValueAtTime(0, at);
      g.gain.setValueAtTime(depth, at + c.lfodl * FRAME);
    } else g.gain.value = depth;
    this.lfoOf(c, at).connect(g).connect(n.src.detune);
    n.vib = g;
  }

  start(at, fadeIn) {
    this.t0 = at;
    const v = (this.info.vol ?? 127) / 127;
    this.out.gain.setValueAtTime(fadeIn > 0 ? 0 : v, at);
    if (fadeIn > 0) this.out.gain.linearRampToValueAtTime(v, at + fadeIn);
  }

  /**
   * Pauses at `at` for a fanfare (PlayFanfare: m4aMPlayStop on the BGM player): notes still sounding
   * or already scheduled past `at` are dropped and the read position goes back to the first event
   * after `at`, so resume() continues at the same spot in the song (m4aMPlayContinue).
   */
  pause(at) {
    if (this.stopped || this.paused) return;
    this.paused = true;
    this.pausedAt = at;
    const g = this.out.gain;
    this.outLevel = g.value;
    g.cancelScheduledValues(at);
    g.setValueAtTime(g.value, at);
    g.linearRampToValueAtTime(0, at + 0.03);
    for (const c of this.ch) {
      for (const n of [...c.notes.values()]) {
        try { n.src.stop(at + 0.04); } catch {}
        this.kill(n);
      }
      c.notes.clear();
    }
    const ev = this.data.events;
    while (this.i > 0 && this.t0 + ev[this.i - 1].t > at) this.i--;
  }

  resume(at) {
    if (this.stopped || !this.paused) return;
    this.paused = false;
    this.t0 += at - this.pausedAt;
    const g = this.out.gain;
    g.cancelScheduledValues(at);
    g.setValueAtTime(0, at);
    g.linearRampToValueAtTime(this.outLevel || (this.info.vol ?? 127) / 127, at + 0.15);
  }

  stop(at, fade) {
    if (this.stopped) return;
    this.stopped = true;
    const g = this.out.gain;
    g.cancelScheduledValues(at);
    g.setValueAtTime(g.value, at);
    g.linearRampToValueAtTime(0, at + Math.max(0.01, fade));
    setTimeout(() => {
      for (const c of this.ch) {
        for (const n of c.notes.values()) this.kill(n);
        c.notes.clear();
        try {
          if (c.lfo) c.lfo.stop();
          if (c.lfo) c.lfo.disconnect();
          if (c.lfoGain) c.lfoGain.disconnect();
        } catch {}
      }
      if (this.rv) for (const x of this.rv) x.disconnect();
      this.ds.disconnect();
      this.out.disconnect();
    }, (fade + 0.3) * 1000 + LOOKAHEAD * 1000);
  }

  /** Schedules every event up to `until` (AudioContext time). Returns false once finished. */
  pump(until) {
    if (this.stopped) return false;
    if (this.paused) return true;
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
        else if (e.a === 10 && c.pan) {
          const v = Math.max(-1, Math.min(1, (e.b - 64) / 64));
          c.pan.pan.setValueAtTime(v, at);
          c.pand.pan.setValueAtTime(v, at);
        }
        else if (e.a === 20) c.range = e.b || 2; // m4a BENDR
        else if (e.a === 1 || e.a === 21 || e.a === 22 || e.a === 26) {
          if (e.a === 1) c.mod = e.b;
          else if (e.a === 21) c.lfos = e.b;
          else if (e.a === 22) c.modt = e.b;
          else c.lfodl = e.b;
          if (c.lfo) c.lfo.frequency.setValueAtTime((c.lfos * 59.7275) / 256, at);
          for (const n of c.notes.values()) {
            if (n.vib) n.vib.gain.setValueAtTime(c.modt === 0 ? c.mod * 6.25 : 0, at);
            else if (e.a === 1) this.vibrato(c, n, at);
          }
          this.updateTremolo(c, at);
        } else if (e.a === 24) {
          // m4a TUNE: c_v +-64 = +-1 semitone
          c.tune = ((e.b - 64) * 100) / 64;
          for (const n of c.notes.values()) if (!n.fixed && n.src.detune) n.src.detune.setValueAtTime(c.bend * 100 + c.tune, at);
        }
        else if (e.a === 120 || e.a === 123) for (const n of [...c.notes.values()]) this.release(c, n, at);
        if (e.a === 7 || e.a === 11) {
          const v = ((c.vol / 127) * c.expr) / 127;
          c.g.gain.setTargetAtTime(v, at, 0.01);
          c.gd.gain.setTargetAtTime(v, at, 0.01);
          if (c.lfoGain && c.modt === 1) c.lfoGain.gain.setValueAtTime((c.mod / 128) * v, at);
        }
        break;
      case 'bend': {
        c.bend = (e.a / 8192) * c.range;
        for (const n of c.notes.values()) if (!n.fixed && n.src.detune) n.src.detune.setValueAtTime(c.bend * 100 + c.tune, at);
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
    const prof = v.t === 'ds' && v.sample ? this.samples[v.sample] : null;
    if (prof) return this.noteOnSample(c, note, vel, at, v, prof);
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
      if (v.t === 'sq' && v.sweep) this.sweep(src, freq, v.sweep, at, g);
      if (v.t === 'sq' || v.t === 'wave') peak *= v.t === 'wave' ? 0.9 : 0.55;
      src.connect(out === g ? g : out);
    }
    if (!fixed) src.detune.setValueAtTime(c.bend * 100 + c.tune, at);
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
    g.connect(v.t === 'ds' ? c.gd : c.g);
    src.start(at);
    const n = { src, g, env, note, fixed, peak, end: oneShot ? at + oneShot * 2.5 : Infinity, t0: at };
    if (oneShot) {
      src.stop(n.end);
      src.onended = () => this.kill(n);
    } else {
      c.notes.set(note, n);
      this.vibrato(c, n, at);
    }
  }

  /**
   * CGB square 1 frequency sweep (NR10 = the voice's sweep byte): every `time`/128 s the 11-bit
   * period register x moves by x >> shift (down when bit 3 is set); past 2047 the channel stops.
   */
  sweep(src, freq, sweep, at, g) {
    const time = (sweep >> 4) & 7, down = (sweep & 8) !== 0, shift = sweep & 7;
    if (!time) return;
    let x = Math.round(2048 - 131072 / freq);
    const step = time / 128;
    for (let k = 1; k <= 48; k++) {
      const d = x >> shift;
      x = down ? x - d : x + d;
      const t = at + k * step;
      if (x > 2047 || x < 0) {
        g.gain.setValueAtTime(0, t);
        break;
      }
      if (shift === 0) break;
      src.frequency.setValueAtTime(131072 / (2048 - x), t);
    }
  }

  /**
   * A DirectSound voice from its measured sample profile (assets music index `samples`): the
   * sample's harmonics as a PeriodicWave (tonal) or band-filtered noise (drums), shaped by the
   * sample's own amplitude curve and the voicegroup's ADSR, pitched like the m4a resampler
   * (note 60 = the sample's `pitch60`; drum kit voices play their own key; no_resample is fixed).
   */
  noteOnSample(c, note, vel, at, v, prof) {
    const ctx = this.e.ctx;
    const env = envOf(v);
    const key = v.drum ? (v.key ?? 60) : note;
    const ratio = v.fixed ? 1 : Math.pow(2, (key - 60) / 12);
    const g = ctx.createGain(); // ADSR
    const sg = ctx.createGain(); // the sample's own envelope
    g.gain.value = 0;
    let src, peak;
    if (prof.re) {
      src = ctx.createOscillator();
      src.setPeriodicWave(this.e.sampleWave(v.sample, prof));
      src.frequency.setValueAtTime(prof.pitch60 * ratio, at);
      if (!v.fixed) src.detune.setValueAtTime(c.bend * 100 + c.tune, at);
      src.connect(sg);
      peak = (vel / 127) * 0.55 * Math.min(2.5, prof.gain || 1);
    } else {
      src = ctx.createBufferSource();
      src.buffer = this.e.noise;
      src.loop = true;
      const bands = prof.bands || [];
      // the three strongest bands as band-pass filters, weighted by their energy
      const top = bands.map((b, i) => [b, i]).sort((a, b) => b[0] - a[0]).slice(0, 3);
      for (const [b, i] of top) {
        if (b <= 0.05) continue;
        const f = ctx.createBiquadFilter();
        f.type = 'bandpass';
        f.frequency.value = Math.min(ctx.sampleRate / 2.2, 60 * Math.pow(2, i / 2.6) * ratio);
        f.Q.value = 1.6;
        const w = ctx.createGain();
        w.gain.value = b;
        src.connect(f).connect(w).connect(sg);
      }
      // a pitched body for kicks / toms / congas (low, clearly periodic)
      if (prof.pitch60 && prof.pitch60 < 400) {
        const o = ctx.createOscillator();
        o.type = 'sine';
        o.frequency.setValueAtTime(prof.pitch60 * ratio, at);
        const og = ctx.createGain();
        og.gain.value = 0.9;
        o.connect(og).connect(sg);
        o.start(at);
        src.onendedExtra = o;
      }
      peak = (vel / 127) * 2.2 * (prof.level || 0.3);
    }
    // sample amplitude curve over its length at this playback rate
    const dur = Math.max(0.01, (prof.envDur || prof.dur) / ratio);
    const curve = Float32Array.from(prof.env && prof.env.length ? prof.env : [1]);
    curve[0] = Math.max(curve[0], 0.0001);
    sg.gain.setValueAtTime(curve[0], at);
    if (curve.length > 1) sg.gain.setValueCurveAtTime(curve, at, dur);
    // GBA ADSR on top
    const gp = g.gain;
    gp.setValueAtTime(0, at);
    gp.linearRampToValueAtTime(peak, at + env.att);
    if (env.tauD) gp.setTargetAtTime(peak * env.sus, at + env.att, env.tauD);
    sg.connect(g).connect(c.gd);
    src.start(at);
    const n = { src, g, env, note, fixed: !!v.fixed, peak, end: Infinity, t0: at };
    if (!prof.loop) {
      // a one-shot sample ends with its data (the m4a channel stops), note off or not
      n.end = at + (prof.dur || dur) / ratio + 0.02;
      src.stop(n.end);
      if (src.onendedExtra) src.onendedExtra.stop(n.end);
      src.onended = () => {
        this.kill(n);
        if (c.notes.get(note) === n) c.notes.delete(note);
      };
    }
    c.notes.set(note, n);
    this.vibrato(c, n, at);
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
      if (n.vib) n.vib.disconnect();
      if (n.src.onendedExtra) n.src.onendedExtra.disconnect();
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
    this.master.gain.setTargetAtTime(v * this.duck, this.ctx.currentTime, 0.05);
    const sv = this.muted ? 0 : this.sfxVolume * this.sfxVolume * 2.2;
    this.sfxMaster.gain.setTargetAtTime(sv, this.ctx.currentTime, 0.02);
  },
  duck: 1,
  sfxVolume: 0.7,
  reverbOn: true,

  /** Cached PeriodicWave of a sample profile's harmonics. */
  sampleWave(name, prof) {
    let w = this.sampleWaves.get(name);
    if (!w) {
      const n = prof.re.length + 1;
      const re = new Float32Array(n), im = new Float32Array(n);
      for (let k = 1; k < n; k++) {
        re[k] = prof.re[k - 1];
        im[k] = prof.im[k - 1];
      }
      w = this.ctx.createPeriodicWave(re, im, { disableNormalization: false });
      this.sampleWaves.set(name, w);
    }
    return w;
  },

  /**
   * Plays a sound effect / fanfare song (SE_* / MUS_OBTAIN_ITEM ...) once on the sfx bus, over the
   * music, as the m4a's separate SE players do. `fanfare` pauses the map music while it plays and
   * brings it back afterwards (PlayFanfare / m4aMPlayContinue). Resolves when it has finished.
   */
  async oneShot(region, name, { fanfare = false } = {}) {
    if (!this.unlocked || !name) return false;
    const idx = await this.loadIndex(region);
    const info = idx && idx.songs && idx.songs[name];
    if (!info) return false;
    const data = await this.loadMidi(info.mid);
    if (!data) return false;
    const plain = { ...data, loopStart: null }; // effects never loop
    const s = new Song(this, name, info, plain, idx.waves || [], idx.samples || {}, this.sfxMaster);
    const at = this.ctx.currentTime + 0.01;
    // A fanfare pauses the map music and resumes it at the same spot afterwards.
    const paused = fanfare ? this.song : null;
    if (paused) paused.pause(at);
    s.start(at, 0);
    this.effects = (this.effects || 0) + 1;
    await new Promise((res) => {
      const t = setInterval(() => {
        if (!s.pump(this.ctx.currentTime + LOOKAHEAD) || s.stopped) {
          clearInterval(t);
          res();
        }
      }, TICK_MS);
      s.pump(this.ctx.currentTime + LOOKAHEAD);
    });
    s.stop(this.ctx.currentTime, 0.05);
    this.effects--;
    if (paused && paused === this.song) paused.resume(this.ctx.currentTime + 0.05);
    return true;
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
      // sound effects and fanfares: their own volume, same limiter
      this.sfxMaster = this.ctx.createGain();
      this.sfxMaster.gain.value = 0;
      this.sfxMaster.connect(comp);
      this.pulses = DUTIES.map((d) => pulseWave(this.ctx, d));
      this.noise = noiseBuffer(this.ctx, false);
      this.noiseShort = noiseBuffer(this.ctx, true);
      this.waveCache = new Map();
      this.sampleWaves = new Map();
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
    const s = new Song(this, name, info, data, idx.waves || [], idx.samples || {});
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
