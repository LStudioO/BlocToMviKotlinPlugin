package com.lstudio.bloctomvikotlinplugin.action

import com.intellij.application.options.CodeStyle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.util.PsiTreeUtil
import com.lstudio.bloctomvikotlinplugin.extension.isChildOfClass
import com.lstudio.bloctomvikotlinplugin.migration.BlocToStoreMigration
import com.lstudio.bloctomvikotlinplugin.util.BLOC_CLASS_NAME
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

class BlocToMviKotlinAction : AnAction() {

    /**
     * Enable this Action for Kotlin files only.
     */
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = editor != null && psiFile is KtFile
    }

    /**
     * Loop through file and rename variable references from snake_case to pascalCase.
     */
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.getRequiredData(CommonDataKeys.PROJECT)
        val psiFile = event.getRequiredData(CommonDataKeys.PSI_FILE) as KtFile

        val blocEntries = ApplicationManager.getApplication().runReadAction(Computable {
            PsiTreeUtil.collectElements(psiFile) { element ->
                element is KtClass && element.isChildOfClass(BLOC_CLASS_NAME)
            }
        }).filterIsInstance<KtClass>()

        if (blocEntries.isEmpty()) {
            val content = "No Bloc implementations found to refactor."
            notify(project, content, NotificationType.ERROR)
            LOG.error(content)
        } else {
            val content = "${blocEntries.size} entries have been found"
            LOG.info(content)

            // Apply file specific code style to the project
            applyCodeStyleSettingsFromCurrentFile(psiFile)

            val migration = BlocToStoreMigration(
                    psiFile = psiFile,
                    event = event,
            )

            blocEntries.forEach { blocKtClass ->
                migration.migrate(blocKtClass)
                notify(project, "Migration of ${blocKtClass.name.orEmpty()} has been finished", NotificationType.INFORMATION)
            }
        }
    }

    private fun applyCodeStyleSettingsFromCurrentFile(psiFile: PsiFile) {
        val settingsManager = CodeStyleSettingsManager.getInstance(psiFile.project)

        val projectSettings = CodeStyle.getSettings(psiFile)
        settingsManager.setTemporarySettings(projectSettings)
    }

    private fun notify(project: Project?, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("BlocToMviKotlin Notification Group")
                .createNotification(content, type)
                .notify(project)
    }

    companion object {
        private val LOG = logger<BlocToMviKotlinAction>()
    }
}