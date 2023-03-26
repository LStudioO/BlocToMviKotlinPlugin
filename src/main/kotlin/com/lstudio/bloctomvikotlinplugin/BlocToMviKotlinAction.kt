package com.lstudio.bloctomvikotlinplugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.pom.Navigatable


class BlocToMviKotlinAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val currentProject: Project = event.project ?: return
        val message: StringBuilder = StringBuilder(event.presentation.text + " Selected!")
        val selectedElement: Navigatable? = event.getData(CommonDataKeys.NAVIGATABLE)
        if (selectedElement != null) {
            message.append("\nSelected Element: ").append(selectedElement)
        }

        val title: String = event.presentation.description
        Messages.showMessageDialog(
                currentProject,
                message.toString(),
                title,
                Messages.getInformationIcon())
    }
}