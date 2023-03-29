package com.lstudio.bloctomvikotlinplugin

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.lstudio.bloctomvikotlinplugin.Utils.writeKotlinFileFromText
import org.jetbrains.kotlin.idea.core.getPackage

object StoreGenerator {
    fun generate(
            project: Project,
            directory: PsiDirectory,
            name: String,
            stateClassName: String,
            intentList: List<String>,
            imports: String,
    ) {
        val filePackage = directory.getPackage()?.qualifiedName
        val intents = StringBuilder()
        intentList.forEachIndexed { index, intent ->
            intents.append("$intent : Intent()")
            if (index != intentList.lastIndex) {
                intents.append("\n")
            }
        }
        val textRepresentation = """
            package $filePackage
            
            import com.arkivanov.mvikotlin.core.store.Store
            import com.arkivanov.mvikotlin.core.utils.JvmSerializable
            $imports
            
            interface $name : Store<$name.Intent, $stateClassName, $name.Label> {
              sealed class Intent : JvmSerializable {
                $intents
              }

              sealed class Label :        JvmSerializable
            }
        """.trimIndent()
        project.writeKotlinFileFromText(
                directory = directory,
                name = "$name.kt",
                text = textRepresentation,
        )
    }
}