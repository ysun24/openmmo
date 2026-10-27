package de.fiereu.openmmo.codegen.script.asm

import java.io.File

/** Assembles one decomp and prints what it found; a development aid. */
object AsmProbe {
  @JvmStatic
  fun main(args: Array<String>) {
    val decomp = File(args[0])
    val firered = decomp.name.contains("firered")
    val t0 = System.currentTimeMillis()
    val result = assemble(decomp, firered)
    println(
        "bytes=${result.bytes.size} labels=${result.labels.size} texts=${result.texts.size} " +
            "externs=${result.externs.size} errors=${result.errors.size} in ${System.currentTimeMillis() - t0} ms")
    result.errors.take(60).forEach { println("  ERR $it") }
    result.externs.values
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(30)
        .forEach { println("  EXT ${it.key} x${it.value}") }
  }

  fun assemble(decomp: File, firered: Boolean): ScriptAssembler.Result {
    val pre = HashMap<String, String>()
    pre["TRUE"] = "1"
    pre["FALSE"] = "0"
    pre["NULL"] = "0"
    if (firered) pre["FIRERED"] = "1"
    val asm = ScriptAssembler(decomp, pre)
    for ((k, v) in GeneratedConstants.all(decomp)) asm.defineC(k, v.toString())
    for ((k, v) in GeneratedConstants.tmHmExpressions(decomp)) asm.defineC(k, v)
    return asm.assemble(File(decomp, "data/event_scripts.s"))
  }
}
