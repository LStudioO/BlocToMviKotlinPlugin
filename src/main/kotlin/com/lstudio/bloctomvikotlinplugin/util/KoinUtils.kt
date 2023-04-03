package com.lstudio.bloctomvikotlinplugin.util

import com.android.tools.idea.kotlin.getQualifiedName
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.lstudio.bloctomvikotlinplugin.extension.addImports
import com.lstudio.bloctomvikotlinplugin.extension.executeWrite
import com.lstudio.bloctomvikotlinplugin.extension.removeImports
import com.lstudio.bloctomvikotlinplugin.extension.toEmptyObjectDeclaration
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression

object KoinUtils {
    // Find all references to the given KtClass element in the project
    fun replaceBlocDiDeclarationWithStore(
            blocKtClass: KtClass,
            storeFactoryKtClass: KtClass,
    ) {
        val projectScope = GlobalSearchScope.allScope(blocKtClass.project)
        val primaryConstructor = blocKtClass.primaryConstructor ?: return

        // Use ReferencesSearch to find all references to the primary constructor calls
        val search = ReferencesSearch.search(primaryConstructor, projectScope)

        // Iterate through the results
        search.forEach { reference ->
            // The reference element of the PSI element that references the ktClass
            val referenceElement = reference.element
            val parentFile = referenceElement.containingFile as? KtFile ?: return@forEach

            // Take the CALL_EXPRESSION of the constructor
            val callExpression = referenceElement.parent as? KtCallExpression ?: return@forEach

            // Find the package of the caller function
            val lambdaArgument = PsiTreeUtil.getParentOfType(callExpression, KtLambdaArgument::class.java)
            val funPackage = (lambdaArgument?.parent?.getChildOfType<KtNameReferenceExpression>()?.resolve() as KtNamedFunction).containingKtFile.packageFqName.asString()

            // If it's Koin related
            if (funPackage.startsWith("org.koin")) {
                val factory = KtPsiFactory(blocKtClass.project)
                blocKtClass.project.executeWrite {
                    // Find the scope Koin function (factory, single, scoped, etc.)
                    val scopeFunction = PsiTreeUtil.getParentOfType(callExpression, KtCallExpression::class.java)
                            ?: return@executeWrite

                    val element = callExpression.originalElement

                    val storeFactoryElement = factory.createBlock(storeFactoryKtClass.toEmptyObjectDeclaration())
                            .getChildOfType<KtCallExpression>() ?: return@executeWrite

                    // Replace Bloc declaration with Store Factory declaration
                    element.replace(storeFactoryElement)

                    // Get the scope function name
                    val scopeName = (scopeFunction.referenceExpression() as? KtNameReferenceExpression)?.getReferencedName()
                            ?: "factory"

                    // Create a new declaration with the same scope name for the Store
                    val newElement1 = factory.createBlock("""
                        $scopeName {
                        get<${storeFactoryKtClass.name}>().create()
                        }
                    """.trimIndent()).getChildOfType<KtCallExpression>() ?: return@executeWrite

                    // Insert the declaration after the factory declaration
                    scopeFunction.parent.addAfter(newElement1, scopeFunction)
                    scopeFunction.parent.addAfter(factory.createNewLine(), scopeFunction)

                    // Add imports
                    parentFile.addImports(
                            listImport = listOfNotNull(
                                    storeFactoryKtClass.getQualifiedName(),
                            ),
                    )

                    // Remove Bloc import
                    parentFile.removeImports(
                            listImport = listOfNotNull(
                                    blocKtClass.getQualifiedName()
                            ),
                    )

                    // Reformat file
                    CodeStyleManager.getInstance(parentFile.project).reformat(parentFile)
                }
            }
        }
    }
}