package de.fiereu.openmmo.server.game.script.vm

/**
 * A static walk over a [ScriptImage]'s bytecode: from entry points it follows every jump, call,
 * std script and trainer battle continuation, and reports each command and special reached. The
 * coverage test uses it to prove every command and special the two games' scripts can reach is
 * implemented; the operand layouts are scrcmd.c's ScriptRead* sequences.
 */
class ScriptDisasm(private val img: ScriptImage) {
  /** Command name -> how many times it was reached. */
  val commands = LinkedHashMap<String, Int>()
  val specials = LinkedHashMap<String, Int>()
  val natives = LinkedHashMap<String, Int>()
  val visited = HashSet<Int>()
  val errors = ArrayList<String>()
  private val queue = ArrayDeque<Int>()

  private val stdTable = img.labels["gStdScripts"] ?: 0
  private val stdEnd = img.labels["gStdScripts_End"] ?: stdTable

  fun enqueue(addr: Int) {
    if (addr != 0 && img.contains(addr) && addr !in visited) queue.addLast(addr)
  }

  fun enqueueLabel(label: String): Boolean {
    val a = img.labels[label] ?: return false
    enqueue(a)
    return true
  }

  /** Every std script, and the map script tables' scripts of [mapNames]. */
  fun enqueueRoots(mapNames: Collection<String>) {
    var a = stdTable
    while (a < stdEnd) {
      enqueue(img.u32(a))
      a += 4
    }
    for (m in mapNames) {
      var t = img.labels["${m}_MapScripts"] ?: continue
      while (img.contains(t)) {
        val type = img.u8(t)
        if (type == 0) break
        val ptr = img.u32(t + 1)
        t += 5
        if (type == 2 || type == 4) {
          var e = ptr
          while (img.contains(e + 1) && img.u16(e) != 0) {
            enqueue(img.u32(e + 4))
            e += 8
          }
        } else enqueue(ptr)
      }
    }
  }

  fun run() {
    while (queue.isNotEmpty()) walk(queue.removeFirst())
  }

  private fun walk(start: Int) {
    var pc = start
    while (img.contains(pc)) {
      if (!visited.add(pc)) return
      val at = pc
      val op = img.u8(pc++)
      val name = img.commands.getOrNull(op)
      if (name == null || name.isEmpty()) {
        errors += "bad opcode $op at ${img.where(at)}"
        return
      }
      commands.merge(name, 1, Int::plus)
      fun b() = img.u8(pc).also { pc += 1 }
      fun h() = img.u16(pc).also { pc += 2 }
      fun w() = img.u32(pc).also { pc += 4 }
      when (name) {
        "end", "endram", "return", "returnram" -> return
        "goto" -> {
          enqueue(w())
          return
        }
        "call" -> enqueue(w())
        "goto_if", "call_if" -> {
          b()
          enqueue(w())
        }
        "gotostd" -> {
          std(b())
          return
        }
        "callstd" -> std(b())
        "gotostd_if", "callstd_if" -> {
          b()
          std(b())
        }
        "special" -> specials.merge(img.specials.getOrNull(h()) ?: "?", 1, Int::plus)
        "specialvar" -> {
          h()
          specials.merge(img.specials.getOrNull(h()) ?: "?", 1, Int::plus)
        }
        "callnative", "gotonative" -> {
          natives.merge(img.externs[pc] ?: "?", 1, Int::plus)
          w()
          if (name == "gotonative") return
        }
        "trainerbattle" -> {
          trainerBattle(pc)
          return
        }
        "warp", "warpsilent", "warpdoor", "warpteleport", "warpspinenter", "warpmossdeepgym",
        "warpwhitefade" -> {
          pc += 7
          return
        }
        "warphole" -> {
          pc += 2
          return
        }
        "pokemart", "pokemartdecoration", "pokemartdecoration2" -> {
          w()
          return
        }
        "gotopostbattlescript", "gotobeatenscript" -> return
        else -> {
          val layout = LAYOUTS[name]
          if (layout == null) {
            errors += "no operand layout for $name at ${img.where(at)}"
            return
          }
          for (c in layout) when (c) {
            'b' -> pc += 1
            'h' -> pc += 2
            'w' -> pc += 4
          }
        }
      }
    }
  }

  private fun std(idx: Int) {
    val a = stdTable + 4 * idx
    if (a < stdEnd) enqueue(img.u32(a))
  }

