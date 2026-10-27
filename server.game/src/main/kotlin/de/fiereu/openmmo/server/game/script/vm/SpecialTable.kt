package de.fiereu.openmmo.server.game.script.vm

import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.script.ScriptSpecial

/** A [ScriptSpecial] built from a name -> body table; each body returns the special's value. */
abstract class SpecialTable : ScriptSpecial {
  private val table = LinkedHashMap<String, suspend (ScriptContext) -> Int>()

  /** Registers [names] with one body. */
  protected fun on(vararg names: String, body: suspend (ScriptContext) -> Int) {
    for (n in names) check(table.put(n, body) == null) { "special $n registered twice" }
  }

  /** Specials whose effect is purely visual on the GBA and needs nothing here (returns 0). */
  protected fun noop(vararg names: String) = on(*names) { 0 }

  override val names: Set<String>
    get() = table.keys

  override suspend fun run(ctx: ScriptContext, name: String): Int = table[name]?.invoke(ctx) ?: 0
}
