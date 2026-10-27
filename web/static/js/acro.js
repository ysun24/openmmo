// Emerald's Acro Bike (bike.c AcroBikeHandleInput* / AcroBikeTransition_*), predicted for the self.
// The server checks every step against the trick state it was told (FieldAction ACRO, PROTOCOL r4)
// with the same terrain rules (server FieldRules.step): bumpy slopes take only a bunny hop, the
// isolated rails only a side jump, a rail is ridden along and never turned off sideways.

/** GBA frames -> ms. */
export const FRAME_MS = 1000 / 60;
/** Riding: MOVE_SPEED_FAST_2, 6 frames a tile (sStep3Funcs). */
export const ACRO_STEP = 100;
/** A rolling wheelie: MOVE_SPEED_FAST_1 (8 frames). */
export const WHEELIE_STEP = 133;
/** Every Acro jump is 16 frames (DoJumpSpriteMovement JUMP_DISTANCE_IN_PLACE / NORMAL). */
export const HOP_MS = 16 * FRAME_MS;
/** AcroBikeHandleInputWheelieStanding: B held 40 frames turns a wheelie into a bunny hop. */
const HOP_AFTER = 40 * FRAME_MS;
/** AcroBikeHandleInputTurning waits 6 frames before turning... */
const TURN_WAIT = 6 * FRAME_MS;
/** ...and sAcroBikeTricksList wants B and the direction pressed within 4 frames. */
const TRICK_WINDOW = 4 * FRAME_MS + 1;

/** sJumpY_High / _Low / _Normal (event_object_movement.c), 16 entries, pixels up. */
export const JUMP_Y = {
  high: [4, 6, 8, 10, 11, 12, 12, 12, 11, 10, 9, 8, 6, 4, 0, 0],
  low: [0, 2, 3, 4, 5, 6, 6, 6, 5, 5, 4, 3, 2, 0, 0, 0],
  normal: [2, 4, 6, 8, 9, 10, 10, 10, 9, 8, 6, 5, 3, 2, 0, 0],
};

/** FieldAction ACRO arg (AcroTrick ordinals). */
const TRICK = { none: 0, wheelie: 1, hop: 2, side_jump: 3, turn_jump: 4 };
const OPPOSITE = { up: 'down', down: 'up', left: 'right', right: 'left' };

const isVRail = (b) => b === 'VERTICAL_RAIL' || b === 'ISOLATED_VERTICAL_RAIL';
const isHRail = (b) => b === 'HORIZONTAL_RAIL' || b === 'ISOLATED_HORIZONTAL_RAIL';
export const isRail = (b) => isVRail(b) || isHRail(b);

/** CanBikeFaceDirOnMetatile: on a rail the bike only faces along it. */
export function canBikeFace(dir, beh) {
  return dir === 'left' || dir === 'right' ? !isVRail(beh) : !isHRail(beh);
}

/**
 * The extra Acro rules on top of the ordinary step rules (server FieldRules.step): returns false
 * when the step from [fromBeh] onto [toBeh] in [dir] is refused in [trick].
 */
export function acroAllows(trick, dir, fromBeh, toBeh, ledge) {
  if (trick === 'turn_jump') trick = 'none';
  if (fromBeh === 'BUMPY_SLOPE' && trick === 'none') trick = 'wheelie';
  if (trick !== 'side_jump' && !canBikeFace(dir, fromBeh)) return false;
  if (trick === 'side_jump') {
    if (ledge || toBeh === 'BUMPY_SLOPE') return false;
    if (isVRail(toBeh) && (dir === 'up' || dir === 'down')) return false;
    if (isHRail(toBeh) && (dir === 'left' || dir === 'right')) return false;
  }
  if (toBeh === 'BUMPY_SLOPE') return trick === 'hop';
  if (toBeh === 'ISOLATED_VERTICAL_RAIL' || toBeh === 'ISOLATED_HORIZONTAL_RAIL') return trick === 'side_jump';
  return true;
}

/** The self's Acro state machine. One per World. */
export class Acro {
  constructor(world) {
    this.w = world;
    this.reset();
  }

  reset() {
    /** 'normal' | 'wheelie' | 'hop' (ACRO_STATE_NORMAL / WHEELIE_* / BUNNY_HOP). */
    this.state = 'normal';
    /** The trick the server was last told. */
    this.sent = 'none';
    /** When the standing wheelie started counting B (bikeFrameCounter). */
    this.holdFrom = 0;
    /** A pending turn: {dir, at} (ACRO_STATE_TURNING). */
    this.turn = null;
    /** An in-place jump (standing hop, turn jump) until this time. */
    this.busyUntil = 0;
  }

  active() {
    const w = this.w;
    return w.self.mode === 'acro_bike' && !!w.map && w.map.region !== 0;
  }

  tell(trick) {
    if (trick === this.sent) return;
    this.sent = trick;
    this.w.send({ t: 'field', action: 'acro', arg: TRICK[trick] });
  }

  /** Pose for the renderer: a transient anim {name, t0} and whether the front wheel is up. */
  setAnim(name, now) {
    this.w.self.acroAnim = { name, t0: now };
  }

  setState(state, now) {
    const me = this.w.self;
    const was = this.state;
    this.state = state;
    me.acro = state === 'normal' ? null : state;
    if (state === 'wheelie' && was === 'normal') this.setAnim('bunnyHopBackWheel', now);
    if (state === 'normal' && was !== 'normal') this.setAnim('standingWheelieBackWheel', now);
    if (state === 'wheelie') this.holdFrom = now;
    this.tell(state === 'normal' ? 'none' : state);
  }

