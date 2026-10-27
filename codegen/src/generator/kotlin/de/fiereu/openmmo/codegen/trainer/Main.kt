@file:JvmName("Main")

package de.fiereu.openmmo.codegen.trainer

import java.io.File

fun main(args: Array<String>) {
  require(args.size >= 4) {
    "Usage: <output-dir> <templates-dir> <class-cache-dir> <region|decomp>... got ${args.toList()}"
  }
  val outputDir = File(args[0])
  val templatesDir = File(args[1])
  val classCacheDir = File(args[2])

  for (spec in args.drop(3)) {
    val (region, decomp) = spec.split("|")
    val trainers = TrainerParser(File(decomp)).parseAll()
    println("[trainer] $region: parsed ${trainers.size} trainers from $decomp")
    TrainerRenderer(region, templatesDir, outputDir, classCacheDir).render(trainers)
    val rematches = RematchParser(File(decomp)).parseAll()
    println("[trainer] $region: parsed ${rematches.size} rematch entries")
    val objectName = "Generated${region.replaceFirstChar { it.uppercase() }}Rematches"
    val pkg = "de.fiereu.openmmo.trainer.generated"
    val out = File(outputDir, "${pkg.replace('.', '/')}/$objectName.kt")
    out.parentFile.mkdirs()
    out.writeText(RematchParser(File(decomp)).render(pkg, objectName, rematches))
  }
  println("[trainer] done")
}
