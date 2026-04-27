package org.aiorka.credentials

import org.aiorka.models.ProviderConfig
import org.aiorka.platform.getEnvVariable

class CredentialResolver(injectedKeys: Map<String, String> = emptyMap()) {
    private val keys: MutableMap<String, String> = injectedKeys.toMutableMap()

    fun addKey(envVarName: String, value: String) {
        keys[envVarName] = value
    }

    fun resolve(provider: ProviderConfig): String? {
        val envVarName = provider.apiKeyEnv ?: return null
        keys[envVarName]?.let { return it }
        return getEnvVariable(envVarName)
    }

    fun hasCredentials(provider: ProviderConfig): Boolean {
        if (provider.apiKeyEnv == null) return true
        return resolve(provider) != null
    }

    fun resolveHeaders(headersEnv: Map<String, String>): Map<String, String> =
        headersEnv.mapNotNull { (headerName, envVarName) ->
            val value = keys[envVarName] ?: getEnvVariable(envVarName)
            if (value != null) headerName to value else null
        }.toMap()
}
