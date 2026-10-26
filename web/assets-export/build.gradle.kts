plugins {
  application
  id("buildsrc.convention.kotlin-jvm")
  id("buildsrc.convention.spotless")
}

application { mainClass.set("de.fiereu.openmmo.web.assets.MainKt") }

dependencies {
  // PretData, MetatilePainter: the same decomp readers and layer rules as the desktop client.
  implementation(project(":client"))
  implementation(libs.kotlinx.serialization.json)
}

// `--decomp decomp --out web/static/assets` are relative to the repository root.
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

tasks.withType<Test>().configureEach {
  systemProperty("decomp.root", rootProject.file("decomp").absolutePath)
}
