package de.fiereu.openmmo.server.game.minigame

import de.fiereu.openmmo.server.game.minigame.gamecorner.GameCornerScripts
import de.fiereu.openmmo.server.game.minigame.gamecorner.GameCornerService
import de.fiereu.openmmo.server.game.script.ScriptContext
import de.fiereu.openmmo.server.game.script.ScriptSpecial
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

typealias SpecialFn = suspend (ScriptContext) -> Int

/** A [ScriptSpecial] given as a name -> function table. */
abstract class SpecialTable : ScriptSpecial {
  protected abstract val table: Map<String, SpecialFn>

  override val names: Set<String>
    get() = table.keys

  override suspend fun run(ctx: ScriptContext, name: String): Int = table.getValue(name)(ctx)
}

/**
 * The Game Corner's decomp specials. `playslotmachine` is a script command, not a special; the
 * interpreter reaches it as the special name "PlaySlotMachine" with the machine id in VAR_RESULT.
 */
@Singleton
class MinigameSpecials
@Inject
constructor(
    private val corner: Provider<GameCornerService>,
    private val cornerScripts: Provider<GameCornerScripts>,
    private val save: MinigameSave,
) : SpecialTable() {
  override val table: Map<String, SpecialFn> =
      mapOf(
          "GetRandomSlotMachineId" to { _ -> cornerScripts.get().randomFrlgMachine() },
          "GetSlotMachineId" to
              { ctx ->
                cornerScripts.get().hoennMachine(ctx, ctx.specialVar(0x4))
              },
          "PlaySlotMachine" to
              { ctx ->
                corner.get().playSlots(ctx.session, ctx.state, ctx.result)
                0
              },
          "PlayRoulette" to
              { ctx ->
                corner.get().playRoulette(ctx.session, ctx.state, ctx.specialVar(0x4))
                0
              },
          // FireRed's CheckAddCoins: can VAR_0x8006 more coins fit? (coins.c)
          "CheckAddCoins" to
              { ctx ->
                if (save.coins(ctx.playerEntityId) + ctx.specialVar(0x6) <= MAX_COINS) 1 else 0
              },
      )
}