  /** Off the bike, a snap, a map change: the decomp's BikeClearState. */
  clear() {
    const me = this.w.self;
    this.reset();
    me.acro = null;
    me.acroAnim = null;
  }

  /** In place jump of [type] lasting HOP_MS (standing bunny hop, turn jump). */
  hopInPlace(type, now) {
    const me = this.w.self;
    me.hopT0 = now;
    me.hopType = type;
    this.busyUntil = now + HOP_MS;
  }

  /**
   * Called by World.update instead of tryStep while on the Acro Bike. [continuing] = the self just
   * finished a step this frame.
   */
  tick(input, carry, continuing, now) {
    const w = this.w;
    const me = w.self;
    if (!input.enabled || w.pending.length >= 8) return;
    if (w.forcedUntil) {
      if (now < w.forcedUntil) return;
      w.forcedUntil = 0;
    }
    if (now < this.busyUntil) return;
    const b = !!input.b;
    const here = w.behAt(me.x, me.y);
    const onBumpy = here === 'BUMPY_SLOPE';
    // Bike_HandleBumpySlopeJump: on a bumpy slope the bike is always on its back wheel.
    if (onBumpy && this.state === 'normal') this.setState('wheelie', now);
    let dir = input.dir;

    if (this.state === 'normal') {
      if (this.turn) {
        // ACRO_STATE_TURNING: B and the new direction together are a jump, otherwise a turn.
        const t = this.turn;
        if (dir !== t.dir) {
          this.turn = null;
          if (!dir) return;
        } else if (b && now - input.bDownAt <= TRICK_WINDOW && now - input.dirDownAt <= TRICK_WINDOW) {
          this.turn = null;
          return dir === OPPOSITE[me.facing] ? this.turnJump(dir, now) : this.sideJump(dir, carry, now);
        } else if (now - t.at < TURN_WAIT) return;
        else {
          this.turn = null;
          this.face(dir);
          return;
        }
      }
      if (!dir) {
        if (b && now - input.bDownAt < 200 && !continuing) this.setState('wheelie', now);
        return;
      }
      if (dir !== me.facing && !continuing) {
        this.turn = { dir, at: now };
        return;
      }
      if (b && dir === me.facing && !continuing) {
        // ACRO_TRANS_WHEELIE_RISING_MOVING: pop the wheelie while rolling off.
        this.setState('wheelie', now);
        this.setAnim('bunnyHopBackWheel', now);
        return this.step(dir, 'wheelie', WHEELIE_STEP, null, carry, now);
      }
      return this.step(dir, 'none', ACRO_STEP, null, carry, now);
    }

    if (this.state === 'wheelie') {
      if (!b && !onBumpy) {
        this.setState('normal', now);
        if (dir && dir === me.facing && continuing) return this.step(dir, 'none', ACRO_STEP, null, carry, now);
        return;
      }
      if (!dir) {
        if (continuing) this.holdFrom = now; // moving -> standing: Bike_SetBikeStill
        if (b && now - this.holdFrom >= HOP_AFTER) this.setState('hop', now);
        return;
      }
      if (dir !== me.facing) {
        if (!continuing) this.face(dir);
        return;
      }
      return this.step(dir, 'wheelie', WHEELIE_STEP, null, carry, now);
    }

    // bunny hop
    if (!b) {
      this.setState(onBumpy ? 'wheelie' : 'normal', now);
      return;
    }
    if (!dir) {
      this.hopInPlace('low', now); // AcroBikeTransition_WheelieHoppingStanding
      return;
    }
    if (dir !== me.facing && !continuing) {
      this.face(dir);
      this.hopInPlace('low', now);
      return;
    }
    if (!this.step(dir, 'hop', HOP_MS, 'low', carry, now)) this.hopInPlace('low', now);
  }

  face(dir) {
    const w = this.w;
    const me = w.self;
    if (!canBikeFace(dir, w.behAt(me.x, me.y))) return;
    if (me.facing === dir) return;
    me.facing = dir;
    w.send({ t: 'face', dir });
    w.lastLocalAct = performance.now();
  }

  sideJump(dir, carry, now) {
    // AcroBikeTransition_SideJump: blocked jumps only turn.
    this.tell('side_jump');
    if (!this.step(dir, 'side_jump', HOP_MS, 'normal', carry, now, true)) {
      this.tell('none');
      this.face(dir);
    } else this.sent = 'none'; // the server goes back to normal after the jump
  }

  turnJump(dir, now) {
    // PlayerAcroTurnJump: a jump turning round in place.
    this.tell('turn_jump');
    this.face(dir);
    this.tell('none');
    this.hopInPlace('normal', now);
  }

  /** Predict and send one step in [trick]; false (nothing sent) when it is refused. */
  step(dir, trick, ms, jumpType, carry, now, keepFacing = false) {
    const w = this.w;
    const out = w.predictStep(dir, trick);
    if (out.kind === 'block' || out.kind === 'push') return false;
    const facing = w.self.facing;
    w.commitStep(out, dir, ms, false, carry, now);
    const me = w.self;
    if (out.kind !== 'jump' && jumpType) {
      me.jumping = true;
      me.jumpType = jumpType;
    }
    if (keepFacing) me.facing = facing;
    return true;
  }
}
