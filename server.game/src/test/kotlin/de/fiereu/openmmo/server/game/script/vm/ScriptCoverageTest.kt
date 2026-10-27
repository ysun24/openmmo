package de.fiereu.openmmo.server.game.script.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Walks every script both decomps can reach (std scripts, every map's script table and object / bg
 * / coord event scripts, and every other *EventScript* label) through the assembled bytecode and
 * asserts each command reached is one the interpreter implements and each special is claimed: by
 * this lane's handlers, or by the lane that owns its system (R5 brief table).
 */
class ScriptCoverageTest :
    FunSpec({
      val root =
          generateSequence(File("").absoluteFile) { it.parentFile }
              .first { File(it, "decomp").isDirectory }

      // The command names ScriptVm.exec handles, read from its source.
      val implemented: Set<String> by lazy {
        val src =
            File(
                    root,
                    "server.game/src/main/kotlin/de/fiereu/openmmo/server/game/script/vm/ScriptVm.kt")
                .readText()
        val body = src.substringAfter("private suspend fun exec(").substringBefore("// ---")
        Regex(""""(\w+)"(?=[,\s]*(?:->|,))""").findAll(body).map { it.groupValues[1] }.toSet()
      }

      val ours: Set<String> by lazy {
        listOf(StorySpecials(), TrainerSpecials(), PartySpecials(), WorldSpecials(), MiscSpecials())
            .flatMap { it.names }
            .toSet()
      }

      // Every handler bound in the real component: this lane's and the other lanes' (r5 merge).
      // Building the registry also proves no two handlers claim one name.
      val bound: Set<String> by lazy {
        de.fiereu.openmmo.server.game.di.DaggerGameServerComponent.factory()
            .create(de.fiereu.openmmo.server.game.config.ConfigLoader.load())
            .scriptSpecials()
            .names
      }

      for ((region, game) in listOf("kanto" to "pokefirered", "hoenn" to "pokeemerald")) {
        test("every command and special the $region scripts reach is implemented") {
          val img = ScriptImage.of(region)!!
          val d = ScriptDisasm(img)
          val maps = File(root, "decomp/$game/data/maps").listFiles()!!.filter { it.isDirectory }
          d.enqueueRoots(maps.map { it.name })
          val json = Json { ignoreUnknownKeys = true }
          var eventScripts = 0
          for (m in maps) {
            val f = File(m, "map.json")
            if (!f.isFile) continue
            val o = json.parseToJsonElement(f.readText()).jsonObject
            for (key in listOf("object_events", "bg_events", "coord_events")) {
              for (e in o[key]?.jsonArray.orEmpty()) {
                val s = e.jsonObject["script"]?.jsonPrimitive?.content ?: continue
                if (d.enqueueLabel(s)) eventScripts++
              }
            }
          }
          for (label in img.labels.keys) {
            if (EVENT_SCRIPT.containsMatchIn(label) && img.texts[img.labels[label]] == null)
                d.enqueueLabel(label)
          }
          d.run()

          val reachedLabels = img.labels.values.count { it in d.visited }
          println(
              "[coverage] $region: ${eventScripts} event scripts, $reachedLabels labels reached, " +
                  "${d.visited.size} commands, ${d.commands.size} distinct commands, " +
                  "${d.specials.size} specials, ${d.natives.size} natives")
          val missingCommands = d.commands.keys - implemented
          println("[coverage] $region: missing commands $missingCommands")
          missingCommands.shouldBeEmpty()
          val unclaimed = (d.specials.keys + d.natives.keys).filter { it !in bound }
          val others = (d.specials.keys + d.natives.keys).filter { it !in ours && it in bound }
          println(
              "[coverage] $region: ${bound.size} bound specials; other lanes' reached: ${others.sorted()}")
          println("[coverage] $region: unclaimed specials ${unclaimed.size}: ${unclaimed.sorted()}")
          d.errors.shouldBeEmpty()
          unclaimed.sorted().shouldBeEmpty()
          reachedLabels shouldBeGreaterThan 1000
        }
      }
    }) {
  private companion object {
    // Script labels; the ON_FRAME / ON_WARP_INTO tables (`X_OnFrame:`, `X_OnWarp:`) are data.
    val EVENT_SCRIPT = Regex("""EventScript|_On(Load|Transition|Resume)$|^Std_""")
  }
}
