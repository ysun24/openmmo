package de.fiereu.openmmo.server.game.script

import dagger.Module
import dagger.multibindings.Multibinds

/**
 * A decomp script `special` / `specialvar` implementation, looked up by the decomp's function name
 * (e.g. `ShouldTryRematchBattle`, `ChoosePartyMon`, `ShowContestWinner`).
 *
 * Contract shared by every round-5 lane:
 * - [names] lists every decomp special name this handler answers. A name may be claimed by exactly
 *   one handler; the registry refuses duplicates at start-up.
 * - [run] receives the running script's context and the special's name and returns the value the
 *   decomp writes into the `specialvar` destination (or into `VAR_RESULT` for plain `special` calls
 *   that set it). Return 0 when the decomp special returns nothing.
 * - A special that opens UI suspends until the player has finished with it, exactly like the
 *   decomp's `waitstate` that follows it.
 */
interface ScriptSpecial {
  val names: Set<String>

  suspend fun run(ctx: ScriptContext, name: String): Int
}

/** Lane modules add handlers with `@Binds @IntoSet fun x(h: MyHandler): ScriptSpecial`. */
@Module
interface ScriptSpecialModule {
  @Multibinds fun specials(): Set<ScriptSpecial>
}

/** Name -> handler, built once from every bound [ScriptSpecial]. */
class ScriptSpecials(handlers: Set<ScriptSpecial>) {
  private val byName: Map<String, ScriptSpecial> = buildMap {
    for (h in handlers) for (n in h.names) {
      val prev = put(n, h)
      check(prev == null) {
        "special $n is claimed by both ${prev!!::class.simpleName} and ${h::class.simpleName}"
      }
    }
  }

  fun forName(name: String): ScriptSpecial? = byName[name]

  val names: Set<String>
    get() = byName.keys
}
