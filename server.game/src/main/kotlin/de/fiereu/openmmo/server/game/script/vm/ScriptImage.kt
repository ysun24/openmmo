package de.fiereu.openmmo.server.game.script.vm

import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/** One `.string` / `.braille` of the scripts: its dialog id (0 when unlabelled) and raw text. */
class ScriptText(
    val addr: Int,
    val textId: Int,
    val label: String,
    val braille: Boolean,
    val content: String
)

/**
 * A GBA region's event scripts as the decomp's own bytecode (built by `:codegen:generateScriptVm`
 * from `data/event_scripts.s`), with the tables the interpreter reads by name: labels, texts, the
 * external C symbols words point at, the script command and special tables, flag / var names,
 * selected constants, the species -> national dex map, multichoice menus and std strings.
 */
class ScriptImage
private constructor(
    val region: String,
    val base: Int,
    val bytes: ByteArray,
    val labels: Map<String, Int>,
    val texts: Map<Int, ScriptText>,
    val externs: Map<Int, String>,
    val commands: List<String>,
    val specials: List<String>,
    val flagNames: Map<Int, List<String>>,
    val varNames: Map<Int, List<String>>,
    val constants: Map<String, Long>,
    val speciesToDex: Map<Int, Int>,
    val menus: Map<Int, List<String>>,
    val stdStrings: List<String>,
    val cStrings: Map<String, String>,
    /** field_specials.c string tables: name -> row -> texts (one-dimensional ones are row 0). */
    val stringTables: Map<String, Map<Int, List<String>>> = emptyMap(),
) {
  val end: Int
    get() = base + bytes.size

  fun contains(addr: Int) = addr >= base && addr < end

  fun u8(addr: Int): Int = bytes[addr - base].toInt() and 0xFF

  fun u16(addr: Int): Int = u8(addr) or (u8(addr + 1) shl 8)

  fun u32(addr: Int): Int = u16(addr) or (u16(addr + 2) shl 16)

  private val labelsAt: Map<Int, List<String>> by lazy {
    labels.entries.groupBy({ it.value }, { it.key })
  }

  fun labelsAt(addr: Int): List<String> = labelsAt[addr].orEmpty()

  /** The nearest label at or before [addr], for logs. */
  fun where(addr: Int): String {
    val best = labels.entries.filter { it.value <= addr }.maxByOrNull { it.value } ?: return "?"
    val off = addr - best.value
    return if (off == 0) best.key else "${best.key}+$off"
  }

  fun const(name: String): Int = (constants[name] ?: error("$region: no constant $name")).toInt()

  fun constOrNull(name: String): Int? = constants[name]?.toInt()

  private val byValue = HashMap<String, Map<Int, String>>()

  /** The first constant named `<prefix>...` whose value is [value], e.g. ("SE_", 21). */
  fun nameOf(prefix: String, value: Int): String? =
      synchronized(byValue) {
        byValue.getOrPut(prefix) {
          val m = HashMap<Int, String>()
          for ((n, v) in constants) if (n.startsWith(prefix)) m.putIfAbsent(v.toInt(), n)
          m
        }
      }[value]

  companion object {
    private const val MAGIC = 0x4F4D5356
    private val cache = HashMap<String, ScriptImage?>()

    /** The image of `kanto` / `hoenn`, or null when the resource is not on the classpath. */
    fun of(region: String): ScriptImage? =
        synchronized(cache) {
          cache.getOrPut(region) {
            val stream =
                ScriptImage::class.java.getResourceAsStream("/scriptvm/$region.bin")
                    ?: return@getOrPut null
            read(DataInputStream(GZIPInputStream(stream.buffered())))
          }
        }

    private fun readLong(i: DataInputStream): String {
      val b = ByteArray(i.readInt())
      i.readFully(b)
      return String(b, Charsets.UTF_8)
    }

    private fun readTable(i: DataInputStream): List<String> = List(i.readInt()) { readLong(i) }

    private fun readIdNames(i: DataInputStream): Map<Int, List<String>> {
      val n = i.readInt()
      val out = LinkedHashMap<Int, List<String>>(n * 2)
      repeat(n) {
        val id = i.readLong().toInt()
        out[id] = List(i.readShort().toInt()) { i.readUTF() }
      }
      return out
    }

    private fun read(i: DataInputStream): ScriptImage =
        i.use {
          check(i.readInt() == MAGIC) { "not a script image" }
          check(i.readInt() == 1) { "script image version" }
          val region = i.readUTF()
          val base = i.readInt()
          val bytes = ByteArray(i.readInt()).also(i::readFully)
          val labels = LinkedHashMap<String, Int>()
          repeat(i.readInt()) { labels[i.readUTF()] = i.readInt() }
          val texts = HashMap<Int, ScriptText>()
          repeat(i.readInt()) {
            val addr = i.readInt()
            val id = i.readInt()
            val label = i.readUTF()
            val braille = i.readBoolean()
            texts[addr] = ScriptText(addr, id, label, braille, readLong(i))
          }
          val externs = HashMap<Int, String>()
          repeat(i.readInt()) { externs[i.readInt()] = i.readUTF() }
          val commands = readTable(i)
          val specials = readTable(i)
          val flags = readIdNames(i)
          val vars = readIdNames(i)
          val constants = LinkedHashMap<String, Long>()
          repeat(i.readInt()) { constants[i.readUTF()] = i.readLong() }
          val species = HashMap<Int, Int>()
          repeat(i.readInt()) { species[i.readShort().toInt() and 0xFFFF] = i.readShort().toInt() }
          val menus = HashMap<Int, List<String>>()
          repeat(i.readInt()) { menus[i.readShort().toInt()] = readTable(i) }
          val std = readTable(i)
          val cStrings = HashMap<String, String>()
          repeat(i.readInt()) { cStrings[i.readUTF()] = readLong(i) }
          val tables = LinkedHashMap<String, Map<Int, List<String>>>()
          repeat(i.readInt()) {
            val name = i.readUTF()
            val rows = LinkedHashMap<Int, List<String>>()
            repeat(i.readInt()) { rows[i.readInt()] = readTable(i) }
            tables[name] = rows
          }
          ScriptImage(
              region,
              base,
              bytes,
              labels,
              texts,
              externs,
              commands,
              specials,
              flags,
              vars,
              constants,
              species,
              menus,
              std,
              cStrings,
              tables)
        }
  }
}
