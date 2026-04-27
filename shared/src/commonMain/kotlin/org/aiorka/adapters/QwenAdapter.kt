package org.aiorka.adapters

import io.ktor.client.*

// Qwen.ai (Alibaba DashScope) exposes an OpenAI-compatible chat completions endpoint.
class QwenAdapter(httpClient: HttpClient) : OpenAiAdapter(
    httpClient = httpClient,
    baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
    providerType = "qwen"
)
