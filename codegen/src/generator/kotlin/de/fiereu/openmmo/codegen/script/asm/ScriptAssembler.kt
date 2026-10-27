package de.fiereu.openmmo.codegen.script.asm

import java.io.ByteArrayOutputStream
import java.io.File

/** A `.string` / `.braille` run starting at [addr], the raw decomp text between the quotes. */
class AsmText(val addr: Int, val braille: Boolean) {
  val content = StringBuilder()
}

/**
 * Assembles a pret GBA `data/event_scripts.s` the way the real toolchain does (preproc inlines the
 * `.include`d `.inc` files, cpp resolves the C headers, GNU as expands the macros of
 * `asm/macros/event.inc`) into the game's own script bytecode, so the server can run the decomp's
 * scripts with a port of script.c / scrcmd.c instead of hand-ported Kotlin.
 *
 * Only what the script interpreter needs is kept beside the bytes: every label's address, the text
 * behind each `.string` (as raw decomp text, it is never encoded), and the external C symbols a
 * word refers to (callnative functions, the special and command tables). Addresses start at [BASE]
 * so a pointer is never confused with a small number.
 */
class ScriptAssembler(
    private val root: File,
    predefined: Map<String, String> = emptyMap(),
) : Resolver {
  private val cdefs = HashMap<String, CMacro>()
  private val asmSyms = HashMap<String, Value>()
  private val macros = HashMap<String, AsmMacro>()
  private var labels = LinkedHashMap<String, Int>()
  private var knownLabels: Map<String, Int> = emptyMap()
  private var image = ByteArrayOutputStream()
  private var texts = LinkedHashMap<Int, AsmText>()
  private var externs = LinkedHashMap<Int, String>()
  private val errors = LinkedHashSet<String>()
  private var macroCounter = 0
  private var currentText: AsmText? = null
  private var pass = 0
  private val parsedHeaders = HashSet<String>()

  init {
    for ((k, v) in predefined) cdefs[k] = CMacro(null, v)
  }

  class AsmMacro(val name: String, val params: List<Param>, val body: List<String>)

  data class Param(val name: String, val default: String?, val vararg: Boolean)

  class Result(
      val bytes: ByteArray,
      val labels: Map<String, Int>,
      val texts: Map<Int, AsmText>,
      val externs: Map<Int, String>,
      val errors: Set<String>,
      val resolver: Resolver,
  )

  fun defineC(name: String, body: String) {
    cdefs[name] = CMacro(null, body)
  }

  fun assemble(entry: File): Result {
    // Pass 1 finds every label; pass 2 emits with forward references resolved.
    for (p in 1..2) {
      pass = p
      knownLabels = if (p == 1) emptyMap() else labels
      labels = LinkedHashMap()
      image = ByteArrayOutputStream()
      texts = LinkedHashMap()
      externs = LinkedHashMap()
      currentText = null
      macros.clear()
      asmSyms.clear()
      macroCounter = 0
      errors.clear()
      parsedHeaders.clear()
      processFile(entry)
    }
    return Result(image.toByteArray(), labels, texts, externs, errors, this)
  }

  private val here: Int
    get() = BASE + image.size()

  // ------------------------------------------------------------------ resolution

  private val resolving = HashSet<String>()

  override fun resolve(name: String): Value? {
    cdefs[name]?.let { m ->
      if (m.params != null) return null
      if (m.body.isBlank()) return Num(1)
      if (!resolving.add(name)) return null
      try {
        return Expr.eval(m.body, this)
      } catch (e: ExprException) {
        return null
      } finally {
        resolving.remove(name)
      }
    }
    asmSyms[name]?.let {
      return it
    }
    labels[name]?.let {
      return Num(it.toLong())
    }
    knownLabels[name]?.let {
      return Num(it.toLong())
    }
    return null
  }

  override fun function(name: String): CMacro? = cdefs[name]?.takeIf { it.params != null }

  /** Evaluates, treating anything undefined as a symbol (a forward label or an extern). */
  private fun evalLoose(expr: String): Value =
      try {
        Expr.eval(expr, looseResolver)
      } catch (e: ExprException) {
        Sym(expr.trim())
      }

  private val looseResolver =
      object : Resolver {
        override fun resolve(name: String): Value? = this@ScriptAssembler.resolve(name) ?: Sym(name)

        override fun function(name: String) = this@ScriptAssembler.function(name)
      }

  // ------------------------------------------------------------------ files

  private fun processFile(file: File) {
    if (!file.isFile) {
      errors += "missing include ${file.path}"
      return
    }
    processLines(cleanLines(file.readText()), file)
  }

  /** Strips C block comments, `//` comments and `@` comments, outside of strings. */
  private fun cleanLines(text: String): List<String> {
    val sb = StringBuilder(text.length)
    var i = 0
    var inStr = false
    while (i < text.length) {
      val c = text[i]
      if (inStr) {
        sb.append(c)
        if (c == '\\' && i + 1 < text.length) {
          sb.append(text[i + 1])
          i += 2
          continue
        }
        if (c == '"') inStr = false
        i++
        continue
      }
      if (c == '"') {
        inStr = true
        sb.append(c)
        i++
        continue
      }
      if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
        val end = text.indexOf("*/", i + 2)
        val skipped = if (end < 0) text.substring(i) else text.substring(i, end + 2)
        // Keep the line structure.
        repeat(skipped.count { it == '\n' }) { sb.append('\n') }
        i = if (end < 0) text.length else end + 2
        continue
      }
      if (c == '/' && i + 1 < text.length && text[i + 1] == '/' ||
          c == '@' && (i == 0 || text[i - 1] != '\\')) {
        while (i < text.length && text[i] != '\n') i++
        continue
      }
      if (c == '\n') inStr = false
      sb.append(c)
      i++
    }
    return sb.toString().split('\n')
  }

  // ------------------------------------------------------------------ conditionals

  private class Cond(val parentActive: Boolean, var active: Boolean, var taken: Boolean)

  private fun active(stack: ArrayDeque<Cond>) = stack.lastOrNull()?.active ?: true

  private fun open(stack: ArrayDeque<Cond>, value: Boolean) {
    val parent = active(stack)
    stack.addLast(Cond(parent, parent && value, value))
  }

  private fun elseIf(stack: ArrayDeque<Cond>, value: () -> Boolean) {
    val c = stack.lastOrNull() ?: return
    if (c.taken || !c.parentActive) {
      c.active = false
    } else {
      val v = value()
      c.active = v
      c.taken = v
    }
  }

  private fun orElse(stack: ArrayDeque<Cond>) {
    val c = stack.lastOrNull() ?: return
    c.active = c.parentActive && !c.taken
    c.taken = true
  }

  private fun truth(expr: String): Boolean =
      when (val v = evalLoose(expr)) {
        is Num -> v.v != 0L
        is Sym -> true
      }

  // ------------------------------------------------------------------ lines

  private fun processLines(lines: List<String>, file: File) {
    val cond = ArrayDeque<Cond>()
    var i = 0
    while (i < lines.size) {
      val raw = lines[i]
      i++
      val line = raw.trim()
      if (line.isEmpty()) continue

      // C preprocessor directives.
      if (line.startsWith("#")) {
        i = cppDirective(line, lines, i, cond)
        continue
      }

      val word = line.substringBefore(' ').substringBefore('\t').trimEnd(',')
      val rest = line.substring(word.length).trim()

      when (word) {
        ".if" -> {
          if (active(cond)) open(cond, truth(rest)) else open(cond, false)
          continue
        }
        ".ifdef" -> {
          open(cond, active(cond) && resolve(rest) != null)
          continue
        }
        ".ifndef",
        ".ifnotdef" -> {
          open(cond, active(cond) && resolve(rest) == null)
          continue
        }
        ".ifb" -> {
          open(cond, active(cond) && rest.isBlank())
          continue
        }
        ".ifnb" -> {
          open(cond, active(cond) && rest.isNotBlank())
          continue
        }
        ".ifc" -> {
          val (a, b) = splitArgs(rest).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
          open(cond, active(cond) && a.trim() == b.trim())
          continue
        }
        ".elseif" -> {
          elseIf(cond) { truth(rest) }
          continue
        }
        ".else" -> {
          orElse(cond)
          continue
        }
        ".endif" -> {
          cond.removeLastOrNull()
          continue
        }
      }
      if (!active(cond)) {
        // Skip a whole macro definition so its .endm is not mistaken for ours.
        continue
      }

      if (word == ".macro") {
        val (body, next) = collect(lines, i, ".macro", ".endm")
        i = next
        defineMacro(rest, body)
        continue
      }
      if (word == ".rept") {
        val (body, next) = collect(lines, i, ".rept", ".endr")
        i = next
        val n = (evalLoose(rest) as? Num)?.v?.toInt() ?: 0
        repeat(n) { processLines(body, file) }
        continue
      }
      statement(line, file)
    }
  }

  /** Lines up to the matching [close], honouring nesting. Returns the body and the next index. */
  private fun collect(
      lines: List<String>,
      start: Int,
      open: String,
      close: String
  ): Pair<List<String>, Int> {
    var depth = 1
    var i = start
    val body = ArrayList<String>()
    while (i < lines.size) {
      val t = lines[i].trim()
      val w = t.substringBefore(' ').substringBefore('\t')
      if (w == open) depth++
      if (w == close) {
        depth--
        if (depth == 0) return body to i + 1
      }
      body += lines[i]
      i++
    }
    errors += "unterminated $open"
    return body to i
  }

  private fun defineMacro(header: String, body: List<String>) {
    val name = header.substringBefore(' ').substringBefore('\t').substringBefore(',').trim()
    val paramText = header.substring(name.length).trim()
    val params =
        if (paramText.isEmpty()) emptyList()
        else
            splitParams(paramText).map { p ->
              var n = p.trim()
              var def: String? = null
              var vararg = false
              val eq = n.indexOf('=')
              if (eq >= 0) {
                def = n.substring(eq + 1).trim()
                n = n.substring(0, eq).trim()
              }
              if (n.endsWith(":req")) n = n.removeSuffix(":req")
              if (n.endsWith(":vararg")) {
                n = n.removeSuffix(":vararg")
                vararg = true
              }
              Param(n.trim(), def, vararg)
            }
    macros[name] = AsmMacro(name, params, body)
  }

  private fun splitParams(s: String): List<String> {
    // Parameters are separated by commas or whitespace (outside a default value).
    val parts = if (s.contains(',')) s.split(',') else s.split(Regex("\\s+"))
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
  }

  /** Splits macro / directive arguments on top-level commas. */
  private fun splitArgs(s: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var depth = 0
    var inStr = false
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (inStr) {
        cur.append(c)
        if (c == '\\' && i + 1 < s.length) {
          cur.append(s[i + 1])
          i += 2
          continue
        }
        if (c == '"') inStr = false
      } else
          when (c) {
            '"' -> {
              inStr = true
              cur.append(c)
            }
            '(' -> {
              depth++
              cur.append(c)
            }
            ')' -> {
              depth--
              cur.append(c)
            }
            ',' ->
                if (depth == 0) {
                  out += cur.toString().trim()
                  cur.clear()
                } else cur.append(c)
            else -> cur.append(c)
          }
      i++
    }
    if (cur.isNotBlank() || out.isNotEmpty()) out += cur.toString().trim()
    return out
  }

  private fun statement(lineIn: String, file: File) {
    var line = lineIn
    // Labels, possibly several or followed by a statement.
    while (true) {
      val m = LABEL.matchAt(line, 0) ?: break
      defineLabel(m.groupValues[1])
      line = line.substring(m.range.last + 1).trim()
      if (line.isEmpty()) return
    }
    if (line.startsWith("#")) return
    val assign = ASSIGN.matchEntire(line)
    if (assign != null) {
      asmSyms[assign.groupValues[1]] = evalLoose(assign.groupValues[2])
      return
    }
    val word = line.substringBefore(' ').substringBefore('\t')
    val rest = line.substring(word.length).trim()
    if (word.startsWith(".")) {
      directive(word, rest, file)
      return
    }
    val macro = macros[word]
    if (macro == null) {
      errors += "unknown instruction '$word' in ${file.name}"
      return
    }
    expand(macro, rest, file)
  }

  private fun defineLabel(name: String) {
    if (pass == 2 && knownLabels[name] != null && knownLabels[name] != here) {
      errors += "label $name moved between passes"
    }
    labels[name] = here
    currentText = null
  }

  private fun expand(macro: AsmMacro, argText: String, file: File) {
    var args = splitArgs(argText)
    if (args.size == 1 && macro.params.size > 1 && args[0].contains(Regex("\\s"))) {
      args = args[0].split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val values = HashMap<String, String>()
    val positional = ArrayList<String>()
    for (a in args) {
      val kw = Regex("""^(\w+)\s*=(?!=)\s*(.*)$""").matchEntire(a)
      if (kw != null && macro.params.any { it.name == kw.groupValues[1] }) {
        values[kw.groupValues[1]] = kw.groupValues[2]
      } else positional += a
    }
    var pi = 0
    for (p in macro.params) {
      if (values.containsKey(p.name)) continue
      if (p.vararg) {
        values[p.name] = positional.drop(pi).joinToString(", ")
        pi = positional.size
      } else if (pi < positional.size) {
        values[p.name] = positional[pi++]
      } else values[p.name] = p.default ?: ""
    }
    macroCounter++
    val counter = macroCounter.toString()
    val names = macro.params.map { it.name }.sortedByDescending { it.length }
    val body =
        macro.body.map { l ->
          var s = l
          if (s.indexOf('\\') >= 0) {
            for (n in names) s = s.replace("\\$n", values[n] ?: "")
            s = s.replace("\\()", "").replace("\\@", counter)
          }
          s
        }
    processLines(body, file)
  }

  // ------------------------------------------------------------------ directives

  private fun directive(word: String, rest: String, file: File) {
    when (word) {
      ".include" -> {
        val path = rest.trim().removeSurrounding("\"")
        processFile(File(root, path))
      }
      ".set",
      ".equ",
      ".equiv" -> {
        val args = splitArgs(rest)
        if (args.size >= 2) asmSyms[args[0]] = evalLoose(args[1])
      }
      ".byte" -> splitArgs(rest).forEach { emit(it, 1) }
      ".2byte",
      ".hword",
      ".short" -> splitArgs(rest).forEach { emit(it, 2) }
      ".4byte",
      ".word",
      ".long",
      ".4bye" -> splitArgs(rest).forEach { emit(it, 4) }
      ".string",
      ".ascii",
      ".asciz" -> splitArgs(rest).forEach { text(it, false) }
      ".braille" -> splitArgs(rest).forEach { text(it, true) }
      ".space",
      ".skip" -> {
        val args = splitArgs(rest)
        val n = (evalLoose(args[0]) as? Num)?.v?.toInt() ?: 0
        val fill = args.getOrNull(1)?.let { (evalLoose(it) as? Num)?.v?.toInt() } ?: 0
        raw(ByteArray(n) { fill.toByte() })
      }
      ".fill" -> {
        val args = splitArgs(rest)
        val n = (evalLoose(args[0]) as? Num)?.v?.toInt() ?: 0
        val size = args.getOrNull(1)?.let { (evalLoose(it) as? Num)?.v?.toInt() } ?: 1
        raw(ByteArray(n * size))
      }
      ".align",
      ".balign",
      ".p2align" -> {
        val n = (evalLoose(splitArgs(rest)[0]) as? Num)?.v?.toInt() ?: 0
        val unit = if (word == ".balign") n else 1 shl n
        if (unit > 1) {
          val pad = (unit - (image.size() % unit)) % unit
          if (pad > 0) raw(ByteArray(pad))
        }
      }
      ".purgem" -> macros.remove(rest.trim())
      ".error" -> errors += "assembler .error in ${file.name}: $rest"
      else -> {
        // .section, .global, .type, .size, .thumb, .warning ... carry no script data.
      }
    }
  }

  private fun raw(bytes: ByteArray) {
    image.write(bytes)
    currentText = null
  }

  private fun emit(expr: String, size: Int) {
    if (expr.isBlank()) return
    val addr = here
    val v = evalLoose(expr)
    val n =
        when (v) {
          is Num -> v.v
          is Sym -> {
            if (pass == 2) {
              if (size == 4) externs[addr] = v.name
              else errors += "unresolved ${v.name} in .${size}byte"
            }
            0L
          }
        }
    val b = ByteArray(size) { ((n shr (8 * it)) and 0xFF).toByte() }
    raw(b)
  }

  private fun text(quoted: String, braille: Boolean) {
    val s = quoted.trim()
    if (!s.startsWith("\"")) return
    val content = s.substring(1, s.lastIndexOf('"').coerceAtLeast(1))
    val cur = currentText
    if (cur != null && cur.braille == braille) {
      cur.content.append(content)
      return
    }
    val t = AsmText(here, braille)
    t.content.append(content)
    texts[t.addr] = t
    // The text occupies one byte; nothing ever reads its encoded form.
    image.write(0xFF)
    currentText = t
  }

  // ------------------------------------------------------------------ cpp

  private fun cppDirective(
      line: String,
      lines: List<String>,
      index: Int,
      cond: ArrayDeque<Cond>
  ): Int {
    var i = index
    var l = line
    while (l.endsWith("\\") && i < lines.size) {
      l = l.removeSuffix("\\") + " " + lines[i].trim()
      i++
    }
    val d = l.removePrefix("#").trim()
    val kw = d.substringBefore(' ').substringBefore('\t')
    val rest = d.substring(kw.length).trim()
    when (kw) {
      "ifdef" -> open(cond, active(cond) && cppDefined(rest))
      "ifndef" -> open(cond, active(cond) && !cppDefined(rest))
      "if" -> open(cond, active(cond) && cppTruth(rest))
      "elif" -> elseIf(cond) { cppTruth(rest) }
      "else" -> orElse(cond)
      "endif" -> cond.removeLastOrNull()
      else ->
          if (active(cond))
              when (kw) {
                "include" -> include(rest.removeSurrounding("\"").removeSurrounding("<", ">"))
                "define" -> define(rest)
                "undef" -> cdefs.remove(rest.trim())
              }
    }
    return i
  }

  private fun cppDefined(name: String) = cdefs.containsKey(name.trim())

  private fun cppTruth(expr: String): Boolean =
      try {
        when (val v = Expr.eval(expr, cppResolver)) {
          is Num -> v.v != 0L
          is Sym -> false
        }
      } catch (e: ExprException) {
        false
      }

  // cpp treats an undefined identifier as 0.
  private val cppResolver =
      object : Resolver {
        override fun resolve(name: String): Value? =
            if (name == "defined") null else this@ScriptAssembler.resolve(name) ?: Num(0)

        override fun function(name: String) = this@ScriptAssembler.function(name)

        override fun isDefined(name: String): Boolean = cdefs.containsKey(name)
      }

  private fun define(rest: String) {
    val m = DEFINE.matchEntire(rest) ?: return
    val name = m.groupValues[1]
    val params = m.groups[2]?.value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    cdefs[name] =
        CMacro(if (m.groups[2] != null) params ?: emptyList() else null, m.groupValues[3].trim())
  }

  private fun include(path: String) {
    val f = listOf(File(root, "include/$path"), File(root, path)).firstOrNull { it.isFile }
    if (f == null) return // generated headers (map_groups.h, layouts.h) are synthesised instead
    if (!parsedHeaders.add(f.canonicalPath)) return
    parseHeader(f)
  }

  /** A C header: defines, conditionals, includes and simple enums. Everything else is skipped. */
  private fun parseHeader(file: File) {
    val lines = cleanLines(file.readText())
    val cond = ArrayDeque<Cond>()
    var i = 0
    var enumBody: StringBuilder? = null
    while (i < lines.size) {
      val line = lines[i].trim()
      i++
      if (line.startsWith("#")) {
        i = cppDirective(line, lines, i, cond)
        continue
      }
      if (!active(cond)) continue
      if (enumBody == null && Regex("""^(typedef\s+)?enum\b[^;]*$""").containsMatchIn(line)) {
        enumBody = StringBuilder(line.substringAfter('{', ""))
        if (!line.contains('{')) enumBody.append("")
        if (line.contains('}')) {
          defineEnum(enumBody.toString().substringBefore('}'))
          enumBody = null
        }
        continue
      }
      if (enumBody != null) {
        if (line.contains('}')) {
          enumBody.append(' ').append(line.substringBefore('}'))
          defineEnum(enumBody.toString().substringAfter('{'))
          enumBody = null
        } else enumBody.append(' ').append(line)
      }
    }
  }

  private fun defineEnum(body: String) {
    var next = 0L
    for (entry in splitArgs(body.replace("{", ""))) {
      val e = entry.trim()
      if (e.isEmpty()) continue
      val name = e.substringBefore('=').trim()
      if (!IDENT.matches(name)) continue
      val value =
          if (e.contains('=')) (evalLoose(e.substringAfter('=')) as? Num)?.v ?: next else next
      if (!cdefs.containsKey(name)) cdefs[name] = CMacro(null, value.toString())
      next = value + 1
    }
  }

  companion object {
    const val BASE = 0x08100000
    private val IDENT = Regex("""[A-Za-z_][\w]*""")
    private val LABEL = Regex("""^([A-Za-z_.$][\w.$]*)::?(?=\s|$)""")
    private val ASSIGN = Regex("""^([A-Za-z_][\w]*)\s*=(?!=)\s*(.+)$""")
    private val DEFINE = Regex("""^([A-Za-z_]\w*)(?:\(([^)]*)\))?\s*(.*)$""")
  }
}
