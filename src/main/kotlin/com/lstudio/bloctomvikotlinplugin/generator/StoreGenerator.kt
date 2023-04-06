package com.lstudio.bloctomvikotlinplugin.generator

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiMethod
import com.lstudio.bloctomvikotlinplugin.extension.createKotlinFileFromText
import com.lstudio.bloctomvikotlinplugin.extension.getArgumentsQualifiedNames
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.core.getPackage
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction

object StoreGenerator {
    fun generate(
            project: Project,
            directory: PsiDirectory,
            name: String,
            stateClass: KtClass,
            blocFunctions: List<PsiMethod>,
    ): StoreCreationResult {
        val stateClassName = stateClass.name.orEmpty()
        val filePackage = directory.getPackage()?.qualifiedName

        val publishFunctions = blocFunctions.filter { it.text.contains("dispatch(") }
        val stateLightClass = stateClass.toLightClass()
        val functionsWithParams = publishFunctions.filter { it.hasParameters() }.mapNotNull { it.navigationElement as? KtNamedFunction }
        val functionsWithoutParams = publishFunctions.filterNot { it.hasParameters() }.mapNotNull { it.navigationElement as? KtNamedFunction }

        val intentFunctions: List<StoreIntent> = createIntentList(
                functionsWithParams,
                functionsWithoutParams,
        )

        val intentStringRepresentationList: List<String> = intentFunctions.map { it.stringRepresentation }
        val importSection: String = createImports(
                blocStateName = stateLightClass?.qualifiedName,
                functionsWithParams = functionsWithParams,
        )

        val intents = StringBuilder()
        intentStringRepresentationList.forEachIndexed { index, intent ->
            intents.append("$intent : Intent()")
            if (index != intentStringRepresentationList.lastIndex) {
                intents.append("\n")
            }
        }
        val textRepresentation = """
            package $filePackage
            
            import com.arkivanov.mvikotlin.core.store.Store
            import com.arkivanov.mvikotlin.core.utils.JvmSerializable
            $importSection
            
            interface $name : Store<$name.Intent, $stateClassName, $name.Label> {
              sealed class Intent : JvmSerializable {
                $intents
              }

              sealed class Label : JvmSerializable
            }
        """.trimIndent()

        val file = project.createKotlinFileFromText(
                name = "$name.kt",
                text = textRepresentation,
        )
        return StoreCreationResult(
                file = file,
                storeFullQualifierName = "${filePackage.orEmpty()}.$name",
                intents = intentFunctions,
        )
    }

    private fun createImports(
            blocStateName: String?,
            functionsWithParams: List<KtNamedFunction>,
    ): String {
        val classesImportList = functionsWithParams.flatMap { func ->
            func.getArgumentsQualifiedNames()
        } + listOfNotNull(blocStateName)
        return classesImportList.distinct().map { "import $it" }.joinToString(separator = "\n") { it }
    }

    private fun createIntentList(
            functionsWithParams: List<KtNamedFunction>,
            functionsWithoutParams: List<KtNamedFunction>,
    ): List<StoreIntent> {
        val intentsWithParams = functionsWithParams.map { func ->
            val params = func.valueParameters
            val intentName = func.name.orEmpty().replaceFirstChar { it.uppercaseChar() }
            val funcParams = params.map { param ->
                val nameText = param.name
                val typeText = param.typeReference?.text
                val fullText = "$nameText: $typeText"
                "val ${fullText},"
            }.joinToString(separator = "\n") { it }
            val stringRepresentation = """
                data class ${intentName}(
                $funcParams
                )
            """.trimIndent()
            StoreIntent(
                    originalFunction = func,
                    name = intentName,
                    stringRepresentation = stringRepresentation,
                    hasParams = true,
            )
        }
        val intentsWithoutParams = functionsWithoutParams.map { func ->
            val intentName = func.name.orEmpty().replaceFirstChar { it.uppercaseChar() }
            val stringRepresentation = "object $intentName"
            StoreIntent(
                    originalFunction = func,
                    name = intentName,
                    stringRepresentation = stringRepresentation,
                    hasParams = false,
            )
        }
        return intentsWithParams + intentsWithoutParams
    }
}