  /** The trainerbattle operands; the continuation after them and any event script run on. */
  private fun trainerBattle(data: Int) {
    var p = data
    val mode = img.u8(p)
    p += 5
    val frlg = img.region == "kanto"
    val words =
        when (mode) {
          ScriptVm.MODE_SINGLE_NO_INTRO -> 1
          ScriptVm.MODE_DOUBLE, ScriptVm.MODE_REMATCH_DOUBLE -> 3
          ScriptVm.MODE_CONTINUE_SCRIPT, ScriptVm.MODE_CONTINUE_SCRIPT_NO_MUSIC -> 3
          ScriptVm.MODE_CONTINUE_SCRIPT_DOUBLE, ScriptVm.MODE_CONTINUE_SCRIPT_DOUBLE_NO_MUSIC -> 4
          else -> 2
        }
    val ptrs = List(words) { img.u32(p + 4 * it) }
    p += 4 * words
    // The event script (continue modes) is the last pointer.
    if (mode in
        setOf(
            ScriptVm.MODE_CONTINUE_SCRIPT,
            ScriptVm.MODE_CONTINUE_SCRIPT_NO_MUSIC,
            ScriptVm.MODE_CONTINUE_SCRIPT_DOUBLE,
            ScriptVm.MODE_CONTINUE_SCRIPT_DOUBLE_NO_MUSIC))
        enqueue(ptrs.last())
    enqueue(p)
    if (!frlg && (mode == 10 || mode == 11)) return
  }

