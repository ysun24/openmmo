package de.fiereu.openmmo.codegen.script.asm

/**
 * A small C / GNU-as expression evaluator, enough for the decomp's script operands and `.if`
 * conditions: integer literals, identifiers (resolved through [Resolver]), function-like C macros
 * (expanded textually), and the C operators with C precedence.
 *
 * A value is either known ([Num]) or [Sym]bolic, for a label whose address is not known yet or an
 * external symbol. Arithmetic on a symbolic value stays symbolic.
 */
sealed interface Value

data class Num(val v: Long) : Value

data class Sym(val name: String) : Value

interface Resolver {
  /** The value of [name], or null when it is not defined anywhere. */
  fun resolve(name: String): Value?

  /** A function-like C macro named [name], or null. */
  fun function(name: String): CMacro? = null

  /** For cpp's `defined(name)`. */
  fun isDefined(name: String): Boolean = resolve(name) != null || function(name) != null
}

/** A C `#define`. [params] is null for an object-like macro. */
data class CMacro(val params: List<String>?, val body: String)

class ExprException(msg: String) : RuntimeException(msg)

object Expr {
  private val tokenRe =
      Regex(
          """\s*(0[xX][0-9a-fA-F]+|0[bB][01]+|\d+|'(?:\\.|[^'])'|[A-Za-z_.$][\w.$]*|<<|>>|<=|>=|==|!=|&&|\|\||[-+*/%&|^~!<>()?:,])""")

