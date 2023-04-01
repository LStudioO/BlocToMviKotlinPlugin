package com.lstudio.bloctomvikotlinplugin.migration

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.lstudio.bloctomvikotlinplugin.Utils.deleteUnusedImports
import com.lstudio.bloctomvikotlinplugin.Utils.writeKotlinFile
import com.lstudio.bloctomvikotlinplugin.action.BlocToMviKotlinAction
import com.lstudio.bloctomvikotlinplugin.generator.StoreGenerator
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

class BlocToStoreMigration(
        private val project: Project,
        private val psiFile: KtFile,
) {
    fun migrate(bloc: KtClass) {
        val directory = psiFile.containingDirectory ?: return
        val lightClass = bloc.toLightClass()
        val name = bloc.name ?: return
        val stateClass = getStateClassFromBloc(bloc) ?: return
        val allFunctions = lightClass?.methods.orEmpty()

        LOG.info("Name $name")

        // Generate in-memory Store file
        val storeFile = StoreGenerator.generate(
                project = project,
                directory = directory,
                stateClass = stateClass,
                name = name.replace("Bloc", "Store"),
                blocFunctions = allFunctions.toList(),
        )

        // Write file
        val savedStoreFile = project.writeKotlinFile(directory, storeFile)

        // Remove unused imports
        if (savedStoreFile != null) {
            project.deleteUnusedImports(savedStoreFile)
        }
    }

    private fun getStateClassFromBloc(blocKtClass: KtClass): KtClass? {
        val inheritanceBlocSection = blocKtClass.superTypeListEntries.firstOrNull { it.typeAsUserType?.referenceExpression?.getReferencedName() == "Bloc" }
        return inheritanceBlocSection?.typeAsUserType?.typeArguments?.get(1)?.typeReference?.typeElement?.firstChild?.reference?.resolve() as? KtClass
    }

    companion object {
        private val LOG = logger<BlocToMviKotlinAction>()
    }
}