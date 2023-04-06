package com.lstudio.bloctomvikotlinplugin.extension

import com.android.tools.idea.kotlin.getQualifiedName
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.idea.refactoring.fqName.getKotlinFqName
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
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

fun KtClass.superClasses(): List<KtClass> {
    return superTypeListEntries.mapNotNull { entry ->
        entry.getKtClass()
    }
}

fun KtClass.superInterfaces(): List<KtClass> {
    return superClasses().filter { it.isInterface() }
}

fun KtSuperTypeListEntry.getKtClass(): KtClass? {
    return when (this) {
        is KtSuperTypeCallEntry -> {
            (findChildOfType<KtTypeReference>()?.typeElement as? KtUserType)?.referenceExpression?.resolve()?.parentOfType()
        }
        else -> {
            (typeReference?.typeElement as KtUserType).referenceExpression?.resolve() as? KtClass
        }
    }
}

fun KtNamedFunction.getArgumentsQualifiedNames(): List<String> {
    val params = valueParameters
    // Parse the param type and its type arguments if exist
    // Let's assume we have Either<String, Int>
    // Either - param type
    // String and Int - type arguments
    return params.mapNotNull { ktParameter ->
        // Find the param type name
        val userType = ktParameter.typeReference?.findChildOfType<KtUserType>() ?: return@mapNotNull null
        val paramTypeFullName = userType.getQualifiedName()
        // Find the type arguments names
        val typeArgumentNames = userType.typeArguments.map { ktTypeProjection ->
            val typeArgumentUserType = ktTypeProjection.typeReference?.findChildOfType<KtUserType>()
            typeArgumentUserType?.getQualifiedName()
        }
        listOfNotNull(paramTypeFullName) + typeArgumentNames
    }.flatten().filterNotNull()
}

fun KtNamedFunction.getReturnedTypeQualifiedNames(): List<String> {
    val userType = typeReference?.findChildOfType<KtUserType>()
    val paramTypeFullName = userType?.getQualifiedName()
    val typeArgumentNames = userType?.typeArguments?.mapNotNull { ktTypeProjection ->
        val typeArgumentUserType = ktTypeProjection.typeReference?.findChildOfType<KtUserType>()
        typeArgumentUserType?.getQualifiedName()
    }.orEmpty()
    return listOfNotNull(paramTypeFullName) + typeArgumentNames
}

fun KtUserType.getQualifiedName() = this.referenceExpression?.resolve()?.getKotlinFqName()?.asString()

fun KtClass.getTypeHierarchy(): List<KtClass> {
    val hierarchy = mutableListOf<KtClass>()

    fun addTypeAndItsSuperTypes(type: KtClass?) {
        if (type != null && type !in hierarchy) {
            hierarchy.add(type)
            type.superTypeListEntries
                    .mapNotNull { entry -> (entry.typeReference?.typeElement as? KtUserType)?.referenceExpression?.resolve() as? KtClass }
                    .forEach { addTypeAndItsSuperTypes(it) }
        }
    }

    addTypeAndItsSuperTypes(this)
    return hierarchy
}

fun KtClass.replaceIdentifier(oldName: String, newName: String) {
    val newExpression = KtPsiFactory(project).createSimpleName(newName)
    accept(referenceExpressionRecursiveVisitor { expression ->
        if (expression.text == oldName) {
            project.executeWrite {
                expression.replace(newExpression)
            }
        }
    })
}