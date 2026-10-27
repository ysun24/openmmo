package de.fiereu.openmmo.server.login

import de.fiereu.openmmo.server.login.config.ConfigLoader
import de.fiereu.openmmo.server.login.di.DaggerLoginServerComponent
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
  val config = ConfigLoader.load()
  val component = DaggerLoginServerComponent.factory().create(config)
  component.databaseBootstrap().migrate()
  if (args.firstOrNull() == "invite") {
    exitProcess(InviteCli.run(args.drop(1), component.jooqUserStore()))
  }
  if (args.isNotEmpty()) {
    System.err.println("usage: server.login [invite ...]\n" + InviteCli.usage())
    exitProcess(2)
  }
  runBlocking { component.adminAccountBootstrap().ensureAdmin() }
  component.server().start()
}