  fun tokenize(s: String): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < s.length) {
      if (s[i].isWhitespace()) {
        i++
        continue
      }
      val m = tokenRe.matchAt(s, i) ?: throw ExprException("bad token at '${s.substring(i)}' in '$s'")
      out += m.groupValues[1]
      i = m.range.last + 1
    }
    return out
  }

  fun eval(s: String, r: Resolver): Value {
    val toks = expand(tokenize(s), r, 0)
    if (toks.isEmpty()) throw ExprException("empty expression")
    val p = Parser(toks, r)
    val v = p.ternary()
    if (p.pos != toks.size) throw ExprException("trailing tokens in '$s'")
    return v
  }

  /** Expands function-like C macros (object-like ones are resolved lazily as identifiers). */
  private fun expand(toks: List<String>, r: Resolver, depth: Int): List<String> {
    if (depth > 20) return toks
    val out = ArrayList<String>()
    var i = 0
    var changed = false
    while (i < toks.size) {
      val t = toks[i]
      val fn = if (t.first().isLetter() || t.first() == '_') r.function(t) else null
      if (fn?.params != null && i + 1 < toks.size && toks[i + 1] == "(") {
        // Collect the arguments.
        var j = i + 2
        var level = 0
        val args = ArrayList<MutableList<String>>()
        var cur = mutableListOf<String>()
        while (j < toks.size) {
          val x = toks[j]
          if (x == "(") level++
          if (x == ")") {
            if (level == 0) break
            level--
          }
          if (x == "," && level == 0) {
            args += cur
            cur = mutableListOf()
          } else cur += x
          j++
        }
        args += cur
        val body = tokenize(fn.body)
        val params = fn.params
        for (b in body) {
          val idx = params.indexOf(b)
          if (idx >= 0) {
            out += "("
            out += args.getOrElse(idx) { mutableListOf() }
            out += ")"
          } else out += b
        }
        i = j + 1
        changed = true
      } else {
        out += t
        i++
      }
    }
    return if (changed) expand(out, r, depth + 1) else out
  }

  private class Parser(val t: List<String>, val r: Resolver) {
    var pos = 0

    fun peek() = t.getOrNull(pos)

    fun next() = t[pos++]

    fun ternary(): Value {
      val c = binary(0)
      if (peek() == "?") {
        next()
        val a = ternary()
        if (next() != ":") throw ExprException("expected :")
        val b = ternary()
        return when (c) {
          is Num -> if (c.v != 0L) a else b
          is Sym -> a
        }
      }
      return c
    }

    private val levels =
        listOf(
            setOf("||"),
            setOf("&&"),
            setOf("|"),
            setOf("^"),
            setOf("&"),
            setOf("==", "!="),
            setOf("<", ">", "<=", ">="),
            setOf("<<", ">>"),
            setOf("+", "-"),
            setOf("*", "/", "%"),
        )

    fun binary(level: Int): Value {
      if (level == levels.size) return unary()
      var lhs = binary(level + 1)
      while (peek() in levels[level]) {
        val op = next()
        val rhs = binary(level + 1)
        lhs = apply(op, lhs, rhs)
      }
      return lhs
    }

    fun apply(op: String, a: Value, b: Value): Value {
      if (a is Sym || b is Sym) {
        // A label is never zero, which is all `.if \script == FALSE` style tests need.
        val symA = a as? Sym
        val symB = b as? Sym
        return when (op) {
          "==" -> Num(if (symA != null && symB != null) (if (symA == symB) 1 else 0) else 0)
          "!=" -> Num(if (symA != null && symB != null) (if (symA == symB) 0 else 1) else 1)
          "&&" -> Num(if ((a is Num && a.v == 0L) || (b is Num && b.v == 0L)) 0 else 1)
          "||" -> Num(1)
          else -> symA ?: symB!!
        }
      }
      val x = (a as Num).v
      val y = (b as Num).v
      return Num(
          when (op) {
            "||" -> if (x != 0L || y != 0L) 1 else 0
            "&&" -> if (x != 0L && y != 0L) 1 else 0
            "|" -> x or y
            "^" -> x xor y
            "&" -> x and y
            "==" -> if (x == y) 1 else 0
            "!=" -> if (x != y) 1 else 0
            "<" -> if (x < y) 1 else 0
            ">" -> if (x > y) 1 else 0
            "<=" -> if (x <= y) 1 else 0
            ">=" -> if (x >= y) 1 else 0
            "<<" -> x shl y.toInt()
            ">>" -> x shr y.toInt()
            "+" -> x + y
            "-" -> x - y
            "*" -> x * y
            "/" -> if (y == 0L) 0 else x / y
            "%" -> if (y == 0L) 0 else x % y
            else -> throw ExprException("op $op")
          })
    }

    fun unary(): Value {
      return when (peek()) {
        "-" -> {
          next()
          neg(unary())
        }
        "+" -> {
          next()
          unary()
        }
        "~" -> {
          next()
          when (val v = unary()) {
            is Num -> Num(v.v.inv())
            else -> v
          }
        }
        "!" -> {
          next()
          when (val v = unary()) {
            is Num -> Num(if (v.v == 0L) 1 else 0)
            else -> Num(0)
          }
        }
        else -> primary()
      }
    }

    fun neg(v: Value) = if (v is Num) Num(-v.v) else v

    fun primary(): Value {
      val tok = peek() ?: throw ExprException("unexpected end")
      next()
      if (tok == "(") {
        // A C cast such as (u16) is dropped.
        if (pos + 1 < t.size && t[pos + 1] == ")" && t[pos] in CASTS) {
          pos += 2
          return unary()
        }
        val v = ternary()
        if (next() != ")") throw ExprException("expected )")
        return v
      }
      if (tok[0].isDigit()) return Num(parseNumber(tok))
      if (tok[0] == '\'') {
        val c = tok.substring(1, tok.length - 1)
        return Num((if (c.startsWith("\\")) c[1] else c[0]).code.toLong())
      }
      if (tok == "defined") {
        val paren = peek() == "("
        if (paren) next()
        val name = next()
        if (paren) next()
        return Num(if (r.isDefined(name)) 1 else 0)
      }
      return r.resolve(tok) ?: throw ExprException("undefined symbol $tok")
    }

    companion object {
      val CASTS = setOf("u8", "u16", "u32", "s8", "s16", "s32", "int", "bool8", "bool32")
    }
  }

  fun parseNumber(tok: String): Long =
      when {
        tok.startsWith("0x") || tok.startsWith("0X") -> tok.substring(2).toLong(16)
        tok.startsWith("0b") || tok.startsWith("0B") -> tok.substring(2).toLong(2)
        tok.length > 1 && tok.startsWith("0") -> tok.toLong(8)
        else -> tok.toLong()
      }
}
