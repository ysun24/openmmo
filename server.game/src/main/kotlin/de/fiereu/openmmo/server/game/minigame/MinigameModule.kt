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

  @Binds @IntoSet fun specials(h: MinigameSpecials): ScriptSpecial

  @Binds @IntoSet fun coinsCommand(c: MinigameCommands.Coins): ChatCommand
}

/**
 * Every decomp script label this lane ports by hand onto its specials (the Game Corner, the Pokémon
 * Center 2F attendants, the deliveryman). They take precedence over the generated stubs.
 */
@Singleton
class MinigameScripts @Inject constructor(gameCorner: GameCornerScripts) {
  val byLabel: Map<String, Script> = gameCorner.scripts
}
