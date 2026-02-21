/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.codegen

import org.jetbrains.kotlin.backend.jvm.mapping.IrCallableMethod
import org.jetbrains.kotlin.backend.jvm.mapping.IrSpecializationTypeMap
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.util.genericTypeParameterIndex
import org.jetbrains.kotlin.ir.util.isJvmSpecializedGeneric
import org.jetbrains.org.objectweb.asm.Handle
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type

class IrSpecializedCallGenerator(private val specializationMap: IrSpecializationTypeMap) : IrCallGenerator {
    override fun genCall(
        callableMethod: IrCallableMethod,
        codegen: ExpressionCodegen,
        expression: IrFunctionAccessExpression,
        isInsideIfCondition: Boolean
    ) {
        val callee = expression.symbol.owner

        val bootstrapOwner = "kotlin/jvm/specialization/BootstrapMethods"
        val bootstrapName = "bootstrapSpecializedGeneric"
        val bootstrapDescriptor = "(" +
                "Ljava/lang/invoke/MethodHandles\$Lookup;" +
                "Ljava/lang/String;" +
                "Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/Class;" +
                "Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/String;" +
                "Ljava/lang/String;" +
                ")Ljava/lang/invoke/CallSite;"

        val bootstrapHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            bootstrapOwner,
            bootstrapName,
            bootstrapDescriptor,
            false,
        )

        codegen.mv.visitInvokeDynamicInsn(
            callableMethod.asmMethod.name,
            callableMethod.asmMethod.descriptor,
            bootstrapHandle,
            callableMethod.owner,
            Type.getType(callableMethod.specGenericImplSignature!!.asmMethod.descriptor),
            encodeSpecSignature(callee),
            specializationMap.specializedTypeParametersForBootstrap.entries.joinToString { (k, v) -> "$k=$v\n" },
        )
    }

    override fun genValueAndPut(
        irValueParameter: IrValueParameter,
        argumentExpression: IrExpression,
        parameterType: Type,
        codegen: ExpressionCodegen,
        blockInfo: BlockInfo
    ) {
        with(codegen) {
            val irParameterType = specializationMap.getSpecializedType(irValueParameter.realType) ?: irValueParameter.realType
            gen(argumentExpression, parameterType, irParameterType, blockInfo)
        }
    }
}

private fun encodeSpecSignature(callee: IrFunction): String {
    val sb = StringBuilder()
    for ((parameterIndex, parameter) in callee.parameters.withIndex()) {
        if (parameter.type.isJvmSpecializedGeneric) {
            val genericIndex = parameter.type.genericTypeParameterIndex!!
            sb.append("${parameterIndex}=$genericIndex\n")
        }
    }
    if (callee.returnType.isJvmSpecializedGeneric) {
        val genericIndex = callee.returnType.genericTypeParameterIndex!!
        sb.append("ret=$genericIndex\n")
    }
    return sb.toString()
}
