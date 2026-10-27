package de.fiereu.openmmo.web.assets

import de.fiereu.openmmo.client.gfx.GbaGame
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Exports a game's songs for the browser's MIDI synth: the decomp's own
 * `sound/songs/midi/mus_*.mid` files unchanged, plus `music/<region>/index.json` describing, per
 * song, the master volume from `midi.cfg` (`-V`) and the voices it uses from its voicegroup (`-G`):
 * which GBA sound channel kind (square / programmable wave / noise / DirectSound sample) and
 * envelope every program, and for drum kits and key splits every note, is played with. No sample
 * data is exported: the browser synthesises everything.
 *
 * Voicegroups are read as the assembler lays them out: one flat table of 12-byte voices in file
 * order (following `.include`s), each label an offset into it, so `voicegroup002[60]` may run into
 * the next group exactly as on the hardware.
 */
class MusicExport(decompRoot: File, private val game: GbaGame, private val out: Output) {
  private val base = File(decompRoot, game.dir)
  val failures: MutableList<String> = mutableListOf()

  private class Voice(val kind: String, val args: List<String>)

  private val voices = mutableListOf<Voice>()
  private val labels = HashMap<String, Int>()
  private val keysplits = HashMap<String, Pair<Int, ByteArray>>()

  private fun loadVoices() {
    val seen = HashSet<String>()
    fun read(path: String) {
      val f = File(base, path)
      if (!f.isFile || !seen.add(f.path)) return
      for (raw in f.readLines()) {
        val line = raw.substringBefore('@').trim()
        if (line.isEmpty()) continue
        when {
          line.startsWith(".include") -> read(line.substringAfter('"').substringBefore('"'))
          line.endsWith("::") || (line.endsWith(":") && !line.contains(' ')) ->
              labels[line.trimEnd(':')] = voices.size
          line.startsWith("voice_group ") -> {
            val a = line.removePrefix("voice_group ").split(',').map { it.trim() }
            labels["voicegroup_${a[0]}"] = voices.size - (a.getOrNull(1)?.toIntOrNull() ?: 0)
          }
          line.startsWith("voice_") -> {
            val kind = line.substringBefore(' ').substringBefore('\t')
            val args =
                line.substring(kind.length).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            voices += Voice(kind, args)
          }
        }
      }
    }
    read("sound/voice_groups.inc")
    // key split tables
    val ks =
        File(base, "sound/keysplit_tables.inc").takeIf { it.isFile }?.readLines() ?: emptyList()
    var cur: String? = null
    var start = 0
    var last = 0
    val bytes = mutableListOf<Byte>()
    fun flush() {
      cur?.let { keysplits[it] = start to bytes.toByteArray() }
      bytes.clear()
    }
    for (raw in ks) {
      val line = raw.substringBefore('@').trim()
      Regex("""\.set\s+(\w+),\s*\.\s*-\s*(\d+)""").find(line)?.let {
        flush()
        cur = it.groupValues[1]
        start = it.groupValues[2].toInt()
        return@let
      }
      Regex("""^keysplit\s+(\w+)(?:,\s*(\d+))?""").find(line)?.let {
        flush()
        cur = "keysplit_${it.groupValues[1]}"
        start = it.groupValues[2].toIntOrNull() ?: 0
        last = start
      }
      Regex("""^\.byte\s+(\d+)""").find(line)?.let { bytes += it.groupValues[1].toInt().toByte() }
      Regex("""^split\s+(\d+),\s*(\d+)""").find(line)?.let {
        val end = it.groupValues[2].toInt()
        repeat(end - last) { _ -> bytes += it.groupValues[1].toInt().toByte() }
        last = end
      }
    }
    flush()
  }

  private fun voiceAt(label: String, index: Int): Voice? =
      labels[label]?.let { voices.getOrNull(it + index) }

  private val waves = LinkedHashMap<String, Int>()

  /** `DirectSoundWaveData_x` -> its `.wav` (`direct_sound_data.inc` incbins the built `.bin`). */
  private val samplePaths: Map<String, File> by lazy {
    val out = HashMap<String, File>()
    var lbl: String? = null
    File(base, "sound/direct_sound_data.inc")
        .takeIf { it.isFile }
        ?.forEachLine { l ->
          val t = l.trim()
          if (t.endsWith("::")) lbl = t.trimEnd(':')
          Regex("""\.incbin\s+"([^"]+)"""").find(t)?.let { m ->
            val wav = File(base, m.groupValues[1].replace(Regex("""\.bin$"""), ".wav"))
            lbl?.let { if (wav.isFile) out[it] = wav }
          }
        }
    out
  }
  private val profiles = LinkedHashMap<String, JsonObject?>()

  /** Measured timbre of a DirectSound sample (see [SampleProfile]); null when unreadable. */
  private fun sampleProfile(sym: String): JsonObject? =
      profiles.getOrPut(sym) {
        samplePaths[sym]?.let { f ->
          runCatching { SampleProfile.profile(f) }
              .onFailure { failures += "${game.dir} sample $sym: $it" }
              .getOrNull()
        }
      }

