// The games' own fonts (manifest ui.fonts, exported from graphics/fonts by :web.assets):
// - TrueType versions for the DOM (registered as CSS families; the browser falls back per character
//   to the next family in the CSS stack, e.g. a system font for CJK chat);
// - the bitmap atlases for the canvas (name tags, labels), drawn pixel for pixel with any colours.
// Window frames (ui.windows) become CSS custom properties for border-image.
import { assets } from './assets.js';

const REGION_FONT = { 0: 'pokefirered/normal', 1: 'pokeemerald/normal' };

export const fonts = {
  ready: false,
  /** key -> {meta, glyphs: Map(cp -> [index, width]), mask: Uint8Array | null} */
  bitmap: new Map(),
  promise: null,

  /** Registers the CSS fonts and loads the bitmap atlases. Resolves when all are usable. */
  load() {
    if (this.promise) return this.promise;
    const ui = (assets.manifest && assets.manifest.ui) || {};
    const list = ui.fonts || {};
    const jobs = [];
    for (const [key, f] of Object.entries(list)) {
      if (f.ttf && typeof FontFace !== 'undefined') {
        const face = new FontFace(f.family, `url(${assets.url(f.ttf)})`, { display: 'swap' });
        document.fonts.add(face);
        jobs.push(face.load().catch((e) => console.warn('font', f.family, e)));
      }
      if (f.json && f.image) jobs.push(this.loadBitmap(key, f));
    }
    applyWindows(ui.windows || {});
    this.promise = Promise.all(jobs).then(() => {
      this.ready = true;
      document.documentElement.classList.add('fonts-ready');
    });
    return this.promise;
  },

  async loadBitmap(key, f) {
    try {
      const [meta, img] = await Promise.all([
        fetch(assets.url(f.json)).then((r) => r.json()),
        new Promise((res, rej) => {
          const i = new Image();
          i.onload = () => res(i);
          i.onerror = rej;
          i.src = assets.url(f.image);
        }),
      ]);
      const c = document.createElement('canvas');
      c.width = img.width;
      c.height = img.height;
      const x = c.getContext('2d', { willReadFrequently: true });
      x.drawImage(img, 0, 0);
      const d = x.getImageData(0, 0, img.width, img.height).data;
      // 0 = empty, 1 = glyph, 2 = shadow
      const mask = new Uint8Array(img.width * img.height);
      for (let i = 0, p = 0; i < mask.length; i++, p += 4) mask[i] = d[p + 3] < 128 ? 0 : d[p] > 128 ? 1 : 2;
      const glyphs = new Map();
      for (const [cp, v] of Object.entries(meta.glyphs)) glyphs.set(+cp, v);
      this.bitmap.set(key, { meta, glyphs, mask, w: img.width });
    } catch (e) {
      console.warn('bitmap font', key, e);
    }
  },

  /** Bitmap font for canvas labels (the small font, else the region's normal one). */
  canvasFont(region) {
    return this.bitmap.get('pokeemerald/small') || this.bitmap.get(REGION_FONT[region] || REGION_FONT[0]) || null;
  },

  /** Width in pixels of `text` in bitmap font `f`; characters it lacks use `fallbackPx` (system font). */
  measure(f, text, fallback) {
    let w = 0;
    for (const ch of text) {
      const g = f.glyphs.get(ch.codePointAt(0));
      w += g ? g[1] : fallback(ch);
    }
    return w;
  },

  /**
   * Draws `text` with bitmap font `f` into a new canvas: glyph pixels in `fg`, the font's own shadow
   * pixels in `shadow` (null = none), on a padded `bg` plate (null = transparent).
   * Characters the font lacks are drawn with the system font at the same height (CJK names...).
   */
  textCanvas(f, text, { fg = '#fff', shadow = '#000', bg = null, pad = 2, maxW = 200 } = {}) {
    const cell = f.meta.cell;
    const top = 2; // empty rows above capitals in the latin cells, trimmed
    const lh = f.meta.lineHeight - top + 1;
    const sysPx = Math.max(8, lh - 3);
    const sysFont = `${sysPx}px system-ui, sans-serif`;
    const m = document.createElement('canvas').getContext('2d');
    m.font = sysFont;
    const fb = (ch) => Math.ceil(m.measureText(ch).width) + 1;
    let w = Math.min(maxW, this.measure(f, text, fb));
    const c = document.createElement('canvas');
    c.width = w + pad * 2 + 1;
    c.height = lh + pad * 2 - 2;
    const x = c.getContext('2d');
    if (bg) {
      x.fillStyle = bg;
      x.fillRect(1, 0, c.width - 2, c.height);
      x.fillRect(0, 1, c.width, c.height - 2);
    }
    const img = x.getImageData(0, 0, c.width, c.height);
    const px = img.data;
    const fgc = rgb(fg), shc = shadow ? rgb(shadow) : null;
    let cx = pad;
    const fallbacks = [];
    for (const ch of text) {
      const g = f.glyphs.get(ch.codePointAt(0));
      if (!g) {
        fallbacks.push([ch, cx]);
        cx += fb(ch);
        continue;
      }
      const [idx, gw] = g;
      if (cx + gw > c.width) break;
      const gx = (idx % 16) * cell, gy = ((idx / 16) | 0) * cell;
      for (let yy = top; yy < cell; yy++) {
        const ty = pad + yy - top - 1;
        if (ty < 0 || ty >= c.height) continue;
        for (let xx = 0; xx < cell; xx++) {
          const v = f.mask[(gy + yy) * f.w + gx + xx];
          if (!v) continue;
          const col = v === 1 ? fgc : shc;
          if (!col) continue;
          const tx = cx + xx;
          if (tx >= c.width) continue;
          const o = (ty * c.width + tx) * 4;
          px[o] = col[0];
          px[o + 1] = col[1];
          px[o + 2] = col[2];
          px[o + 3] = 255;
        }
      }
      cx += gw;
    }
    x.putImageData(img, 0, 0);
    if (fallbacks.length) {
      x.font = sysFont;
      x.textBaseline = 'alphabetic';
      for (const [ch, fx] of fallbacks) {
        const by = pad + f.meta.baseline - top - 1;
        if (shadow) {
          x.fillStyle = shadow;
          x.fillText(ch, fx + 1, by + 1);
        }
        x.fillStyle = fg;
        x.fillText(ch, fx, by);
      }
    }
    return c;
  },
};

