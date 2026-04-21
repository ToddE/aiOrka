# <img src="shared/src/commonMain/composeResources/drawable/aiOrka-icon.png" height="48" valign="middle" /> aiOrka 

[aiOrka.org](https://aiOrka.org)


**The Orchestrator for Cross-Platform AI.**  
aiOrka is a high-performance Kotlin Multiplatform (KMP) library that acts as an intelligent, policy-driven broker between your application's **Intent** and the world's **AI Providers**.  

In an era of fluctuating model prices and frequent provider "brownouts," aiOrka provides the infrastructure to ensure your app remains resilient, cost-effective, and always-online.

## **🌊 Why aiOrka?**

Most AI integrations are hardcoded to a specific model. When that model is slow, expensive, or down, your app fails. aiOrka introduces a **Selection Policy** layer that decouples your business logic from the underlying infrastructure.

* **Intelligence:** Automatically selects the best model based on your intent (e.g., "interviewing" vs "coding").  
* **Resilience:** Proactive health checks and "local-first" failover ensure 100% uptime, even without internet.  
* **Economics:** Real-time "Least-Cost" routing saves up to 70% on inference costs by utilizing local or cheaper cloud models when quality thresholds are met.  
* **Standardization:** Built-in support for the **Model Context Protocol (MCP)** for universal tool and data access.

## **🛠️ Core Concepts**

### **1\. The Intent-Based Model**

Your application doesn't ask for "GPT-4" or "Qwen-14B." It requests a **Policy** (e.g., strategic-interrogation). This allows you to swap models in the background without touching your UI code.

### **2\. The Multi-Stage Funnel**

Every request goes through the **Orka Selection Engine**:

1. **Capability Filtering:** Ensures the model supports JSON mode, Tools, or Vision if required.  
2. **Environmental Awareness:** Detects network status and limits candidates to local providers if offline.  
3. **Validation (The Heartbeat):** Checks provider health and credentials before making the call.  
4. **Optimization:** Runs a scoring algorithm to pick the winner based on **Cost**, **Latency**, or **Quality**.

## **📦 Implementation Guide**

### **Installation (KMP)**
```
// build.gradle.kts  
commonMain.dependencies {  
    implementation("org.aiorka:aiorka-core:1.0.0")  
}
```

### **Basic Usage**
```
val orka \= AiOrka.initialize(configPath \= "aiOrka.yaml")

suspend fun handleUserQuery(text: String) {  
    val response \= orka.execute(  
        policy \= "strategic-interrogator",  
        messages \= listOf(Message.user(text))  
    )  
    println("Response from ${response.metadata.modelUsed}: ${response.content}")  
}
```

## **🤝 Contributing & Community**

aiOrka is open-source. We believe the community should collaborate on a global models-registry.yaml so every developer has access to accurate, real-time pricing and capability benchmarks.

* **License:** Apache 2.0  
* **Website:** [aiOrka.org](https://www.google.com/search?q=https://aiOrka.org)