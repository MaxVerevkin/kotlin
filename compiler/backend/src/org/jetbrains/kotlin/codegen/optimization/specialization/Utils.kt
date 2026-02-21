/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.optimization.specialization

@JvmInline
internal value class GenericIndex(val index: Int)

//internal fun printInsn(insn: AbstractInsnNode): String {
//    val printer: Printer = Textifier()
//    insn.accept(TraceMethodVisitor(printer))
//    val sw = StringWriter()
//    printer.print(PrintWriter(sw))
//    printer.getText().clear()
//    return sw.toString().trimEnd()
//}
