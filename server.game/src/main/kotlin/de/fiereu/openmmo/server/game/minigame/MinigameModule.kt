package de.fiereu.openmmo.server.game.minigame






import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import de.fiereu.openmmo.server.game.minigame.gamecorner.GameCornerScripts
import de.fiereu.openmmo.server.game.minigame.gamecorner.GameCornerService
import de.fiereu.openmmo.server.game.script.Script
import de.fiereu.openmmo.server.game.script.ScriptSpecial
import de.fiereu.openmmo.server.game.services.command.ChatCommand
import javax.inject.Inject
import javax.inject.Singleton

/**
 * r5-minigames' Dagger module: the topics of the c2s 0xDC channel, the specials this lane owns
 * (Slot*, Roulette*, *Coin*, BerryCrush*, Dodrio*, PokemonJump*, FameChecker*, *UnionRoom*,
 * RecordMix*, MysteryGift*, *EasyChat*, *Trendy*, *Interview*), the dev / admin commands and the
 * repository.
 */
@Module
interface MinigameModule {
  @Binds fun repository(impl: JooqMinigameRepository): MinigameRepository

  @Binds @IntoSet fun menuTopic(h: MinigameMenus): MinigameTopic

  @Binds @IntoSet fun gameCornerTopic(h: GameCornerService): MinigameTopic

  @Binds @IntoSet fun linkTopic(h: de.fiereu.openmmo.server.game.minigame.link.LinkGameService): MinigameTopic

  @Binds @IntoSet fun unionTopic(h: de.fiereu.openmmo.server.game.minigame.link.UnionRoomService): MinigameTopic

  @Binds @IntoSet fun fameTopic(h: de.fiereu.openmmo.server.game.minigame.fame.FameCheckerService): MinigameTopic

  @Binds @IntoSet fun giftTopic(h: de.fiereu.openmmo.server.game.minigame.gift.MysteryGiftService): MinigameTopic

  @Binds @IntoSet fun recordsTopic(h: de.fiereu.openmmo.server.game.minigame.records.RecordsService): MinigameTopic

  @Binds @IntoSet fun specials(h: MinigameSpecials): ScriptSpecial

  @Binds @IntoSet fun linkSpecials(h: de.fiereu.openmmo.server.game.minigame.link.LinkSpecials): ScriptSpecial

  @Binds @IntoSet fun fameSpecials(h: de.fiereu.openmmo.server.game.minigame.fame.FameCheckerSpecials): ScriptSpecial

  @Binds @IntoSet fun giftSpecials(h: de.fiereu.openmmo.server.game.minigame.gift.MysteryGiftSpecials): ScriptSpecial

  @Binds @IntoSet fun recordsSpecials(h: de.fiereu.openmmo.server.game.minigame.records.RecordsSpecials): ScriptSpecial

  @Binds @IntoSet fun giftCommand(c: de.fiereu.openmmo.server.game.minigame.gift.MysteryGiftCommand): ChatCommand

  @Binds @IntoSet fun fameCommand(c: MinigameCommands.Fame): ChatCommand

  @Binds @IntoSet fun coinsCommand(c: MinigameCommands.Coins): ChatCommand

  @Binds @IntoSet fun warpCommand(c: MinigameCommands.Warp): ChatCommand
}

/**
 * Every decomp script label this lane ports by hand onto its specials (the Game Corner, the Pokémon
 * Center 2F attendants, the deliveryman). They take precedence over the generated stubs.
 */
@Singleton
class MinigameScripts
@Inject
constructor(
    gameCorner: GameCornerScripts,
    link: de.fiereu.openmmo.server.game.minigame.link.LinkScripts,
    gift: de.fiereu.openmmo.server.game.minigame.gift.MysteryGiftScripts,
) {
  val byLabel: Map<String, Script> = gameCorner.scripts + link.scripts + gift.scripts
}
