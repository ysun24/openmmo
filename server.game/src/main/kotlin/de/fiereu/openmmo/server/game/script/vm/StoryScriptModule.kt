package de.fiereu.openmmo.server.game.script.vm

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import de.fiereu.openmmo.server.game.script.ScriptSpecial
import de.fiereu.openmmo.server.game.script.ScriptSpecials
import javax.inject.Singleton

/** r5-scripts: the specials of the story / field scripts, and the special registry itself. */
@Module(includes = [StoryScriptModule.Provision::class])
interface StoryScriptModule {
  @Binds @IntoSet fun story(h: StorySpecials): ScriptSpecial

  @Binds @IntoSet fun trainers(h: TrainerSpecials): ScriptSpecial

  @Binds @IntoSet fun party(h: PartySpecials): ScriptSpecial

  @Binds @IntoSet fun world(h: WorldSpecials): ScriptSpecial

  @Binds @IntoSet fun misc(h: MiscSpecials): ScriptSpecial

  @Module
  object Provision {
    @Provides
    @Singleton
    fun specials(handlers: Set<@JvmSuppressWildcards ScriptSpecial>): ScriptSpecials =
        ScriptSpecials(handlers)
  }
}
