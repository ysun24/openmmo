package de.fiereu.openmmo.client.gfx

import de.fiereu.openmmo.client.net.BattleClient
import de.fiereu.openmmo.client.net.BattlePhase
import java.awt.event.KeyEvent

/**
 * Keyboard control of the battle screen: arrows move the cursor, Z/Enter/Space picks, X/Backspace
 * goes back. After the battle ends any pick closes the screen.
 */
class BattleInput(private val battle: BattleClient) {
  @Volatile
  var ui = BattleUi()
    private set

  /** True when the key was used by the battle screen. */
  fun keyPressed(e: KeyEvent): Boolean {
    val s = battle.snapshot()
    if (!s.active) return false
    val menu = if (s.phase == BattlePhase.FORCED_SWITCH) BattleMenu.PARTY else ui.menu
    val count =
        when (menu) {
          BattleMenu.MAIN -> 4
          BattleMenu.FIGHT -> s.me?.moves?.size ?: 0
          BattleMenu.PARTY -> s.party.size
        }.coerceAtLeast(1)
    when (e.keyCode) {
      KeyEvent.VK_LEFT -> move(-1, count)
      KeyEvent.VK_RIGHT -> move(1, count)
      KeyEvent.VK_UP -> move(-2, count)
      KeyEvent.VK_DOWN -> move(2, count)
      KeyEvent.VK_X,
      KeyEvent.VK_BACK_SPACE,
      KeyEvent.VK_ESCAPE -> if (s.phase != BattlePhase.FORCED_SWITCH) ui = BattleUi()
      KeyEvent.VK_Z,
      KeyEvent.VK_ENTER,
      KeyEvent.VK_SPACE -> select(menu)
      else -> {}
    }
    return true
  }

  private fun move(delta: Int, count: Int) {
    ui = ui.copy(cursor = (ui.cursor + delta).mod(count))
  }

  private fun select(menu: BattleMenu) {
    val s = battle.snapshot()
    if (s.phase == BattlePhase.ENDED) {
      battle.close()
      ui = BattleUi()
      return
    }
    when (menu) {
      BattleMenu.MAIN ->
          when (ui.cursor) {
            0 -> ui = BattleUi(BattleMenu.FIGHT)
            1 -> battle.throwBall()
            2 -> ui = BattleUi(BattleMenu.PARTY)
            3 -> battle.run()
          }
      BattleMenu.FIGHT -> if (battle.fight(ui.cursor) == null) ui = BattleUi()
      BattleMenu.PARTY -> if (battle.switchTo(ui.cursor) == null) ui = BattleUi()
    }
  }
}
