package com.lstudio.bloctomvikotlinplugin.generator

import org.jetbrains.kotlin.psi.KtFile

data class StoreCreationResult(
        val file: KtFile,
        val storeFullQualifierName: String,
        val intents: List<StoreIntent>,
)
