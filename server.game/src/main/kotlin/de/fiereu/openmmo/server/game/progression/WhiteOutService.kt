package de.fiereu.openmmo.server.game.progression

import de.fiereu.network.SessionContext
import de.fiereu.openmmo.common.enums.PokemonContainer
import de.fiereu.openmmo.common.enums.Region
import de.fiereu.openmmo.net.game.packets.CurrencyBalancePacket
import de.fiereu.openmmo.net.game.packets.LocalCharacterDeltaPacket
import de.fiereu.openmmo.server.game.services.StoryPlayerService
import de.fiereu.openmmo.server.game.services.WarpService
import de.fiereu.openmmo.server.game.services.notice
import de.fiereu.openmmo.server.game.session.PLAYER_STATE
import de.fiereu.openmmo.server.game.storage.CharacterStore
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/**
 * Gen 3 white-out after a lost battle or the last monster fainting from field poison: the money
 * loss ([HealLocations.moneyLoss]), the decomp's lines, a full heal (HealPlayerParty) and the warp
 * to the last heal location ([HealLocations.respawn]).
 */
@Singleton
class WhiteOutService
@Inject
constructor(
    private val characters: CharacterStore,
    private val heal: HealLocations,
    private val warps: WarpService,
    private val story: StoryPlayerService,
    private val scope: CoroutineScope,
) {

  /** [againstTrainer] picks FireRed's "paid as the prize money" line over "panicked and lost". */
  fun whiteOut(session: SessionContext, charId: Long, againstTrainer: Boolean) {
    scope.launch { run(session, charId, againstTrainer) }
  }

  internal suspend fun run(session: SessionContext, charId: Long, againstTrainer: Boolean) {
    val state = session.attributes[PLAYER_STATE] ?: return
    val stored = characters.getCharacter(charId) ?: return
    val region = Region.byWireValue(state.regionId.toByte()) ?: Region.KANTO
    val name = stored.info.name
    val party = stored.pokemon.filter { it.container == PokemonContainer.PARTY && !it.isEgg }
    val loss =
        HealLocations.moneyLoss(
            region,
            stored.info.money,
            BadgeServiceCount.count(stored, region),
            party.maxOfOrNull { it.level.toInt() } ?: 1)
    if (loss > 0 && characters.addMoney(charId, -loss)) {
      val money = characters.getCharacter(charId)?.info?.money ?: 0
      session.send(CurrencyBalancePacket(money, 0))
      session.send(LocalCharacterDeltaPacket(money = money))
    }
    session.send(notice("$name is out of usable POKéMON!"))
    if (region == Region.KANTO) {
      session.send(
          notice(
              if (againstTrainer) "$name paid ¥$loss as the prize money… … … … …"
              else "$name panicked and lost ¥$loss… … … … …"))
    }
    session.send(notice("$name whited out!"))
    story.healParty(session, state)
    val point = heal.respawn(stored, region, stored.info.rivalSex.toInt() == 1)
    log.info { "char=$charId whited out in $region: -$loss money, respawn at $point" }
    if (point != null) warps.executeWarp(session, charId, heal.warpTile(point))
  }
}

/** Badge counting for the money loss, from [BadgeService]'s flags. */
internal object BadgeServiceCount {
  fun count(s: de.fiereu.openmmo.server.game.storage.StoredCharacter, region: Region): Int =
      (1..BadgeService.BADGES).count { BadgeService.has(s, region, it) }
}
