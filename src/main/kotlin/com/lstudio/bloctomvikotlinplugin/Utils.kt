package com.lstudio.bloctomvikotlinplugin

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.intellij.lang.jvm.types.JvmType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.inspections.KotlinUnusedImportInspection
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
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
        var ktFile: KtFile? = null
        executeWrite {
            val file = PsiFileFactory.getInstance(this).createFileFromText(name, KotlinLanguage.INSTANCE, text) as KtFile
            formatCode(file)
            ktFile = file
        }
        return requireNotNull(ktFile)
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

object GradleUtils {
    fun addDependencyToKtsFile(
            project: Project,
            sameModuleClass: KtClass,
            dependencyLine: String,
            event: AnActionEvent,
    ) {
        project.executeWrite {
            val buildGradle = getBuildGradleKtsFileFromKtClass(sameModuleClass) ?: return@executeWrite
            val buildPsiFile = virtualFileToPsiFile(buildGradle, project) ?: return@executeWrite
            val document = PsiDocumentManager.getInstance(project).getDocument(buildPsiFile) ?: return@executeWrite
            val dependenciesExpression = PsiTreeUtil.findChildOfType(buildPsiFile, KtBlockExpression::class.java)
                    ?.statements?.firstOrNull { it.text.startsWith("dependencies") }?.firstChild as? KtCallExpression
            val existingDependencies = dependenciesExpression?.valueArguments?.mapNotNull { it.text } ?: emptyList()
            if (existingDependencies.any { it.contains(dependencyLine) }) {
                return@executeWrite // Dependency already exists, do nothing
            }
            val blocExpression = PsiTreeUtil.findChildOfType(dependenciesExpression, KtBlockExpression::class.java)
                    ?: return@executeWrite
            val insertOffset = blocExpression.textRange.endOffset
            document.insertString(insertOffset, "${if (existingDependencies.isNotEmpty()) "\n" else ""}$dependencyLine")
            CodeStyleManager.getInstance(project).reformatText(buildPsiFile, ContainerUtil.newArrayList(buildPsiFile.textRange))
            PsiDocumentManager.getInstance(project).commitDocument(document)
            syncGradle(event)
        }
    }

    private fun syncGradle(event: AnActionEvent) {
        val am: ActionManager = ActionManager.getInstance()
        val sync: AnAction = am.getAction("Android.SyncProject")
        sync.actionPerformed(event)
    }

    private fun getBuildGradleKtsFileFromKtClass(psiClass: KtClass): VirtualFile? {
        val module = ModuleUtil.findModuleForFile(psiClass.containingFile.virtualFile, psiClass.project) ?: return null
        return getBuildFile(module)
    }

    private fun getBuildFile(module: Module): VirtualFile? {
        return ProjectBuildModel.get(module.project).getModuleBuildModel(module)?.virtualFile
    }

    private fun virtualFileToPsiFile(virtualFile: VirtualFile, project: Project): PsiFile? {
        val psiManager = PsiManager.getInstance(project)
        return psiManager.findFile(virtualFile)
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

fun JvmType.qualifiedName() = (this as? PsiClassReferenceType)?.resolve()?.qualifiedName
