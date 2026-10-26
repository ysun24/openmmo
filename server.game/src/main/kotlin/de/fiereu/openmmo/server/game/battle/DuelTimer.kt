package de.fiereu.openmmo.server.game.battle

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules duel turn timeouts. Tests swap in a timer they fire by hand. */
@Singleton
open class DuelTimer @Inject constructor() {
  private val executor: ScheduledExecutorService by lazy {
    Executors.newSingleThreadScheduledExecutor { r ->
      Thread(r, "duel-timer").apply { isDaemon = true }
    }
  }

  /** Runs [task] after [delayMs]. The returned function cancels it. */
  open fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
    val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    return { future.cancel(false) }
  }
}
