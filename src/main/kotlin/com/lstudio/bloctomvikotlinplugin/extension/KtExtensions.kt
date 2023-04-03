package com.lstudio.bloctomvikotlinplugin.extension

import com.android.tools.idea.kotlin.getQualifiedName
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

fun KtFile.removeImports(listImport: List<String>) {
    listImport.forEach { importName ->
        this.importDirectives.find { it.importedFqName?.asString() == importName }?.run {
            delete()
        }
    }
}

fun KtFile.addImports(listImport: List<String>) {
    fun createListImportDirective(listImport: List<String>): List<KtImportDirective> {
        val factory = KtPsiFactory(project)
        if (listImport.isEmpty()) return emptyList()
        return listImport.mapNotNull { importPath ->
            if (importPath.isNotBlank()) {
                factory.createImportDirective(ImportPath.fromString(importPath))
            } else null
        }
    }

    val notExistedImports = listImport.filterNot { importName ->
        importDirectives.any { it.importedFqName?.asString() == importName }
    }

    val ktImports = this.importList ?: return
    val listImportDirective = createListImportDirective(notExistedImports)
    val lastImport = ktImports.imports.lastOrNull()
    project.executeWrite {
        listImportDirective.forEach { ktImports.addBefore(it, lastImport) }
    }
}

fun KtClass.toEmptyObjectDeclaration(
        useFullQualifiedName: Boolean = false,
): String {
    val emptyParametersText = primaryConstructorParameters.joinToString(
            prefix = "\n", postfix = "\n", separator = "\n"
    ) { parameter ->
        "${parameter.name} = ,"
    }
    val name = if (useFullQualifiedName) {
        this.getQualifiedName()
    } else {
        this.name
    }
    return "$name($emptyParametersText)"
}

fun KtClass.isChildOfClass(parentClassName: String): Boolean {
    return this.superTypeListEntries.any { entry ->
        entry.typeAsUserType?.referenceExpression?.getReferencedName() == parentClassName
    }
}