function rgb(css) {
  const c = document.createElement('canvas').getContext('2d');
  c.fillStyle = css;
  const v = c.fillStyle; // normalised #rrggbb
  if (v[0] === '#') return [parseInt(v.slice(1, 3), 16), parseInt(v.slice(3, 5), 16), parseInt(v.slice(5, 7), 16)];
  const m = v.match(/\d+/g) || [0, 0, 0];
  return [+m[0], +m[1], +m[2]];
}

/** ui.windows -> CSS vars --win-dialog-<r>, --win-frame-<r> (frame style picked in settings). */
let windowsInfo = {};
export function applyWindows(w) {
  windowsInfo = w || {};
  setFrameStyle(frameStyle);
}
let frameStyle = 0;
export function frameStyles(region) {
  const r = windowsInfo[region];
  return r && r.frames ? r.frames.length : 0;
}
export function setFrameStyle(n) {
  frameStyle = n | 0;
  const root = document.documentElement.style;
  for (const [r, info] of Object.entries(windowsInfo)) {
    if (info.dialog) {
      root.setProperty(`--win-dialog-${r}`, `url("${abs(info.dialog)}")`);
      const s = info.dialogSlice || [8, 8, 8, 8];
      root.setProperty(`--win-dialog-slice-${r}`, s.join(' '));
      root.setProperty(`--win-dialog-w-${r}`, s.map((v) => `calc(var(--u) * ${v})`).join(' '));
    }
    const fr = info.frames || [];
    if (fr.length) root.setProperty(`--win-frame-${r}`, `url("${abs(fr[Math.min(frameStyle, fr.length - 1)])}")`);
  }
  arrowStyles();
  document.documentElement.classList.toggle('frames-ready', Object.keys(windowsInfo).length > 0);
}

/** The dialog's bobbing "more" arrow, stepped like TextPrinterDrawDownArrow (8 frames per step). */
function arrowStyles() {
  let el = document.getElementById('arrow-styles');
  if (!el) {
    el = document.createElement('style');
    el.id = 'arrow-styles';
    document.head.append(el);
  }
  let css = '';
  for (const [r, info] of Object.entries(windowsInfo)) {
    if (!info.arrow) continue;
    const [w, hh] = (info.arrowSize || '8x16').split('x').map(Number);
    const steps = info.arrowSteps || [[0, 0]];
    const sel = r === '1' ? 'body.region-1' : 'body:not(.region-1)';
    const dur = (steps.length * 8) / 59.7275;
    css += `html.frames-ready ${sel} #dialog .more{background-image:url("${abs(info.arrow)}");` +
      `background-size:calc(var(--u)*${w}) calc(var(--u)*${hh});height:calc(var(--u)*16);animation:arrow${r} ${dur.toFixed(3)}s steps(1) infinite}`;
    css += `@keyframes arrow${r}{` + steps.map(([x, y], i) =>
      `${((i * 100) / steps.length).toFixed(2)}%{background-position:calc(var(--u)*${-x}) calc(var(--u)*${-y})}`).join('') + '}';
  }
  css += '@media (prefers-reduced-motion: reduce){#dialog .more{animation:none!important}}';
  el.textContent = css;
}

/** Absolute asset URL: a relative url() inside a CSS variable resolves against the stylesheet. */
function abs(p) {
  return new URL(assets.url(p), location.href).href;
}
