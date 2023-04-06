package com.lstudio.bloctomvikotlinplugin.visitor

import com.lstudio.bloctomvikotlinplugin.extension.executeWrite
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.types.isError

class RemoveUnnecessarySafeCallsVisitor : KtTreeVisitorVoid() {
    override fun visitSafeQualifiedExpression(expression: KtSafeQualifiedExpression) {

        // Recursively get children of the expression.
        fun getAllExpressionChildren(expression: KtExpression): List<KtSafeQualifiedExpression> {
            val expressions = mutableListOf<KtSafeQualifiedExpression>()
            expression.acceptChildren(object : KtVisitorVoid() {
                override fun visitKtElement(element: KtElement) {
                    element.acceptChildren(this)
                }

                override fun visitExpression(expression: KtExpression) {
                    if (expression is KtSafeQualifiedExpression) {
                        expressions.add(expression)
                    }
                    super.visitExpression(expression)
                }
            })
            return expressions
        }

        // We start replacing from the inner elements to outer in order to resolve types correctly
        val expressionTree = getAllExpressionChildren(expression).reversed() + expression

        expressionTree.forEach { exp ->
            // Get receiver type
            val type = exp.analyze().getType(exp.receiverExpression) ?: return@forEach
            val safeToRemove = !type.isError && !type.isMarkedNullable
            // If it's not an error (can't be parsed) or nullable
            if (safeToRemove) {
                exp.project.executeWrite {
                    val receiverText = exp.receiverExpression.text
                    val selectorText = exp.selectorExpression?.text.orEmpty()
                    val dotExpression = KtPsiFactory(exp.project).createExpressionByPattern("$0.$1", receiverText, selectorText)
                    exp.replace(dotExpression)
                }
            }
        }
    }
}