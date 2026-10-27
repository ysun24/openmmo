# r3-items: items, pokedex, badges, day care, decorations, notices, admin tools

Branch `feat/r3-items`. Every rule follows the pret decomp. Each rule names the decomp function it comes from.

## Built

### Field items (`services/FieldItemService.kt`, `FieldItemEffects.kt`, `progression/MonGrowth.kt`)
Out-of-battle use as in `item_use.c` / `party_menu.c` / `pokemon.c`:
- **Status cures.** Antidote, Burn Heal, Ice Heal, Awakening, Parlyz Heal, Full Heal, Heal Powder, Lava Cookie, Full Restore, and the berries Cheri, Chesto, Pecha, Rawst, Aspear and Lum.
  - Status is now persisted: `pokemon.status` holds the Gen 3 status1 bits (`V8_3`, `ADD COLUMN IF NOT EXISTS`, so it works alongside battle's V8_1). It is shown as a PSN/BRN/… chip in the party.
  - The nurse heal (`healParty`) cures status.
- **PP restore.** Ether, Max Ether and Leppa ask which move first. Elixir and Max Elixir cover all moves.
- **PP Up / PP Max.** Bonuses are stored in `pokemon.pp_bonuses` (the decomp's `ppBonuses`). Max PP follows `CalculatePPWithBonus`, and current PP rises with it. A move is refused at 3 bonuses or when its base PP is below 5.
- **Rare Candy.** Sets exp to the floor of the next level. Max HP rises but the damage taken is kept (`CalculateMonStats`). Level-up moves go into free slots. When all four slots are full, a **learn prompt** asks which move to forget or lets the player give it up. Then the level evolution check runs:
  - LEVEL, ATK>/=/<DEF, Silcoon/Cascoon by personality, Ninjask plus Shedinja.
  - The evolved form learns its own moves for that level.
  - The pokedex registers the new species.
- **Evolution stones.** `EVO_ITEM` from `evolution.h`. The battle branch has no `EvolutionService` yet, so this branch has its own field-side `MonGrowth.evolve`, which battle can reuse.
- **Vitamins.** +10 EVs, capped at 100 per stat and 510 total. **EV berries** (Pomeg…Tamato) take off 10.
- **TMs/HMs.**
  - The Gen 3 TM→move mapping comes from `tms_hms.h`, so TM01 is FOCUS PUNCH, not Gen 5's Hone Claws.
  - Species compatibility comes from `tmhm_learnsets.h`; the party picker shows ABLE / NOT ABLE.
  - On a monster that knows four moves, the player picks one to forget. HM moves cannot be forgotten.
  - TMs are used up; HMs are kept.
  - The blank TM descriptions are fixed: they now read "Teaches MOVE. <move description>".
- **Repels** work as before. **Escape Rope** works in caves and takes you to your last outdoor tile.
- **Key items.** They can be registered (Register button in the Bag), and **C** uses the registered item, like the GBA's SELECT.
- All the tables come from `codegen/tools/gen_progression_data.py`, which writes `codegen/.../progression/Gen3Tables.kt` (shared by the server and the gateway): TM moves, TM learnsets, egg moves, evolutions, Hoenn dex order, decorations and decoration mart stocks.

### Pokedex
- `character_pokedex` table. The store registers every owned species as caught whenever monsters change: catch, gift, trade, market, evolution, hatch, and characters loaded from before this change.
- `BattleService` registers seen for the opponent's lead and each switch-in. Eggs are not registered.
- UI:
  - The list has regional (Kanto 1–151 / Hoenn order) and national tabs, "----------" for unseen numbers, a red ball for caught, plus type and sprite.
  - The entry page has the front sprite, category, height/weight (imperial like the English GBA, metric beside it), Hoenn number and the description. Texts come from `pokedex_entries.h` / `pokedex_text*.h`: FireRed's wording for Kanto, Emerald's for Hoenn.
  - Uncaught entries show `?` like the games.

### Badges, gyms, trainer card
- **Badges** are the decomp's own `FLAG_BADGE0n_GET` per region.
- **`BadgeService`** offers these for other lanes:
  - `hasBadge`, `mask`, `count`.
  - `canUseFieldMove(region, move)` — Emerald and FireRed badge→HM orders from `party_menu.c`. For the field branch.
  - `obedienceLevel` — 10/30/50/70/100, `IsMonDisobedient`. For the battle branch.
- **Gym scripts.** All 16 gym leaders of both regions are ported. `codegen/tools/port_gym_scripts.py` did 14 of them plus 90 gym trainers; Norman was done by hand (his scene was partly ported already); Roxanne already existed and now reports her badge.
  - A leader fights `trainerBattle(TRAINER_*)` and sets the decomp's flags and vars.
  - Then the leader hands over the TM (`TM39` from Brock, …), says the badge lines, and the player gets the notice "X received the BOULDERBADGE!".
  - Talking to the leader again gives the post-battle line, or the TM if the bag refused it.
- **Trainer card** shows name, money, pokedex count, play time (`openmmo/PLAY_SECONDS`, banked on logout and counted live) and a **badge case** with Kanto and Hoenn rows. ADMIN/DEV are tagged.

### Day care and eggs (`progression/Daycare*.kt`)
- Ported scripts: Route 117 lady and man (Hoenn), Four Island lady and man (FireRed), and the FireRed Route 5 one-monster day care.
- The decomp's party menu and level menu are pickers in the browser. The script waits on them (`ScriptProgress.pick`).
- Rules, as in `daycare.c`:
  - The deposit guards: only one monster in the party; no other able monster left; Emerald's "two in all".
  - 1 exp per step. When the monster is taken back it levels up and learns moves, with the oldest pushed out (`DeleteFirstMoveAndGiveMoveToMon`).
  - Fee 100 + 100 per level gained.
  - Compatibility 0/20/50/70 (`GetDaycareCompatibilityScore`, with the OT name standing in for the trainer id). The egg roll happens on every 0xFF step count of the second monster.
  - Egg species is the bottom of the mother's (or non-Ditto parent's) line. Nidoran♀ and Illumise lay either line; Azurill and Wynaut need an incense, so without one they hatch as Marill and Wobbuffet.
  - Three inherited IVs.
  - Egg moves: the father's egg moves, then his TM moves the baby can learn, then moves both parents know from the baby's level-up learnset.
  - Hatching after the species' egg cycles × 255 steps: "Huh? CHARMANDER hatched from the EGG!"
  - The man offers the egg twice before letting it go.
- Steps are hooked into `MovementService`.

### Decoration marts, flower shop, "Please come again"
- Fortree desks and chairs, the Lilycove 5F dolls/cushions/posters/mats and rooftop sale, and Slateport's doll and decoration clerks (gated on SECRET POWER) all open a decoration shop on their decomp stock list.
- You can buy any quantity. What you buy goes to the PC as decorations (`openmmo/DECOR/<id>`), and **Menu → Decorations** lists them.
- The Pretty Petal Flower Shop has its owner (berries talk, then plant decorations after badge 3), the Wailmer Pail girl, and the daily random berry girl.
- Closing a mart or decoration shop now runs the clerk's **"Please come again!"** box. `shop.close` sends `SHOP_CLOSED`, and the server runs it on the clerk.

### Notices
- The client classifies server lines (`NoticeKind`: presence/system/reward/error/info). The gateway sends `ui.notice.kind`.
- The browser does not toast `presence`; joins and leaves stay in the chat log, and they no longer go into the battle log.
- The leave line now names the player.

### Admin tooling
- `server.adminUserIds` (default `1`, env `OPENMMO_ADMIN_USER_IDS`). The login bootstrap admin is user 1 of an empty database.
- Characters of admin accounts get `ADMIN (0x200)` + `DEVELOPER` when they join.
- `/grant <player> developer|admin|none` is admin-only and works on online players.
- `/givemon` sends the monster to the first free PC slot when the party is full.
- Tester commands: `/tp <region> <bank> <map> <x> <y>` and `/setstatus <slot> psn|tox|brn|frz|par|slp|none`. (Not `/warp`: a client-side `/w` would swallow it. Hoenn banks are decomp group + 50.)

## How verified
- **Unit tests**, all green:
  - `FieldItemProgressionTest`: 16 rules — status cures, berries, Ether / Max Ether, PP Up / PP Max, Rare Candy (exp floor, learn, HP, Lv100, evolution, learn prompt), stones, Eevee, vitamins and EV berries, TM compatibility, TM forget and HM rules, Escape Rope, eggs.
  - `ProgressionTest`: Gen 3 tables, badges and obedience, HM badges, pokedex caught/seen, compatibility, deposit/steps/fee/withdraw, the egg roll on step 255 and its egg moves, hatching by cycles, decoration buy, progress packet.
  - `ProgressPacketsTest`, `ProgressJsonTest` (dex, card, decor, notice kinds, dispatcher routing), and the updated `StateJsonTest` and `FieldItemServiceTest`.
  - Command: `:server.game:test :protocols.game:test :client:check :web.gateway:test :codegen:test :common:check spotlessCheck`.
  - **Gateway e2e: ALL OK.**
- **Real stack** (login 2216, game 7887, gateway 8530, dbs 21111/21121). The browser runs clicked the real buttons in headless Chromium; the screenshots are in `docs/reports/items/`, and I looked at each one.
  - Antidote on a PSN Vulpix ("VULPIX was cured of its poisoning.").
  - The Ether move picker (SCRATCH 34/35 → restored).
  - Rare Candy: Charmander 15 → Lv16 → "evolved into CHARMELEON".
  - Fire Stone: Vulpix → Ninetales.
  - The TM pocket with Gen 3 texts, ABLE/NOT ABLE, and the forget-a-move prompt.
  - Rare Candy on a full moveset: Bulbasaur → Ivysaur and the learn prompt; forgetting RAZOR LEAF for SWEET SCENT.
  - Brock (WS-driven): the full dialog, TM39, "Ash received the BOULDERBADGE!", the post-battle line on a second talk. Then the badge case with the BOULDERBADGE, desktop and mobile.
  - The decoration shop: list, quantity picker, "SMALL DESK x2 was sent to your PC", money ₽35,811 → ₽29,811, then "Please come again!", then Decorations in the PC.
  - Pokedex list (regional/national, mobile too) and the NINETALES entry page.
  - Day care (WS-driven deposit through the picker messages):
    - Deposit Ditto + Charmeleon.
    - An egg in under 6000 steps. The man: "Ash received the EGG from the DAY-CARE MAN."
    - "Huh? CHARMANDER hatched from the EGG!" after 5220 steps.
    - In the browser, the lady's withdraw picker (DITTO +6 ₽700, CHARMELEON +10 ₽1,100), then "it will cost ¥700" YES/NO.
    - A full team correctly gets "Your POKéMON team is full."
  - `/grant` (WS): refused for a non-admin; `/grant Gary none` removes `/givemoney`; `developer` restores it. Joins showed in chat only, with no toast.

## Not done / notes
- **Optional parts:** secret bases (decorations can be bought and stored only), pokedex area/habitat, Game Corner coins.
- **No friendship stored:** no friendship evolutions (Espeon, Pichu→Pikachu, …), and EV berries do nothing on a stat with 0 EVs. Trade and beauty evolutions are left to their lanes.
- **HM07 WATERFALL and HM08 DIVE have no Gen 5 item id**, so they cannot be given or taught as items (both are in the tables).
- **Escape Rope:** our map data lacks `allowEscaping`, so caves (MAP_TYPE_UNDERGROUND) stand in for it, and the escape point is the last town/route tile, not the decomp's escape warp.
- **Not wired into the battle engine:** the battle engine does not read or write `status` / `pp_bonuses` yet (battle lane), and `obedienceLevel` is offered but not called by battle.
- **HM gating** is offered for the field branch (`BadgeService.canUseFieldMove`) but not called from field moves.
- **Gym trainer details:**
  - Gym trainers are ported in gym maps only. `set_gym_trainers` is not applied.
  - Some intro/defeat lines exist only as battle text in the decomp (no dialog id), so they are skipped.
  - The Dewford "brighten room" callbacks belong to the field branch.
- **Wailmer Pail:** Gen 5 has no WAILMER PAIL, so the girl gives the SPRAYDUCK.
- **Daycare man's compatibility line:** a notice, because `gText_DaycareCompatibility*` has no dialog id.
- **Missing dialog line, also a notice:** Slateport's "come back with SECRET POWER".
- **Day care hatching:** Flame Body / Magma Armor do not halve egg cycles.
- **Seen registration** only comes from battles. Trades and gifts mark caught directly.

## Files touched outside my lane (small, additive)
- **`common`:** `Pokemon` (+`status`, `ppBonuses` with defaults), `CharacterPermissions.ADMIN`, new `MonStatus`.
- **`protocols.game`:** two registrations in `GameProtocol.kt`.
- **`server.game`, hooks and shared code:**
  - `BattleService` (2 `registerSeen` lines), `MovementService` (step hook), `LoginService` (join hook), `GameAppHandler` (handler, leave hook, the leave line with the name).
  - `ScriptContext` / `ScriptRunner` (+`progress`, `decorationShop`), `CharacterStore` / `CharacterRepository` (pokedex, day care list, status columns, `commitDurably`, `setStoryVars`, `registerSeen`), `StoryPlayerService.healParty`, `SessionAttributes.OpenShop.npcEntityId`, `config` (`adminUserIds`, `application.conf`), `ChatCommandModule`.
- **Generated scripts edited in place:**
  - Every `*_Gym*.kt` (tool) and `PetalburgCity_Gym.kt`.
  - `Route117.kt`, `Route117_PokemonDayCare.kt`, `FourIsland.kt`, `FourIsland_PokemonDayCare.kt`, `Route5_PokemonDayCare.kt`.
  - `FortreeCity_DecorationShop.kt`, `LilycoveCity_DepartmentStore_5F.kt`, `LilycoveCity_DepartmentStoreRooftop.kt`, `SlateportCity.kt`, `Route104_PrettyPetalFlowerShop.kt`.
- **`client`:** `GameClient` (`progress`, notice kind), `OverworldState.Notice.kind`.
- **`web/static`:**
  - `js/main.js` (import + UIS entry), `js/net.js` (5 section names).
  - `js/ui/misc.js`: notice toast filter, status/egg chips, Register/Teach buttons, ABLE labels, routing move/teach items to `ui/progress.js`, menu entries.
  - New: `js/ui/progress.js` and `css/items.css`, the latter loaded by `progress.js`, so `index.html` and `main.css` are untouched.
- **`codegen`:** new `progression/Gen3Data.kt` + generated `Gen3Tables.kt`; tools `gen_progression_data.py`, `port_gym_scripts.py`.

## PROTOCOL changes
New section **6. r3-items** in `docs/web/PROTOCOL.md`:
- New browser messages: `item.move`, `learn.reply`, `item.register`, `item.registered`, `daycare.*`, `decor.*`, `progress.refresh`.
- New `ui` and `party` fields, and the new `dex` / `trainer` / `daycare` / `decor` / `learn` sections.
- Game opcodes: c2s 0xF8 and s2c 0xE7. s2c 0xF8-0xFB were already taken, so the state packet uses the free 0xE7. Other branches must not pick 0xE7.
