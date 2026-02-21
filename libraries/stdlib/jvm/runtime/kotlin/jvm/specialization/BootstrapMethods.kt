/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package kotlin.jvm.specialization

import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.*
import org.jetbrains.org.objectweb.asm.util.TraceClassVisitor
import java.io.PrintWriter
import java.lang.invoke.*
import java.util.TreeSet

public object BootstrapMethods {
    private val cache = HashMap<String, MethodHandle>()

    /**
     * @param lookup Call site lookup, provided by JVM
     * @param methodName The name of the specialized method
     * @param specializedMethodType The specialized method type
     * @param genericImplClass The class that contains the generic implementation of the specialized method
     * @param genericImplMethodType The method type of the generic implementation of the specialized method
     * @param specSignatureStr TODO
     * @param specializedTypeParametersStr TODO
     */
    @JvmStatic
    public fun bootstrapSpecializedGeneric(
        lookup: MethodHandles.Lookup,
        methodName: String,
        specializedMethodType: MethodType,
        genericImplClass: Class<*>,
        genericImplMethodType: MethodType,
        specSignatureStr: String,
        specializedTypeParametersStr: String,
    ): CallSite {
        val genericImplDesc = genericImplMethodType.toMethodDescriptorString()
        val specializedDesc = specializedMethodType.toMethodDescriptorString()
        val specSignature = SpecSignature.decode(specSignatureStr)
        val specializedTypeParameters = decodeSpecializedTypeParametersStr(specializedTypeParametersStr).mapValues {
            SpecializedType.fromString(it.value) ?: error("unsupported specialized type parameter: ${it.value}")
        }

        val cacheEntryName =
            genericImplClass.name + "." + methodName + ":" + genericImplDesc + "#" + specializedTypeParameters

        cache[cacheEntryName]?.let { return ConstantCallSite(it) }

        val genericClassNode = readClassNode(genericImplClass)

        val genericMethodNode = genericClassNode.methods.find { it.name == methodName && it.desc == genericImplDesc }
            ?: throw RuntimeException("generic method not found: $methodName $genericImplDesc")

        val metadata = Metadata.extract(genericMethodNode)

        val specializedClassNode = ClassNode().apply {
            this.name = "kotlin/jvm/specialization/SpecializedClass"
            this.version = genericClassNode.version
            this.superName = "java/lang/Object"
            this.access = Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL
        }

        val specializedMethodNode = MethodNode().apply {
            specializedClassNode.methods.add(this)
            this.name = "invoke"
            this.desc = specializedDesc
            this.instructions.add(genericMethodNode.instructions)
            this.access = Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL + Opcodes.ACC_SYNTHETIC
        }

        peeholeAdapt(
            specializedMethodNode.instructions,
            specializedMethodType.parameterArray(),
            specSignature,
            specializedTypeParameters,
            metadata,
        )

        val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES + ClassWriter.COMPUTE_MAXS).apply { specializedClassNode.accept(this) }
        val bytecode = classWriter.toByteArray()
        dumpClass(bytecode)

        val implHandle = defineClass(bytecode, specializedMethodType)
        cache[cacheEntryName] = implHandle
        return ConstantCallSite(implHandle)
    }
}

private fun readClassNode(clazz: Class<*>): ClassNode {
    val node = ClassNode()
    ClassReader(clazz.name).accept(node, 0)
    return node
}

// TODO: this only works with modern java, add support for all significant versions starting from java 8
private fun defineClass(bytecode: ByteArray, specializedImplType: MethodType): MethodHandle {
    val lookup = MethodHandles.lookup()

    val classOptClass = Class.forName($$"java.lang.invoke.MethodHandles$Lookup$ClassOption")
    val classOptArray = java.lang.reflect.Array.newInstance(classOptClass, 0)

    val defineClass = lookup.javaClass.getDeclaredMethod(
        "defineHiddenClass",
        ByteArray::class.java,
        Boolean::class.javaPrimitiveType,
        classOptArray::class.java,
    )

    val lk = defineClass(lookup, bytecode, true, classOptArray) as MethodHandles.Lookup

    return lk.findStatic(lk.lookupClass(), "invoke", specializedImplType)
}

private fun dumpClass(bytecode: ByteArray) {
    println("Dumping class: (${bytecode.size} bytes)")
    val classReader = ClassReader(bytecode)
    classReader.accept(TraceClassVisitor(PrintWriter(System.out)), 0)
}

