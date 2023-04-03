package com.lstudio.bloctomvikotlinplugin.generator

import com.intellij.lang.jvm.JvmParameter
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.lstudio.bloctomvikotlinplugin.extension.createKotlinFileFromText
import com.lstudio.bloctomvikotlinplugin.extension.qualifiedName
import com.lstudio.bloctomvikotlinplugin.extension.toKotlinType
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.core.getPackage
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

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
        val functionsWithParams = publishFunctions.filter { it.hasParameters() }
        val functionsWithoutParams = publishFunctions.filterNot { it.hasParameters() }

        val intentClassesList: List<String> = createIntentList(functionsWithParams, functionsWithoutParams)
        val importSection: String = createImports(stateLightClass, functionsWithParams)

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

    private fun createImports(
            blocState: KtLightClass?,
            functionsWithParams: List<PsiMethod>,
    ): String {
        val importList = listOfNotNull(blocState?.qualifiedName) + functionsWithParams.flatMap { func ->
            val params = func.parameters
            params
                    .filterNot { isInImportIgnoreList(it) }
                    .filterNot { it.type is PsiPrimitiveType }
                    .map { it.type.qualifiedName() }
        }
        return importList.distinct().map { "import $it" }.joinToString(separator = "\n") { it }
    }

    private fun createIntentList(
            functionsWithParams: List<PsiMethod>,
            functionsWithoutParams: List<PsiMethod>,
    ): List<String> {
        val funcWithParText = functionsWithParams.map { func ->
            val params = func.parameters
            val funcParams = params.map { param ->
                val nameText = param.name
                val typeText = if (param.type is PsiPrimitiveType) {
                    (param.type as PsiPrimitiveType).toKotlinType()
                } else {
                    (param.type as? PsiClassReferenceType)?.name
                }
                val fullText = "$nameText: $typeText"
                "val ${fullText},"
            }.joinToString(separator = "\n") { it }
            """
                data class ${func.name.replaceFirstChar { it.uppercaseChar() }}(
                $funcParams
                )
            """.trimIndent()
        }
        val funcWithoutParText = functionsWithoutParams.map { func ->
            "object ${func.name.replaceFirstChar { it.uppercaseChar() }}"
        }
        return funcWithParText + funcWithoutParText
    }

    private fun isInImportIgnoreList(param: JvmParameter): Boolean {
        val ignoreList = listOf(
                "java.lang.String",
        )
        return ignoreList.contains(param.type.qualifiedName())
    }
}