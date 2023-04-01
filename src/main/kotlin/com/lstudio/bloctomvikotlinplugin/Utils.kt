package com.lstudio.bloctomvikotlinplugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.inspections.KotlinUnusedImportInspection
import org.jetbrains.kotlin.psi.KtFile

object Utils {
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

    fun Project.deleteUnusedImports(file: PsiFile) {
        when (file) {
            is PsiJavaFile -> {
                removeUnusedJavaImports(file)
            }
            is KtFile -> {
                removeUnusedKotlinImports(file)
            }
        }
    }

    fun Project.createKotlinFileFromText(
            name: String,
            text: String,
    ): KtFile {
        val file = PsiFileFactory.getInstance(this).createFileFromText(name, KotlinLanguage.INSTANCE, text) as KtFile
        formatCode(file)
        return file
    }

    private fun Project.removeUnusedKotlinImports(file: KtFile) {
        executeWrite {
            val importList = file.importList
            val analyzeRes = KotlinUnusedImportInspection.analyzeImports(file)
            val unusedImports = analyzeRes?.unusedImports ?: return@executeWrite
            importList?.imports?.forEach {
                if (it in unusedImports) {
                    it.delete()
                }
            }
        }
    }

    private fun Project.removeUnusedJavaImports(file: PsiJavaFile) {
        executeWrite {
            val javaCodeStyleManager = JavaCodeStyleManager.getInstance(this)
            val findRedundantImports = javaCodeStyleManager.findRedundantImports(file)
            if (findRedundantImports.isNullOrEmpty()) {
                return@executeWrite
            }
            javaCodeStyleManager.removeRedundantImports(file)
        }
    }

    private fun formatCode(ktFile: KtFile) {
        CodeStyleManager.getInstance(ktFile.project).reformatText(ktFile,
                ContainerUtil.newArrayList(ktFile.textRange))
    }
}

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