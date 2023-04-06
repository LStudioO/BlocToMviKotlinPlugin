package com.lstudio.bloctomvikotlinplugin.util

import com.android.tools.idea.kotlin.getQualifiedName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.isAncestor
import com.intellij.psi.util.parentOfType
import com.intellij.usageView.UsageInfo
import com.lstudio.bloctomvikotlinplugin.extension.*
import com.lstudio.bloctomvikotlinplugin.generator.StoreIntent
import com.lstudio.bloctomvikotlinplugin.visitor.RemoveUnnecessarySafeCallsVisitor
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.findUsages.KotlinFindUsagesHandlerFactory
import org.jetbrains.kotlin.idea.findUsages.KotlinFunctionFindUsagesOptions
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.nj2k.postProcessing.type
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression

object PostProcessingUtils {
    private data class BlocClassUsageWithName(
            val usageClass: KtClass,
            val newName: String,
            val oldName: String,
    )

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

    fun replaceBlocWithStore(
            bloc: KtClass,
            storeFile: KtFile,
            storeQualifiedName: String,
            storeIntents: List<StoreIntent>,
    ) {
        val project = bloc.project
        val storeClass = storeFile.findChildOfType<KtClass>() ?: return

        // Replace a bloc functions calls with the store calls
        replaceBlocCalls(
                bloc = bloc,
                storeQualifiedName = storeQualifiedName,
                storeIntents = storeIntents,
        )

        // Replace class usages
        val usageClasses = replaceClassUsages(
                oldClass = bloc,
                newClass = storeClass,
        ) { oldName ->
            oldName.replace(BLOC_CLASS_NAME, "Store", ignoreCase = true)
        }

        // Get rid of class delegates
        usageClasses.forEach { usageHolder ->
            removeProcessorDelegate(
                    usageBlocClass = usageHolder.usageClass,
                    storeVariableName = usageHolder.newName,
                    blocClass = bloc,
                    storeName = storeQualifiedName,
                    intents = storeIntents,
            )
        }

        // Remove parameter name in usage classes constructors
        usageClasses.forEach {
            replaceBlocUserParameterName(it)
        }

        // Remove safe calls
        usageClasses.forEach { usageHolder ->
            usageHolder.usageClass.accept(RemoveUnnecessarySafeCallsVisitor())
        }

        // Shorten full qualifiers of the Store and remove unused imports
        usageClasses.map { it.usageClass }.forEach { ktClass ->
            project.executeWrite {
                ShortenReferences.DEFAULT.process(ktClass.containingKtFile)
            }
            ktClass.containingKtFile.deleteUnusedImports()
        }
    }

    private fun replaceBlocUserParameterName(
            blocUser: BlocClassUsageWithName,
    ) {
        val project = blocUser.usageClass.project
        val projectScope = GlobalSearchScope.projectScope(project)
        val primaryConstructor = blocUser.usageClass.primaryConstructor ?: return
        // Find usages of the constructor
        val usages = ReferencesSearch.search(primaryConstructor, projectScope)
        val psiFactory = KtPsiFactory(project)
        usages.forEach { reference ->
            val element = reference.element
            if (element is KtNameReferenceExpression) {
                // If the Bloc is referenced in a value parameter
                val parent = element.parentOfType<KtCallExpression>() ?: return@forEach
                val valueArguments = parent.valueArguments
                // Find the old bloc argument
                val foundArg = valueArguments.find { it.getArgumentName()?.asName?.identifier == blocUser.oldName }
                        ?: return@forEach
                val argName = foundArg.getArgumentName() ?: return@forEach
                val newArgName = psiFactory.createArgument(
                        expression = null,
                        name = Name.identifier(blocUser.newName)
                ).getArgumentName() ?: return@forEach
                // Replace
                project.executeWrite {
                    argName.replace(newArgName)
                }
            }
        }
    }

