// Sound effects: the games' own SE songs (sound/songs/midi/se_*.mid) and short fanfares
// (MUS_OBTAIN_ITEM, MUS_LEVEL_UP, MUS_HEAL), by role from the manifest's `sfx` table, played on
// the music engine's effect bus with their own volume (Settings → Sound).
import { music } from './audio.js';
import { assets } from './assets.js';
import { settings } from './settings.js';

const FANFARES = new Set(['itemGet', 'keyItemGet', 'levelUp', 'heal']);

export const sfx = {
  last: new Map(), // role -> ms, to avoid machine-gunning the same effect
  played: [], // debug: recent roles

  setVolume(v) {
    music.sfxVolume = Math.max(0, Math.min(1, v));
    music.applyGain();
  },
  setMuted() {
    music.applyGain();
  },

  /** Plays effect `role` ("select", "door", "bump", "levelUp", ...) of `region`. */
  play(role, region = 0, minGapMs = 60) {
    const table = assets.manifest && assets.manifest.sfx && assets.manifest.sfx[region];
    const name = table && table[role];
    if (!name) return Promise.resolve(false);
    const now = performance.now();
    if (now - (this.last.get(role) ?? -Infinity) < minGapMs) return Promise.resolve(false);
    this.last.set(role, now);
    this.played.push(role);
    if (this.played.length > 30) this.played.shift();
    return music.oneShot(region, name, { fanfare: FANFARES.has(role) });
  },
};
music.sfxVolume = settings.get('sfxVolume');