private val Class<*>.width: Int
    get() = when (this) {
        Long::class.javaPrimitiveType, Double::class.javaPrimitiveType -> 2
        else -> 1
    }

private fun peeholeAdapt(
    instructions: InsnList,
    specializedTypes: Array<Class<*>>,
    specSignature: SpecSignature,
    specializedTypeParameters: Map<Int, SpecializedType>,
    metadata: Metadata,
) {
    fun AbstractInsnNode.isIntrinsic(namePredicate: (String) -> Boolean): Boolean =
        this is MethodInsnNode &&
                this.opcode == Opcodes.INVOKESTATIC &&
                this.owner == "kotlin/jvm/internal/Intrinsics" &&
                namePredicate(this.name)

    fun AbstractInsnNode.isCheckNotNullParameter() = isIntrinsic { it == "checkNotNullParameter" }
    fun AbstractInsnNode.isSpecializedTypeDefaultValueMarker() = isIntrinsic { it.startsWith("specializedTypeDefaultValueMarker") }
    fun AbstractInsnNode.isSpecializedTypeMarker() = isIntrinsic { it.startsWith("specializedTypeMarker") }
    fun AbstractInsnNode.isSpecializedTypeBoxMarker() = isIntrinsic { it.startsWith("specializedTypeBoxMarker") }
    fun AbstractInsnNode.isSpecializedTypeUnboxMarker() = isIntrinsic { it.startsWith("specializedTypeUnboxMarker") }

    val varIxdToParamIdx = calcVarIdxToParamIdx(specializedTypes)

    val widenedSlots = metadata.calcWidenedSlots(specializedTypeParameters)

    for (insn in instructions) {
        when {
            insn.isCheckNotNullParameter() -> {
                val prev = insn.previous ?: continue
                val prev2 = prev.previous ?: continue
                if (prev2 !is VarInsnNode) continue
                if (prev !is LdcInsnNode) continue
                if (specializedTypes[varIxdToParamIdx[prev2.`var`]!!].isPrimitive) {
                    instructions.set(insn, InsnNode(Opcodes.NOP))
                    instructions.set(prev, InsnNode(Opcodes.NOP))
                    instructions.set(prev2, InsnNode(Opcodes.NOP))
                }
            }

            insn.isSpecializedTypeDefaultValueMarker() -> {
                val typeParameterIndex = (insn as MethodInsnNode).name.substring("specializedTypeDefaultValueMarker".length).toInt()
                instructions.set(insn, InsnNode(specializedTypeParameters[typeParameterIndex]?.defaultOpcode ?: Opcodes.ACONST_NULL))
            }

            insn is VarInsnNode -> {
                // Adjust local variables to account for long and double types in place of specialized generics.
                // These types occupy two slots, so the indices need to be shifted.
                for (slotIndex in widenedSlots.indices) {
                    if (insn.`var` > widenedSlots[slotIndex] + slotIndex) {
                        insn.`var` += 1
                    } else {
                        break
                    }
                }

                if (insn.opcode == Opcodes.ALOAD || insn.opcode == Opcodes.ASTORE) {
                    insn.previous?.takeIf { it.isSpecializedTypeMarker() }?.let { prev ->
                        val typeParameterIndex = (prev as MethodInsnNode).name.substring("specializedTypeMarker".length).toInt()
                        instructions.set(prev, InsnNode(Opcodes.NOP))
                        specializedTypeParameters[typeParameterIndex]?.let {
                            instructions.set(insn, VarInsnNode(insn.opcode - 4 + it.loadStoreReturnOpcodeOffset, insn.`var`))
                        }
                    }
                }
            }

            insn.opcode == Opcodes.ARETURN -> {
                specSignature.returnGenericIndex?.let { returnGenericIndex ->
                    specializedTypeParameters[returnGenericIndex]?.let {
                        instructions.set(insn, InsnNode(Opcodes.IRETURN + it.loadStoreReturnOpcodeOffset))
                    }
                }
            }

            insn.isSpecializedTypeBoxMarker() -> {
                val typeParameterIndex = (insn as MethodInsnNode).name.substring("specializedTypeBoxMarker".length).toInt()
                when (val specializedType = specializedTypeParameters[typeParameterIndex]) {
                    null -> instructions.set(insn, InsnNode(Opcodes.NOP))
                    else -> specializedType.genBox(instructions, insn)
                }
            }

            insn.isSpecializedTypeUnboxMarker() -> {
                val typeParameterIndex = (insn as MethodInsnNode).name.substring("specializedTypeUnboxMarker".length).toInt()
                when (val specializedType = specializedTypeParameters[typeParameterIndex]) {
                    null -> instructions.set(insn, InsnNode(Opcodes.NOP))
                    else -> specializedType.genUnbox(instructions, insn)
                }
            }

            insn is InvokeDynamicInsnNode &&
                    insn.bsm.owner == "kotlin/jvm/specialization/BootstrapMethods" &&
                    insn.bsm.name == "bootstrapSpecializedGeneric" -> {
                val nestedSpecSignature = SpecSignature.decode(insn.bsmArgs[2] as String)
                val nestedTypeParameters = decodeSpecializedTypeParametersStr(insn.bsmArgs[3] as String).mapValues {
                    if (it.value.startsWith("i:")) {
                        val idx = it.value.substring("i:".length).toInt()
                        specializedTypeParameters[idx]
                    } else {
                        SpecializedType.fromString(it.value) ?: error("unsupported specialized type parameter: ${it.value} (in nested)")
                    }
                }
                val descArgs = Type.getArgumentTypes(insn.desc)
                var descReturnType = Type.getReturnType(insn.desc)
                for ((parameterIndex, genericIndex) in nestedSpecSignature.parameterGenericIndices) {
                    nestedTypeParameters[genericIndex]?.let { descArgs[parameterIndex] = Type.getType(it.reprDesc) }
                }
                if (nestedSpecSignature.returnGenericIndex != null) {
                    nestedTypeParameters[nestedSpecSignature.returnGenericIndex]?.let { descReturnType = Type.getType(it.reprDesc) }
                }
                insn.desc = Type.getMethodType(descReturnType, *descArgs).descriptor
                insn.bsmArgs[3] = nestedTypeParameters.entries.joinToString { (k, v) -> "$k=$v\n" }
            }
        }
    }

    instructions.removeAll { it.opcode == Opcodes.NOP }
}

