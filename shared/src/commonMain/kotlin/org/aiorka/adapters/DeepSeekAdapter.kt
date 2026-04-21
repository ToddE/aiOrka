package org.aiorka.adapters

import io.ktor.client.*

// DeepSeek uses the OpenAI-compatible chat completions wire format
class DeepSeekAdapter(httpClient: HttpClient) : OpenAiAdapter(
    httpClient = httpClient,
    baseUrl = "https://api.deepseek.com/chat/completions",
    providerType = "deepseek"
)
