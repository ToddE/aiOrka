# **aiOrka Installation & Implementation Guide**

This guide walks through setting up the **aiOrka** library in your cross-platform project.

## **1\. Project Prerequisites**

aiOrka requires a Kotlin Multiplatform environment if used in mobile/desktop apps, or a standard Node.js environment for Web/Backend.

### **Environment Variables**

The library looks for these keys by default (configurable in aiOrka.yaml):

* `GEMINI_API_KEY`  
* `CF_ACCOUNT_ID & CF_AI_TOKEN` 
* `OPENAI_API_KEY`

## **2\. Core Library Setup**

### **Kotlin / Compose Multiplatform**

In your commonMain dependencies:  
```
implementation("org.aiorka:aiorka-core:1.0.0")
```

### **TypeScript / JavaScript**

```
npm install @aiorka/core
```

## **3\. Initialization**

Load your configuration and registry files at app launch. In a KMP project, this usually happens in your di or Application class.  

```
import org.aiorka.core.AiOrka

val orka \= AiOrka.initialize(  
    configPath \= "aiOrka.yaml",  
    registryPath \= "models-registry.yaml"  
)
```

## **4\. Executing an Intent**

The beauty of aiOrka is that your UI code doesn't need to know which model is being used. It simply passes the "Policy" and the data.  

```
suspend fun onUserSubmit(prompt: String) {  
    try {  
        val result \= orka.execute(  
            policy \= "probing-interview",  
            messages \= listOf(Message.user(prompt))  
        )  
          
        // Handle the response  
        uiState.text \= result.content  
        println("Processed by model: ${result.metadata.actualModelUsed}")  
    } catch (e: NoValidProviderException) {  
        // All models in the funnel failed or were unreachable  
        showError("The AI engine is currently unavailable. Please check your connection.")  
    }  
}
```

## **5\. Adding MCP Tools (Optional)**

To enable tool use, register your MCP servers before execution.  

```
orka.registerMcpServer(  
    name \= "product-db",  
    command \= "npx",  
    args \= listOf("-y", "@modelcontextprotocol/server-postgres", "--db-url", dbUrl)  
)
```
aiOrka will automatically inject these tools into the models that support them within your selected policy.