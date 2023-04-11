package com.lstudio.bloctomvikotlinplugin.extension

import com.intellij.lang.jvm.types.JvmType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.inspections.KotlinUnusedImportInspection
import org.jetbrains.kotlin.psi.KtFile

fun Project.executeWrite(runnable: Runnable) {
    WriteCommandAction.runWriteCommandAction(this, runnable)
}

fun PsiPrimitiveType.toKotlinType(): String? {
    return when (this.canonicalText) {
        "byte" -> "Byte"
        "short" -> "Short"
        "int" -> "Int"
        "long" -> "Long"
        "float" -> "Float"
        "double" -> "Double"
        "char" -> "Char"
        "boolean" -> "Boolean"
        else -> null
    }
}

fun JvmType.qualifiedName() = (this as? PsiClassReferenceType)?.resolve()?.qualifiedName

fun Project.writeKotlinFile(
        directory: PsiDirectory,
        ktFile: KtFile,
): KtFile? {
    var finalFile: PsiElement? = null
    executeWrite {
        formatCode(ktFile)
        finalFile = directory.add(ktFile)
    }
    return finalFile as? KtFile
}

fun PsiFile.deleteUnusedImports() {
    when (this) {
        is PsiJavaFile -> {
            removeUnusedJavaImports()
        }
        is KtFile -> {
            removeUnusedKotlinImports()
        }
    }
}

fun Project.createKotlinFileFromText(
        name: String,
        text: String,
): KtFile {
    var ktFile: KtFile? = null
    executeWrite {
        val file = PsiFileFactory.getInstance(this).createFileFromText(name, KotlinLanguage.INSTANCE, text) as KtFile
        formatCode(file)
        ktFile = file
    }
    return requireNotNull(ktFile)
}

inline fun <reified T : PsiElement> PsiElement.findChildOfType(): T? {
    return PsiTreeUtil.findChildOfType(this, T::class.java)
}

inline fun <reified T : PsiElement> PsiElement.findChildrenOfType(): List<T> {
    return PsiTreeUtil.findChildrenOfType(this, T::class.java).toList()
}

private fun KtFile.removeUnusedKotlinImports() {
    project.executeWrite {
        val importList = this.importList
        val analyzeRes = KotlinUnusedImportInspection.analyzeImports(this)
        val unusedImports = analyzeRes?.unusedImports ?: return@executeWrite
        importList?.imports?.forEach {
            if (it in unusedImports) {
                it.delete()
            }
        }
    }
}

private fun PsiJavaFile.removeUnusedJavaImports() {
    project.executeWrite {
        val javaCodeStyleManager = JavaCodeStyleManager.getInstance(project)
        val findRedundantImports = javaCodeStyleManager.findRedundantImports(this)
        if (findRedundantImports.isNullOrEmpty()) {
            return@executeWrite
        }
        javaCodeStyleManager.removeRedundantImports(this)
    }
}

private fun formatCode(ktFile: KtFile) {
    CodeStyleManager.getInstance(ktFile.project).reformatText(ktFile,
            ContainerUtil.newArrayList(ktFile.textRange))
}

fun <T, E : Exception> Project.runProcessWithProgressSynchronously(
        message: String,
        process: ThrowableComputable<T, E>
): T {
    return ProgressManager.getInstance().runProcessWithProgressSynchronously(
            process, message, false, this
    )
}