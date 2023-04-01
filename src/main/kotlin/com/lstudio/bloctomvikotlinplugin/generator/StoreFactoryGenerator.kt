package com.lstudio.bloctomvikotlinplugin.generator

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.lstudio.bloctomvikotlinplugin.Utils.createKotlinFileFromText
import com.lstudio.bloctomvikotlinplugin.qualifiedName
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.core.getPackage
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.nj2k.postProcessing.type
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getValueParameters

object StoreFactoryGenerator {
    fun generate(
            project: Project,
            directory: PsiDirectory,
            storeInterfaceName: String,
            stateClass: KtClass,
            bloc: KtClass,
    ): KtFile {
        val stateClassName = stateClass.name.orEmpty()
        val filePackage = directory.getPackage()?.qualifiedName
        val stateLightClass = stateClass.toLightClass()
        val blocFunctions = bloc.toLightClass()?.methods.orEmpty()
        val constructorFunction = blocFunctions.firstOrNull { it.isConstructor }
        val dependencyClasses = findDependencies(constructorFunction)

        var importSection: String = dependencyClasses
                .mapNotNull { it.second.qualifiedName }
                .plus(stateLightClass?.qualifiedName)
                .map { "import $it" }
                .joinToString(separator = "\n") { it }

        val userDependenciesString = dependencyClasses.map { dep ->
            "private val ${dep.first}: ${dep.second.name},"
        }.joinToString(separator = "\n") { it }.removeSuffix("\n")

        val mviKotlinDependencies = """
                private val storeFactory: StoreFactory,
                private val dispatcherProvider: CoroutineDispatcherProvider,
        """.trimIndent()

        val dependencies = if (userDependenciesString.isEmpty()) {
            mviKotlinDependencies
        } else {
            mviKotlinDependencies + "\n" + userDependenciesString
        }

        val coroutineScopeFunctions = findCoroutineScopeUsages(bloc)
        val coroutineScopeFunctionsString = coroutineScopeFunctions
                .joinToString(separator = "\n\n") {
                    // Add tabs to make a commented code good-looking
                    val lines = it.text.lines()
                    lines.first() + "\n" +
                            lines.drop(1).map { line -> "                $line" }.joinToString(separator = "\n") { it }
                }

        val needBootstrapper = coroutineScopeFunctions.isNotEmpty()

        val bootstrapper = if (needBootstrapper) {
            """
            coroutineBootstrapper {
                /*
                $coroutineScopeFunctionsString
                */
            }
        """.trimIndent()
        } else {
            "null"
        }

        if (needBootstrapper) {
            importSection = "import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper\n$importSection"
        }

        val executorFactoryImplementation = """
            
        """.trimIndent()

        val textRepresentation = """
            package $filePackage
            
            import com.arkivanov.mvikotlin.core.store.Store
            import com.arkivanov.mvikotlin.core.store.StoreFactory
            import com.arkivanov.mvikotlin.core.utils.ExperimentalMviKotlinApi
            import com.arkivanov.mvikotlin.core.utils.JvmSerializable
            import com.arkivanov.mvikotlin.extensions.coroutines.coroutineExecutorFactory
            import com.core.utils.coroutine.dispatcher.CoroutineDispatcherProvider
            $importSection
            
            @OptIn(ExperimentalMviKotlinApi::class)
            class ${storeInterfaceName}Factory(
                $dependencies
            ) {
              fun create(
                  name: String = "$storeInterfaceName",
                  initialState: $stateClassName = $stateClassName(TODO()),
              ): $storeInterfaceName = object : $storeInterfaceName, Store<$storeInterfaceName.Intent, $stateClassName, $storeInterfaceName.Label> by storeFactory.create<$storeInterfaceName.Intent, Action, Message, $stateClassName, $storeInterfaceName.Label>(
                  name = name,
                  initialState = initialState,
                  bootstrapper = $bootstrapper,
                  executorFactory = coroutineExecutorFactory(dispatcherProvider.main()) {
                    $executorFactoryImplementation
                  },
                  reducer = { msg ->
                    when (msg) {
                      else -> TODO()
                    }
                  },
              ) {}

              private sealed class Action : JvmSerializable

              private sealed class Message : JvmSerializable
            }
        """.trimIndent()

        return project.createKotlinFileFromText(
                name = "${storeInterfaceName}Factory.kt",
                text = textRepresentation,
        )
    }

    private fun findCoroutineScopeUsages(
            ktClass: KtClass,
    ): List<KtNamedFunction> {
        val className = "kotlinx.coroutines.CoroutineScope"

        val parameter = ktClass.getValueParameters().firstOrNull { it.type()?.fqName?.asString() == className }
                ?: return emptyList()
        val usages = ReferencesSearch.search(parameter, LocalSearchScope(ktClass)).findAll()

        return usages.map { it.element }
                .filterIsInstance<KtNameReferenceExpression>()
                .mapNotNull { it.getParentOfType<KtNamedFunction>(true) }
                .filter { findCollectCallsInNamedFunction(it).isNotEmpty() }
    }

    private fun findCollectCallsInNamedFunction(namedFunction: KtNamedFunction): List<KtCallExpression> {
        val collectCalls = mutableListOf<KtCallExpression>()
        namedFunction.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression
                if (callee is KtSimpleNameExpression && callee.getReferencedName() == "collect") {
                    collectCalls.add(expression)
                }
                super.visitCallExpression(expression)
            }
        })
        return collectCalls
    }

    private fun findDependencies(constructorFunction: PsiMethod?): List<Pair<String?, PsiClass>> {
        val constructor = constructorFunction ?: return emptyList()
        val params = constructor.parameters.filter {
            it.type.qualifiedName()?.contains("reducer", ignoreCase = true) == true
        }

        val deps = params.flatMap { param ->
            val type = param.type as? PsiClassReferenceType ?: return@flatMap emptyList<Pair<String?, PsiClass>>()
            val subDeps = type.resolve()?.constructors?.first()?.parameters?.mapNotNull { p ->
                p.name to ((p.type as? PsiClassReferenceType)?.resolve() ?: return@mapNotNull null)
            }
            return@flatMap subDeps.orEmpty()
        }

        return deps.distinctBy { it.second }
    }
}