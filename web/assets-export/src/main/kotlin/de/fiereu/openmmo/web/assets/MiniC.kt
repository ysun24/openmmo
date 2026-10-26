package de.fiereu.openmmo.web.assets

/**
 * A tiny interpreter for the C subset the decomps' `src/tileset_anims.c` is written in:
 * `if`/`else`, blocks, typed locals (`u8`/`u16` wrap like on the GBA), assignments (`= += -= %=
 * /=`), integer arithmetic and comparisons, casts, array indexing, calls. Running the animation
 * callbacks for every counter value yields exactly which frame each VRAM tile range gets at which
 * tick, without hand-copying the timing tables.
 *
 * Values are `Long` (numbers) or `String` (a symbol: a frame's graphics array or a pointer table).
 * Unknown functions return 0; a statement that cannot be evaluated is skipped.
 */
class MiniC(
    source: String,
    private val constants: Map<String, Long>,
    /** Built-in functions: name -> implementation over evaluated arguments; null = not built in. */
    private val builtins: (String, List<Any?>) -> Any?,
) {
  private val text = stripComments(source)
  /** Functions outside the subset (only ever a problem if an animation callback calls one). */
  val failures = mutableListOf<String>()

  /** Top-level pointer tables (`... *const NAME[] = { a, b, ... };`), elements unevaluated. */
  val arrays: Map<String, List<Expr>> = run {
    val out = LinkedHashMap<String, List<Expr>>()
    Regex("""\*\s*const\s+(\w+)\s*\[\s*\w*\s*]\s*=\s*\{(.*?)};""", RegexOption.DOT_MATCHES_ALL)
        .findAll(text)
        .forEach { m ->
          val body = m.groupValues[2].trim()
          if (body.isEmpty()) return@forEach
          val p = Parser(tokenize(body))
          val items = mutableListOf<Expr>()
          while (!p.atEnd()) {
            items += p.expr()
            if (!p.accept(",")) break
          }
          out[m.groupValues[1]] = items
        }
    out
  }

  class Function(val name: String, val params: List<Pair<String, String>>, val body: Stmt)

  val functions: Map<String, Function> = run {
    val out = LinkedHashMap<String, Function>()
    val head = Regex("""(?m)^(?:static\s+)?void\s+(\w+)\s*\(([^)]*)\)\s*\{""")
    head.findAll(text).forEach { m ->
      var depth = 1
      var i = m.range.last + 1
      while (i < text.length && depth > 0) {
        when (text[i]) {
          '{' -> depth++
          '}' -> depth--
        }
        i++
      }
      val body = text.substring(m.range.last + 1, i - 1)
      val params =
          m.groupValues[2]
              .split(',')
              .map { it.trim() }
              .filter { it.isNotEmpty() && it != "void" }
              .map {
                val parts = it.split(Regex("\\s+"))
                parts.first() to parts.last()
              }
      runCatching {
            val p = Parser(tokenize(body))
            val stmts = mutableListOf<Stmt>()
            while (!p.atEnd()) stmts += p.stmt()
            out[m.groupValues[1]] = Function(m.groupValues[1], params, Stmt.Block(stmts))
          }
          .onFailure { failures += "function ${m.groupValues[1]}: ${it.message}" }
    }
    out
  }

  fun call(name: String, args: List<Any?>): Any? {
    builtins(name, args)?.let {
      return it
    }
    val f = functions[name] ?: return 0L
    val locals = HashMap<String, Pair<String, Any?>>()
    f.params.forEachIndexed { i, (type, n) -> locals[n] = type to mask(type, args.getOrNull(i)) }
    try {
      exec(f.body, locals)
    } catch (_: Return) {}
    return 0L
  }

  private object Return : RuntimeException() {
    private fun readResolve(): Any = Return
  }

  private fun exec(s: Stmt, locals: MutableMap<String, Pair<String, Any?>>) {
    when (s) {
      is Stmt.Block -> s.items.forEach { exec(it, locals) }
      is Stmt.If -> {
        val c = runCatching { truthy(eval(s.cond, locals)) }.getOrNull() ?: return
        if (c) exec(s.then, locals) else s.otherwise?.let { exec(it, locals) }
      }
      is Stmt.Decl -> locals[s.name] = s.type to mask(s.type, s.init?.let { safe(it, locals) })
      is Stmt.ExprStmt -> safe(s.expr, locals)
      Stmt.Ret -> throw Return
    }
  }

  private fun safe(e: Expr, locals: MutableMap<String, Pair<String, Any?>>): Any? =
      try {
        eval(e, locals)
      } catch (r: Return) {
        throw r
      } catch (_: Exception) {
        null
      }

  private fun truthy(v: Any?) =
      when (v) {
        null -> false
        is Long -> v != 0L
        else -> true
      }

  private fun num(v: Any?): Long = v as? Long ?: error("not a number: $v")

  private fun mask(type: String, v: Any?): Any? =
      when {
        v !is Long -> v
        type == "u8" -> v and 0xFF
        type == "u16" -> v and 0xFFFF
        type == "u32" -> v and 0xFFFFFFFFL
        type == "s8" -> v.toByte().toLong()
        type == "s16" -> v.toShort().toLong()
        type == "int" || type == "s32" -> v.toInt().toLong()
        else -> v
      }

  private fun eval(e: Expr, locals: MutableMap<String, Pair<String, Any?>>): Any? =
      when (e) {
        is Expr.Num -> e.v
        is Expr.Id ->
            when {
              e.name in locals -> locals.getValue(e.name).second
              e.name in constants -> constants.getValue(e.name)
              else -> e.name
            }
        is Expr.Cast -> mask(e.type, eval(e.inner, locals))
        is Expr.Un -> {
          val v = eval(e.inner, locals)
          when (e.op) {
            "-" -> -num(v)
            "!" -> if (truthy(v)) 0L else 1L
            "~" -> num(v).inv()
            else -> error("op ${e.op}")
          }
        }
        is Expr.Bin -> {
          if (e.op == "&&") {
            if (!truthy(eval(e.a, locals))) 0L else if (truthy(eval(e.b, locals))) 1L else 0L
          } else if (e.op == "||") {
            if (truthy(eval(e.a, locals)) || truthy(eval(e.b, locals))) 1L else 0L
          } else {
            val a = eval(e.a, locals)
            val b = eval(e.b, locals)
            if (e.op == "==" || e.op == "!=") {
              val eq = a == b
              if ((e.op == "==") == eq) 1L else 0L
            } else {
              val x = num(a)
              val y = num(b)
              when (e.op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> x / y
                "%" -> x % y
                "<" -> if (x < y) 1L else 0L
                ">" -> if (x > y) 1L else 0L
                "<=" -> if (x <= y) 1L else 0L
                ">=" -> if (x >= y) 1L else 0L
                "&" -> x and y
                "|" -> x or y
                "^" -> x xor y
                "<<" -> x shl y.toInt()
                ">>" -> x shr y.toInt()
                else -> error("op ${e.op}")
              }
            }
          }
        }
        is Expr.Index -> {
          val arr = eval(e.arr, locals) as? String ?: error("index of non-array")
          val items = arrays[arr] ?: error("unknown array $arr")
          val i = num(eval(e.index, locals)).toInt()
          eval(items.getOrNull(i) ?: error("index $i out of $arr"), locals)
        }
        is Expr.Call -> {
          val args = e.args.map { eval(it, locals) }
          when (e.name) {
            "ARRAY_COUNT" -> (arrays[args[0] as? String] ?: error("ARRAY_COUNT")).size.toLong()
            "min" -> minOf(num(args[0]), num(args[1]))
            "max" -> maxOf(num(args[0]), num(args[1]))
            else -> call(e.name, args)
          }
        }
        is Expr.Assign -> {
          val cur = locals[e.name] ?: error("assign to non-local ${e.name}")
          val v = eval(e.value, locals)
          val nv =
              when (e.op) {
                "=" -> v
                "+=" -> num(cur.second) + num(v)
                "-=" -> num(cur.second) - num(v)
                "*=" -> num(cur.second) * num(v)
                "/=" -> num(cur.second) / num(v)
                "%=" -> num(cur.second) % num(v)
                else -> error("op ${e.op}")
              }
          locals[e.name] = cur.first to mask(cur.first, nv)
          locals.getValue(e.name).second
        }
        is Expr.IncDec -> {
          val cur = locals[e.name] ?: error("inc of non-local ${e.name}")
          locals[e.name] = cur.first to mask(cur.first, num(cur.second) + e.delta)
          locals.getValue(e.name).second
        }
      }

  // ------------------------------------------------------------------------------------- syntax

  sealed interface Expr {
    data class Num(val v: Long) : Expr

    data class Id(val name: String) : Expr

    data class Bin(val op: String, val a: Expr, val b: Expr) : Expr

    data class Un(val op: String, val inner: Expr) : Expr

    /** [type] is the integer type, or "" for a pointer cast (value unchanged). */
    data class Cast(val type: String, val inner: Expr) : Expr

    data class Index(val arr: Expr, val index: Expr) : Expr

    data class Call(val name: String, val args: List<Expr>) : Expr

    data class Assign(val op: String, val name: String, val value: Expr) : Expr

    data class IncDec(val name: String, val delta: Long) : Expr
  }

  sealed interface Stmt {
    data class Block(val items: List<Stmt>) : Stmt

    data class If(val cond: Expr, val then: Stmt, val otherwise: Stmt?) : Stmt

    data class Decl(val type: String, val name: String, val init: Expr?) : Stmt

    data class ExprStmt(val expr: Expr) : Stmt

    data object Ret : Stmt
  }

  private class Parser(val t: List<String>) {
    var i = 0

    fun atEnd() = i >= t.size

    fun peek(o: Int = 0) = t.getOrNull(i + o)

    fun accept(s: String) = (peek() == s).also { if (it) i++ }

    fun expect(s: String) {
      if (!accept(s)) error("expected '$s' at '${peek()}'")
    }

    fun stmt(): Stmt {
      val tok = peek() ?: error("eof")
      return when {
        tok == "{" -> {
          i++
          val items = mutableListOf<Stmt>()
          while (!accept("}")) items += stmt()
          Stmt.Block(items)
        }
        tok == ";" -> {
          i++
          Stmt.Block(emptyList())
        }
        tok == "if" -> {
          i++
          expect("(")
          val c = expr()
          expect(")")
          val th = stmt()
          val el = if (accept("else")) stmt() else null
          Stmt.If(c, th, el)
        }
        tok == "return" -> {
          i++
          while (!accept(";")) i++
          Stmt.Ret
        }
        tok in TYPES && peek(1)?.first()?.isJavaIdentifierStart() == true -> {
          i++
          val name = t[i++]
          val init = if (accept("=")) expr() else null
          expect(";")
          Stmt.Decl(tok, name, init)
        }
        else -> {
          val e = expr()
          expect(";")
          Stmt.ExprStmt(e)
        }
      }
    }

    fun expr(): Expr {
      val a = binary(0)
      if (a is Expr.Id && peek() in ASSIGN) {
        val op = t[i++]
        return Expr.Assign(op, a.name, expr())
      }
      return a
    }

    private fun binary(minPrec: Int): Expr {
      var a = unary()
      while (true) {
        val op = peek() ?: break
        val p = PREC[op] ?: break
        if (p < minPrec) break
        i++
        a = Expr.Bin(op, a, binary(p + 1))
      }
      return a
    }

    private fun unary(): Expr {
      val tok = peek() ?: error("eof")
      if (tok == "-" || tok == "!" || tok == "~") {
        i++
        return Expr.Un(tok, unary())
      }
      if (tok == "++" || tok == "--") {
        i++
        val id = postfix() as? Expr.Id ?: error("++ on non-identifier")
        return Expr.IncDec(id.name, if (tok == "++") 1 else -1)
      }
      if (tok == "(" && (peek(1) in TYPES || peek(1) == "const")) {
        i++
        var type = ""
        while (!accept(")")) {
          val x = t[i++]
          if (x in TYPES) type = x
          if (x == "*") type = "*"
        }
        return Expr.Cast(if (type == "*") "" else type, unary())
      }
      return postfix()
    }

    private fun postfix(): Expr {
      var e = primary()
      while (true) {
        e =
            when (peek()) {
              "[" -> {
                i++
                val idx = expr()
                expect("]")
                Expr.Index(e, idx)
              }
              "(" -> {
                i++
                val args = mutableListOf<Expr>()
                if (!accept(")")) {
                  do args += expr() while (accept(","))
                  expect(")")
                }
                Expr.Call((e as? Expr.Id)?.name ?: error("call of non-name"), args)
              }
              "++",
              "--" -> {
                val d = if (t[i++] == "++") 1L else -1L
                Expr.IncDec((e as? Expr.Id)?.name ?: error("++ on non-identifier"), d)
              }
              else -> return e
            }
      }
    }

    private fun primary(): Expr {
      val tok = t.getOrNull(i++) ?: error("eof")
      return when {
        tok == "(" -> expr().also { expect(")") }
        tok[0].isDigit() -> Expr.Num(parseNum(tok))
        tok[0].isJavaIdentifierStart() -> Expr.Id(tok)
        else -> error("unexpected '$tok'")
      }
    }
  }

  companion object {
    private val TYPES = setOf("u8", "u16", "u32", "s8", "s16", "s32", "int", "bool8", "void")
    private val ASSIGN = setOf("=", "+=", "-=", "*=", "/=", "%=")
    private val PREC =
        mapOf(
            "||" to 1,
            "&&" to 2,
            "|" to 3,
            "^" to 4,
            "&" to 5,
            "==" to 6,
            "!=" to 6,
            "<" to 7,
            ">" to 7,
            "<=" to 7,
            ">=" to 7,
            "<<" to 8,
            ">>" to 8,
            "+" to 9,
            "-" to 9,
            "*" to 10,
            "/" to 10,
            "%" to 10,
        )
    private val MULTI =
        listOf(
            "<<=",
            ">>=",
            "==",
            "!=",
            "<=",
            ">=",
            "&&",
            "||",
            "+=",
            "-=",
            "*=",
            "/=",
            "%=",
            "++",
            "--",
            "->",
            "<<",
            ">>")

    fun parseNum(s: String): Long {
      val v = s.trimEnd('u', 'U', 'l', 'L')
      return if (v.startsWith("0x") || v.startsWith("0X")) v.substring(2).toLong(16) else v.toLong()
    }

    fun stripComments(s: String): String =
        s.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), "")

    fun tokenize(s: String): List<String> {
      val out = mutableListOf<String>()
      var i = 0
      while (i < s.length) {
        val c = s[i]
        when {
          c.isWhitespace() -> i++
          c.isJavaIdentifierStart() || c.isDigit() -> {
            val st = i
            while (i < s.length && (s[i].isJavaIdentifierPart())) i++
            out += s.substring(st, i)
          }
          c == '"' -> {
            val st = i++
            while (i < s.length && s[i] != '"') i++
            out += s.substring(st, ++i)
          }
          else -> {
            val m = MULTI.firstOrNull { s.startsWith(it, i) }
            if (m != null) {
              out += m
              i += m.length
            } else {
              out += c.toString()
              i++
            }
          }
        }
      }
      return out
    }
  }
}