    private fun replaceBlocCalls(
            bloc: KtClass,
            storeQualifiedName: String,
            storeIntents: List<StoreIntent>,
    ) {
        val project = bloc.project
        storeIntents.forEach { storeIntent ->
            val namedFunction = storeIntent.originalFunction
            val functionName = namedFunction.name ?: return@forEach

            // Create a PsiFactory
            val psiFactory = KtPsiFactory(bloc.project)

            val factory = KotlinFindUsagesHandlerFactory(namedFunction.project)

            // Create a KotlinFindUsagesHandler for the named function
            val findUsagesHandler = factory.createFindUsagesHandler(namedFunction, false)

            val elementsToReplace = mutableListOf<KtCallExpression>()
            // Create a processor to handle the usages
            val processor = com.intellij.util.Processor<UsageInfo> { usage ->
                // Save call expressions
                usage.element?.parentOfType<KtCallExpression>()?.let { elementsToReplace.add(it) }
                true
            }

            // create find usages options
            val options = KotlinFunctionFindUsagesOptions(project)

            // process the usages of the named function
            findUsagesHandler.processElementUsages(namedFunction, processor, options)

            project.executeWrite {
                elementsToReplace.forEach { element ->
                    // Get the old function name
                    val oldFunctionText = element.text.orEmpty()
                    // Parse the params
                    val splitResult = oldFunctionText.split(functionName)
                    if (splitResult.size != 2) return@forEach
                    // Get the parameters line
                    val paramsLine = splitResult[1]
                    // Clean the params
                    val cleanParams = if (storeIntent.hasParams) {
                        paramsLine
                    } else {
                        paramsLine.replace("(", "").replace(")", "")
                    }
                    // Form a body of the new function
                    val newFuncBody = "accept(${storeQualifiedName}.Intent.${storeIntent.name}$cleanParams)"
                    // Create a new call expression with the updated function name and arguments
                    val newCallExpression = psiFactory.createExpression(newFuncBody) as KtCallExpression
                    // Replace the old call
                    element.replace(newCallExpression)
                }
            }
        }

        // replace 'currentState' references
        replaceCurrentStateReferences(project, bloc)

        // replace asFlow function
        replaceAsFlowReferences(project, bloc)
    }

    private fun replaceCurrentStateReferences(
            project: Project,
            bloc: KtClass,
    ) {
        // Find 'currentState' property
        val element = bloc.superClasses().find { it.name == BLOC_CLASS_NAME }?.declarations?.find { it.name == "currentState" } as? KtProperty
                ?: return

        val psiFactory = KtPsiFactory(project)
        val newReference = psiFactory.createSimpleName("state")

        // Find usages of the property
        val usages = ReferencesSearch.search(element).findAll()

        project.executeWrite {
            usages.map { it.element }
                    .filterIsInstance<KtNameReferenceExpression>()
                    .filter { reference ->
                        // Let assume the expression is bloc.currentState
                        // Find the name of the expression (bloc)
                        val callerNameExpression = (reference.parentOfType<KtDotQualifiedExpression>())?.firstChild as? KtNameReferenceExpression
                        // Get the type of the caller
                        val callerType = (callerNameExpression?.resolve() as? KtParameter)?.type()
                        callerType?.getQualifiedName()?.asString() == bloc.getQualifiedName()
                    }
                    .forEach { reference ->
                        reference.replace(newReference)
                    }
        }
    }

    private fun replaceAsFlowReferences(
            project: Project,
            bloc: KtClass,
    ) {
        // Find 'asFlow' function
        val element = bloc.superClasses().find { it.name == BLOC_CLASS_NAME }?.declarations?.find { it.name == "asFlow" } as? KtNamedFunction
                ?: return

        val psiFactory = KtPsiFactory(project)
        val newReference = psiFactory.createSimpleName("states")

        // Find usages of the property
        val usages = ReferencesSearch.search(element).findAll()

        project.executeWrite {
            val references = usages.map { it.element }
                    .filterIsInstance<KtNameReferenceExpression>()
                    .filter { reference ->
                        // Let assume the expression is bloc.currentState
                        // Find the name of the expression (bloc)
                        val callerFunc = reference.parentOfType<KtCallExpression>() ?: return@filter false
                        val callerNameExpression = (callerFunc.parentOfType<KtDotQualifiedExpression>())?.firstChild as? KtNameReferenceExpression
                        // Get the type of the caller
                        val callerType = (callerNameExpression?.resolve() as? KtParameter)?.type()
                        callerType?.getQualifiedName()?.asString() == bloc.getQualifiedName()
                    }
                    .mapNotNull { it.parentOfType<KtCallExpression>() }
            val newRefs = references.map { reference ->
                reference.replace(newReference) as KtElement
            }
            newRefs.forEach { ref ->
                ref.containingKtFile.addImports(listOf("com.arkivanov.mvikotlin.extensions.coroutines.states"))
            }
        }
    }