  /** The leaf voice (no key split) [note] of [program] plays with, as JSON. */
  private fun leaf(v: Voice, note: Int, depth: Int = 0): JsonObject? {
    val a = v.args
    fun i(k: Int) = a.getOrNull(k)?.toIntOrNull() ?: 0
    return when (v.kind) {
      "voice_keysplit" -> {
        if (depth > 2) return null
        val (st, table) = keysplits[a.getOrNull(1)] ?: return null
        val idx = table.getOrNull(note - st)?.toInt() ?: return null
        voiceAt(a[0], idx)?.let { leaf(it, note, depth + 1) }
      }
      "voice_keysplit_all" -> {
        if (depth > 2) return null
        voiceAt(a[0], note)
            ?.let { leaf(it, note, depth + 1) }
            ?.let {
              // drum kit voices play at their own base key: the note only picks the drum
              JsonObject(it + ("drum" to kotlinx.serialization.json.JsonPrimitive(true)))
            }
      }
      "voice_square_1",
      "voice_square_1_alt" ->
          buildJsonObject {
            put("t", "sq")
            put("key", i(0))
            put("pan", i(1))
            put("sweep", i(2))
            put("duty", i(3) and 3)
            env(i(4), i(5), i(6), i(7), true)
          }
      "voice_square_2",
      "voice_square_2_alt" ->
          buildJsonObject {
            put("t", "sq")
            put("key", i(0))
            put("pan", i(1))
            put("duty", i(2) and 3)
            env(i(3), i(4), i(5), i(6), true)
          }
      "voice_programmable_wave",
      "voice_programmable_wave_alt" ->
          buildJsonObject {
            put("t", "wave")
            put("key", i(0))
            put("pan", i(1))
            put("wave", waves.getOrPut(a.getOrElse(2) { "" }) { waves.size })
            env(i(3), i(4), i(5), i(6), true)
          }
      "voice_noise",
      "voice_noise_alt" ->
          buildJsonObject {
            put("t", "noise")
            put("key", i(0))
            put("pan", i(1))
            put("period", i(2))
            env(i(3), i(4), i(5), i(6), true)
          }
      "voice_directsound",
      "voice_directsound_alt",
      "voice_directsound_no_resample" ->
          buildJsonObject {
            put("t", "ds")
            put("key", i(0))
            put("pan", i(1))
            put(
                "name",
                a.getOrElse(2) { "" }
                    .removePrefix("DirectSoundWaveData_")
                    .removePrefix("sc88pro_")
                    .removePrefix("sd90_")
                    .removePrefix("unused_"))
            if (v.kind.endsWith("no_resample")) put("fixed", true)
            a.getOrNull(2)?.let { sym -> if (sampleProfile(sym) != null) put("sample", sym) }
            env(i(3), i(4), i(5), i(6), false)
          }
      else -> null
    }
  }

  private fun kotlinx.serialization.json.JsonObjectBuilder.env(
      a: Int,
      d: Int,
      s: Int,
      r: Int,
      cgb: Boolean
  ) {
    putJsonArray("env") {
      add(a)
      add(d)
      add(s)
      add(r)
    }
    if (cgb) put("cgb", true)
  }

  /** (program, note) pairs a standard MIDI file plays, programs per channel (default 0). */
  private fun usedNotes(bytes: ByteArray): Set<Pair<Int, Int>> {
    val used = HashSet<Pair<Int, Int>>()
    fun u(i: Int) = bytes[i].toInt() and 0xFF
    var p = 14
    val prog = IntArray(16)
    while (p + 8 <= bytes.size) {
      if (String(bytes, p, 4) != "MTrk") break
      val len = (u(p + 4) shl 24) or (u(p + 5) shl 16) or (u(p + 6) shl 8) or u(p + 7)
      var q = p + 8
      val end = minOf(bytes.size, q + len)
      p = end
      var rs = 0
      fun vlq(): Int {
        var v = 0
        while (q < end) {
          val b = u(q++)
          v = (v shl 7) or (b and 0x7F)
          if (b < 0x80) break
        }
        return v
      }
      while (q < end) {
        vlq()
        if (q >= end) break
        var st = u(q)
        if (st == 0xFF) {
          q += 2
          q += vlq()
          continue
        }
        if (st == 0xF0 || st == 0xF7) {
          q++
          q += vlq()
          continue
        }
        if (st and 0x80 != 0) {
          rs = st
          q++
        } else st = rs
        val ch = rs and 15
        when (rs and 0xF0) {
          0xC0 -> prog[ch] = u(q++)
          0xD0 -> q++
          0x90 -> {
            if (q + 1 < end && u(q + 1) > 0) used += prog[ch] to u(q)
            q += 2
          }
          else -> q += 2
        }
      }
    }
    return used
  }

