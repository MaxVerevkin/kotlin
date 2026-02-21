/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.optimization.specialization

import org.jetbrains.kotlin.codegen.optimization.transformer.MethodTransformer
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.TypeReference
import org.jetbrains.org.objectweb.asm.signature.SignatureReader
import org.jetbrains.org.objectweb.asm.signature.SignatureVisitor
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import org.jetbrains.org.objectweb.asm.tree.AnnotationNode
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode
import org.jetbrains.org.objectweb.asm.tree.analysis.Analyzer
import java.util.TreeSet

class SpecializationTransformer(private val generationState: GenerationState) : MethodTransformer() {
    override fun transform(internalClassName: String, methodNode: MethodNode) {
        val specGenericSignature = SpecGenericSignature.forMethodNode(methodNode) ?: return
        val argumentTypes = Type.getArgumentTypes(methodNode.desc)
        val interpreter = SpecializationInterpreter(argumentTypes, specGenericSignature)
        val analyzer = Analyzer(interpreter)
        val frames = analyzer.analyze(internalClassName, methodNode)

        for ((insn, genericIndex) in interpreter.specializedLoadStore) {
            methodNode.instructions.insertBefore(
                insn, MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "kotlin/jvm/internal/Intrinsics",
                    "specializedTypeMarker${genericIndex.index}",
                    "()V",
                    false
                )
            )
        }

        val specializedSlotsMap = HashMap<GenericIndex, TreeSet<Int>>()
        for (frame in frames) {
            val frame = frame ?: continue
            for (slot in 0 until frame.locals) {
                frame.getLocal(slot)?.specGenericIndex?.let { genericIndex ->
                    specializedSlotsMap.getOrPut(genericIndex, ::TreeSet).add(slot)
                }
            }
        }
        val specializedSlots = buildList {
            for ((genericIndex, slots) in specializedSlotsMap) {
                add(genericIndex.index)
                add(slots.size)
                for (slot in slots) {
                    add(slot)
                }
            }
        }
        if (methodNode.invisibleAnnotations == null) methodNode.invisibleAnnotations = ArrayList()
        methodNode.invisibleAnnotations.add(
            AnnotationNode("Lkotlin/jvm/JvmSpecializeMetadata;").apply {
                values = listOf("specializedSlots", specializedSlots)
            }
        )
    }
}

internal data class SpecGenericSignature(
    val parameterMapping: Map<Int, GenericIndex>, // parameter index -> generic index
    val returnGeneric: GenericIndex?,
) {
    companion object {
        fun forMethodNode(methodNode: MethodNode): SpecGenericSignature? {
            val genericTypeParametersIndices =
                (methodNode.visibleTypeAnnotations ?: return null)
                    .filter { it.desc == "Lkotlin/jvm/JvmSpecialize;" }
                    .map { GenericIndex(TypeReference(it.typeRef).typeParameterIndex) }
                    .toSet()
                    .ifEmpty { return null }

            val signatureMap = parseSignature(methodNode.signature ?: error("specialized function has no signature"))

            return SpecGenericSignature(
                signatureMap.first.filterValues { genericTypeParametersIndices.contains(it) },
                signatureMap.second?.takeIf { genericTypeParametersIndices.contains(it) },
            )
        }
    }
}

internal fun parseSignature(signature: String): Pair<Map<Int, GenericIndex>, GenericIndex?> {
    val formalParams = mutableListOf<String>()

    fun topLevelTypeVarVisitor(set: (GenericIndex) -> Unit) = object : SignatureVisitor(Opcodes.ASM9) {
        private var topLevelSeen = false

        override fun visitTypeVariable(name: String) {
            if (!topLevelSeen) {
                topLevelSeen = true
                formalParams.indexOf(name).takeIf { it >= 0 }?.let { set(GenericIndex(it)) }
            }
        }

        override fun visitBaseType(descriptor: Char) {
            topLevelSeen = true
        }

        override fun visitClassType(name: String) {
            topLevelSeen = true
        }

        override fun visitArrayType(): SignatureVisitor {
            topLevelSeen = true
            return object : SignatureVisitor(Opcodes.ASM9) {}
        }
    }

    val visitor = object : SignatureVisitor(Opcodes.ASM9) {
        private var paramIndex = 0
        val paramToGeneric = HashMap<Int, GenericIndex>()
        var returnGeneric: GenericIndex? = null

        override fun visitFormalTypeParameter(name: String) {
            formalParams += name
        }

        override fun visitParameterType(): SignatureVisitor {
            val i = paramIndex++
            return topLevelTypeVarVisitor { g -> paramToGeneric[i] = g }
        }

        override fun visitReturnType(): SignatureVisitor {
            return topLevelTypeVarVisitor { g -> returnGeneric = g }
        }
    }

    SignatureReader(signature).accept(visitor)

    return visitor.paramToGeneric to visitor.returnGeneric
}
