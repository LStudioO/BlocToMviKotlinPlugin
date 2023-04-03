package com.lstudio.bloctomvikotlinplugin.util

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import com.lstudio.bloctomvikotlinplugin.extension.executeWrite
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass

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