  /** Writes the songs and `music/<region>/index.json`; returns the index path. */
  fun export(region: Int): String? {
    val dir = File(base, "sound/songs/midi")
    val cfg = File(dir, "midi.cfg").takeIf { it.isFile } ?: return null
    loadVoices()
    val songs = buildJsonObject {
      cfg.readLines().forEach { line ->
        // songs and the sound effects (SE_*), which are songs on their own players
        val m = Regex("""^((?:mus|se)_\w+)\.mid:\s*(.*)$""").find(line.trim()) ?: return@forEach
        val stem = m.groupValues[1]
        val flags = m.groupValues[2]
        val f = File(dir, "$stem.mid").takeIf { it.isFile } ?: return@forEach
        val bytes = f.readBytes()
        val group = Regex("""-G(\S+)""").find(flags)?.groupValues?.get(1)
        val vol = Regex("""-V(\d+)""").find(flags)?.groupValues?.get(1)?.toInt() ?: 127
        // -R: m4a reverb amount (reverb_set + n), applied to the DirectSound mix
        val rev = Regex("""-R(\d+)""").find(flags)?.groupValues?.get(1)?.toInt()
        val path = out.write("music/$region/$stem.mid", bytes)
        val label = group?.let { "voicegroup$it" }
        put(
            stem.uppercase(),
            buildJsonObject {
              put("mid", path)
              put("vol", vol)
              rev?.let { put("rev", it) }
              putJsonObject("voices") {
                if (label == null || label !in labels) {
                  if (label != null) failures += "${game.dir} $stem: voicegroup $label not found"
                  return@putJsonObject
                }
                usedNotes(bytes).groupBy({ it.first }, { it.second }).toSortedMap().forEach {
                    (prog, notes) ->
                  val v = voiceAt(label, prog) ?: return@forEach
                  if (v.kind == "voice_keysplit" || v.kind == "voice_keysplit_all") {
                    putJsonObject("$prog") {
                      putJsonObject("keys") {
                        notes.distinct().sorted().forEach { n ->
                          leaf(v, n)?.let { put("$n", it as JsonElement) }
                        }
                      }
                    }
                  } else leaf(v, 60)?.let { put("$prog", it) }
                }
              }
            })
      }
    }
    val index = buildJsonObject {
      put("songs", songs)
      put(
          "samples",
          buildJsonObject { profiles.toSortedMap().forEach { (k, v) -> v?.let { put(k, it) } } })
      putJsonArray("waves") {
        val wavePaths = HashMap<String, String>()
        var lbl: String? = null
        File(base, "sound/programmable_wave_data.inc")
            .takeIf { it.isFile }
            ?.forEachLine { l ->
              val t = l.trim()
              if (t.endsWith("::")) lbl = t.trimEnd(':')
              Regex("""\.incbin\s+"([^"]+)"""").find(t)?.let { m ->
                lbl?.let { wavePaths[it] = m.groupValues[1] }
              }
            }
        waves.keys.forEach { sym ->
          val pcm = File(base, wavePaths[sym] ?: "missing")
          add(
              buildJsonArray32(
                  if (pcm.isFile) pcm.readBytes()
                  else ByteArray(16) { if (it < 8) 0xFF.toByte() else 0 }))
        }
      }
    }
    return out.write(
        "music/$region/index.json",
        kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), index)
            .toByteArray())
  }

  /** A 16-byte GB wave RAM image as its 32 4-bit samples, high nibble first. */
  private fun buildJsonArray32(b: ByteArray) =
      kotlinx.serialization.json.buildJsonArray {
        for (k in 0 until 16) {
          val v = b.getOrElse(k) { 0 }.toInt() and 0xFF
          add(v shr 4)
          add(v and 15)
        }
      }

  companion object {
    /** The songs the browser plays in battles, by the decomp constant (when the game has it). */
    val BATTLE =
        linkedMapOf(
            "wild" to "MUS_VS_WILD",
            "trainer" to "MUS_VS_TRAINER",
            "gym" to "MUS_VS_GYM_LEADER",
            "victoryWild" to "MUS_VICTORY_WILD",
            "victoryTrainer" to "MUS_VICTORY_TRAINER",
            "victoryGym" to "MUS_VICTORY_GYM_LEADER",
            "caught" to "MUS_CAUGHT",
        )

    /** UI / field sound effects and short jingles the browser plays, by role. */
    val SFX =
        linkedMapOf(
            "select" to listOf("SE_SELECT"),
            "winOpen" to listOf("SE_WIN_OPEN", "SE_SELECT"),
            "door" to listOf("SE_DOOR"),
            "exit" to listOf("SE_EXIT"),
            "bump" to listOf("SE_WALL_HIT"),
            "ledge" to listOf("SE_LEDGE"),
            "pcOn" to listOf("SE_PC_ON"),
            "pcLogin" to listOf("SE_PC_LOGIN"),
            "pcOff" to listOf("SE_PC_OFF"),
            "shop" to listOf("SE_SHOP"),
            "save" to listOf("SE_SAVE"),
            "shiny" to listOf("SE_SHINY"),
            "failure" to listOf("SE_FAILURE", "SE_BOO"),
            "success" to listOf("SE_SUCCESS"),
            "itemGet" to listOf("MUS_OBTAIN_ITEM"),
            "keyItemGet" to listOf("MUS_OBTAIN_KEY_ITEM", "MUS_OBTAIN_ITEM"),
            "levelUp" to listOf("MUS_LEVEL_UP"),
            "heal" to listOf("MUS_HEAL"),
        )
  }
}