  companion object {
    /** scrcmd.c's operand reads per command (b = byte, h = halfword, w = word). */
    val LAYOUTS: Map<String, String> =
        mapOf(
            "nop" to "",
            "nop1" to "",
            "setvaddress" to "w",
            "vgoto" to "w",
            "vcall" to "w",
            "vgoto_if" to "bw",
            "vcall_if" to "bw",
            "vmessage" to "w",
            "vbuffermessage" to "w",
            "vbufferstring" to "bw",
            "setmysteryeventstatus" to "b",
            "loadword" to "bw",
            "loadbyte" to "bb",
            "setptr" to "bw",
            "loadbytefromptr" to "bw",
            "setptrbyte" to "bw",
            "copylocal" to "bb",
            "copybyte" to "ww",
            "setvar" to "hh",
            "addvar" to "hh",
            "subvar" to "hh",
            "copyvar" to "hh",
            "setorcopyvar" to "hh",
            "compare_local_to_local" to "bb",
            "compare_local_to_value" to "bb",
            "compare_local_to_ptr" to "bw",
            "compare_ptr_to_local" to "wb",
            "compare_ptr_to_value" to "wb",
            "compare_ptr_to_ptr" to "ww",
            "compare_var_to_value" to "hh",
            "compare_var_to_var" to "hh",
            "waitstate" to "",
            "delay" to "h",
            "setflag" to "h",
            "clearflag" to "h",
            "checkflag" to "h",
            "initclock" to "hh",
            "dotimebasedevents" to "",
            "gettime" to "",
            "playse" to "h",
            "waitse" to "",
            "playfanfare" to "h",
            "waitfanfare" to "",
            "playbgm" to "hb",
            "savebgm" to "h",
            "fadedefaultbgm" to "",
            "fadenewbgm" to "h",
            "fadeoutbgm" to "b",
            "fadeinbgm" to "b",
            "setwarp" to "bbbhh",
            "setdynamicwarp" to "bbbhh",
            "setdivewarp" to "bbbhh",
            "setholewarp" to "bbbhh",
            "setescapewarp" to "bbbhh",
            "getplayerxy" to "hh",
            "getpartysize" to "",
            "additem" to "hh",
            "removeitem" to "hh",
            "checkitemspace" to "hh",
            "checkitem" to "hh",
            "checkitemtype" to "h",
            "addpcitem" to "hh",
            "checkpcitem" to "hh",
            "adddecoration" to "h",
            "removedecoration" to "h",
            "checkdecor" to "h",
            "checkdecorspace" to "h",
            "applymovement" to "hw",
            "applymovementat" to "hwbb",
            "waitmovement" to "h",
            "waitmovementat" to "hbb",
            "removeobject" to "h",
            "removeobjectat" to "hbb",
            "addobject" to "h",
            "addobjectat" to "hbb",
            "setobjectxy" to "hhh",
            "showobjectat" to "hbb",
            "hideobjectat" to "hbb",
            "faceplayer" to "",
            "turnobject" to "hb",
            "setobjectxyperm" to "hhh",
            "copyobjectxytoperm" to "h",
            "setobjectmovementtype" to "hb",
            "setobjectsubpriority" to "hbbb",
            "resetobjectsubpriority" to "hbb",
            "createvobject" to "bbhhbb",
            "turnvobject" to "bb",
            "lockall" to "",
            "lock" to "",
            "releaseall" to "",
            "release" to "",
            "selectapproachingtrainer" to "",
            "lockfortrainer" to "",
            "message" to "w",
            "messageautoscroll" to "w",
            "messageinstant" to "w",
            "pokenavcall" to "w",
            "braillemessage" to "w",
            "getbraillestringwidth" to "w",
            "closebraillemessage" to "",
            "waitmessage" to "",
            "closemessage" to "",
            "waitbuttonpress" to "",
            "yesnobox" to "bb",
            "multichoice" to "bbbb",
            "multichoicedefault" to "bbbbb",
            "multichoicegrid" to "bbbbb",
            "drawbox" to "bbbb",
            "drawboxtext" to "bbbb",
            "erasebox" to "bbbb",
            "showmonpic" to "hbb",
            "hidemonpic" to "",
            "showcontestpainting" to "b",
            "bufferspeciesname" to "bh",
            "bufferleadmonspeciesname" to "b",
            "bufferpartymonnick" to "bh",
            "bufferitemname" to "bh",
            "bufferitemnameplural" to "bhh",
            "bufferdecorationname" to "bh",
            "buffermovename" to "bh",
            "buffernumberstring" to "bh",
            "bufferstdstring" to "bh",
            "buffercontestname" to "bh",
            "bufferstring" to "bw",
            "bufferboxname" to "bh",
            "buffertrainerclassname" to "bh",
            "buffertrainername" to "bh",
            "givemon" to "hbhwwb",
            "giveegg" to "h",
            "setmonmove" to "bbh",
            "checkpartymove" to "h",
            "addmoney" to "wb",
            "removemoney" to "wb",
            "checkmoney" to "wb",
            "showmoneybox" to "bbb",
            "hidemoneybox" to "bb",
            "updatemoneybox" to "bbb",
            "addcoins" to "h",
            "removecoins" to "h",
            "checkcoins" to "h",
            "showcoinsbox" to "bb",
            "hidecoinsbox" to "bb",
            "updatecoinsbox" to "bb",
            "dotrainerbattle" to "",
            "checktrainerflag" to "h",
            "settrainerflag" to "h",
            "cleartrainerflag" to "h",
            "setwildbattle" to "hbh",
            "dowildbattle" to "",
            "playslotmachine" to "h",
            "setberrytree" to "bbb",
            "choosecontestmon" to "",
            "startcontest" to "",
            "showcontestresults" to "",
            "contestlinktransfer" to "",
            "random" to "h",
            "playmoncry" to "hh",
            "waitmoncry" to "",
            "setmetatile" to "hhhh",
            "opendoor" to "hh",
            "closedoor" to "hh",
            "waitdooranim" to "",
            "setdooropen" to "hh",
            "setdoorclosed" to "hh",
            "setweather" to "h",
            "resetweather" to "",
            "doweather" to "",
            "setstepcallback" to "b",
            "setmaplayoutindex" to "h",
            "setflashlevel" to "h",
            "animateflash" to "b",
            "dofieldeffect" to "h",
            "setfieldeffectargument" to "bh",
            "waitfieldeffect" to "h",
            "setrespawn" to "h",
            "checkplayergender" to "",
            "fadescreen" to "b",
            "fadescreenspeed" to "bb",
            "fadescreenswapbuffers" to "b",
            "incrementgamestat" to "b",
            "comparestat" to "bw",
            "setworldmapflag" to "h",
            "textcolor" to "b",
            "signmsg" to "",
            "normalmsg" to "",
            "loadhelp" to "w",
            "unloadhelp" to "",
            "setmonmetlocation" to "hb",
            "setmodernfatefulencounter" to "h",
            "setmonmodernfatefulencounter" to "h",
            "checkmodernfatefulencounter" to "h",
            "checkmonmodernfatefulencounter" to "h",
            "getpokenewsactive" to "h",
            "trywondercardscript" to "",
            "addelevmenuitem" to "bhhh",
            "showelevmenu" to "",
            "initrotatingtilepuzzle" to "h",
            "moverotatingtileobjects" to "h",
            "turnrotatingtileobjects" to "",
            "freerotatingtilepuzzle" to "",
        )
  }
}
