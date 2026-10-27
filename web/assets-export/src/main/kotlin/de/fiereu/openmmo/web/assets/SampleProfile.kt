package de.fiereu.openmmo.web.assets

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A sample-free description of one DirectSound instrument sample (`sound/direct_sound_samples/
 * *.wav`), measured from the sample itself so the browser synth can approximate it without the
 * sample data:
 * - `pitch60`: the fundamental (Hz) it sounds at MIDI note 60, from the sample's own pitch chunk
 *   (`agbp` = playback rate x 1024 at C4, what the m4a engine resamples from) and its measured f0;
 * - tonal samples: the first [HARMONICS] harmonics (cos/sin) of one period of the steady part (the
 *   loop when it has one) -> a WebAudio PeriodicWave, `gain` to match the sample's loudness;
 * - noisy samples (drums): energies in [BANDS] log-spaced bands -> filtered noise;
 * - `env`: the amplitude envelope over `dur` seconds (at note 60), 0..1, `loop` and `loopAt`.
 */
object SampleProfile {
  const val HARMONICS = 24
  const val ENV_POINTS = 48
  val BANDS = (0 until 20).map { 60.0 * 2.0.pow(it / 2.6) } // 60 Hz .. ~10 kHz

  class Wav(
      val pcm: FloatArray,
      val rate: Int,
      val loopStart: Int?,
      val loopEnd: Int?,
      val agbp: Long?,
  )

  fun readWav(f: File): Wav? {
    val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    if (b.limit() < 12 || b.getInt(0) != 0x46464952) return null // RIFF
    var p = 12
    var rate = 0
    var bits = 8
    var channels = 1
    var data: FloatArray? = null
    var ls: Int? = null
    var le: Int? = null
    var agbp: Long? = null
    while (p + 8 <= b.limit()) {
      val id = b.getInt(p)
      val n = b.getInt(p + 4)
      val body = p + 8
      if (n < 0 || body + n > b.limit()) break
      when (id) {
        0x20746d66 -> { // "fmt "
          channels = b.getShort(body + 2).toInt()
          rate = b.getInt(body + 4)
          bits = b.getShort(body + 14).toInt()
        }
        0x6c706d73 -> { // "smpl"
          val loops = b.getInt(body + 28)
          if (loops > 0 && n >= 60) {
            ls = b.getInt(body + 36 + 8)
            le = b.getInt(body + 36 + 12)
          }
        }
        0x70626761 -> if (n >= 4) agbp = b.getInt(body).toLong() and 0xFFFFFFFFL // "agbp"
        0x61746164 -> { // "data"
          val bytesPer = bits / 8 * channels
          val count = n / max(1, bytesPer)
          data =
              FloatArray(count) { i ->
                val o = body + i * bytesPer
                if (bits == 8) ((b.get(o).toInt() and 0xFF) - 128) / 128f
                else b.getShort(o) / 32768f
              }
        }
      }
      p = body + n + (n and 1)
    }
    return data?.let { Wav(it, rate, ls, le, agbp) }
  }

  private fun rms(x: FloatArray, a: Int, b: Int): Double {
    var s = 0.0
    for (i in a until b) s += x[i] * x[i]
    return sqrt(s / max(1, b - a))
  }

  /** Normalised autocorrelation f0 (Hz at the native rate) and its clarity 0..1. */
  private fun f0(x: FloatArray, a: Int, b: Int, rate: Int): Pair<Double, Double> {
    val n = min(b - a, 4096)
    if (n < 64) return 0.0 to 0.0
    val minLag = max(2, rate / 2500)
    val maxLag = min(n / 2, rate / 30)
    val corr = DoubleArray(maxLag + 2)
    var e0 = 0.0
    for (i in 0 until n) e0 += x[a + i] * x[a + i]
    if (e0 <= 1e-9) return 0.0 to 0.0
    for (lag in minLag..maxLag) {
      var s = 0.0
      var e1 = 0.0
      for (i in 0 until n - lag) {
        s += x[a + i] * x[a + i + lag]
        e1 += x[a + i + lag] * x[a + i + lag]
      }
      corr[lag] = s / sqrt(e0 * e1 + 1e-12)
    }
    var best = 0.0
    for (lag in minLag..maxLag) best = max(best, corr[lag])
    // first local peak close to the best one (avoids picking an octave below)
    for (lag in minLag + 1 until maxLag) {
      if (corr[lag] >= best * 0.9 && corr[lag] >= corr[lag - 1] && corr[lag] >= corr[lag + 1]) {
        // parabolic refinement
        val y0 = corr[lag - 1]
        val y1 = corr[lag]
        val y2 = corr[lag + 1]
        val d = (y0 - y2) / (2 * (y0 - 2 * y1 + y2)).let { if (abs(it) < 1e-9) 1e9 else it }
        return rate / (lag + d.coerceIn(-0.5, 0.5)) to corr[lag]
      }
    }
    return 0.0 to 0.0
  }