/**
 * Calculate <local variable index> -> <parameter index> mapping
 */
private fun calcVarIdxToParamIdx(parameterTypes: Array<Class<*>>): Map<Int, Int> {
    val varIxdToParamIdx = HashMap<Int, Int>()
    var offset = 0
    for (x in parameterTypes.withIndex()) {
        varIxdToParamIdx[offset] = x.index
        offset += x.value.width
    }
    return varIxdToParamIdx
}

private class SpecSignature(val parameterGenericIndices: Map<Int, Int>, val returnGenericIndex: Int?) {
    companion object {
        fun decode(str: String): SpecSignature {
            val parameterGenericIndices = HashMap<Int, Int>()
            var returnGenericIndex: Int? = null
            for (line in str.lines()) {
                if (line.isEmpty()) continue
                val eqIdx = line.indexOf('=')
                val key = line.substring(0, eqIdx)
                val value = line.substring(eqIdx + 1).toInt()
                if (key == "ret") {
                    returnGenericIndex = value
                } else {
                    parameterGenericIndices[key.toInt()] = value
                }
            }
            return SpecSignature(parameterGenericIndices, returnGenericIndex)
        }
    }
}

/**
 * Decode a string of form `key1=value2\nkey2=value2\n"
 */
private fun decodeSpecializedTypeParametersStr(str: String): Map<Int, String> {
    val map = HashMap<Int, String>()
    for (line in str.lines()) {
        if (line.isEmpty()) continue
        val eqIdx = line.indexOf('=')
        val key = line.substring(0, eqIdx).toInt()
        val value = line.substring(eqIdx + 1)
        map[key] = value
    }
    return map
}

private sealed interface SpecializedType {

    val loadStoreReturnOpcodeOffset: Int
    val defaultOpcode: Int
    val reprDesc: String
    val isWide: Boolean get() = reprDesc == "J" || reprDesc == "D"

    fun genBox(instructions: InsnList, targetInsn: AbstractInsnNode)
    fun genUnbox(instructions: InsnList, targetInsn: AbstractInsnNode)

    companion object {
        fun fromString(desc: String): SpecializedType? {
            Primitive.fromString(desc)?.let { return it }
            InlineClass.fromString(desc)?.let { return it }
            return null
        }
    }
}

