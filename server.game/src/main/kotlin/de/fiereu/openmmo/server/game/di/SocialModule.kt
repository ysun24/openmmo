package de.fiereu.openmmo.server.game.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import de.fiereu.openmmo.server.game.services.command.ChatCommand
import de.fiereu.openmmo.server.game.services.social.ChatFilter
import de.fiereu.openmmo.server.game.services.social.ExtraNpcSource
import de.fiereu.openmmo.server.game.services.social.MarketClerks
import de.fiereu.openmmo.server.game.services.social.RateLimiter
import de.fiereu.openmmo.server.game.services.social.SocialConfig
import de.fiereu.openmmo.server.game.services.social.TeleportCommand
import de.fiereu.openmmo.server.game.services.social.WordListChatFilter
import de.fiereu.openmmo.server.game.services.trade.TradeEvolutionHook
import de.fiereu.openmmo.server.game.storage.JooqSocialRepository
import de.fiereu.openmmo.server.game.storage.SocialRepository
import javax.inject.Singleton

/** Bindings of the r3-social features: config, abuse limits, persistence and hooks. */
@Module
object SocialModule {
  @Provides @Singleton fun socialConfig(): SocialConfig = SocialConfig.fromEnvironment()

  @Provides
  @Singleton
  fun rateLimiter(config: SocialConfig): RateLimiter = RateLimiter(config.rates)

  /** The profanity filter hook: a word list, empty unless `chat.filterFile` names one. */
  @Provides
  @Singleton
  fun chatFilter(config: SocialConfig): ChatFilter = WordListChatFilter(config.chatFilterWords)

  @Provides @Singleton fun socialRepository(impl: JooqSocialRepository): SocialRepository = impl

  @Provides @Singleton fun extraNpcs(clerks: MarketClerks): ExtraNpcSource = clerks

  @Provides @IntoSet fun teleportCommand(command: TeleportCommand): ChatCommand = command

  /** No-op until the evolution engine (battle branch) binds its own. */
  @Provides @Singleton fun tradeEvolution(): TradeEvolutionHook = TradeEvolutionHook.NONE
}
