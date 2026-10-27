package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.openmmo.common.enums.Direction
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.server.game.battle.BattleResult
import de.fiereu.openmmo.server.game.script.MovementStep
import de.fiereu.openmmo.server.game.script.ScriptContext
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalTime
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

private val log = KotlinLogging.logger {}

/** Thrown by a command the interpreter does not know; the coverage test keeps this list empty. */
class UnknownScriptCommand(name: String, where: String) :
    RuntimeException("script command $name at $where is not implemented")

/**
 * The decomp's script engine (script.c + scrcmd.c) running the game's own bytecode from a
 * [ScriptImage]. One instance runs one script for one player, with that player's [ScriptContext].
 *
 * Commands that wait in the decomp (waitmessage + waitbuttonpress, waitmovement, waitstate after a
 * special that opens UI, delay) suspend; commands that only start something (applymovement, playse)
 * return at once. [immediate] mode is RunScriptImmediately: map ON_LOAD / ON_TRANSITION / ON_RESUME
 * / ON_WARP_INTO scripts, where nothing may wait.
 */
class ScriptVm(
    val ctx: ScriptContext,
    val img: ScriptImage,
    val immediate: Boolean = false,
) {
  val state: VmState = VmState.of(ctx.session)
  val region: Region = if (img.region == "hoenn") Region.HOENN else Region.KANTO
  val isFrlg = region == Region.KANTO

  var pc = 0
  private val stack = ArrayDeque<Int>()
  val data = IntArray(4)
  var cmp = 0
  private lateinit var scope: CoroutineScope
  private val movements = HashMap<Int, Job>()
  private var lastMoved = 0
  private var message: CompletableDeferred<Unit>? = null
  private var messageText = 0
  private var stopped = false
  private var steps = 0

  /** VAR_LAST_TALKED for this run (the decomp's gSpecialVar_LastTalked). */
  var lastTalked: Int
    get() = state.specialVars[VAR_LAST_TALKED - 0x8000]
    set(v) {
      state.specialVars[VAR_LAST_TALKED - 0x8000] = v
    }

  // ------------------------------------------------------------------ constants

  private val varsStart = img.const("VARS_START")
  private val varsEnd = img.const("VARS_END")
  private val specialVarsStart = img.const("SPECIAL_VARS_START")
  private val trainerFlagsStart = img.const("TRAINER_FLAGS_START")
  private val trainerFlagsEnd =
      img.constOrNull("TRAINER_FLAGS_END") ?: (trainerFlagsStart + TRAINER_FLAG_COUNT - 1)
  private val specialFlagsStart = img.constOrNull("SPECIAL_FLAGS_START") ?: 0x4000
  private val stdTable = img.labels["gStdScripts"] ?: 0
  private val stdEnd = img.labels["gStdScripts_End"] ?: img.labels["gStdScriptsEnd"] ?: stdTable

  // ------------------------------------------------------------------ running

  suspend fun runLabel(label: String) {
    val addr = img.labels[label] ?: error("${img.region}: no script $label")
    runAt(addr)
  }

  suspend fun runAt(addr: Int) {
    ctx.vm = this
    try {
      coroutineScope {
        scope = this
        pc = addr
        loop()
        // The decomp's movements outlive the script; ours finish before the script ends so the
        // dialog lock is not released under a walking npc.
        movements.values.forEach { it.join() }
      }
    } finally {
      ctx.vm = null
    }
  }

  private suspend fun loop() {
    while (!stopped && pc != 0) {
      if (++steps > MAX_STEPS) {
        log.warn {
          "${img.region}: script loop at ${img.where(pc)} stopped after $MAX_STEPS commands"
        }
        return
      }
      val at = pc
      val op = u8()
      val name = img.commands.getOrNull(op) ?: throw UnknownScriptCommand("#$op", img.where(at))
      if (!exec(name, at)) return
    }
  }

  fun stop() {
    stopped = true
  }

  // ------------------------------------------------------------------ operand reads

  fun u8(): Int = img.u8(pc).also { pc += 1 }

  fun u16(): Int = img.u16(pc).also { pc += 2 }

  fun u32(): Int = img.u32(pc).also { pc += 4 }

  /** VarGet(ScriptReadHalfword(ctx)). */
  private fun hv(): Int = varGet(u16())

  // ------------------------------------------------------------------ vars and flags

  private fun varKey(id: Int): List<String> {
    val names = img.varNames[id]
    return if (names.isNullOrEmpty()) listOf("${img.region}/VAR_0x%04X".format(id))
    else names.map { "${img.region}/$it" }
  }

  private fun flagKeys(id: Int): List<String> {
    if (id in trainerFlagsStart..trainerFlagsEnd) {
      val t = id - trainerFlagsStart
      return listOf("${img.region}/TRAINER_DEFEATED_$t", "${img.region}/OPENMMO_TRAINER_$t")
    }
    val names = img.flagNames[id]
    return if (names.isNullOrEmpty()) listOf("${img.region}/FLAG_0x%04X".format(id))
    else names.map { "${img.region}/$it" }
  }

  fun isVar(id: Int) =
      (id in varsStart..varsEnd) || (id >= specialVarsStart && id < specialVarsStart + 0x100)

  /** The decomp's VarGet: a value that is not a var id is the value itself. */
  fun varGet(id: Int): Int =
      when {
        id in varsStart..varsEnd -> {
          val keys = varKey(id)
          keys.firstNotNullOfOrNull { k -> ctx.getVar(k).takeIf { it != 0 } } ?: 0
        }
        id >= specialVarsStart && id < specialVarsStart + 0x100 -> specialVar(id)
        else -> id
      }

  private fun specialVar(id: Int): Int =
      when (id) {
        VAR_FACING -> dirCode(ctx.state.facingDirection)
        else -> state.specialVars[id - specialVarsStart]
      }

  fun varSet(id: Int, value: Int) {
    val v = value and 0xFFFF
    when {
      id in varsStart..varsEnd -> varKey(id).forEach { ctx.setVar(it, v) }
      id >= specialVarsStart && id < specialVarsStart + 0x100 ->
          state.specialVars[id - specialVarsStart] = v
      else -> log.debug { "${img.region}: write to non-var ${"0x%X".format(id)}" }
    }
  }

  fun flagGet(id: Int): Boolean =
      when {
        id == 0 -> false
        id >= specialFlagsStart -> id in state.specialFlags
        else -> flagKeys(id).any(ctx::isFlagSet)
      }

  fun flagSet(id: Int) {
    when {
      id == 0 -> {}
      id >= specialFlagsStart -> state.specialFlags += id
      else -> {
        val keys = flagKeys(id)
        // Trainer flags keep the field service's key; named flags every alias.
        if (id in trainerFlagsStart..trainerFlagsEnd) ctx.setFlag(keys[0])
        else keys.forEach(ctx::setFlag)
        ctx.onFlagSet(keys.first())
      }
    }
  }

  fun flagClear(id: Int) {
    when {
      id == 0 -> {}
      id >= specialFlagsStart -> state.specialFlags -= id
      else -> flagKeys(id).forEach(ctx::clearFlag)
    }
  }

  var result: Int
    get() = state.specialVars[VAR_RESULT - 0x8000]
    set(v) {
      state.specialVars[VAR_RESULT - 0x8000] = v and 0xFFFF
    }

  fun sv(i: Int): Int = state.specialVars[i]

  fun setSv(i: Int, v: Int) {
    state.specialVars[i] = v and 0xFFFF
  }

  // ------------------------------------------------------------------ control flow

  private fun call(addr: Int) {
    stack.addLast(pc)
    pc = addr
  }

  private fun ret(): Boolean {
    val back = stack.removeLastOrNull() ?: return false
    pc = back
    return true
  }

  private fun cond(c: Int): Boolean = COND.getOrNull(c)?.getOrNull(cmp) == 1

  private fun compare(a: Int, b: Int): Int = if (a < b) 0 else if (a == b) 1 else 2

  private fun std(index: Int): Int? {
    val a = stdTable + 4 * index
    return if (a < stdEnd) img.u32(a) else null
  }

  // ------------------------------------------------------------------ text

  /** The dialog id of the text at [ptr] (NULL = data[0]), or 0 when it has none. */
  fun textAt(ptr: Int): ScriptText? {
    val p = if (ptr == 0) data[0] else ptr
    img.texts[p]?.let {
      return it
    }
    val ext = img.externs[p] ?: return null
    return dynamicText(ext)
  }

  /** gStringVar4 and the C strings (gText_*): shown through the dynamic text line. */
  private fun dynamicText(symbol: String): ScriptText? {
    val content =
        if (symbol == "gStringVar4") state.stringVar4 else img.cStrings[symbol] ?: return null
    return ScriptText(0, 0, symbol, false, content)
  }

  suspend fun showMessage(t: ScriptText?) {
    closeMessage(sendClose = false)
    if (t == null) return
    if (immediate) return
    messageText = t.textId
    message = ctx.vmShowText(t, sign = ctx.entityId < 0 || state.signBox)
  }

  suspend fun waitButton() {
    val m = message ?: return
    if (!immediate) m.await()
    message = null
  }

  fun closeMessage(sendClose: Boolean = true) {
    if (message != null || sendClose) ctx.vmCloseText(sendClose && message != null)
    message = null
  }

  /** Shows [t] and waits for the player, the std msgbox. */
  suspend fun say(t: ScriptText?) {
    showMessage(t)
    waitButton()
  }

  // ------------------------------------------------------------------ objects

  private fun localIdOf(raw: Int): Int = varGet(raw)

  fun isPlayer(localId: Int) = localId == OBJ_PLAYER

  fun poseOf(localId: Int): ObjPose? {
    if (isPlayer(localId))
        return ObjPose(ctx.state.x.toInt(), ctx.state.y.toInt(), ctx.state.facingDirection)
    state.objects[localId]?.let {
      return it
    }
    val npc = ctx.vmMapNpc(localId) ?: return null
    val tp = state.templatePos[npcIdx(localId)]
    return ObjPose(tp?.first ?: npc.x, tp?.second ?: npc.y, npc.facing)
  }

  private fun faceToward(from: ObjPose, to: ObjPose): Direction {
    val dx = to.x - from.x
    val dy = to.y - from.y
    return if (kotlin.math.abs(dx) > kotlin.math.abs(dy))
        (if (dx > 0) Direction.RIGHT else Direction.LEFT)
    else if (dy < 0) Direction.UP else Direction.DOWN
  }

  /** applymovement: starts [localId] walking [ops] and returns at once. */
  fun applyMovement(localId: Int, ops: List<MoveOp>) {
    if (localId == OBJ_CAMERA || immediate) return
    lastMoved = localId
    val previous = movements[localId]
    movements[localId] =
        scope.launch {
          previous?.join()
          playMovement(localId, ops)
        }
  }

  private suspend fun playMovement(localId: Int, ops: List<MoveOp>) {
    var pose = poseOf(localId) ?: return
    val batch = ArrayList<MovementStep>()
    suspend fun flush() {
      if (batch.isEmpty()) return
      val steps = batch.toList()
      batch.clear()
      if (isPlayer(localId)) ctx.moveSelf(*steps.toTypedArray())
      else ctx.vmMoveNpc(localId, pose.let { ObjPose(it.x, it.y, it.facing) }, steps)
    }
    for (op in ops) {
      when (op) {
        is MoveOp.Step -> {
          batch += op.step
          pose = advance(pose, op.step)
        }
        is MoveOp.FaceRelative -> {
          val player = poseOf(OBJ_PLAYER) ?: continue
          val npc = ctx.vmMapNpc(localId)
          val d =
              when (op.kind) {
                "player" -> faceToward(pose, player)
                "away" -> faceToward(pose, player).opposite()
                else -> npc?.facing ?: pose.facing
              }
          val s = VmMovement.faceStep(d)
          batch += s
          pose = pose.copy(facing = d)
        }
        is MoveOp.Emote -> {
          flush()
          ctx.vmEmote(localId, op.kind)
        }
        MoveOp.Show -> {
          flush()
          ctx.vmShowObject(localId, pose)
        }
      }
    }
    flush()
    if (!isPlayer(localId)) state.objects[localId] = pose
  }

  private fun advance(p: ObjPose, s: MovementStep): ObjPose =
      if (s.walks) ObjPose(p.x + s.direction.dx, p.y + s.direction.dy, s.direction)
      else if (s.changesFacing) p.copy(facing = s.direction) else p

  /** The movement script at [ptr]: MOVEMENT_ACTION_* bytes up to step_end. */
  fun movementAt(ptr: Int): List<MoveOp> {
    val out = ArrayList<MoveOp>()
    var a = ptr
    val end = img.constOrNull("MOVEMENT_ACTION_STEP_END") ?: 0xFE
    while (img.contains(a)) {
      val b = img.u8(a++)
      if (b == end) break
      val name = img.nameOf("MOVEMENT_ACTION_", b) ?: continue
      out += VmMovement.ops(name)
    }
    return out
  }

  suspend fun waitMovement(localId: Int) {
    val id = if (localId == 0) lastMoved else localId
    movements[id]?.join()
  }

  // ------------------------------------------------------------------ the commands

  /** Runs one command. False ends the script. */
  private suspend fun exec(name: String, at: Int): Boolean {
    when (name) {
      "nop",
      "nop1" -> {}
      "end",
      "endram" -> return false
      "return",
      "returnram" -> if (!ret()) return false
      "call" -> call(u32())
      "goto" -> pc = u32()
      "goto_if" -> {
        val c = u8()
        val dest = u32()
        if (cond(c)) pc = dest
      }
      "call_if" -> {
        val c = u8()
        val dest = u32()
        if (cond(c)) call(dest)
      }
      "gotostd" -> std(u8())?.let { pc = it }
      "callstd" -> std(u8())?.let(::call)
      "gotostd_if" -> {
        val c = u8()
        val idx = u8()
        if (cond(c)) std(idx)?.let { pc = it }
      }
      "callstd_if" -> {
        val c = u8()
        val idx = u8()
        if (cond(c)) std(idx)?.let(::call)
      }
      // RAM (Mystery Event) scripts address relative to where they were loaded.
      "setvaddress" -> state.vaddrOffset = pc - 1 - u32()
      "vgoto" -> pc = u32() + state.vaddrOffset
      "vcall" -> call(u32() + state.vaddrOffset)
      "vgoto_if" -> {
        val c = u8()
        val d = u32() + state.vaddrOffset
        if (cond(c)) pc = d
      }
      "vcall_if" -> {
        val c = u8()
        val d = u32() + state.vaddrOffset
        if (cond(c)) call(d)
      }
      "vmessage" -> showMessage(textAt(u32() + state.vaddrOffset))
      "vbuffermessage" -> state.stringVar4 = textAt(u32() + state.vaddrOffset)?.content.orEmpty()
      "vbufferstring" -> {
        val sv = u8()
        bufferRaw(sv, textAt(u32() + state.vaddrOffset)?.content.orEmpty())
      }
      "setmysteryeventstatus" -> u8()
      "loadword" -> {
        val i = u8()
        data[i and 3] = u32()
      }
      "loadbyte" -> {
        val i = u8()
        data[i and 3] = u8()
      }
      "setptr" -> {
        val v = u8()
        ram[u32()] = v
      }
      "loadbytefromptr" -> {
        val i = u8()
        data[i and 3] = readPtr(u32())
      }
      "setptrbyte" -> {
        val i = u8()
        ram[u32()] = data[i and 3] and 0xFF
      }
      "copylocal" -> {
        val d = u8()
        data[d and 3] = data[u8() and 3]
      }
      "copybyte" -> {
        val d = u32()
        ram[d] = readPtr(u32())
      }
      "setvar" -> {
        val v = u16()
        varSet(v, u16())
      }
      "addvar" -> {
        val v = u16()
        varSet(v, varGet(v) + u16())
      }
      "subvar" -> {
        val v = u16()
        varSet(v, varGet(v) - hv())
      }
      "copyvar" -> {
        val d = u16()
        varSet(d, varGet(u16()))
      }
      "setorcopyvar" -> {
        val d = u16()
        varSet(d, hv())
      }
      "compare_local_to_local" -> {
        val a = data[u8() and 3]
        cmp = compare(a, data[u8() and 3])
      }
      "compare_local_to_value" -> {
        val a = data[u8() and 3]
        cmp = compare(a, u8())
      }
      "compare_local_to_ptr" -> {
        val a = data[u8() and 3]
        cmp = compare(a, readPtr(u32()))
      }
      "compare_ptr_to_local" -> {
        val a = readPtr(u32())
        cmp = compare(a, data[u8() and 3])
      }
      "compare_ptr_to_value" -> {
        val a = readPtr(u32())
        cmp = compare(a, u8())
      }
      "compare_ptr_to_ptr" -> {
        val a = readPtr(u32())
        cmp = compare(a, readPtr(u32()))
      }
      "compare_var_to_value" -> {
        val a = varGet(u16())
        cmp = compare(a, u16())
      }
      "compare_var_to_var" -> {
        val a = varGet(u16())
        cmp = compare(a, varGet(u16()))
      }
      "callnative" -> native(img.externs[pc].also { u32() }, at)
      "gotonative" -> {
        native(img.externs[pc].also { u32() }, at)
      }
      "special" -> {
        val idx = u16()
        val r = special(img.specials.getOrNull(idx) ?: "", at)
        if (r != 0) result = r
      }
      "specialvar" -> {
        val dest = u16()
        val idx = u16()
        varSet(dest, special(img.specials.getOrNull(idx) ?: "", at))
      }
      "waitstate" -> {}
      "delay" -> {
        val frames = u16()
        if (!immediate) delay(frames * 1000L / 60)
      }
      "setflag" -> flagSet(u16())
      "clearflag" -> flagClear(u16())
      "checkflag" -> cmp = if (flagGet(u16())) 1 else 0
      "initclock" -> {
        hv()
        hv()
      }
      "dotimebasedevents" -> {}
      "gettime" -> {
        val t = LocalTime.now()
        setSv(0, t.hour)
        setSv(1, t.minute)
        setSv(2, t.second)
      }
      "playse" -> sound("se", u16())
      "waitse" -> pause(SE_MS)
      "playfanfare" -> sound("fanfare", u16())
      "waitfanfare" -> pause(FANFARE_MS)
      "playbgm" -> {
        val song = u16()
        u8()
        sound("bgm", song)
      }
      "savebgm" -> state.savedBgm = u16()
      "fadedefaultbgm" ->
          ui("sound") {
            put("kind", "bgm")
            put("default", true)
          }
      "fadenewbgm" -> sound("bgm", u16())
      "fadeoutbgm" -> {
        u8()
        ui("sound") { put("kind", "fadeout") }
      }
      "fadeinbgm" -> {
        u8()
        ui("sound") { put("kind", "fadein") }
      }
      "warp",
      "warpsilent",
      "warpdoor",
      "warpteleport",
      "warpspinenter",
      "warpmossdeepgym",
      "warpwhitefade" -> {
        val g = u8()
        val n = u8()
        val w = u8()
        val x = hv()
        val y = hv()
        closeMessage()
        warp(g, n, w, x, y, name)
        return false
      }
      "warphole" -> {
        val g = u8()
        val n = u8()
        val dest = ctx.vmHoleDestination(g, n)
        closeMessage()
        if (dest != null) warp(dest[0], dest[1], 0xFF, dest[2], dest[3], name)
        return false
      }
      "setwarp",
      "setdynamicwarp",
      "setdivewarp",
      "setholewarp",
      "setescapewarp" -> {
        val g = u8()
        val n = u8()
        val w = u8()
        val x = hv()
        val y = hv()
        state.warps[name] = intArrayOf(g, n, w, x, y)
        if (name == "setdynamicwarp") setDynamicWarp(g, n, w, x, y)
      }
      "getplayerxy" -> {
        val vx = u16()
        val vy = u16()
        varSet(vx, ctx.state.x.toInt())
        varSet(vy, ctx.state.y.toInt())
      }
      "getpartysize" -> result = ctx.vmParty().size
      "additem" -> {
        val item = hv()
        val q = hv()
        result = if (ctx.vmAddItem(item, q)) 1 else 0
      }
      "removeitem" -> {
        val item = hv()
        val q = hv()
        result = if (ctx.vmAddItem(item, -q)) 1 else 0
      }
      "checkitemspace" -> {
        hv()
        hv()
        result = 1
      }
      "checkitem" -> {
        val item = hv()
        val q = hv()
        result = if (ctx.vmItemCount(item) >= q) 1 else 0
      }
      "checkitemtype" -> result = ctx.vmItemPocket(hv())
      "addpcitem" -> {
        val item = hv()
        val q = hv()
        result = if (ctx.vmAddItem(item, q)) 1 else 0
      }
      "checkpcitem" -> {
        val item = hv()
        val q = hv()
        result = if (ctx.vmItemCount(item) >= q) 1 else 0
      }
      "adddecoration" -> result = if (ctx.vmAddDecoration(hv(), 1)) 1 else 0
      "removedecoration" -> result = if (ctx.vmAddDecoration(hv(), -1)) 1 else 0
      "checkdecor" -> result = if (ctx.vmDecorationCount(hv()) > 0) 1 else 0
      "checkdecorspace" -> {
        hv()
        result = 1
      }
      "applymovement" -> {
        val id = localIdOf(u16())
        applyMovement(id, movementAt(u32()))
      }
      "applymovementat" -> {
        val id = localIdOf(u16())
        val ptr = u32()
        val g = u8()
        val n = u8()
        if (ctx.vmOnMap(g, n)) applyMovement(id, movementAt(ptr))
      }
      "waitmovement" -> waitMovement(localIdOf(u16()))
      "waitmovementat" -> {
        val id = localIdOf(u16())
        u8()
        u8()
        waitMovement(id)
      }
      "removeobject" -> removeObject(localIdOf(u16()))
      "removeobjectat" -> {
        val id = localIdOf(u16())
        val g = u8()
        val n = u8()
        if (ctx.vmOnMap(g, n)) removeObject(id) else ctx.vmSetObjectFlag(g, n, id)
      }
      "addobject" -> addObject(localIdOf(u16()))
      "addobjectat" -> {
        val id = localIdOf(u16())
        val g = u8()
        val n = u8()
        if (ctx.vmOnMap(g, n)) addObject(id)
      }
      "setobjectxy" -> {
        val id = localIdOf(u16())
        val x = hv()
        val y = hv()
        setObjectXY(id, x, y)
      }
      "showobjectat" -> {
        val id = localIdOf(u16())
        u8()
        u8()
        poseOf(id)?.let { ctx.vmShowObject(id, it) }
      }
      "hideobjectat" -> {
        val id = localIdOf(u16())
        u8()
        u8()
        if (!immediate) ctx.vmHideObject(id)
      }
      "faceplayer" -> facePlayer()
      "turnobject" -> {
        val id = localIdOf(u16())
        val d = dirOf(u8())
        turnObject(id, d)
      }
      "setobjectxyperm" -> {
        val id = localIdOf(u16())
        val x = hv()
        val y = hv()
        state.templatePos[npcIdx(id)] = x to y
      }
      "copyobjectxytoperm" -> {
        val id = localIdOf(u16())
        poseOf(id)?.let { state.templatePos[npcIdx(id)] = it.x to it.y }
      }
      "setobjectmovementtype" -> {
        val id = localIdOf(u16())
        state.templateMovement[npcIdx(id)] = u8()
      }
      "setobjectsubpriority" -> {
        u16()
        u8()
        u8()
        u8()
      }
      "resetobjectsubpriority" -> {
        u16()
        u8()
        u8()
      }
      "createvobject" -> {
        val gfx = u8()
        val vid = u8()
        val x = hv()
        val y = hv()
        val elev = u8()
        val dir = u8()
        ui("vobject") {
          put("gfx", gfx)
          put("id", vid)
          put("x", x)
          put("y", y)
          put("elevation", elev)
          put("facing", dir)
        }
      }
      "turnvobject" -> {
        val vid = u8()
        val dir = u8()
        ui("vobject") {
          put("id", vid)
          put("facing", dir)
        }
      }
      "lockall",
      "lock",
      "releaseall",
      "release" -> {
        if (name.startsWith("release")) closeMessage()
      }
      "selectapproachingtrainer" -> {}
      "lockfortrainer" -> {}
      "message",
      "messageautoscroll",
      "messageinstant",
      "pokenavcall" -> showMessage(textAt(u32()))
      "braillemessage" -> showMessage(textAt(u32()))
      "getbraillestringwidth" -> {
        val t = textAt(u32())
        setSv(4, (t?.content?.length ?: 0) * 6)
      }
      "closebraillemessage" -> closeMessage()
      "waitmessage" -> {}
      "closemessage" -> closeMessage()
      "waitbuttonpress" -> waitButton()
      "yesnobox" -> {
        u8()
        u8()
        result = if (yesNo()) 1 else 0
      }
      "multichoice" -> {
        u8()
        u8()
        val id = u8()
        val ignoreB = u8() != 0
        result = multichoice(id, 0, ignoreB, 1)
      }
      "multichoicedefault" -> {
        u8()
        u8()
        val id = u8()
        val def = u8()
        val ignoreB = u8() != 0
        result = multichoice(id, def, ignoreB, 1)
      }
      "multichoicegrid" -> {
        u8()
        u8()
        val id = u8()
        val ignoreB = u8() != 0
        val cols = u8()
        result = multichoice(id, 0, ignoreB, cols)
      }
      "drawbox",
      "drawboxtext",
      "erasebox" -> {
        u8()
        u8()
        u8()
        u8()
      }
      "showmonpic" -> {
        val sp = hv()
        u8()
        u8()
        ui("monpic") {
          put("dex", dexOf(sp))
          put("show", true)
        }
      }
      "hidemonpic" -> ui("monpic") { put("show", false) }
      "showcontestpainting" -> {
        setSv(4, u8())
        delegate("ShowContestPainting", at)
      }
      "bufferspeciesname" -> {
        val sv = u8()
        val sp = hv()
        buffer(sv) { ctx.bufferSpeciesName(it, dexOf(sp)) }
      }
      "bufferleadmonspeciesname" -> {
        val sv = u8()
        val lead = ctx.vmParty().firstOrNull { !it.isEgg }
        if (lead != null) buffer(sv) { ctx.bufferSpeciesName(it, lead.dexId) }
      }
      "bufferpartymonnick" -> {
        val sv = u8()
        val slot = hv()
        bufferRaw(sv, ctx.vmMonName(slot))
      }
      "bufferitemname" -> {
        val sv = u8()
        bufferRaw(sv, ctx.vmItemName(hv(), 1))
      }
      "bufferitemnameplural" -> {
        val sv = u8()
        val item = hv()
        bufferRaw(sv, ctx.vmItemName(item, hv()))
      }
      "bufferdecorationname" -> {
        val sv = u8()
        bufferRaw(sv, ctx.vmDecorationName(hv()))
      }
      "buffermovename" -> {
        val sv = u8()
        bufferRaw(sv, ctx.vmMoveName(hv()))
      }
      "buffernumberstring" -> {
        val sv = u8()
        bufferRaw(sv, hv().toString())
      }
      "bufferstdstring" -> {
        val sv = u8()
        bufferRaw(sv, img.stdStrings.getOrElse(hv()) { "" })
      }
      "buffercontestname" -> {
        val sv = u8()
        bufferRaw(sv, CONTEST_NAMES.getOrElse(hv()) { "" })
      }
      "bufferstring" -> {
        val sv = u8()
        bufferRaw(sv, textAt(u32())?.content.orEmpty())
      }
      "bufferboxname" -> {
        val sv = u8()
        bufferRaw(sv, "BOX ${hv() + 1}")
      }
      "buffertrainerclassname" -> {
        val sv = u8()
        bufferRaw(sv, ctx.vmTrainerClassName(hv()))
      }
      "buffertrainername" -> {
        val sv = u8()
        bufferRaw(sv, ctx.vmTrainerName(hv()))
      }
      "givemon" -> {
        val sp = hv()
        val level = u8()
        val item = hv()
        u32()
        u32()
        u8()
        result = ctx.vmGiveMon(dexOf(sp), level, item, egg = false)
      }
      "giveegg" -> result = ctx.vmGiveMon(dexOf(hv()), 5, 0, egg = true)
      "setmonmove" -> {
        val slot = u8()
        val moveSlot = u8()
        ctx.vmSetMonMove(slot, moveSlot, u16())
      }
      "checkpartymove" -> {
        val move = u16()
        val party = ctx.vmParty()
        val i = party.indexOfFirst { m -> !m.isEgg && m.moves.any { it.id.toInt() == move } }
        if (i >= 0) {
          result = i
          setSv(4, internalOf(party[i].dexId))
        } else result = PARTY_SIZE
      }
      "addmoney" -> {
        val amount = u32()
        u8()
        ctx.vmAddMoney(amount)
      }
      "removemoney" -> {
        val amount = u32()
        u8()
        ctx.vmAddMoney(-amount)
      }
      "checkmoney" -> {
        val amount = u32()
        u8()
        result = if (ctx.vmMoney() >= amount) 1 else 0
      }
      "showmoneybox" -> {
        u8()
        u8()
        u8()
        ui("moneybox") { put("money", ctx.vmMoney()) }
      }
      "hidemoneybox" -> {
        u8()
        u8()
        ui("moneybox") { put("hide", true) }
      }
      "updatemoneybox" -> {
        u8()
        u8()
        u8()
        ui("moneybox") { put("money", ctx.vmMoney()) }
      }
      "addcoins" -> result = if (ctx.vmAddCoins(hv())) 0 else 1
      "removecoins" -> result = if (ctx.vmAddCoins(-hv())) 0 else 1
      "checkcoins" -> varSet(u16(), ctx.coins())
      "showcoinsbox" -> {
        u8()
        u8()
        val coins = ctx.coins()
        ui("coinsbox") { put("coins", coins) }
      }
      "hidecoinsbox" -> {
        u8()
        u8()
        ui("coinsbox") { put("hide", true) }
      }
      "updatecoinsbox" -> {
        u8()
        u8()
        val coins = ctx.coins()
        ui("coinsbox") { put("coins", coins) }
      }
      "trainerbattle" -> {
        val next = configureTrainerBattle(pc, approaching = false) ?: return true
        pc = next
      }
      "dotrainerbattle" -> if (!doTrainerBattle()) return false
      "gotopostbattlescript" -> pc = state.trainer?.endScript?.takeIf { it != 0 } ?: return false
      "gotobeatenscript" -> {
        val t = state.trainer ?: return false
        val r = if (t.scriptRetA != 0) t.scriptRetA else t.scriptRetB
        if (r == 0) {
          pc = img.labels["EventScript_TryGetTrainerScript"] ?: return false
        } else pc = r
      }
      "checktrainerflag" -> cmp = if (flagGet(trainerFlagsStart + hv())) 1 else 0
      "settrainerflag" -> flagSet(trainerFlagsStart + hv())
      "cleartrainerflag" -> flagClear(trainerFlagsStart + hv())
      "setwildbattle" -> {
        val sp = u16()
        val level = u8()
        val item = u16()
        state.wild = Triple(dexOf(sp), level, item)
      }
      "dowildbattle" -> {
        val w = state.wild ?: return true
        closeMessage()
        val r = ctx.vmWildBattle(w.first, w.second, w.third, legendary = false)
        state.battleOutcome = outcome(r)
        if (r == BattleResult.DEFEAT) return false
      }
      "pokemart" -> {
        ctx.vmMart(readItemList(u32()))
        return false
      }
      "pokemartdecoration",
      "pokemartdecoration2" -> {
        val ptr = u32()
        ctx.decorationShop(img.labelsAt(ptr).firstOrNull() ?: "")
        return false
      }
      "playslotmachine" -> {
        setSv(4, hv())
        delegate("PlaySlotMachine", at)
      }
      "setberrytree" -> {
        val tree = u8()
        val berry = u8()
        val stage = u8()
        ctx.setVar("${img.region}/OPENMMO_BERRY_TREE_$tree", (berry shl 8) or stage)
      }
      "choosecontestmon" -> delegate("ChooseContestMon", at)
      "startcontest" -> delegate("StartContest", at)
      "showcontestresults" -> delegate("ShowContestResults", at)
      "contestlinktransfer" -> delegate("ContestLinkTransfer", at)
      "random" -> result = Random.nextInt(maxOf(1, hv()))
      "playmoncry" -> {
        val sp = hv()
        hv()
        ui("sound") {
          put("kind", "cry")
          put("dex", dexOf(sp))
        }
      }
      "waitmoncry" -> pause(CRY_MS)
      "setmetatile" -> {
        val x = hv()
        val y = hv()
        val tile = hv()
        val imp = hv() != 0
        ctx.vmSetMetatile(x, y, tile, imp)
      }
      "opendoor",
      "closedoor" -> {
        val x = hv()
        val y = hv()
        ui("fx") {
          put("kind", if (name == "opendoor") "door_open" else "door_close")
          put("x", x)
          put("y", y)
        }
      }
      "waitdooranim" -> pause(DOOR_MS)
      "setdooropen",
      "setdoorclosed" -> {
        hv()
        hv()
      }
      "setweather" -> state.weather = hv()
      "resetweather" -> state.weather = -1
      "doweather" ->
          ui("fx") {
            put("kind", "weather")
            put("weather", img.nameOf("WEATHER_", state.weather) ?: "WEATHER_NONE")
          }
      "setstepcallback" -> u8()
      "setmaplayoutindex" -> ctx.vmSetLayout(hv())
      "setflashlevel" -> ctx.vmFlash(hv())
      "animateflash" -> ctx.vmFlash(u8())
      "dofieldeffect" -> {
        val fe = hv()
        ui("fx") {
          put("kind", "fieldeffect")
          put("effect", fe)
          put("args", JsonArray(state.fieldEffectArgs.map { JsonPrimitive(it) }))
        }
        if (!immediate) delay(FX_MS)
      }
      "setfieldeffectargument" -> {
        val i = u8()
        state.fieldEffectArgs[i and 7] = hv()
      }
      "waitfieldeffect" -> {
        hv()
        pause(FX_MS)
      }
      "setrespawn" -> ctx.vmSetRespawn(hv())
      "checkplayergender" -> result = if (ctx.isFemale) 1 else 0
      "fadescreen" -> fade(u8())
      "fadescreenspeed" -> {
        val mode = u8()
        u8()
        fade(mode)
      }
      "fadescreenswapbuffers" -> fade(u8())
      "incrementgamestat" -> {
        val stat = u8()
        val key = "${img.region}/OPENMMO_GAME_STAT_$stat"
        ctx.setVar(key, ctx.getVar(key) + 1)
      }
      "comparestat" -> {
        val stat = u8()
        val v = u32()
        cmp = compare(ctx.getVar("${img.region}/OPENMMO_GAME_STAT_$stat"), v)
      }
      "setworldmapflag" -> flagSet(u16())
      "textcolor" ->
          state.specialVars[(img.constOrNull("VAR_TEXT_COLOR") ?: 0x8013) - 0x8000] = u8()
      "signmsg" -> state.signBox = true
      "normalmsg" -> state.signBox = false
      "loadhelp" -> u32()
      "unloadhelp" -> {}
      "setmonmetlocation" -> {
        hv()
        u8()
      }
      "setmodernfatefulencounter",
      "setmonmodernfatefulencounter" -> ctx.vmSetFateful(hv())
      "checkmodernfatefulencounter",
      "checkmonmodernfatefulencounter" -> result = if (ctx.vmIsFateful(hv())) 1 else 0
      "getpokenewsactive" -> {
        hv()
        result = 0
      }
      "trywondercardscript" -> delegate("TryWonderCardScript", at)
      "addelevmenuitem" -> {
        val a = u8()
        val b = hv()
        val c = hv()
        val d = hv()
        state.elevatorItems += intArrayOf(a, b, c, d)
      }
      "showelevmenu" -> {
        result = elevatorMenu()
      }
      "initrotatingtilepuzzle" -> hv()
      "moverotatingtileobjects",
      "turnrotatingtileobjects",
      "freerotatingtilepuzzle" -> if (name == "moverotatingtileobjects") hv()
      else -> throw UnknownScriptCommand(name, img.where(at))
    }
    return true
  }

  // ------------------------------------------------------------------ command helpers

  private val ram = HashMap<Int, Int>()

  private fun readPtr(addr: Int): Int = ram[addr] ?: if (img.contains(addr)) img.u8(addr) else 0

  private fun dirOf(d: Int): Direction =
      when (d) {
        1 -> Direction.DOWN
        2 -> Direction.UP
        3 -> Direction.LEFT
        4 -> Direction.RIGHT
        else -> Direction.DOWN
      }

  fun dirCode(d: Direction): Int =
      when (d) {
        Direction.DOWN -> 1
        Direction.UP -> 2
        Direction.LEFT -> 3
        Direction.RIGHT -> 4
        else -> 0
      }

  /** Internal species id -> national dex number (they differ from Treecko on). */
  fun dexOf(species: Int): Int = img.speciesToDex[species] ?: species

  fun internalOf(dex: Int): Int =
      img.speciesToDex.entries.firstOrNull { it.value == dex }?.key ?: dex

  private inline fun buffer(sv: Int, f: (Int) -> Unit) {
    if (sv in 0..2) f(sv + 1)
  }

  fun bufferRaw(sv: Int, text: String) {
    if (sv in 0..2) ctx.bufferString(sv + 1, text.substringBefore('$'))
  }

  private fun ui(kind: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
    if (!immediate) ctx.services?.ui?.send(ctx.session, kind, body)
  }

  private fun sound(kind: String, id: Int) {
    val prefix = if (kind == "se") "SE_" else "MUS_"
    ui("sound") {
      put("kind", kind)
      put("id", id)
      put("name", img.nameOf(prefix, id) ?: "")
    }
  }

  private suspend fun pause(ms: Long) {
    if (!immediate) delay(ms)
  }

  private suspend fun fade(mode: Int) {
    ui("fade") { put("mode", mode) }
    pause(FADE_MS)
  }

  private suspend fun yesNo(): Boolean {
    if (immediate) return false
    val text = messageText
    message = null
    return ctx.vmYesNo(text)
  }

  private suspend fun multichoice(id: Int, default: Int, ignoreB: Boolean, cols: Int): Int {
    if (immediate) return MULTI_B_PRESSED
    val items = img.menus[id] ?: listOf("YES", "NO")
    message = null
    return ctx.vmMenu("multichoice", items, default, if (ignoreB) -1 else MULTI_B_PRESSED, cols)
  }

  private suspend fun elevatorMenu(): Int {
    val items = state.elevatorItems.toList()
    state.elevatorItems.clear()
    if (immediate || items.isEmpty()) return 0
    val labels = items.map { "${it[1]}F" }
    return ctx.vmMenu("elevator", labels, 0, MULTI_B_PRESSED, 1)
  }

  private fun readItemList(ptr: Int): List<Int> {
    val out = ArrayList<Int>()
    var a = ptr
    while (img.contains(a + 1)) {
      val item = img.u16(a)
      a += 2
      if (item == 0) break
      out += item
    }
    return out
  }

  private fun removeObject(localId: Int) {
    ctx.vmRemoveObject(localId, immediate)
    state.objects.remove(localId)
  }

  private fun addObject(localId: Int) {
    if (immediate) return
    val pose = poseOf(localId) ?: return
    ctx.vmShowObject(localId, pose)
  }

  /** setobjectxy from a special (the Deoxys triangle). */
  fun moveObject(localId: Int, x: Int, y: Int) = setObjectXY(localId, x, y)

  private fun setObjectXY(localId: Int, x: Int, y: Int) {
    if (isPlayer(localId)) {
      if (!immediate) ctx.repositionSelf(x, y, ctx.state.facingDirection)
      return
    }
    val prev = poseOf(localId)
    state.objects[localId] = ObjPose(x, y, prev?.facing ?: Direction.DOWN)
    if (!immediate) ctx.repositionNpc(npcIdx(localId), x, y)
  }

  private suspend fun facePlayer() {
    val id = lastTalked
    if (id == 0 || isPlayer(id)) return
    val npc = poseOf(id) ?: return
    val player = poseOf(OBJ_PLAYER) ?: return
    turnObject(id, faceToward(npc, player))
  }

  private suspend fun turnObject(localId: Int, d: Direction) {
    if (isPlayer(localId)) {
      if (!immediate) ctx.vmTurnSelf(d)
      return
    }
    poseOf(localId)?.let { state.objects[localId] = it.copy(facing = d) }
    if (!immediate) ctx.vmTurnNpc(localId, d)
  }

  private suspend fun warp(g: Int, n: Int, w: Int, x: Int, y: Int, kind: String) {
    if (immediate) return
    ctx.vmWarp(g, n, w, x, y, kind)
  }

  private fun setDynamicWarp(g: Int, n: Int, w: Int, x: Int, y: Int) {
    val dest = ctx.vmWarpDestination(g, n, w, x, y) ?: return
    ctx.setDynamicWarp(
        region.wireValue.toInt(),
        ctx.vmBank(g),
        n,
        dest.first,
        dest.second,
        ctx.state.facingDirection)
  }

  fun outcome(r: BattleResult): Int =
      when (r) {
        BattleResult.VICTORY -> B_OUTCOME_WON
        BattleResult.DEFEAT -> B_OUTCOME_LOST
        BattleResult.FLED -> B_OUTCOME_RAN
        BattleResult.CAUGHT -> B_OUTCOME_CAUGHT
        else -> B_OUTCOME_DREW
      }

  // ------------------------------------------------------------------ specials and natives

  /** The special running now (for handlers that serve several names). */
  var currentSpecial: String = ""

  /** A decomp special by name; one nobody claimed is logged and returns 0. */
  suspend fun special(name: String, at: Int): Int {
    currentSpecial = name
    val handler = ctx.services?.specials?.forName(name)
    if (handler == null) {
      log.warn { "${img.region}: special $name at ${img.where(at)} has no handler" }
      return 0
    }
    return handler.run(ctx, name)
  }

  private suspend fun native(name: String?, at: Int) {
    if (name == null) return
    special(name, at)
  }

  /** A command whose system another lane owns, routed through the specials by this name. */
  private suspend fun delegate(name: String, at: Int) {
    val r = special(name, at)
    if (r != 0) result = r
  }

  // ------------------------------------------------------------------ trainer battles

  /**
   * BattleSetup_ConfigureTrainerBattle: reads the `trainerbattle` operands at [data] (which point
   * at its mode byte) into the player's trainer battle and returns the std script to continue with,
   * or null to continue after the command (TRAINER_BATTLE_SET_TRAINER_A/B).
   */
  fun configureTrainerBattle(data: Int, approaching: Boolean): Int? {
    var p = data
    fun b() = img.u8(p).also { p += 1 }
    fun h() = img.u16(p).also { p += 2 }
    fun w() = img.u32(p).also { p += 4 }
    val mode = b()
    val trainer = h()
    val local = h()
    val emerald = !isFrlg
    var introA = 0
    var defeatA = 0
    var cannot = 0
    var retA = 0
    when (mode) {
      MODE_SINGLE_NO_INTRO -> defeatA = w()
      MODE_DOUBLE,
      MODE_REMATCH_DOUBLE -> {
        introA = w()
        defeatA = w()
        cannot = w()
      }
      MODE_CONTINUE_SCRIPT,
      MODE_CONTINUE_SCRIPT_NO_MUSIC -> {
        introA = w()
        defeatA = w()
        retA = w()
      }
      MODE_CONTINUE_SCRIPT_DOUBLE,
      MODE_CONTINUE_SCRIPT_DOUBLE_NO_MUSIC -> {
        introA = w()
        defeatA = w()
        cannot = w()
        retA = w()
      }
      MODE_EARLY_RIVAL_OR_PYRAMID ->
          if (emerald) {
            introA = w()
            defeatA = w()
          } else {
            // FRLG early rival: no intro, defeat + victory speech.
            defeatA = w()
            w()
          }
      else -> {
        introA = w()
        defeatA = w()
      }
    }
    val end = p
    val prev = state.trainer
    val second = approaching && prev != null && prev.approaching && prev.opponentA != 0
    val setup =
        if (second && prev != null)
            prev.copy(opponentB = trainer, introB = introA, defeatB = defeatA, scriptRetB = retA)
        else
            TrainerBattleSetup(
                mode,
                trainer,
                if (local != 0) local else lastTalked,
                introA,
                defeatA,
                cannot,
                retA,
                end,
                approaching = approaching)
    state.trainer = setup
    val label =
        when (mode) {
          MODE_SINGLE_NO_INTRO -> "EventScript_DoNoIntroTrainerBattle"
          MODE_DOUBLE,
          MODE_CONTINUE_SCRIPT_DOUBLE,
          MODE_CONTINUE_SCRIPT_DOUBLE_NO_MUSIC -> "EventScript_TryDoDoubleTrainerBattle"
          MODE_REMATCH_DOUBLE -> "EventScript_TryDoDoubleRematchBattle"
          MODE_REMATCH -> "EventScript_TryDoRematchBattle"
          MODE_EARLY_RIVAL_OR_PYRAMID ->
              if (emerald) "EventScript_TryDoNormalTrainerBattle"
              else "EventScript_DoNoIntroTrainerBattle"
          10,
          11 -> if (emerald) return null else "EventScript_TryDoNormalTrainerBattle"
          else -> "EventScript_TryDoNormalTrainerBattle"
        }
    return img.labels[label]
  }

  /** dotrainerbattle: fights the configured trainer(s); false when the player lost. */
  suspend fun doTrainerBattle(): Boolean {
    val t = state.trainer ?: return true
    closeMessage()
    if (immediate) return true
    val ids = listOf(t.opponentA, t.opponentB).filter { it != 0 }
    for ((i, id) in ids.withIndex()) {
      val r = ctx.trainerBattle(id)
      state.battleOutcome = outcome(r)
      if (r != BattleResult.VICTORY) {
        // FRLG early rival battles go on after a loss (RIVAL_BATTLE_CONTINUE_ON_LOSE).
        return isFrlg && t.mode == MODE_EARLY_RIVAL_OR_PYRAMID && r == BattleResult.DEFEAT
      }
      flagSet(trainerFlagsStart + id)
      val defeat = if (i == 0) t.defeatA else t.defeatB
      if (defeat != 0) {
        val text = textAt(defeat)
        showMessage(text)
        waitButton()
      }
    }
    return true
  }

  companion object {
    const val MAX_STEPS = 200_000
    const val TRAINER_FLAG_COUNT = 864
    const val OBJ_PLAYER = 0xFF
    const val OBJ_CAMERA = 0x7F
    const val VAR_FACING = 0x800C
    const val VAR_RESULT = 0x800D
    const val VAR_LAST_TALKED = 0x800F
    const val PARTY_SIZE = 6
    const val MULTI_B_PRESSED = 127

    const val MODE_SINGLE = 0
    const val MODE_CONTINUE_SCRIPT_NO_MUSIC = 1
    const val MODE_CONTINUE_SCRIPT = 2
    const val MODE_SINGLE_NO_INTRO = 3
    const val MODE_DOUBLE = 4
    const val MODE_REMATCH = 5
    const val MODE_CONTINUE_SCRIPT_DOUBLE = 6
    const val MODE_REMATCH_DOUBLE = 7
    const val MODE_CONTINUE_SCRIPT_DOUBLE_NO_MUSIC = 8
    const val MODE_EARLY_RIVAL_OR_PYRAMID = 9

    const val B_OUTCOME_WON = 1
    const val B_OUTCOME_LOST = 2
    const val B_OUTCOME_DREW = 3
    const val B_OUTCOME_RAN = 4
    const val B_OUTCOME_CAUGHT = 7

    const val SE_MS = 250L
    const val FANFARE_MS = 1800L
    const val CRY_MS = 600L
    const val DOOR_MS = 250L
    const val FADE_MS = 300L
    const val FX_MS = 400L

    private val COND =
        arrayOf(
            intArrayOf(1, 0, 0),
            intArrayOf(0, 1, 0),
            intArrayOf(0, 0, 1),
            intArrayOf(1, 1, 0),
            intArrayOf(0, 1, 1),
            intArrayOf(1, 0, 1))

    private val CONTEST_NAMES = listOf("COOL", "BEAUTY", "CUTE", "SMART", "TOUGH")
  }
}
