package com.lstudio.bloctomvikotlinplugin

import com.android.tools.idea.gradle.actions.SyncProjectAction
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.ImportPath


class InsertNewImport {
//    operator fun invoke(listImport: List<String>, psiFile: PsiFile) {
//        val ktImports = PsiTreeUtil.findChildOfType(psiFile, KtImportList::class.java)
//
//        val listImportDirective = createListImportDirective(psiFile.project, listImport)
//        val lastImport = ktImports?.imports?.lastOrNull()
//        psiFile.project.executeWrite {
//            listImportDirective.forEach { ktImports?.addBefore(it, lastImport) }
//        }
//    }
//
//    operator fun invoke(newImports: List<String>, newImportsString: String?, psiFile: PsiFile) {
//        val listString = newImportsString?.split("\n") ?: emptyList()
//        var result = newImports.toMutableList()
//        result.addAll(listString)
//        result = result.filter { it.isNotBlank() }.distinct().toMutableList()
//        if (result.isNotEmpty()) invoke(result, psiFile)
//    }
//
}

fun GlobalSearchScope.findFiles(project: Project, fileName: String): Array<PsiFile> {
    return FilenameIndex.getFilesByName(project, fileName, this)
}

fun tmp() {
//    val directory = psiFile.containingDirectory ?: return
//    val filePackage = directory.getPackage()?.qualifiedName
//    val imports = findImports(psiFile).joinToString(separator = "\n") { "import $it" }
//
//    val constructorEntries = blocEntries.mapNotNull { psiClass ->
//        val constructor = psiClass.primaryConstructor
//        //val constructors = constructor.refe
//        val lightClass = psiClass.toLightClass() ?: return
////                val deps = lightClass.constructors.map { psiMethod ->
////                    psiMethod.findDescendantOfType<KtPrima> {  }
////                    psiMethod.toLightMethods()
////                }
////                lightClass?.constructors?.any {
////                    //it.param
////                }
//        lightClass?.methods?.joinToString { it.text }
//    }

//    WriteCommandAction.runWriteCommandAction(psiFile.project) {
//        var index = 1
//        for (element in blocEntries) {
//            val testStoreClass = findClassByName("com.core.domain.lrp.enrollment.date.store.TestStore", project)
//            val forceUpdateClass = findClassByName("com.core.domain.force_update.ForceUpdateBloc", project)
//            if (testStoreClass != null && forceUpdateClass != null) {
//                replaceClassUsages(forceUpdateClass, testStoreClass)
//            }
//                    writeKotlinFileFromText(
//                            project,
//                            directory,
//                            "File${index}.kt",
//                            """
//                        package $filePackage
//
//                        $imports
//
//                        class TestClass() {
//                        // Test class ${index++}
//                        }
//                    """.trimIndent()
//                    )
//        }
    //notify(project, "${blocEntries.size} variables have been renamed.", NotificationType.INFORMATION)
//    }
//
//    // commit changes
//    with(PsiDocumentManager.getInstance(project)) {
//        getDocument(psiFile)?.let(::commitDocument)
//    }
}


fun findClassByName(className: String, project: Project): PsiClass? {
    val scope = GlobalSearchScope.allScope(project)
    val psiFacade = JavaPsiFacade.getInstance(project)
    return psiFacade.findClass(className, scope)
}

fun replaceClassUsages(oldClass: PsiClass, newClass: PsiClass) {
    WriteCommandAction.runWriteCommandAction(oldClass.project) {
        val projectScope = GlobalSearchScope.projectScope(oldClass.project)
        ReferencesSearch.search(oldClass, projectScope).forEach { reference ->
            val element = reference.element
            if (element is KtNameReferenceExpression) {
                // Add import statement for new class, if needed
                val containingFile = element.containingKtFile
                addImportToFile(ktFile = containingFile, importPath = newClass.qualifiedName ?: "ololo")
                val factory = JavaPsiFacade.getInstance(element.project).elementFactory
                val newReference = factory.createReferenceFromText(newClass.name ?: "ololoName", null)
                element.replace(newReference)
            }
        }
    }
}

