package com.lstudio.bloctomvikotlinplugin.migration

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.lstudio.bloctomvikotlinplugin.action.BlocToMviKotlinAction
import com.lstudio.bloctomvikotlinplugin.extension.executeWrite
import com.lstudio.bloctomvikotlinplugin.extension.writeKotlinFile
import com.lstudio.bloctomvikotlinplugin.generator.StoreFactoryGenerator
import com.lstudio.bloctomvikotlinplugin.generator.StoreGenerator
import com.lstudio.bloctomvikotlinplugin.util.BLOC_CLASS_NAME
import com.lstudio.bloctomvikotlinplugin.util.GradleUtils
import com.lstudio.bloctomvikotlinplugin.util.PostProcessingUtils
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.inspections.KotlinUnusedImportInspection
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.plugins.groovy.lang.psi.util.childrenOfType

class BlocToStoreMigration(
        private val psiFile: KtFile,
        private val event: AnActionEvent,
) {
    fun migrate(bloc: KtClass) {
        val project = bloc.project
        val directory = psiFile.containingDirectory ?: return
        val lightClass = bloc.toLightClass()
        val name = bloc.name ?: return
        val stateClass = getStateClassFromBloc(bloc) ?: return
        val allFunctions = lightClass?.methods.orEmpty().toList()
        val storeInterfaceName = name.replace(BLOC_CLASS_NAME, "Store")

        LOG.info("Name $name")

        // Add MVIKotlin dependency
        GradleUtils.addDependencyToKtsFile(
                project = project,
                sameModuleClass = bloc,
                dependencyLine = "implementation(libs.bundles.mvi.kotlin)",
                event = event,
        )

        // Generate in-memory Store file
        val storeCreationResult = StoreGenerator.generate(
                project = project,
                directory = directory,
                stateClass = stateClass,
                name = storeInterfaceName,
                blocFunctions = allFunctions,
        )

        val storeFile = storeCreationResult.file

        // Write file
        val savedStoreFile = project.writeKotlinFile(directory, storeFile) ?: return

        // Optimize Store imports
        project.optimizeImports(savedStoreFile)

        // Generate in-memory StoreFactory file
        val storeFactoryFile = StoreFactoryGenerator.generate(
                project = project,
                directory = directory,
                stateClass = stateClass,
                storeInterfaceName = storeInterfaceName,
                bloc = bloc,
        )

        // Write file
        val savedStoreFactoryFile = project.writeKotlinFile(directory, storeFactoryFile) ?: return

        // Optimize StoreFactory imports
        project.optimizeImports(savedStoreFactoryFile)

        // Add DI declarations
        PostProcessingUtils.replaceBlocDiDeclarationWithStore(
                blocKtClass = bloc,
                storeFactoryKtClass = storeFactoryFile.childrenOfType<KtClass>().first(),
        )

        // Replace usages
        PostProcessingUtils.replaceBlocWithStore(
                bloc = bloc,
                storeFile = savedStoreFile,
                storeQualifiedName = storeCreationResult.storeFullQualifierName,
                storeIntents = storeCreationResult.intents,
        )
    }

    private fun Project.optimizeImports(file: KtFile) {
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            val unusedImports = runReadAction {
                KotlinUnusedImportInspection.analyzeImports(file)
            }

            ApplicationManager.getApplication().invokeAndWait {
                executeWrite {
                    unusedImports?.unusedImports?.forEach { importDirective ->
                        (importDirective as? KtImportDirective)?.delete()
                    }
                }
            }
        }, "Optimizing imports", false, this)
    }

    // TODO: Refactor
    private fun getStateClassFromBloc(blocKtClass: KtClass): KtClass? {
        val inheritanceBlocSection = blocKtClass.superTypeListEntries.firstOrNull { it.typeAsUserType?.referenceExpression?.getReferencedName() == BLOC_CLASS_NAME }
        return inheritanceBlocSection?.typeAsUserType?.typeArguments?.get(1)?.typeReference?.typeElement?.firstChild?.reference?.resolve() as? KtClass
    }

    companion object {
        private val LOG = logger<BlocToMviKotlinAction>()
    }
}