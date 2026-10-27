package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.maps.MapDef
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.script.ScriptRegistry
import de.fiereu.openmmo.server.game.script.ScriptRunner
import de.fiereu.openmmo.server.game.session.PlayerState
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private val log = KotlinLogging.logger {}

/**
 * A map's own script table (`<Map>_MapScripts`, map_script / map_script_2) run the way the decomp's
 * field code runs it:
 * - ON_TRANSITION, ON_LOAD and ON_RESUME when the map loads, before its objects spawn, in
 *   RunScriptImmediately mode (they place objects, set metatiles, flags and vars);
 * - the ON_WARP_INTO_MAP table once the player stands on the map, immediately;
 * - the ON_FRAME table: the first entry whose var equals its value runs as a normal script.
 *
 * Hand-ported ON_TRANSITION / ON_FRAME scripts still run where MapEntryScripts always ran them.
 */
@Singleton
class VmMapScripts
@Inject
constructor(
    private val runner: Provider<ScriptRunner>,
    private val registry: ScriptRegistry,
) {
  /** Map load: per-map state reset, temp flags / vars cleared, then the immediate scripts. */
  fun onLoad(session: SessionContext, state: PlayerState, map: MapDef) {
    val vmState = VmState.of(session)
    val key = (map.regionId.toInt() shl 16) or ((map.bankId.toInt() and 0xFF) shl 8) or
        (map.mapId.toInt() and 0xFF)
    val changed = vmState.mapKey != key
    vmState.enterMap(key)
    val img = ScriptImage.of(regionOf(map)) ?: return
    val table = img.labels["${map.name}_MapScripts"] ?: return
    val scripts = ArrayList<Int>()
    var a = table
    while (img.contains(a)) {
      val type = img.u8(a)
      if (type == 0) break
      val ptr = img.u32(a + 1)
      a += 5
      when (type) {
        MAP_SCRIPT_ON_TRANSITION -> {
          val label = img.labelsAt(ptr).firstOrNull()
          if (label == null || registry.handPorted(label) == null) scripts += ptr
        }
        MAP_SCRIPT_ON_LOAD, MAP_SCRIPT_ON_RESUME -> scripts += ptr
      }
    }
    runImmediate(session, state, img, scripts, clearTemps = changed)
  }

  /** ON_WARP_INTO_MAP: the matching entry, run immediately on the caller's script context. */
  fun warpIntoScript(map: MapDef): Script? {
    val img = ScriptImage.of(regionOf(map)) ?: return null
    val ptr = tableEntry(img, map, MAP_SCRIPT_ON_WARP_INTO_MAP_TABLE) ?: return null
    return Script { ctx ->
      val vm = ScriptVm(ctx, img, immediate = true)
      val hit = matching(vm, ptr) ?: return@Script
      vm.runAt(hit)
    }
  }

  /** ON_FRAME: a script that picks the first matching entry when it runs, or null if none can. */
  fun frameScript(map: MapDef): Script? {
    val img = ScriptImage.of(regionOf(map)) ?: return null
    val ptr = tableEntry(img, map, MAP_SCRIPT_ON_FRAME_TABLE) ?: return null
    return Script { ctx ->
      val probe = ScriptVm(ctx, img)
      val hit = matching(probe, ptr) ?: return@Script
      val label = img.labelsAt(hit).firstOrNull()
      val handPort = label?.let(registry::handPorted)
      if (handPort != null) handPort.run(ctx) else probe.runAt(hit)
    }
  }

  private fun tableEntry(img: ScriptImage, map: MapDef, wanted: Int): Int? {
    var a = img.labels["${map.name}_MapScripts"] ?: return null
    while (img.contains(a)) {
      val type = img.u8(a)
      if (type == 0) return null
      if (type == wanted) return img.u32(a + 1)
      a += 5
    }
    return null
  }

  /** MapHeaderCheckScriptTable: the first (var, value, script) whose VarGets are equal. */
  private fun matching(vm: ScriptVm, table: Int): Int? {
    val img = vm.img
    var a = table
    while (img.contains(a + 1)) {
      val v1 = img.u16(a)
      if (v1 == 0) return null
      val v2 = img.u16(a + 2)
      val ptr = img.u32(a + 4)
      a += 8
      if (vm.varGet(v1) == vm.varGet(v2)) return ptr
    }
    return null
  }

  private fun runImmediate(
      session: SessionContext,
      state: PlayerState,
      img: ScriptImage,
      scripts: List<Int>,
      clearTemps: Boolean,
  ) {
    if (scripts.isEmpty() && !clearTemps) return
    runner.get().immediate(session, state) { ctx ->
      if (clearTemps) clearTemporaries(ctx, img)
      for (ptr in scripts) {
        try {
          ScriptVm(ctx, img, immediate = true).runAt(ptr)
        } catch (e: Exception) {
          log.warn(e) { "${img.region}: map script ${img.where(ptr)} failed" }
        }
      }
    }
  }

  /** ClearTempFieldEventData: FLAG_TEMP_1..1F and VAR_TEMP_0..F. */
  private fun clearTemporaries(ctx: ScriptContext, img: ScriptImage) {
    val vm = ScriptVm(ctx, img, immediate = true)
    ctx.vm = vm
    try {
      for (id in 1..0x1F) if (vm.flagGet(id)) vm.flagClear(id)
      for (id in 0x4000..0x400F) if (vm.varGet(id) != 0) vm.varSet(id, 0)
    } finally {
      ctx.vm = null
    }
  }

  private fun regionOf(map: MapDef) = if (map.regionId.toInt() == 1) "hoenn" else "kanto"

  companion object {
    const val MAP_SCRIPT_ON_LOAD = 1
    const val MAP_SCRIPT_ON_FRAME_TABLE = 2
    const val MAP_SCRIPT_ON_TRANSITION = 3
    const val MAP_SCRIPT_ON_WARP_INTO_MAP_TABLE = 4
    const val MAP_SCRIPT_ON_RESUME = 5

    /** Runs [block] to completion now; immediate scripts never wait, so this is bounded. */
    fun <T> bounded(block: suspend () -> T): T? = runBlocking { withTimeoutOrNull(2000) { block() } }
  }
}
