package org.aiorka.resources

import org.aiorka.generated.Res

object ResourceLoader {
    suspend fun loadModelsRegistry(): String =
        Res.readBytes("files/models-registry.yaml").decodeToString()

    suspend fun loadDefaultPolicies(): String =
        Res.readBytes("files/policies.yaml").decodeToString()

    suspend fun loadDefaultAppConfig(): String =
        Res.readBytes("files/aiOrka.yaml").decodeToString()
}
