package de.fiereu.openmmo.server.game.di

import dagger.BindsInstance
import dagger.Component
import de.fiereu.openmmo.server.game.GameServer
import de.fiereu.openmmo.server.game.config.GameServerConfig
import de.fiereu.openmmo.server.game.db.DatabaseBootstrap
import de.fiereu.openmmo.server.game.handler.GameAppHandler
import de.fiereu.openmmo.server.game.script.ScriptSpecialModule
import de.fiereu.openmmo.server.game.session.SessionRegistry
import de.fiereu.openmmo.server.game.storage.CharacterStore
import de.fiereu.openmmo.server.game.storage.DevCharacterSeeder
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
@Component(
    modules =
        [
            GameServerModule::class,
            ChatCommandModule::class,
            SocialModule::class,
            ScriptSpecialModule::class,
            de.fiereu.openmmo.server.game.script.vm.StoryScriptModule::class,
            de.fiereu.openmmo.server.game.services.BagItemScriptModule::class,
            // r5-contests
            de.fiereu.openmmo.server.game.contest.ContestModule::class,
            de.fiereu.openmmo.server.game.frontier.FrontierModule::class,
            de.fiereu.openmmo.server.game.minigame.MinigameModule::class,
        ])
interface GameServerComponent {

  fun server(): GameServer

  fun handlerProvider(): Provider<GameAppHandler>

  fun sessionRegistry(): SessionRegistry

  fun databaseBootstrap(): DatabaseBootstrap

  fun characterStore(): CharacterStore

  fun devCharacterSeeder(): DevCharacterSeeder

  /** r5: built at start-up so two handlers claiming one decomp special fail fast. */
  fun scriptSpecials(): de.fiereu.openmmo.server.game.script.ScriptSpecials

  @Component.Factory
  fun interface Factory {
    fun create(@BindsInstance config: GameServerConfig): GameServerComponent
  }
}
