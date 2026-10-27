// Field moves, the bike and the FLY map (PROTOCOL.md "r3-field"). The server decides; this panel
// only lists what world.field says the party can use and sends `field` messages.
import { h, $ } from '../util.js';
import { panels, setFocus, rerender } from './panels.js';
import * as misc from './misc.js';

let app = null;
let lastOpenSeq = null;

const LABEL = {
  cut: 'CUT', flash: 'FLASH', rocksmash: 'ROCK SMASH', strength: 'STRENGTH', surf: 'SURF', fly: 'FLY',
  dive: 'DIVE', waterfall: 'WATERFALL', teleport: 'TELEPORT', dig: 'DIG', sweetscent: 'SWEET SCENT',
};

export function init(a) {
  app = a;
  // A small HUD button next to the menu (touch users have no F key).
  const bar = $('#topbar');
  if (bar && !$('#btn-field')) {
    const b = h('button#btn-field.small', { type: 'button', title: 'Field moves (F)', on: { click: () => openField() } }, '✦ Field');
    const menu = $('#btn-menu');
    if (menu && menu.parentNode) menu.parentNode.insertBefore(b, menu);
    else bar.append(b);
  }
}

export function update(state, changed) {
  if (changed.includes('ui') && state.ui) {
    const o = state.ui.open;
    if (o && lastOpenSeq !== null && o.seq !== lastOpenSeq) {
      // The server asked for a UI: the PC tile, or FLY without a target.
      if (o.kind === 'pc' || o.kind === 'pcItems') {
        // r3-items owns the PC panel and may open it from ui.open itself; only open it if not.
        queueMicrotask(() => panels.isOpen('pc') || misc.openPc());
      } else if (o.kind === 'fly') openFly();
    }
    lastOpenSeq = o ? o.seq : lastOpenSeq ?? 0;
  }
  if (changed.includes('world') && panels.isOpen('field')) renderField();
}

const field = () => (app.net.state.world && app.net.state.world.field) || null;
const selfMode = () => app.world.self.mode || 'walk';

/** Keys from input.js: 'field' opens the panel, 'bike' toggles the bicycle. */
export function onPress(btn) {
  if (btn === 'field') return openField(), true;
  if (btn === 'bike') return toggleBike(), true;
  return false;
}

function toggleBike(kind) {
  const f = field();
  if (!f || !f.hasBike) return app.send({ t: 'field', action: 'bike' }); // the server explains
  const riding = selfMode() === 'mach_bike' || selfMode() === 'acro_bike';
  app.send({ t: 'field', action: riding ? 'dismount' : 'bike', arg: kind === 'acro' ? 1 : 0 });
}

export function openField() {
  panels.open('field', 'Field moves');
  renderField();
  const p = panels.get('field');
  setFocus(p.body, p.body.querySelector('button'));
}

function renderField() {
  const p = panels.get('field');
  if (!p) return;
  const f = field() || { moves: [], fly: [] };
  const region = app.world.map ? app.world.map.region : 0;
  const act = (label, k, fn, disabled = false) =>
    h('button', { type: 'button', 'data-k': k, disabled, on: { click: () => { panels.close('field'); fn(); } } }, label);
  const riding = selfMode() === 'mach_bike' || selfMode() === 'acro_bike';
  const bag = (app.net.state.ui && app.net.state.ui.bag) || [];
  const has = (name) => bag.some((b) => b.name && b.name.toLowerCase() === name && b.qty > 0);
  rerender(p.body, () => [
    ...f.moves.map((m) => act(LABEL[m] || m.toUpperCase(), 'fm-' + m, () => (m === 'fly' ? openFly() : app.send({ t: 'field', action: m })))),
    !f.moves.length ? h('p.muted', 'No field moves yet (a POKéMON must know the move, HMs need their BADGE).') : null,
    f.hasBike
      ? riding
        ? act('Get off the BIKE', 'bike-off', () => toggleBike())
        : region === 1
          ? [act('MACH BIKE', 'bike-mach', () => toggleBike('mach'), !f.canBike), act('ACRO BIKE', 'bike-acro', () => toggleBike('acro'), !f.canBike)]
          : act('BICYCLE', 'bike', () => toggleBike(), !f.canBike)
      : null,
    has('escape rope') ? act('ESCAPE ROPE', 'rope', () => app.send({ t: 'field', action: 'escaperope' }), !f.canEscape) : null,
    has('dowsing mchn') || has('itemfinder') ? act('ITEMFINDER', 'finder', () => app.send({ t: 'field', action: 'itemfinder' })) : null,
    h('p.muted', `${f.canRun ? 'Hold Shift (or Run) to run. ' : ''}F: this menu · C: bike`),
  ].flat());
  p.body.style.gridTemplateColumns = '1fr 1fr';
}

/** The FLY map: the towns visited so far (FLAG_WORLD_MAP_* / FLAG_VISITED_*). */
export function openFly() {
  const p = panels.open('fly', 'FLY — where to?');
  const f = field() || { fly: [] };
  const here = app.world.map;
  rerender(p.body, () => [
    ...f.fly.map((d) =>
      h('button', {
        type: 'button', 'data-k': `fly-${d.bank}-${d.map}`,
        disabled: here && here.bank === d.bank && here.map === d.map,
        on: { click: () => { panels.close('fly'); app.send({ t: 'field', action: 'fly', bank: d.bank, map: d.map }); } },
      }, prettyName(d.name))),
    !f.fly.length ? h('p.muted', 'No towns visited yet.') : null,
  ]);
  p.body.style.gridTemplateColumns = '1fr 1fr';
  setFocus(p.body, p.body.querySelector('button:not(:disabled)'));
}

const prettyName = (n) => String(n || '?').replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ').toUpperCase();
