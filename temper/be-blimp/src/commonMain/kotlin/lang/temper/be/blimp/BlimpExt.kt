package lang.temper.be.blimp

import lang.temper.be.tmpl.TmpL
import lang.temper.name.CoreCodeLocation
import lang.temper.name.ResolvedParsedName

/**
 * Whether this is the module-level `console` temporary the frontend injects
 * for every reference to the global console.
 *
 * `Console.log` is inlined at its call site and drops the receiver, so the
 * temporary has no reader and emitting it would leave a stray binding at
 * module scope. be-rust and be-cppv skip it the same way.
 */
internal fun TmpL.ModuleLevelDeclaration.isConsole(): Boolean {
    (type.ot as? TmpL.NominalType)?.typeName?.sourceDefinition?.let { typeDefinition ->
        if (typeDefinition.sourceLocation === CoreCodeLocation) {
            when ((typeDefinition.name as? ResolvedParsedName)?.baseName?.nameText) {
                "Console", "GlobalConsole" -> return true
                else -> {}
            }
        }
    }
    return false
}

/**
 * The name a class is known by when flattening a subclass into it.
 *
 * Supertypes are named through `NominalType`, so both sides have to agree on
 * the same text.
 */
internal fun typeKeyOf(decl: TmpL.TypeDeclaration): String? =
    (decl.name.nameContent as? lang.temper.common.Either.Left)?.item?.let { name ->
        (name as? ResolvedParsedName)?.baseName?.nameText ?: "$name"
    }
