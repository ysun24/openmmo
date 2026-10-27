plugins {
  application
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.spotless")
}

application {
  mainClass.set("de.fiereu.openmmo.web.MainKt")
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
  // The headless game client: login, overworld, battle, trade, market.
  implementation(project(":client"))
  implementation(project(":network"))
  implementation(project(":protocols.game"))
  // RegisterResult for the OpenMMO-only register message.
  implementation(project(":protocols.login"))
  implementation(project(":common"))
  // Species, move and item tables, the same ones the desktop client names things with.
  implementation(project(":codegen"))

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.logging)
  implementation(libs.logback)
}