    private fun removeProcessorDelegate(
            usageBlocClass: KtClass,
            storeVariableName: String,
            blocClass: KtClass,
            storeName: String,
            intents: List<StoreIntent>,
    ) {
        val project = usageBlocClass.project
        val factory = KtPsiFactory(project)
        val interfaceClasses = mutableListOf<KtClass>()
        // Remove delegated super types
        project.executeWrite {
            usageBlocClass.superTypeListEntries.forEach { entry ->
                // Find a delegation entry
                if (entry is KtDelegatedSuperTypeEntry) {
                    // Check if it's an interface delegation
                    val interfaceClass = entry.getKtClass().takeIf { it?.isInterface() == true }
                            ?: return@forEach
                    // If the interface delegation belongs to the bloc
                    if (blocClass.superInterfaces().any { interfaceClass.isAncestor(it) }) {
                        val text = entry.typeReference?.text ?: return@forEach
                        val newEntry = factory.createSuperTypeEntry(text) as PsiElement
                        // Replace the interface delegation with inheritance
                        entry.replace(newEntry)
                        interfaceClasses.add(interfaceClass)
                    }
                }
            }
        }

        // Implement functions previously covered by the delegate
        interfaceClasses.forEach { interfaceClass ->
            implementBlocInterfaceMethods(
                    interfaceFile = interfaceClass.containingKtFile,
                    implementingClass = usageBlocClass,
                    funcBody = { ktNamedFunction ->
                        val storeIntent = intents.find { it.originalFunction.name == ktNamedFunction.name }
                        // If a corresponding intent exists
                        if (storeIntent != null) {
                            val intentFunction = ktNamedFunction.name.orEmpty().replaceFirstChar { it.uppercaseChar() }
                            val params = if (storeIntent.hasParams) {
                                val parametersText = ktNamedFunction.valueParameters.joinToString(
                                        prefix = "\n", postfix = "\n", separator = "\n"
                                ) { parameter ->
                                    "${parameter.name} = ${parameter.name},"
                                }
                                "($parametersText)"
                            } else {
                                ""
                            }
                            "${storeVariableName}.accept(\n${storeName}.Intent.${intentFunction}$params\n)"
                        } else {
                            "TODO()"
                        }
                    },
            )
        }
    }

    private fun implementBlocInterfaceMethods(
            interfaceFile: KtFile,
            implementingClass: KtClass,
            funcBody: (KtNamedFunction) -> String,
    ) {
        val project = implementingClass.project
        val factory = KtPsiFactory(project)
        val interfaceClass = interfaceFile.children.filterIsInstance<KtClass>().firstOrNull()

        if (interfaceClass != null) {
            project.executeWrite {
                // TODO: use qualified name for the function instead of manually adding imports
                val classesToImport = mutableListOf<String>()
                // Find all parents of the bloc interface (processor)
                val hierarchy = interfaceClass.getTypeHierarchy()

                for (type in hierarchy) {
                    val declarations = type.declarations.filterIsInstance<KtNamedFunction>()

                    for (interfaceMethod in declarations) {
                        // Replace the function with a new body
                        val funDeclaration = KtTokens.OVERRIDE_KEYWORD.value + " " + interfaceMethod.text + " {${funcBody(interfaceMethod)}}"
                        val implementingMethod = factory.createFunction(funDeclaration)
                        implementingMethod.typeReference = interfaceMethod.typeReference
                        implementingClass.addDeclaration(implementingMethod)

                        // Add imports of the function arguments and returned types
                        val importNames = interfaceMethod.getReturnedTypeQualifiedNames() + interfaceMethod.getArgumentsQualifiedNames()
                        classesToImport.addAll(importNames)
                    }
                }

                implementingClass.containingKtFile.addImports(classesToImport.distinct())
            }
        }
    }

    private fun replaceClassUsages(
            oldClass: KtClass,
            newClass: KtClass,
            newName: (String) -> String,
    ): List<BlocClassUsageWithName> {
        // A list of classes where Bloc was replaced with Store
        val modifiedClasses = mutableListOf<BlocClassUsageWithName>()
        val project = oldClass.project
        project.executeWrite {
            val projectScope = GlobalSearchScope.projectScope(project)
            // Find usages
            val usages = ReferencesSearch.search(oldClass, projectScope)
            usages.forEach { reference ->
                val element = reference.element
                if (element is KtNameReferenceExpression) {
                    // If the Bloc is referenced in a value parameter
                    val valueParamNode = element.parentOfType<KtParameter>() ?: return@forEach
                    val typeReference = element.parentOfType<KtTypeReference>() ?: return@forEach
                    val nameNode = valueParamNode.nameIdentifier ?: return@forEach
                    // Bloc name
                    val oldName = nameNode.text.orEmpty()
                    // Get a factory to create a new reference and identified
                    val ktFactory = KtPsiFactory(project)
                    // Create a new identifier for the future Store based on the Bloc name
                    val newStoreName = newName.invoke(oldName)
                    val newNameElement = ktFactory.createNameIdentifier(newStoreName)
                    // Replace the identifier
                    nameNode.replace(newNameElement)
                    // Replace the Bloc reference with the Store reference
                    val fullName = newClass.fqName?.asString() ?: return@forEach
                    val newReference = ktFactory.createType(fullName)
                    typeReference.replace(newReference) as KtTypeReference
                    // Save classes where Bloc was replaced with Store
                    val ktClassWithReference = valueParamNode.containingClass()
                    if (ktClassWithReference != null) {
                        val item = BlocClassUsageWithName(
                                usageClass = ktClassWithReference,
                                newName = newStoreName,
                                oldName = oldName,
                        )
                        modifiedClasses.add(item)

                        // Replace the old bloc identifier in the class
                        ktClassWithReference.replaceIdentifier(oldName, newStoreName)
                    }
                }
            }
        }

        return modifiedClasses
    }
}