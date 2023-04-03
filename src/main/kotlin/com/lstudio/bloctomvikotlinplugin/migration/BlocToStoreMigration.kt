package com.lstudio.bloctomvikotlinplugin.migration

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.lstudio.bloctomvikotlinplugin.GradleUtils
import com.lstudio.bloctomvikotlinplugin.Utils.deleteUnusedImports
import com.lstudio.bloctomvikotlinplugin.Utils.writeKotlinFile
import com.lstudio.bloctomvikotlinplugin.action.BlocToMviKotlinAction
import com.lstudio.bloctomvikotlinplugin.generator.StoreFactoryGenerator
import com.lstudio.bloctomvikotlinplugin.generator.StoreGenerator
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

class BlocToStoreMigration(
        private val project: Project,
        private val psiFile: KtFile,
        private val event: AnActionEvent,
) {
    fun migrate(bloc: KtClass) {
        val directory = psiFile.containingDirectory ?: return
        val lightClass = bloc.toLightClass()
        val name = bloc.name ?: return
        val stateClass = getStateClassFromBloc(bloc) ?: return
        val allFunctions = lightClass?.methods.orEmpty().toList()
        val storeInterfaceName = name.replace("Bloc", "Store")

        LOG.info("Name $name")

        // Generate in-memory Store file
        val storeFile = StoreGenerator.generate(
                project = project,
                directory = directory,
                stateClass = stateClass,
                name = storeInterfaceName,
                blocFunctions = allFunctions,
        )

        // Write file
        val savedStoreFile = project.writeKotlinFile(directory, storeFile)

        // Remove unused imports
        if (savedStoreFile != null) {
            project.deleteUnusedImports(savedStoreFile)
        }

        // Generate in-memory StoreFactory file
        val storeFactoryFile = StoreFactoryGenerator.generate(
                project = project,
                directory = directory,
                stateClass = stateClass,
                storeInterfaceName = storeInterfaceName,
                bloc = bloc,
        )

        // Write file
        val savedStoreFactoryFile = project.writeKotlinFile(directory, storeFactoryFile)

        // Remove unused imports
        if (savedStoreFactoryFile != null) {
            project.deleteUnusedImports(savedStoreFactoryFile)
        }

        // Add MVIKotlin dependency
        GradleUtils.addDependencyToKtsFile(
                project = project,
                sameModuleClass = bloc,
                dependencyLine = "implementation(libs.bundles.mvi.kotlin)",
                event = event,
        )
    }

    private fun getStateClassFromBloc(blocKtClass: KtClass): KtClass? {
        val inheritanceBlocSection = blocKtClass.superTypeListEntries.firstOrNull { it.typeAsUserType?.referenceExpression?.getReferencedName() == "Bloc" }
        return inheritanceBlocSection?.typeAsUserType?.typeArguments?.get(1)?.typeReference?.typeElement?.firstChild?.reference?.resolve() as? KtClass
    }

    companion object {
        private val LOG = logger<BlocToMviKotlinAction>()
    }
}