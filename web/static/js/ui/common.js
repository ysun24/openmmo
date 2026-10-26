// Small shared view pieces.
import { h } from '../util.js';
import { assets } from '../assets.js';

/** <img> for a pokemon sprite (icon|front|back), or a placeholder disc with the dex number. */
export function monImg(dex, kind = 'icon', cls = '.ic') {
  const url = dex ? assets.pokemon(dex, kind) : null;
  if (!url) return h('span' + cls + '.ph', '#' + (dex ?? '?'));
  const img = h('img' + cls, { src: url, alt: '', draggable: false });
  img.addEventListener('error', () => img.replaceWith(h('span' + cls + '.ph', '#' + dex)), { once: true });
  return img;
}
export const monName = (m) => (m.nick ? `${m.nick}` : m.name) || '?';
export const itemIc = () => h('span.ic.ph', '◆');
