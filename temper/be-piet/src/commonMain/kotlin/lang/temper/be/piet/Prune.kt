package lang.temper.be.piet

import lang.temper.be.tmpl.TmpL
import lang.temper.common.Either
import lang.temper.name.ResolvedName
import lang.temper.type.TypeDefinition

/**
 * The top levels a program can reach, in their original order.
 *
 * Everything in [roots] is kept. A dependency's declaration is kept if
 * something kept names it, and a dependency's module init block is kept if
 * it names something kept. That matters more here than for most backends:
 * a library pulls in all of `std`, and all of `std` would make every image
 * huge and drag in Float64 code that nothing calls.
 */
fun reachableTopLevels(modules: List<TmpL.Module>, roots: Set<TmpL.Module>): List<TmpL.TopLevel> {
    val aliases = mutableMapOf<ResolvedName, ResolvedName>()
    for (module in modules) {
        for (import in module.imports) {
            val local = import.localName?.name ?: continue
            aliases[local] = import.externalName.name
        }
    }
    fun canon(name: ResolvedName) = aliases[name] ?: name

    val byName = mutableMapOf<ResolvedName, TmpL.TopLevel>()
    val byType = mutableMapOf<TypeDefinition, TmpL.TopLevel>()
    val all = modules.flatMap { m -> m.topLevels.map { it to m } }
    for ((topLevel, _) in all) {
        when (topLevel) {
            is TmpL.ModuleFunctionDeclaration -> byName[topLevel.name.name] = topLevel
            is TmpL.ModuleLevelDeclaration -> byName[topLevel.name.name] = topLevel
            is TmpL.SupportCodeDeclaration -> byName[topLevel.name.name] = topLevel
            is TmpL.TypeDeclaration -> {
                byName[topLevel.name.name] = topLevel
                byType[topLevel.typeShape] = topLevel
            }
            else -> {}
        }
    }

    val kept = mutableSetOf<TmpL.TopLevel>()
    val keptNames = mutableSetOf<ResolvedName>()
    val work = ArrayDeque<TmpL.TopLevel>()
    fun keep(topLevel: TmpL.TopLevel) {
        if (kept.add(topLevel)) work.addLast(topLevel)
    }

    fun mentions(node: TmpL.Tree, into: MutableSet<Any>) {
        when (node) {
            is TmpL.Id -> (node.nameContent as? Either.Left)?.let { into.add(canon(it.item)) }
            is TmpL.TypeName -> into.add(node.sourceDefinition)
            is TmpL.ConstructorReference -> into.add(node.typeShape)
            else -> {}
        }
        for (i in 0 until node.childCount) node.childOrNull(i)?.let { mentions(it, into) }
    }

    fun drain() {
        while (work.isNotEmpty()) {
            val topLevel = work.removeFirst()
            val found = mutableSetOf<Any>()
            mentions(topLevel, found)
            for (m in found) {
                when (m) {
                    is ResolvedName -> {
                        keptNames.add(m)
                        byName[m]?.let(::keep)
                    }
                    is TypeDefinition -> byType[m]?.let(::keep)
                }
            }
        }
    }

    for ((topLevel, module) in all) if (module in roots) keep(topLevel)
    drain()
    // Dependency init blocks that touch anything kept, until nothing changes.
    var changed = true
    while (changed) {
        changed = false
        for ((topLevel, module) in all) {
            if (topLevel !is TmpL.ModuleInitBlock || topLevel in kept || module in roots) continue
            val found = mutableSetOf<Any>()
            mentions(topLevel, found)
            if (found.any { it is ResolvedName && it in keptNames && byName[it]?.let { d -> d in kept } == true }) {
                keep(topLevel)
                drain()
                changed = true
            }
        }
    }
    return all.map { it.first }.filter { it in kept }
}