  fun profile(f: File): JsonObject? {
    val w = readWav(f) ?: return null
    val x = w.pcm
    if (x.size < 32 || w.rate <= 0) return null
    val playRate = (w.agbp?.toDouble()?.div(1024.0)) ?: w.rate.toDouble()
    val loop = w.loopStart != null && w.loopEnd != null && w.loopEnd > w.loopStart + 16
    val end = if (loop) min(x.size, w.loopEnd!! + 1) else x.size
    // steady window: the loop, else the loudest stretch after the attack
    val (wa, wb) =
        if (loop) w.loopStart!! to end
        else {
          val start = min(x.size - 1, (x.size * 0.08).toInt())
          start to min(x.size, start + max(512, x.size / 3))
        }
    val (fNative, clarity) = f0(x, wa, wb, w.rate)
    val tonal = fNative > 0 && clarity > 0.55
    val dur = x.size / playRate
    return buildJsonObject {
      put("dur", round4(dur))
      put("loop", loop)
      if (loop) put("loopAt", round4(w.loopStart!! / playRate))
      // envelope over the whole sample, normalised to its peak
      val env = DoubleArray(ENV_POINTS)
      val seg = max(1, end / ENV_POINTS)
      for (k in 0 until ENV_POINTS) {
        val a = min(end - 1, k * end / ENV_POINTS)
        env[k] = rms(x, a, min(end, a + seg))
      }
      val peak = env.max().takeIf { it > 1e-6 } ?: 1.0
      putJsonArray("env") { env.forEach { add(kotlinx.serialization.json.JsonPrimitive(round3(it / peak))) } }
      put("envDur", round4(end / playRate))
      val level = rms(x, wa, wb)
      if (tonal) {
        put("pitch60", round3(fNative * playRate / w.rate))
        // integer number of periods for the DFT
        val period = w.rate / fNative
        val periods = max(1, floor((wb - wa) / period).toInt()).coerceAtMost(64)
        val n = (periods * period).toInt().coerceAtMost(wb - wa)
        val re = DoubleArray(HARMONICS + 1)
        val im = DoubleArray(HARMONICS + 1)
        for (k in 1..HARMONICS) {
          var r = 0.0
          var s = 0.0
          val wk = 2 * PI * k / period
          for (i in 0 until n) {
            r += x[wa + i] * cos(wk * i)
            s += x[wa + i] * sin(wk * i)
          }
          re[k] = 2 * r / n
          im[k] = 2 * s / n
        }
        // loudness of the reconstruction vs the sample
        var synth = 0.0
        for (k in 1..HARMONICS) synth += (re[k] * re[k] + im[k] * im[k]) / 2
        val norm = (1..HARMONICS).maxOf { sqrt(re[it] * re[it] + im[it] * im[it]) }.takeIf { it > 1e-9 } ?: 1.0
        putJsonArray("re") { for (k in 1..HARMONICS) add(kotlinx.serialization.json.JsonPrimitive(round3(re[k] / norm))) }
        putJsonArray("im") { for (k in 1..HARMONICS) add(kotlinx.serialization.json.JsonPrimitive(round3(im[k] / norm))) }
        put("gain", round3(level / max(1e-6, sqrt(synth) / norm)))
        put("level", round3(level))
      } else {
        // band energies over the first ~150 ms (drums are short), at note 60 playback
        val n = min(x.size, (w.rate * 0.15).toInt().coerceAtLeast(64))
        val bands =
            BANDS.map { hz ->
              val fn = hz * w.rate / playRate // frequency in the native sample
              if (fn >= w.rate / 2.0) 0.0
              else {
                var r = 0.0
                var s = 0.0
                val wk = 2 * PI * fn / w.rate
                for (i in 0 until n) {
                  val hann = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
                  r += x[i] * hann * cos(wk * i)
                  s += x[i] * hann * sin(wk * i)
                }
                sqrt(r * r + s * s) / n
              }
            }
        val bmax = bands.max().takeIf { it > 1e-9 } ?: 1.0
        putJsonArray("bands") { bands.forEach { add(kotlinx.serialization.json.JsonPrimitive(round3(it / bmax))) } }
        if (fNative > 0 && clarity > 0.3) put("pitch60", round3(fNative * playRate / w.rate))
        put("level", round3(rms(x, 0, min(x.size, n))))
      }
    }
  }

  private fun round3(v: Double) = Math.round(v * 1000) / 1000.0

  private fun round4(v: Double) = Math.round(v * 10000) / 10000.0
}
