# feat/battlefx: battle presentation and PvP duel rules

## What was built

**Battle state (client `BattleClient`, gateway `battle` section)**
- `turn` is a real counter now: +1 per action prompt. `activeSlot` is sent explicitly, so the switch list no longer guesses which party member is out.
- Moves carry `type` and `power`. `me.exp` is the 0..1 fill of the EXP bar, using the Gen 3 curves the server's `ExpCurves` uses.
- **Effect stream `fx`:** the client records every battle event in order, as `{n, k, side, ...}`. The kinds are start, text, move, hp, miss, stat, faint, switch, ball, exp, level and end. The gateway sends the newest 60. Stat-stage changes appear as `stat` effects and as log lines.
- Status conditions: the server does not model them, so nothing is sent for them yet.
- **Duel state:** `rules` (level cap, party size, timer) and `timer` (seconds, mustAct, both sides' timeouts, autoPicked). `invite.rules` shows the rules of a pending challenge.
- **Learn prompt:** carries the offered move and the 4 known moves, each with type, power, accuracy and PP.

**Browser (`web/static/js/ui/battle.js`, new `web/static/css/battle.css`)**
- Animations are driven by the `fx` queue, one effect at a time, so they follow the server's order even though state messages are coalesced. While effects play, the screen shows a presented copy of both monsters. When the queue drains, that copy is reconciled with the real state.
- Each step has a 6 s cap. Text types itself out, auto-advances, and skips ahead on A or a tap. A long backlog plays at about 3x speed. Input is never blocked for long.
- Effects shown:
  - Intro: platforms slide in and a wild monster slides in from the left. For trainer and duel battles, the ball send-out rides on the "sent out" / "Go!" lines.
  - Hits: the attacker lunges, then a type-coloured particle burst and flash land on the target (a ring for status moves).
  - HP: the sprite blinks and the info box shakes, then the HP bar tweens with green/yellow/red colour changes and the HP number counts down.
  - Miss: the target dodges.
  - Stat changes: stripes rise or fall over the monster.
  - Faint: the sprite slides down out of its clip box.
  - Switches: a red flash and shrink on recall, then a send-out.
  - Poké Ball: arc throw, the monster is absorbed, the ball drops and bounces, shakes N times, then catches (stars, ball dims) or breaks open (monster reappears).
  - EXP bar fill and a level-up glow, plus the end result box.
- Only transform and opacity are animated (the HP and EXP bars use scaleX). A Pixel 7 profile ran at **60.0 fps, 0.46 ms work/frame, max frame 16.8 ms** during a hit animation.
- The sprites and the Poké Ball are drawn with CSS. No assets are used apart from the existing decomp sprites.
- **Learn-move menu:** shows the new move (highlighted) and the 4 known moves, each with a type chip, power and PP, plus "Don't learn". Picking any row opens a YES/NO confirm, and B backs out of the confirm.
- **Duel challenge dialog** (Menu → Duel…, or a tapped player → ⚔ Duel): choose the level cap (Off/50/100), party size (1/3/6) and turn timer (Off/30/60/90 s). The invite panel shows the rules.
- **In battle:** a badge shows the turn, the rules, and a countdown that turns red at 10 s or less ("⏳ foe" while only the rival is choosing), plus both sides' timeout counts. RUN reads FORFEIT in a duel.
- Fixed a focus bug where an open BAG or party sheet left the cursor on the menu behind it, so A re-clicked FIGHT.

**Server (`PvpBattleService`, `PvpDuel`, new `DuelTimer`)**
- **Rules:** the challenge (new `DuelRulesChallengePacket`) carries the rules. The server clamps them: cap 0 or 1-100, party 1-6, timer 0 or 5-240 s. They are stored with the invite and sent to the target as `DuelRulesPacket`, before the invite. They are sent again to both players at the start. The retail `DuelChallengePacket` still works and means default rules.
- **Accept:** both parties are validated again under the duel's rules.
- **Teams:** each side brings its first `maxParty` healthy monsters. Monsters above the cap are copied at the cap level, with stats recomputed and HP kept at the same fraction. They are copies, so the stored party is never touched and the "no persistent effects" guarantee holds. The tests check that the stored monster is unchanged after a capped duel.
- **Turn timer:** started on every prompt (both sides) and on forced switches (only the owing sides). `BattleTurnTimerPacket` tells each side whether it must act. On timeout, each side that still owes an action takes a timeout and the server acts for it: the first usable move, or the first healthy monster for a forced switch. A player with 3 timeouts forfeits. Stale timers are ignored by generation, and the timer is cancelled when the duel ends.

## How it was verified
- **Tests:** `:server.game:test` (289), `:protocols.game:test` (85), `:client:check`, `:web.gateway:test` (21), `:web.assets:test` (2). All pass.
- **New server tests** (`PvpBattleServiceTest`, with a hand-fired `DuelTimer`):
  - the invite carries the rules, and a timeout auto-picks the first move
  - forfeit after 3 timeouts, with nothing persisted
  - both sides idle: one timeout each, and the turn still resolves
  - level cap 50 scales Lv80 down to 50 and leaves the stored monster untouched
  - party-size limit 1
  - no clock without a timer
- **New codec tests:** `DuelRulesPacketsTest` (roundtrip and opcodes).
- **New gateway tests:** `StateJsonTest` (rules/timer/fx/invite) and `DispatcherTest` (the `duel` rule fields and their validation).
- **Gateway e2e:** ALL OK, run twice, the second time on the final build.
- **Real stack** (login 2176, game 7847, gateway 8490, own Postgres pair) with the browser in docker Chromium. I looked at every screenshot. They are in `docs/web/battlefx/`:
  - wild battle: `01-wild-intro`, `02-sendout`, `03-hp-mid-tween` (bar at about 60% while tweening), `04-faint` (sliding out)
  - catch: `05-ball-absorb`, `06-ball-shake` (tilted mid-shake), `07-caught`
  - `08-levelup-exp`: EXP bar full plus the level-up glow, Lv18→19
  - learn move: `09-learn-menu`, `10-learn-confirm` (Rage replaced Ember)
  - **PvP**, two concurrent browsers, Ash vs Gary, Lv. cap 50, 1v1, 30 s timer:
    - `11-duel-rules-dialog`
    - `12-invite-with-rules-mobile` (from a separate run with 100 / 3v3 / 60 s rules)
    - both Lv70 monsters were shown and fought at Lv50
    - Gary idled: auto-move each turn, the badge counted "⚠1/3" and then "⚠2/3" (`13-duel-clock-idle-side`, `14-duel-clock-active-side`)
    - the third timeout ended it: "Gary ran out of time and forfeits.", Ash VICTORY / Gary DEFEAT (`15-duel-forfeit-idle-side`)
  - `16-mobile-battle`

## Not done / known limits
- **Status conditions:** the server has none, so there is no status effect or icon.
- **Catching:** the server still catches every time. The client always gets shakes=3 and caught=true, so the break animation is written but has never been seen against the server. The server needs a Gen 3 catch formula and a shake count in the ball event.
- **Graphics:** no trainer sprites or battle backgrounds, and no `graphics/battle_anims` sprites; the effects are CSS-drawn. Those files would have to be exported by `:web.assets`, which is not my module.
- **Reload:** reloading mid-battle shows the current state without replaying effects (by design).
- **Pre-existing bug, not mine:** the top-bar "☰ Menu" button (`#btn-menu`) has no click handler. Tab or the touch MENU button work. The duel dialog is reached through those.

## Files touched outside my area (small)
- `web/static/js/ui/misc.js`: the Duel entries open `battle.openChallenge`, and the invite panel shows a rules line.
- `web/static/index.html`: links `css/battle.css`.
- `server.game/.../handler/GameAppHandler.kt`: registers the handler for `DuelRulesChallengePacket`.
- `protocols.game/.../GameProtocol.kt`: three registrations.

## PROTOCOL changes (additive, `docs/web/PROTOCOL.md`)
- **Browser → gateway:** `duel` takes optional `levelCap`, `party` and `timer`.
- **Battle section:** new `activeSlot`, `me.exp`, `moves[].type/power`, `rules`, `timer` and `fx`, plus `learn.mon/offer/moves`.
- **Invite:** `invite.rules`.
- **Game protocol (ours; free in both directions, checked against every worktree):**
  - c2s 0xEE `DuelRulesChallengePacket`
  - s2c 0xEE `DuelRulesPacket`
  - s2c 0xEF `BattleTurnTimerPacket`
