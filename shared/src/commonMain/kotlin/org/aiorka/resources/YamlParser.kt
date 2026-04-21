package org.aiorka.resources

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import org.aiorka.models.AppConfig
import org.aiorka.models.ModelRegistry
import org.aiorka.models.SelectionPolicy

@Serializable
private data class PoliciesFile(
    val policies: Map<String, SelectionPolicy> = emptyMap()
)

object YamlParser {
    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false
        )
    )

    fun parseModelRegistry(content: String): ModelRegistry =
        yaml.decodeFromString(ModelRegistry.serializer(), content)

    fun parsePoliciesFile(content: String): Map<String, SelectionPolicy> =
        yaml.decodeFromString(PoliciesFile.serializer(), content).policies

    fun parseAppConfig(content: String): AppConfig =
        yaml.decodeFromString(AppConfig.serializer(), content)
}
