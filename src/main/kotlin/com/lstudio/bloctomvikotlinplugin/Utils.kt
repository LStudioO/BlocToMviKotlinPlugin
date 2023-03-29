package com.lstudio.bloctomvikotlinplugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile

object Utils {
    fun Project.writeKotlinFileFromText(
            directory: PsiDirectory,
            name: String,
            text: String,
    ) {
        executeWrite {
            val file = createKotlinFileFromText(name, text)
            formatCode(file)
            directory.add(file)
        }
    }

    private fun formatCode(ktFile: KtFile) {
        CodeStyleManager.getInstance(ktFile.project).reformatText(ktFile,
                ContainerUtil.newArrayList(ktFile.textRange))
    }

    private fun Project.createKotlinFileFromText(
            name: String,
            text: String,
    ): KtFile {
        return PsiFileFactory.getInstance(this).createFileFromText(name, KotlinLanguage.INSTANCE, text) as KtFile
    }
}

fun Project.executeWrite(runnable: Runnable) {
    WriteCommandAction.runWriteCommandAction(this, runnable)
}