fun addImportToFile(ktFile: KtFile, importPath: String): KtImportDirective {
    // First, check if the file already has an import statement for the specified class
    val existingImport = ktFile.importList?.imports?.find {
        it.importedFqName?.asString() == importPath
    }
    if (existingImport != null) {
        // The file already has an import statement for the specified class, so return it
        return existingImport
    }

    // The file doesn't have an import statement for the specified class, so create a new one
    val psiFactory = KtPsiFactory(ktFile.project)
    val newImport = psiFactory.createImportDirective(ImportPath.fromString(importPath))

    // Add the new import statement to the file's import list
    //ktFile.addImports(ktFile.importList.imports.map { it.importedFqName } + listOfNotNull(newImport.importedFqName))
//        val importList = ktFile.importList?.imports
//        importList?.add(newImport)

    return newImport
}

fun findImports(
        ktFile: KtFile,
): List<String> {
    val importList = ktFile.importDirectives
    return importList.mapNotNull { importDirective ->
        val importPath = importDirective.importPath
        importPath?.pathStr
    }
}

// Returns interface name
fun migrateDelegatedSuperTypeEntryToSuperTypeEntry(ktClass: KtClass): String? {
    val factory = KtPsiFactory(ktClass.project)
    var interfaceName: String? = null
    WriteCommandAction.runWriteCommandAction(ktClass.project) {
        ktClass.superTypeListEntries.forEach { entry ->
            if (entry is KtDelegatedSuperTypeEntry) {
                val newEntry = factory.createSuperTypeEntry(entry.typeReference!!.text) as PsiElement
                entry.replace(newEntry)
                interfaceName = entry.typeReference!!.text
            }
        }
    }

    // Move to the other function, because it implements interface
    WriteCommandAction.runWriteCommandAction(ktClass.project) {
        ktClass.superTypeListEntries.forEach { entry ->
            if (entry is KtSuperTypeEntry && entry.typeReference?.text == interfaceName) {
                //
                val inter = (entry.typeReference?.typeElement as KtUserType).referenceExpression?.resolve() as KtClass

                implementInterfaceMethods(inter.containingKtFile, implementingClass = ktClass)
            }
        }
    }

    return interfaceName
}


fun implementInterfaceMethods(interfaceFile: KtFile, implementingClass: KtClass) {
    val factory = KtPsiFactory(implementingClass.project)
    val interfaceClass = interfaceFile.children.filterIsInstance<KtClass>().firstOrNull()

    if (interfaceClass != null) {
        WriteCommandAction.runWriteCommandAction(implementingClass.project) {
            val hierarchy = getTypeHierarchy(interfaceClass)

            for (type in hierarchy) {
                val declarations = type.declarations.filterIsInstance<KtNamedFunction>()

                for (interfaceMethod in declarations) {
                    val implementingMethod = factory.createFunction(KtTokens.OVERRIDE_KEYWORD.value + " " + interfaceMethod.text + " {TODO()}")
                    implementingMethod.typeReference = interfaceMethod.typeReference
                    implementingClass.addDeclaration(implementingMethod)
                }
            }
        }
    }
}

private fun getTypeHierarchy(clazz: KtClass): List<KtClass> {
    val hierarchy = mutableListOf<KtClass>()

    fun addTypeAndItsSuperTypes(type: KtClass?) {
        if (type != null && type !in hierarchy) {
            hierarchy.add(type)
            type.superTypeListEntries
                    .mapNotNull { entry -> (entry.typeReference?.typeElement as? KtUserType)?.referenceExpression?.resolve() as? KtClass }
                    .forEach { addTypeAndItsSuperTypes(it) }
        }
    }

    addTypeAndItsSuperTypes(clazz)
    return hierarchy
}

