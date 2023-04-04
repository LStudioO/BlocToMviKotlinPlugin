package com.lstudio.bloctomvikotlinplugin.generator

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiMethod
import com.lstudio.bloctomvikotlinplugin.extension.createKotlinFileFromText
import com.lstudio.bloctomvikotlinplugin.extension.getQualifiedName
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.core.getPackage
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtUserType

object StoreGenerator {
    fun generate(
            project: Project,
            directory: PsiDirectory,
            name: String,
            stateClass: KtClass,
            blocFunctions: List<PsiMethod>,
    ): KtFile {
        val stateClassName = stateClass.name.orEmpty()
        val filePackage = directory.getPackage()?.qualifiedName

        val publishFunctions = blocFunctions.filter { it.text.contains("dispatch(") }
        val stateLightClass = stateClass.toLightClass()
        val functionsWithParams = publishFunctions.filter { it.hasParameters() }.mapNotNull { it.navigationElement as? KtNamedFunction }
        val functionsWithoutParams = publishFunctions.filterNot { it.hasParameters() }.mapNotNull { it.navigationElement as? KtNamedFunction }

        val intentClassesList: List<String> = createIntentListNew(
                functionsWithParams,
                functionsWithoutParams,
        )
        val importSection: String = createImportsNew(
                blocStateName = stateLightClass?.qualifiedName,
                functionsWithParams = functionsWithParams,
        )

        val intents = StringBuilder()
        intentClassesList.forEachIndexed { index, intent ->
            intents.append("$intent : Intent()")
            if (index != intentClassesList.lastIndex) {
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

        return project.createKotlinFileFromText(
                name = "$name.kt",
                text = textRepresentation,
        )
    }

    private fun createImportsNew(
            blocStateName: String?,
            functionsWithParams: List<KtNamedFunction>,
    ): String {
        val importList = functionsWithParams.flatMap { func ->
            val params = func.valueParameters
            // Parse the param type and its type arguments if exist
            // Let's assume we have Either<String, Int>
            // Either - param type
            // String and Int - type arguments
            params.mapNotNull { ktParameter ->
                // Find the param type name
                val userType = (ktParameter.typeReference?.typeElement as? KtUserType) ?: return@mapNotNull null
                val paramTypeFullName = userType.getQualifiedName()
                // Find the type arguments names
                val typeArgumentNames = userType.typeArguments.map { ktTypeProjection ->
                    val typeArgumentUserType = ktTypeProjection.typeReference?.typeElement as? KtUserType
                    typeArgumentUserType?.getQualifiedName()
                }
                listOfNotNull(paramTypeFullName) + typeArgumentNames
            }.flatten().filterNotNull()
        } + listOfNotNull(blocStateName)
        return importList.distinct().map { "import $it" }.joinToString(separator = "\n") { it }
    }

    private fun createIntentListNew(
            functionsWithParams: List<KtNamedFunction>,
            functionsWithoutParams: List<KtNamedFunction>,
    ): List<String> {
        val funcWithParText = functionsWithParams.map { func ->
            val params = func.valueParameters
            val funcParams = params.map { param ->
                val nameText = param.name
                val typeText = param.typeReference?.text
                val fullText = "$nameText: $typeText"
                "val ${fullText},"
            }.joinToString(separator = "\n") { it }
            """
                data class ${func.name.orEmpty().replaceFirstChar { it.uppercaseChar() }}(
                $funcParams
                )
            """.trimIndent()
        }
        val funcWithoutParText = functionsWithoutParams.map { func ->
            "object ${func.name.orEmpty().replaceFirstChar { it.uppercaseChar() }}"
        }
        return funcWithParText + funcWithoutParText
    }
}