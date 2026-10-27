# feat/content — marts, nurses, PC, dialog names, bag & party (round 2)

## Built

**Server (`server.game`)**
- **Every Poke Mart and nurse ported from the decomp.** `codegen/tools/port_shop_scripts.py` reads
  `data/maps/*/scripts.inc` of pokefirered/pokeemerald and replaces the `TODO("port …")` stubs:
  42 clerks (all Kanto marts incl. Celadon Dept. Store, Indigo Plateau, Sevii, Trainer Tower; all
  Hoenn marts incl. Lilycove Dept. Store, Herb Shop, Energy Guru, Battle Frontier, Trainer Hill) and
  35 nurses. Gated stock follows the decomp's own branches: Oldale (`FLAG_ADVENTURE_STARTED`),
  Petalburg (`FLAG_PETALBURG_MART_EXPANDED_ITEMS`), Rustboro (`FLAG_MET_DEVON_EMPLOYEE`), Two Island
  (`VAR_MAP_SCENE_TWO_ISLAND` + intro flags), Trainer Hill. (FireRed marts are not badge gated in
  the decomp.) Emerald `ITEM_TM_<MOVE>` map to the TM number; 7 Gen-3 mail items have no wire id
  and are dropped (listed by the tool). The generator wipes hand ports, so the tool edits in place
  and is re-runnable.
- **Kanto nurse** (`kanto/PokemonCenterNurse.kt`): its text lives in `data/event_scripts.s`, which
  has no ROM offset in our table, so the lines are in `codegen/dialog/kanto.extra.json` with
  synthetic ids (`region | 0x0F000000 | n`); dialog codegen merges `<region>.extra.json`, the client's
  `DialogText` loads it.
- **Dialog buffers**: `ScriptContext.bufferSpeciesName/bufferItemName/bufferString`; every box
  (say/sign/yes-no) carries the buffers plus `{RIVAL}` (Kanto: GREEN, FireRed's first preset;
  Hoenn: MAY/BRENDAN by player gender). Oak's lab now names both starters.
- **Field items** (`FieldItemService`, `FieldItemEffects`): Potions/drinks/herbs/berries heal,
  Revive/Max Revive, Elixir/Max Elixir, Repel/Super/Max (counts down per step in
  `EncounterService`, blocks wild monsters below the lead's level, "wore off" notice). Status heals
  report "It won't have any effect" (status is not persisted outside battle). Item is taken first,
  refusals cost nothing; result is a notice.
- **Party/PC** (`PartyStorageService`): reorder anywhere; deposit/withdraw/swap only on a map that has
  a nurse; last able monster stays. `CharacterStore.rearrangeMonsters` parks moved rows on slot
  30000+ first, because the repository's merge also matches on (owner, container, slot) — a direct
  swap failed with a pk violation in the real DB.

**Protocol (`protocols.game`, additive, ours)**: dialog arg tag 4 `TextStringArg(var, text)`
(`RIVAL = 0x10`); c2s `0xB0 FieldItemUsePacket(itemId, pokemonId)`; c2s `0xB1`
`SwapInventorySlotsPacket` registered (existing class; list type 1 party, 0 PC; dest -1 = any).

**Client**: fills `TextStringArg` (STR_VAR / RIVAL), species names via `BattleNames`;
`useItem`, `moveMonster`; last server notice in `OverworldState`.

**Gateway**: `ItemCatalog` (pocket + description from FireRed `items.json`, Emerald `items.h`,
pokeblack prices/field use); `ui.shop.sell` (half price), `ui.shop.offers[].desc`, `ui.bag[]`
pocket/desc/use, `ui.notice`, `ui.dialog.speaker` (named npcs by `OBJ_EVENT_GFX_*`), party summary
(stats with nature, ability, types, moves+PP, OT, EXP), new `pc` section; messages `item.use`,
`party.move`, `pc.deposit`, `pc.withdraw`.

**Browser**: `shop.js` rewritten (Buy/Sell tabs, −10/−/+/+10 quantity picker, total, money after,
B backs out of the picker); `misc.js`: POKéMON panel (reorder ▲▼, Info → summary), Bag by pocket
with descriptions and Use (party target picker), PC panel, notice toasts; menu gains Bag/PC.

## How verified
- Tests: `:server.game:test` (new `ContentScriptsTest`, `FieldItemServiceTest`,
  `PartyStorageServiceTest`), `:protocols.game:test` (TextStringArg round trip), `:client:check`,
  `:web.gateway:test` (StateJson/Dispatcher updated + new cases), `:web.assets:test`,
  `:codegen:test`, `spotlessCheck` — all green. Gateway e2e: **ALL OK**.
- Real stack (login 2186, game 7857, gateway 8500, dbs 20811/20821): a **fresh account/character**
  (`red1`/Red) went through Pallet → Oak's cutscene → lab → picked Bulbasaur ("Red received the
  BULBASAUR", "GREEN received the CHARMANDER") → Route 1 (wild battles) → Viridian Mart parcel →
  back to Oak (Pokédex + Poké Balls) → Viridian Pokémon Center heal → Mart **Buy** 3 Potions
  (30000→29100) → **Sell** 1 Poké Ball (+100) → Route 1 battle → **Potion from bag** (8→24 HP) →
  PC deposit/withdraw/reorder (persisted across a server restart). Walking was scripted over the
  gateway WebSocket (driver in scratch); every UI step was done in headless Chromium by clicking the
  real buttons, and a second browser (friend/Gary) was logged in concurrently.
- Screenshots (looked at): `docs/reports/content/*.png` — nurse dialog with NURSE speaker and
  YES/NO, summary, CLERK greeting, Buy list, quantity picker, Sell list, after sale, bag pockets,
  potion target, potion toast, mobile menu and PC.

## Not done / notes
- Rival battle trigger in Oak's lab can be walked around (row 8 is open beyond x 5–7 in our
  collision); not my area, left as is.
- No PC tile interaction (the GBA PC is a metatile behaviour, not an event); the PC is reachable
  from the menu while standing in any map with a nurse.
- Decoration marts (Fortree, Lilycove 5F/rooftop, Slateport, Flower Shop) not ported.
- Status heals do nothing out of battle (no persisted status). Ether/Max Ether (need a move
  choice), Rare Candy, evolution stones, TMs not usable from the bag.
- Gen 5 TM numbers ≠ Gen 3 for a few TMs; TM descriptions are left blank for that reason.
- "Please come again" after a mart closes is not shown (the mart window does not wait, as before).
- Notices (including "X joined the game") toast in the browser.

## Files touched outside my area
`codegen/src/generator/.../dialog/Main.kt` (merge extra lines), `codegen/dialog/kanto.extra.json`,
`codegen/tools/port_shop_scripts.py`, `protocols.game` (packet + arg), `server.game/.../storage/
CharacterStore.kt` (rearrange, setRepel), `EncounterService.kt` (repel), `GameAppHandler.kt`,
`client/.../GameClient.kt` (notice), `web/static/js/main.js` (the ☰ Menu button had no click
handler; shop B), `web/static/js/net.js` (`pc` section), `web/static/css/main.css`.

## PROTOCOL changes (additive)
§2: `item.use`, `party.move`, `pc.deposit`, `pc.withdraw`. §3: `ui.dialog.speaker` now set,
`ui.shop.sell`, `ui.shop.offers[].desc`, `ui.bag[].pocket/desc/use`, `ui.notice`, party summary
fields, new `pc` section; notes on filled dialog text and notices.
