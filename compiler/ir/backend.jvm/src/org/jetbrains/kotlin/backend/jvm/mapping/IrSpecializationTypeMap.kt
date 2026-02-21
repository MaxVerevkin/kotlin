/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.mapping

import org.jetbrains.kotlin.backend.jvm.InlineClassAbi
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.asTypeParameterSymbolOrNull
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.getPrimitiveType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.genericTypeParameterIndex
import org.jetbrains.kotlin.ir.util.isJvmSpecialized
import org.jetbrains.kotlin.ir.util.isJvmSpecializedGeneric
import org.jetbrains.kotlin.ir.util.render

class IrSpecializationTypeMap {
    /**
     * Generic type index -> Specialized to type
     */
    private val map: HashMap<Int, IrType> = HashMap()

    val specializedTypeParametersForBootstrap: HashMap<Int, String> = HashMap()

    val callee: IrSimpleFunction

    var isSpecialized = false
        private set

    constructor(callExpression: IrCall, backendContext: JvmBackendContext) {
        callee = callExpression.symbol.owner
        for ((typeParameterIndex, genericAndActual) in (callee.typeParameters zip callExpression.typeArguments).withIndex()) {
            val (generic, actual) = genericAndActual
            if (actual == null) error("unexpected actual type for generic ${generic.render()} = null")
            if (generic.isJvmSpecialized) {
                isSpecialized = true
                if (actual.isMarkedNullable()) continue
                val asClassInternalName = actual.classInternalName
                val asPrimitive = actual.getPrimitiveTypeJvmDesc()
                val unboxedInlineClass = InlineClassAbi.unboxType(actual)?.let { backendContext.defaultTypeMapper.mapType(it) }
                if (asPrimitive != null) {
                    map[typeParameterIndex] = actual
                    specializedTypeParametersForBootstrap[typeParameterIndex] = asPrimitive
                } else if (unboxedInlineClass != null) {
                    val classInternalName = asClassInternalName ?: error("inline class name is null for ${actual.render()}")
                    map[typeParameterIndex] = actual
                    specializedTypeParametersForBootstrap[typeParameterIndex] = "ic:$classInternalName(${unboxedInlineClass.descriptor}"
                } else if (actual.isJvmSpecializedGeneric) {
                    map[typeParameterIndex] = actual
                    specializedTypeParametersForBootstrap[typeParameterIndex] = "i:${actual.genericTypeParameterIndex!!}"
                }
            }
        }
    }

    fun getSpecializedType(ty: IrType): IrType? {
        val parameterSymbol = ty.asTypeParameterSymbolOrNull ?: return null
        if (parameterSymbol.owner.parent != callee) return null
        return map[parameterSymbol.owner.index]
    }
}

private val IrType.classInternalName: String?
    get() = classFqName?.asString()?.replace('.', '/')

private fun IrType.getPrimitiveTypeJvmDesc(): String? {
    if (isMarkedNullable()) return null
    return when (getPrimitiveType()) {
        null -> null
        PrimitiveType.BOOLEAN -> "Z"
        PrimitiveType.CHAR -> "C"
        PrimitiveType.BYTE -> "B"
        PrimitiveType.SHORT -> "S"
        PrimitiveType.INT -> "I"
        PrimitiveType.FLOAT -> "F"
        PrimitiveType.LONG -> "J"
        PrimitiveType.DOUBLE -> "D"
    }
}