private data class Primitive(
    override val reprDesc: String,
    val javaName: String,
    val boxedClass: String,
    override val loadStoreReturnOpcodeOffset: Int,
    override val defaultOpcode: Int,
) : SpecializedType {

    override fun genBox(instructions: InsnList, targetInsn: AbstractInsnNode) {
        instructions.set(
            targetInsn,
            MethodInsnNode(Opcodes.INVOKESTATIC, boxedClass, "valueOf", "($reprDesc)L$boxedClass;", false)
        )
    }

    override fun genUnbox(instructions: InsnList, targetInsn: AbstractInsnNode) {
        instructions.insertBefore(targetInsn, TypeInsnNode(Opcodes.CHECKCAST, boxedClass))
        instructions.set(
            targetInsn,
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                boxedClass,
                "${javaName}Value",
                "()$reprDesc",
                false
            )
        )
    }

    override fun toString() = reprDesc

    companion object {
        fun fromString(desc: String): Primitive? {
            return when (desc) {
                "Z" -> Primitive("Z", "boolean", "java/lang/Boolean", 0, Opcodes.ICONST_0)
                "C" -> Primitive("C", "char", "java/lang/Character", 0, Opcodes.ICONST_0)
                "B" -> Primitive("B", "byte", "java/lang/Byte", 0, Opcodes.ICONST_0)
                "S" -> Primitive("S", "short", "java/lang/Short", 0, Opcodes.ICONST_0)
                "I" -> Primitive("I", "int", "java/lang/Integer", 0, Opcodes.ICONST_0)
                "F" -> Primitive("F", "float", "java/lang/Float", 2, Opcodes.FCONST_0)
                "J" -> Primitive("J", "long", "java/lang/Long", 1, Opcodes.LCONST_0)
                "D" -> Primitive("D", "double", "java/lang/Double", 3, Opcodes.DCONST_0)
                else -> null
            }
        }
    }
}

private data class InlineClass(val boxedInternalName: String, override val reprDesc: String) : SpecializedType {
    val asPrimitive = Primitive.fromString(reprDesc)
    override val loadStoreReturnOpcodeOffset get() = asPrimitive?.loadStoreReturnOpcodeOffset ?: 4
    override val defaultOpcode get() = asPrimitive?.defaultOpcode ?: Opcodes.ACONST_NULL

    override fun genBox(instructions: InsnList, targetInsn: AbstractInsnNode) {
        instructions.set(
            targetInsn,
            MethodInsnNode(Opcodes.INVOKESTATIC, boxedInternalName, "box-impl", "(${reprDesc})L$boxedInternalName;", false)
        )
    }

    override fun genUnbox(instructions: InsnList, targetInsn: AbstractInsnNode) {
        instructions.insertBefore(targetInsn, TypeInsnNode(Opcodes.CHECKCAST, boxedInternalName))
        instructions.set(
            targetInsn,
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                boxedInternalName,
                "unbox-impl",
                "()${reprDesc}",
                false
            )
        )
    }

    override fun toString() = "ic:$boxedInternalName($reprDesc"

    companion object {
        fun fromString(desc: String): InlineClass? {
            if (!desc.startsWith("ic:")) return null
            val desc = desc.substring(3)
            val sepIndex = desc.indexOf('(')
            if (sepIndex == -1) return null
            return InlineClass(desc.substring(0, sepIndex), desc.substring(sepIndex + 1))
        }
    }
}

private class Metadata(val specializedSlots: List<Int>) {
    fun calcWidenedSlots(specializedTypeParameters: Map<Int, SpecializedType>): List<Int> {
        val slots = TreeSet<Int>()
        var index = 0
        while (index < specializedSlots.size) {
            val genericIndex = specializedSlots[index++]
            val size = specializedSlots[index++]
            if (specializedTypeParameters[genericIndex]?.isWide == true) {
                repeat(size) { slots.add(specializedSlots[index++]) }
            } else {
                index += size
            }
        }
        return slots.toList()
    }

    companion object {
        fun extract(methodNode: MethodNode): Metadata {
            val annotation = methodNode.invisibleAnnotations?.find { it.desc == "Lkotlin/jvm/JvmSpecializeMetadata;" }
                ?: error("specialized method is missing the metadata annotation")

            val specializedSlotsValueIdx = annotation.values.indexOf("specializedSlots").takeIf { it != -1 }
                ?: error("invalid JvmSpecializeMetadata")

            @Suppress("UNCHECKED_CAST")
            val specializedSlots = annotation.values[specializedSlotsValueIdx + 1] as List<Int>

            return Metadata(specializedSlots)
        }
    }
}
