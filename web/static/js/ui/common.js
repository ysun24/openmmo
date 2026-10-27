// Small shared view pieces.
import { h } from '../util.js';
import { assets } from '../assets.js';

/**
 * <img> for a pokemon sprite (icon|front|back), or a placeholder disc with the dex number.
 * Icons use the two-frame sheet and cycle like the GBA party menu (CSS `img.anim2`); `opts.shiny`
 * picks the shiny palette for front/back; `opts.slow` slows the icon (low HP, as the party menu).
 */
export function monImg(dex, kind = 'icon', cls = '.ic', opts = {}) {
  const anim = kind === 'icon' && !opts.still ? assets.pokemon(dex, 'iconAnim') : null;
  const url = dex ? anim || assets.pokemon(dex, kind, !!opts.shiny) : null;
  if (!url) return h('span' + cls + '.ph', '#' + (dex ?? '?'));
  const img = h('img' + cls + (anim ? '.anim2' : '') + (anim && opts.slow ? '.slow' : ''), { src: url, alt: '', draggable: false });
  img.addEventListener('error', () => img.replaceWith(h('span' + cls + '.ph', '#' + dex)), { once: true });
  return img;
}
export const monName = (m) => (m.nick ? `${m.nick}` : m.name) || '?';
export const itemIc = () => h('span.ic.ph', '◆');
