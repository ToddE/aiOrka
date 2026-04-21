package org.aiorka.models

data class ModelRegistry(val models: Map<String, ModelMetadata>)
data class ModelMetadata(
    val type: String,
    val capabilities: List<String>,
    val costPer1k: Double,
    val contextWindow: Int,
    val scores: Map<String, Double>
)

data class SelectionPolicy(
    val strategy: String,
    val requirements: Map<String, Any>,
    val candidates: List<String>
)
