plugins {
  application
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.spotless")
}

application {
  mainClass.set("de.fiereu.openmmo.client.MainKt")
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
  implementation(project(":network"))
  implementation(project(":protocols.login"))
  implementation(project(":protocols.game"))
  implementation(project(":common"))

  implementation(libs.kotlinx.coroutines)
  implementation(libs.kotlin.logging)
  implementation(libs.logback)
  implementation(libs.kotlinx.serialization.json)
}
