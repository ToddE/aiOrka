package org.aiorka.credentials

import org.aiorka.models.ProviderConfig
import org.aiorka.platform.getEnvVariable

class CredentialResolver(
    private val injectedKeys: Map<String, String> = emptyMap()
) {
    fun resolve(provider: ProviderConfig): String? {
        val envVarName = provider.apiKeyEnv ?: return null
        injectedKeys[envVarName]?.let { return it }
        return getEnvVariable(envVarName)
    }

    fun hasCredentials(provider: ProviderConfig): Boolean {
        if (provider.apiKeyEnv == null) return true
        return resolve(provider) != null
    }
}
