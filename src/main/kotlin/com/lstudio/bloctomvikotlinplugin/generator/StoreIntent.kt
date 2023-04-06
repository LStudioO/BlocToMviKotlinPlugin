package com.lstudio.bloctomvikotlinplugin.generator

import org.jetbrains.kotlin.psi.KtNamedFunction

data class StoreIntent(
        val originalFunction: KtNamedFunction,
        val name: String,
        val hasParams: Boolean,
        val stringRepresentation: String,
)