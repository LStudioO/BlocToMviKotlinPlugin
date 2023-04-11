package com.lstudio.bloctomvikotlinplugin.generator

import com.android.tools.idea.kotlin.getQualifiedName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.lstudio.bloctomvikotlinplugin.extension.createKotlinFileFromText
import com.lstudio.bloctomvikotlinplugin.extension.qualifiedName
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.core.getPackage
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
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
        val ktClassInitializers = bloc.getAnonymousInitializers()

        var importSection: String = createImportUserDependencies(
                dependencyClasses = dependencyClasses,
                stateLightClass = stateLightClass,
        )

        val dependencies = createConstructorUserDependencies(dependencyClasses)

        val coroutineScopeFunctions = findCoroutineScopeUsages(bloc)
        val coroutineScopeFunctionsString = coroutineScopeFunctions
                .joinToString(separator = "\n\n") {
                    // Add tabs to make a commented code good-looking
                    val lines = it.text.lines()
                    addExtraTabAndJoinToString(lines)
                }

        val initBlocsBody = ktClassInitializers.mapNotNull { it.body?.text }
        val initBlocsBodyString = initBlocsBody
                .joinToString(separator = "\n\n") {
                    // Add tabs to make a commented code good-looking
                    val lines = it.lines()
                    addExtraTabAndJoinToString(lines)
                }

        val needBootstrapper = coroutineScopeFunctions.isNotEmpty() || initBlocsBody.isNotEmpty()

        val bootstrapper = if (needBootstrapper) {
            """
            coroutineBootstrapper {
                /*
                $initBlocsBodyString
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

        val executorFactoryImplementation = generateExecutorFactoryImplementation(
                storeInterfaceName = storeInterfaceName,
                blocFunctions = bloc.declarations.filterIsInstance<KtNamedFunction>().filter { it.text.contains("dispatch") },
        )

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

    private fun createConstructorUserDependencies(dependencyClasses: List<Pair<String?, PsiClass>>): String {
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
        return dependencies
    }

    private fun createImportUserDependencies(
            dependencyClasses: List<Pair<String?, PsiClass>>,
            stateLightClass: KtLightClass?,
    ): String {
        return dependencyClasses
                .mapNotNull { it.second.qualifiedName }
                .plus(stateLightClass?.qualifiedName)
                .map { "import $it" }
                .joinToString(separator = "\n") { it }
    }

    private fun generateExecutorFactoryImplementation(
            storeInterfaceName: String,
            blocFunctions: List<KtNamedFunction>
    ): String {
        val sb = StringBuilder()
        blocFunctions.forEachIndexed { index, method ->
            val body = method.bodyExpression
            val reducerId = PsiTreeUtil.findChildrenOfType(body, PsiElement::class.java)
                    .filter { it.elementType == KtTokens.IDENTIFIER }
                    .firstOrNull { it.text.contains("reducer", ignoreCase = true) }
            val ktNameReferenceExpression = reducerId?.parent as? KtNameReferenceExpression
            val ktParameter = ktNameReferenceExpression?.resolve() as? KtParameter
            val ktClass = (ktParameter?.typeReference?.typeElement as? KtUserType)?.referenceExpression?.resolve() as? KtClass
            val qualifiedName = ktClass?.getQualifiedName()
            val fName = method.name.orEmpty().replaceFirstChar { it.uppercaseChar() }
            val bodyText = body?.text.orEmpty()
            val bodyFormatted = addExtraTabAndJoinToString(bodyText.lines())
            sb.append("""
                onIntent<$storeInterfaceName.Intent.$fName> {
                    /*
                    $qualifiedName
                    $bodyFormatted
                    */
                }
            """.trimIndent())
            if (index != blocFunctions.lastIndex) {
                sb.append("\n\n")
            }
        }
        return sb.toString()
    }

    private fun addExtraTabAndJoinToString(lines: List<String>) = lines.first() + "\n" +
            lines.drop(1).map { line -> "$EXTRA_TAB$line" }.joinToString(separator = "\n") { it }

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
            val subDeps = type.resolve()?.constructors?.firstOrNull()?.parameters?.mapNotNull { p ->
                p.name to ((p.type as? PsiClassReferenceType)?.resolve() ?: return@mapNotNull null)
            }
            return@flatMap subDeps.orEmpty()
        }

        return deps.distinctBy { it.second }
    }

    private val EXTRA_TAB = " ".repeat(20)
}