# <img src="shared/src/commonMain/composeResources/drawable/aiOrka-icon.png" height="48" valign="middle" /> aiOrka

**Policy-driven AI orchestration for every platform.**

aiOrka is a Kotlin Multiplatform library that acts as an intelligent broker between your application and the world's AI providers. Instead of hardcoding a model name, you declare a **policy** — and aiOrka picks the best available provider at runtime using a four-stage selection funnel.

- **Resilient** — automatic fallback when a provider is slow, rate-limited, or down
- **Economical** — least-cost routing cuts inference spend by up to 70%
- **Private** — `local-only` connectivity mode keeps sensitive data off the internet
- **Portable** — runs on Android, iOS, JVM, JavaScript, Linux, macOS, and Windows

---
**Status:** Testing
---

## Table of Contents

- [How It Works](#how-it-works)
- [Platform Support](#platform-support)
- [Supported Providers](#supported-providers)
- [Prerequisites](#prerequisites)
- [Project Structure](#project-structure)
- [Configuration](#configuration)
  - [.env](#env)
  - [aiOrka.yaml](#aiorkayaml)
  - [Policy Reference](#policy-reference)
- [Quick Start — Kotlin / JVM](#quick-start--kotlin--jvm)
- [Benchmarking](#benchmarking)
- [Language Bindings](#language-bindings)
  - [Python](#python)
  - [Go](#go)
  - [Rust](#rust)
- [Testing](#testing)
  - [Unit Tests](#unit-tests)
  - [Integration Tests](#integration-tests)
  - [Test Apps (all languages)](#test-apps-all-languages)
- [Building the Native Library](#building-the-native-library)
- [C API Reference](#c-api-reference)
- [Contributing](#contributing)
- [License](#license)

---

## How It Works

Every `execute()` call passes through the **Orka Selection Funnel**:

```
Your app
   │
   ▼
 execute("fast-chat", messages)
   │
   ▼  Stage 1 — Sifting
   │  Resolve candidates from policy.selection list
   │
   ▼  Stage 2 — Capability Filter
   │  Drop providers that lack required features (json_mode, tools, vision…)
   │  Drop providers below min_context
   │  Drop cloud providers if connectivity = "local-only"
   │
   ▼  Stage 3 — Health + Credential Filter
   │  Drop providers marked dead by the heartbeat monitor
   │  Drop providers with no resolvable API key
   │
   ▼  Stage 4 — Optimization
      Score remaining candidates by strategy:
        fastest    → lowest median latency (rolling 20-sample window)
        least-cost → lowest cost_per_1k from the model registry
        quality    → highest logic score from the model registry
        first      → first candidate that survived (deterministic)
   │
   ▼
 OrkaResponse { content, providerId, modelUsed, durationMs, tokensUsed, cost }
```

If the winning provider fails, exponential-backoff retry selects the next best candidate automatically.

---

## Platform Support

| Platform | Target | Output |
|---|---|---|
| Android | `androidTarget` | AAR |
| iOS (device) | `iosArm64` | XCFramework |
| iOS (simulator, Apple Silicon) | `iosSimulatorArm64` | XCFramework |
| iOS (simulator, Intel) | `iosX64` | XCFramework |
| JVM / Server | `jvm` | JAR |
| JavaScript | `js(IR)` | ESM module |
| Linux x86-64 | `linuxX64` | `.so` shared library |
| macOS Apple Silicon | `macosArm64` | `.dylib` shared library |
| macOS Intel | `macosX64` | `.dylib` shared library |
| Windows | `mingwX64` | `.dll` shared library |

---

## Supported Providers

| Provider | Type | Key env var | Auth headers |
|---|---|---|---|
| Self-hosted (Ollama, LM Studio, Msty, …) | `selfhosted` | *(none)* | `headers_env` (optional) |
| Anthropic Claude | `anthropic` | `ANTHROPIC_API_KEY` | — |
| OpenAI GPT | `openai` | `OPENAI_API_KEY` | — |
| Google Gemini | `gemini` | `GEMINI_API_KEY` | — |
| DeepSeek | `deepseek` | `DEEPSEEK_API_KEY` | — |
| Qwen.ai (Alibaba DashScope) | `qwen` | `QWEN_API_KEY` | — |

`headers_env` injects arbitrary HTTP headers per request — useful for Cloudflare Access tokens, API gateway keys, or any header-based auth on tunneled self-hosted servers. See [Cloudflare Zero Trust Setup](#cloudflare-zero-trust-setup).

---

## Prerequisites

**All targets:**
- JDK 17+ (JDK 21 recommended)
- Gradle 8.11+ (the included `gradlew` wrapper handles this)

**Android:**
- Android SDK 35, min SDK 24

**iOS / macOS:**
- Xcode 15+
- macOS 13+

**Native shared library (Python / Go / Rust bindings):**
- Linux: `gcc`, `libcurl-dev`
- macOS: Xcode Command Line Tools (provides `clang` and `curl`)
- Windows: MinGW-w64

**Python bindings:**
- Python 3.11+

**Go bindings:**
- Go 1.22+, a C compiler (for cgo)

**Rust bindings:**
- Rust 1.75+ stable toolchain

---

## Project Structure

```
aiOrka/
├── shared/                          # KMP library (all business logic lives here)
│   ├── src/
│   │   ├── commonMain/kotlin/org/aiorka/
│   │   │   ├── AiOrka.kt            # Main entry point
│   │   │   ├── adapters/            # Provider adapters (SelfHosted, Anthropic, OpenAI…)
│   │   │   ├── benchmark/           # BenchmarkRunner, MarkdownReporter, config + models
│   │   │   ├── credentials/         # API key and header resolution
│   │   │   ├── engine/              # SelectionEngine (the 4-stage funnel)
│   │   │   ├── models/              # RegistryModels, Message, OrkaResponse
│   │   │   ├── monitoring/          # HealthMonitor, HeartbeatManager
│   │   │   ├── platform/            # expect/actual: time, env vars, file I/O, timestamps
│   │   │   └── resources/           # YamlParser, ResourceLoader
│   │   ├── commonMain/composeResources/files/
│   │   │   ├── models-registry.yaml # 30+ model capability/cost database
│   │   │   ├── policies.yaml        # Bundled default policies
│   │   │   └── aiOrka.yaml          # Default app config (override in your app)
│   │   ├── nativeInteropMain/       # C API surface (CApi.kt) — all native targets
│   │   ├── androidMain/             # Android platform actuals
│   │   ├── darwinMain/              # iOS + macOS platform actuals
│   │   ├── jsMain/                  # JavaScript platform actuals
│   │   ├── jvmMain/                 # JVM platform actuals
│   │   ├── linuxX64Main/            # Linux native actuals
│   │   ├── mingwX64Main/            # Windows native actuals
│   │   └── commonTest/              # Unit tests (63 tests, no network required)
├── bindings/
│   ├── python/aiorka/               # ctypes Python package
│   ├── go/aiorka/                   # cgo Go package
│   └── rust/src/                    # Rust safe wrapper
├── examples/
│   ├── aiOrka.yaml                  # Shared config used by all test apps
│   ├── kotlin/                      # Kotlin JVM test app
│   ├── python/test_app.py           # Python test app
│   ├── go/main.go                   # Go test app
│   └── rust/src/main.rs             # Rust test app
├── scripts/
│   └── build_bindings.sh            # Builds native .so/.dylib/.dll for all bindings
├── .env.example                     # Copy to .env and fill in your API keys
└── .github/workflows/ci.yml         # CI: compiles + runs unit tests on every push
```

---

## Configuration

### .env

All API keys and runtime settings live in a `.env` file in the repo root. It is gitignored — never commit real keys.

```bash
cp .env.example .env
```

Then edit `.env`:

```bash
# Cloud API keys — leave blank to skip that provider
ANTHROPIC_API_KEY=sk-ant-...
OPENAI_API_KEY=sk-...
GEMINI_API_KEY=AI...
DEEPSEEK_API_KEY=sk-...

# Self-hosted inference server
SELFHOSTED_ENDPOINT=http://localhost:11434
SELFHOSTED_MODELS=qwen3.5:9b

# Integration tests (set to true to make real API calls)
AIORKA_INTEGRATION_TESTS=false

# Test app settings
AIORKA_TEST_TIMEOUT_MS=10000
AIORKA_TEST_PROMPT=Hello! Reply in exactly five words.

# Native bindings (Python / Go / Rust)
AIORKA_LIB_PATH=          # absolute path to libaiorka.so/.dylib/.dll
AIORKA_TEST_ENABLED=0     # set to 1 to enable native binding tests

# Cloudflare Authentication
CLOUDFLARE_ACCESS_ID=
CLOUDFLARE_ACCESS_SECRET=
```

The library reads keys from this priority order:
1. Keys injected programmatically via `setApiKey()` / `set_key()` / `SetKey()`
2. System environment variables (including anything exported from `.env` via `direnv`)
3. *(Keys are never read directly from the `.env` file by the library — only by the test apps and scripts)*

To have keys available automatically in every shell session, use [direnv](https://direnv.net/):
```bash
brew install direnv   # or: apt install direnv
echo 'eval "$(direnv hook zsh)"' >> ~/.zshrc
direnv allow          # run once in the repo root
```

### aiOrka.yaml

This file defines your provider fleet and policy overrides. The library ships with a bundled default; you override it by passing a YAML string to `AiOrka.initialize { configYaml = ... }`.

The `examples/aiOrka.yaml` is used by all test apps and is a good starting template:

```yaml
monitoring:
  heartbeat:
    enabled: true
    interval_ms: 300000     # ping every 5 minutes
    timeout_ms: 8000
    failure_threshold: 2

defaults:
  retry:
    max_attempts: 2
    initial_backoff_ms: 300
    max_backoff_ms: 3000

providers:
  local-qwen:
    type: "selfhosted"
    model_ref: "qwen3.5:9b"
    endpoint: "http://localhost:11434"
    # For servers behind a Cloudflare Access tunnel, add headers_env:
    # headers_env:
    #   CF-Access-Client-Id: CLOUDFLARE_ACCESS_ID
    #   CF-Access-Client-Secret: CLOUDFLARE_ACCESS_SECRET

  anthropic-sonnet:
    type: "anthropic"
    model_ref: "claude-sonnet-4-6"
    api_key_env: "ANTHROPIC_API_KEY"   # env var name, never the key value

  google-flash:
    type: "gemini"
    model_ref: "gemini-1.5-flash"
    api_key_env: "GEMINI_API_KEY"

policies:
  "fast-chat":
    strategy: "least-cost"
    selection: ["local-qwen", "google-flash", "anthropic-sonnet"]
```

#### Provider fields reference

| Field | Required | Description |
|---|---|---|
| `type` | ✓ | Provider adapter: `selfhosted`, `anthropic`, `openai`, `gemini`, `deepseek` |
| `model_ref` | ✓ | Model identifier passed to the provider API |
| `endpoint` | selfhosted only | Base URL of the inference server (e.g. `http://localhost:11434`) |
| `api_key_env` | cloud only | Name of the env var holding the API key |
| `headers_env` | optional | Map of `Header-Name: ENV_VAR_NAME` pairs injected on every request. Use for Cloudflare Access tokens, Bearer tokens, or any custom auth header. |
| `config` | optional | Provider-specific settings (e.g. `num_ctx` for self-hosted servers) |

### Policy Reference

| Field | Type | Description |
|---|---|---|
| `strategy` | `fastest` \| `least-cost` \| `quality` \| `first` | Selection scoring |
| `selection` | `List<String>` | Ordered list of provider IDs to consider |
| `requirements.features` | `List<String>` | Required capabilities (e.g. `tools`, `vision`, `json_mode`) |
| `requirements.min_context` | `Int` | Minimum context window in tokens |
| `requirements.connectivity` | `any` \| `local-only` | `local-only` restricts to providers with `type: local` in the registry |
| `retry_override` | `RetryConfig` | Per-policy retry settings, overrides global defaults |

The library ships with these built-in policies (all overridable):

| Policy | Strategy | Use case |
|---|---|---|
| `fast-chat` | fastest | Low-latency conversational chat |
| `apex-logic` | quality | Complex reasoning and chain-of-thought |
| `structured-extraction` | least-cost | JSON schema adherence |
| `coding-assistant` | quality | Agentic coding with tool support |
| `real-time-research` | fastest | Web-search enabled models |
| `massive-context` | quality | Documents > 1M tokens |
| `creative-writing` | quality | Long-form creative content |
| `local-only` | least-cost | Air-gapped / privacy-first |
| `data-sifter` | least-cost | High-volume classification |
| `visual-analysis` | quality | Image / OCR / document layout |

---

## Quick Start — Kotlin / JVM

```kotlin
import kotlinx.coroutines.runBlocking
import org.aiorka.AiOrka
import org.aiorka.models.Message

fun main() = runBlocking {
    val orka = AiOrka.initialize {
        // Pass your own aiOrka.yaml, or omit to use bundled defaults
        configYaml = java.io.File("aiOrka.yaml").readText()

        // Inject keys programmatically (or set env vars and omit these lines)
        apiKeys["ANTHROPIC_API_KEY"] = System.getenv("ANTHROPIC_API_KEY") ?: ""
        apiKeys["OPENAI_API_KEY"]    = System.getenv("OPENAI_API_KEY") ?: ""
    }

    try {
        val response = orka.execute(
            policyId = "fast-chat",
            messages = listOf(Message.user("Explain transformers in one sentence."))
        )
        println("${response.content}")
        println("Provider : ${response.metadata.providerId}")
        println("Model    : ${response.metadata.modelUsed}")
        println("Duration : ${response.metadata.durationMs}ms")
        println("Tokens   : ${response.metadata.tokensUsed}")
        println("Cost     : \$${response.metadata.cost}")
    } finally {
        orka.shutdown()
    }
}
```

**Multi-turn conversation:**

```kotlin
val messages = mutableListOf(
    Message.system("You are a concise assistant."),
    Message.user("My name is Alex.")
)

val r1 = orka.execute("fast-chat", messages)
messages += Message.assistant(r1.content)
messages += Message.user("What is my name?")

val r2 = orka.execute("fast-chat", messages)
println(r2.content) // "Your name is Alex."
```

**Runtime key injection** (useful for sandboxed environments):

```kotlin
val orka = AiOrka.initialize { configYaml = myYaml }
orka.setApiKey("ANTHROPIC_API_KEY", vaultClient.getSecret("anthropic-key"))
```

**Health monitoring:**

```kotlin
val snapshot = orka.healthSnapshot()
snapshot.forEach { (id, status) ->
    println("$id → alive=${status.isAlive}, latency=${status.lastLatencyMs}ms")
}
```

---

## Benchmarking

`benchmark()` runs one or more prompts against every configured provider in parallel and writes a human-readable Markdown report. It is designed for manual evaluation: you read the output, compare responses, and adjust your `policies.yaml` accordingly.

```kotlin
val report = orka.benchmark(
    BenchmarkConfig(
        prompts = listOf(
            "Explain quantum entanglement in one sentence.",
            "Write a Python function that reverses a string."
        ),
        providerIds = null,       // null = all configured providers
        outputPath = "eval.md"    // null = aiorka-benchmark-{timestamp}.md
    )
)
println("Report written to: ${report.outputPath}")
```

**Report structure** — for each prompt the report contains:

- A **Performance** table sorted by latency (provider, model, latency, tokens, estimated cost, status)
- A **Responses** section with each provider's full reply
- An **Overall Summary** table aggregated across all prompts

**Example output:**

```markdown
# aiOrka Benchmark Report

**Generated:** 2026-04-27 14:32:01 UTC
**Providers tested:** 3 (local-qwen, anthropic-sonnet, google-flash)
**Prompts:** 2

---

## Prompt 1 of 2

> Explain quantum entanglement in one sentence.

### Performance

| Provider         | Model               | Latency | Tokens | Est. Cost  | Status |
|:-----------------|:--------------------|--------:|-------:|-----------:|:------:|
| google-flash     | `gemini-1.5-flash`  |   430ms |     78 | $0.000008  |   ✓    |
| anthropic-sonnet | `claude-sonnet-4-6` |   890ms |     92 | $0.000276  |   ✓    |
| local-qwen       | `qwen3.5:9b`        | 1,240ms |     87 | free       |   ✓    |

*Sorted by latency ascending.*

### Responses

---
**google-flash** · `gemini-1.5-flash` · 430ms

Quantum entanglement is a phenomenon where two particles...
```

Cost is computed from the model registry's `cost_per_1k` value. Self-hosted models show `free`. Providers that error show the error message in place of a response — they are never silently dropped.

---

## Language Bindings

All three language bindings wrap the same native shared library (`.so` / `.dylib` / `.dll`) built from the Kotlin/Native target. Build it first:

```bash
bash scripts/build_bindings.sh          # release build for current platform
bash scripts/build_bindings.sh --debug  # debug build
```

This compiles `libaiorka` and copies it into `bindings/python/aiorka/`, `bindings/go/`, and `bindings/rust/`.

### Python

**Install:**
```bash
bash scripts/build_bindings.sh
pip install -e bindings/python/
```

**Usage:**
```python
from aiorka import AiOrka, Message

with AiOrka(config_yaml=open("aiOrka.yaml").read()) as orka:
    orka.set_key("ANTHROPIC_API_KEY", "sk-ant-...")
    response = orka.execute("fast-chat", [Message.user("Hello!")])
    print(response.content)
    print(f"{response.provider_id} — {response.duration_ms}ms")
```

**Library path resolution** (in priority order):
1. `AIORKA_LIB_PATH` environment variable
2. System library path (`LD_LIBRARY_PATH` / `DYLD_LIBRARY_PATH`)
3. The `aiorka/` package directory (bundled)

### Go

**Usage:**
```go
import "github.com/your-org/aiorka-go/aiorka"

client, err := aiorka.New(aiorka.Config{
    ConfigYAML: string(configBytes),
})
if err != nil { log.Fatal(err) }
defer client.Close()

client.SetKey("OPENAI_API_KEY", os.Getenv("OPENAI_API_KEY"))

resp, err := client.Execute("fast-chat", []aiorka.Message{
    {Role: "user", Content: "Hello!"},
})
if err != nil { log.Fatal(err) }
fmt.Printf("%s (%s, %dms)\n", resp.Content, resp.ProviderID, resp.DurationMs)
```

**Build:**
```bash
export CGO_LDFLAGS="-L$(pwd)/bindings/go -Wl,-rpath,$(pwd)/bindings/go"
go build ./...
```

### Rust

**`Cargo.toml`:**
```toml
[dependencies]
aiorka = { path = "bindings/rust" }
```

**Usage:**
```rust
use aiorka::{Client, Config, Message};

let mut client = Client::new(Config {
    config_yaml: Some(std::fs::read_to_string("aiOrka.yaml")?),
})?;
client.set_key("DEEPSEEK_API_KEY", &std::env::var("DEEPSEEK_API_KEY")?);

let response = client.execute(
    "fast-chat",
    &[Message::user("Hello!")],
)?;
println!("{} ({}ms)", response.content, response.duration_ms);
```

**Build:**
```bash
AIORKA_LIB_DIR=$(pwd)/bindings/rust cargo build
```

---

## Testing

### Unit Tests

The unit test suite has 63 tests covering all core logic. No API keys, no network, no native build required.

```bash
./gradlew :shared:jvmTest
```

What is covered:

| Test file | What it tests |
|---|---|
| `SelectionEngineTest` | All 4 funnel stages, all 4 strategies, edge cases |
| `HealthMonitorTest` | Failure tracking, latency rolling window, dead-provider eviction |
| `CredentialResolverTest` | Key priority, env var fallback, missing-key handling |
| `YamlParserTest` | Registry, policies, and app config parsing |
| `AiOrkaExecuteTest` | Retry loop, backoff, `NoValidProviderException` propagation |

Run a specific test class:
```bash
./gradlew :shared:jvmTest --tests "*.SelectionEngineTest"
```

### Integration Tests

Integration tests make real API calls and require keys in `.env`.

**Setup:**
```bash
cp .env.example .env
# Edit .env — add keys for the providers you want to test
# Set AIORKA_INTEGRATION_TESTS=true
```

**Run:**
```bash
./gradlew :shared:jvmTest --tests "*.IntegrationTest"
```

The integration suite runs 13 ordered scenarios:

| # | Scenario | Description |
|---|---|---|
| 1 | Health Snapshot | All configured providers appear in snapshot |
| 2 | Self-hosted | Round-trip with no API key |
| 3 | Anthropic | Smoke test with real key |
| 4 | OpenAI | Smoke test with real key |
| 5 | Google Gemini | Smoke test with real key |
| 6 | DeepSeek | Smoke test with real key |
| 7 | Qwen.ai | Smoke test with real key |
| 8 | Heartbeat | Records latency for reachable providers |
| 9 | Fallback chain | Bad Anthropic key → succeeds via next provider |
| 10 | All exhausted | Correctly throws when every provider fails |
| 11 | Multi-turn | Context preserved across conversation turns |
| 12 | Runtime key inject | `setApiKey()` works after `initialize()` |
| 13 | Performance | Response time within configured `AIORKA_TEST_TIMEOUT_MS` |

Tests for providers whose keys are not set are skipped automatically. Running with only a self-hosted server configured still exercises tests 1, 2, 7, and 9.

### Test Apps (all languages)

The `examples/` directory contains identical test apps in Kotlin, Python, Go, and Rust. All four apps:
- Read `.env` automatically by walking up from the working directory
- Load `examples/aiOrka.yaml` as shared configuration
- Run the same 7 scenarios and print a consistent pass/fail report
- Exit with code `1` if any test fails (CI-friendly)

**Scenarios:**

| # | Scenario | What it verifies |
|---|---|---|
| 1 | Health Snapshot | Library initializes; all providers registered |
| 2 | Local Inference | Self-hosted server responds with no API key |
| 3 | General Chat | Least-cost routing selects cheapest live provider |
| 4 | Reasoning | Capability filter routes to a reasoning-capable model |
| 5 | Fallback Chain | Invalid Anthropic key → falls through to next live provider |
| 6 | Multi-turn | Conversation context is maintained across turns |
| 7 | Runtime Key Inject | Key injected after initialization is used for subsequent calls |

**Kotlin** — no native library required; uses the JVM target directly:
```bash
./gradlew :examples:kotlin:jvmRun
```

**Python:**
```bash
bash scripts/build_bindings.sh
pip install -e bindings/python/
python examples/python/test_app.py
```

**Go:**
```bash
bash scripts/build_bindings.sh
cd examples/go
CGO_LDFLAGS="-L../../bindings/go -Wl,-rpath,../../bindings/go" go run main.go
```

**Rust:**
```bash
bash scripts/build_bindings.sh
AIORKA_LIB_DIR=$(pwd)/bindings/rust \
  cargo run --manifest-path examples/rust/Cargo.toml
```

**Expected output (all apps):**
```
════════════════════════════════════════════════════════════
  aiOrka Kotlin Test App
════════════════════════════════════════════════════════════
Library : 0.1.0
Config  : examples/aiOrka.yaml
Endpoint: http://localhost:11434
Keys    : anthropic, gemini

Running: 1. Health Snapshot ...     PASS
Running: 2. Local Inference ...     PASS
Running: 3. General Chat ...        PASS
Running: 4. Reasoning ...           PASS
Running: 5. Fallback Chain ...      PASS
Running: 6. Multi-turn ...          PASS
Running: 7. Runtime Key Inject ...  SKIPPED — GEMINI_API_KEY not set

════════════════════════════════════════════════════════════
  Results
════════════════════════════════════════════════════════════
✓ PASS  1. Health Snapshot
        6 providers registered:
           ✓ local-qwen    failures=0  latency=no data
           ✓ anthropic-sonnet  failures=0  latency=no data
           ...
```

---

## Building the Native Library

The master build script compiles for the current host platform and distributes the library to all binding directories:

```bash
bash scripts/build_bindings.sh            # release (default)
bash scripts/build_bindings.sh --debug    # debug build
```

To build a specific Gradle task directly:

```bash
# Linux
./gradlew :shared:linkReleaseSharedLinuxX64

# macOS Apple Silicon
./gradlew :shared:linkReleaseSharedMacosArm64

# macOS Intel
./gradlew :shared:linkReleaseSharedMacosX64

# Windows
./gradlew :shared:linkReleaseSharedMingwX64
```

Output locations:
```
shared/build/bin/linuxX64/releaseShared/libaiorka.so
shared/build/bin/macosArm64/releaseShared/libaiorka.dylib
shared/build/bin/macosX64/releaseShared/libaiorka.dylib
shared/build/bin/mingwX64/releaseShared/aiorka.dll
```

---

## C API Reference

The native library exports a stable C API suitable for FFI from any language.

```c
#include <stdlib.h>

// Create an instance. Pass NULL to use bundled defaults.
// Returns an opaque handle, or NULL on failure.
void* aiorka_create(const char* config_yaml);

// Execute a policy. messages_json is a JSON array: [{"role":"user","content":"..."}]
// Returns heap-allocated JSON (caller must free), or NULL on failure.
char* aiorka_execute(void* handle, const char* policy_id, const char* messages_json);

// Inject an API key at runtime.
void  aiorka_set_key(void* handle, const char* env_var_name, const char* key_value);

// Return the last error as a heap-allocated string, or NULL if none.
char* aiorka_last_error(void);

// Free any string returned by this library. Safe to call with NULL.
void  aiorka_free_string(char* ptr);

// Return health snapshot JSON. Caller must free.
char* aiorka_health(void* handle);

// Destroy the handle and release all resources.
void  aiorka_destroy(void* handle);

// Return the library version string. Caller must free.
char* aiorka_version(void);
```

**Response JSON shape** (from `aiorka_execute`):
```json
{
  "content":     "The model's reply",
  "provider_id": "anthropic-sonnet",
  "model_used":  "claude-sonnet-4-6",
  "duration_ms": 843,
  "tokens_used": 127,
  "cost":        0.00038
}
```

**Memory contract:** Every non-void return value is heap-allocated. The caller owns it and must free it with `aiorka_free_string()`. Passing `NULL` to `aiorka_free_string` is a no-op.

---
## Cloudflare Zero Trust Setup

Self-hosted inference servers (Ollama, LM Studio, Msty, etc.) are typically exposed via a Cloudflare Tunnel when accessed remotely. Cloudflare Access **Service Tokens** let aiOrka authenticate to the tunnel without exposing the server to the public internet.

### Step 1 — Create a Service Token

1. Open the **Cloudflare Zero Trust Dashboard → Access → Service Tokens**.
2. Click **Create Service Token**. Name it (e.g., `aiOrka-Client`).
3. Copy the **Client ID** and **Client Secret** into your `.env`:

```bash
CLOUDFLARE_ACCESS_ID=your-client-id.access
CLOUDFLARE_ACCESS_SECRET=your-client-secret
```

### Step 2 — Protect the Application

1. Go to **Access → Applications** and open (or create) the application for your tunnel endpoint.
2. Under **Policies**, add a policy: **Action:** Service Auth → **Include:** the token from Step 1.
3. Save.

### Step 3 — Wire up aiOrka.yaml

Add `headers_env` to any `selfhosted` provider that lives behind the tunnel. aiOrka resolves the env var values at runtime and injects them as HTTP headers on every request.

```yaml
providers:
  remote-qwen:
    type: "selfhosted"
    model_ref: "qwen3.5:9b"
    endpoint: "https://your-tunnel.example.com"
    headers_env:
      CF-Access-Client-Id: CLOUDFLARE_ACCESS_ID
      CF-Access-Client-Secret: CLOUDFLARE_ACCESS_SECRET
```

`headers_env` is not Cloudflare-specific — use the same mechanism for any header-based auth (Bearer tokens, API gateway keys, etc.).

Once configured, Cloudflare only admits requests that carry the valid service token headers, so the inference server remains private.

---

## Contributing

aiOrka is open-source under Apache 2.0. Contributions are welcome in these areas:

**Model registry** — `shared/src/commonMain/composeResources/files/models-registry.yaml`  
Pricing and benchmark data drifts quickly. PRs that update `cost_per_1k`, `context_window`, or capability lists for any model are always welcome.

**New adapters** — implement `ProviderAdapter` in `shared/src/commonMain/kotlin/org/aiorka/adapters/`  
Any provider with an OpenAI-compatible API can extend `OpenAiAdapter` with just a URL change.

**New platform targets** — add `actual` implementations for `currentTimeMillis()`, `getEnvVariable()`, `writeTextFile()`, and `formatTimestamp()` in the new platform source set.

**Bug reports and feature requests** — open an issue at the repository.

---

## License

**aiOrka** is licensed under the **Business Source License 1.1 (BSL)**.

### What this means

  - **Free for almost everyone:** You are free to use, modify, and distribute aiOrka for any purpose, including internal commercial use, provided you are not building a competing "Managed AI Orchestration Service".

  - **Managed Service Restriction:** You may not use aiOrka to provide a hosted or managed platform where the core value is offering our AI model routing, policy management, or brokerage functionality to third parties.

  - **Becomes Open Source:** On **April 23, 2030**, this version of aiOrka will automatically convert to the **Apache License, Version 2.0**, making it fully open source.

### Why BSL?
We chose the BSL to ensure that aiOrka remains widely available to the developer community while preventing large cloud providers from commoditizing our hard work without contribution. This model allows us to fund the continued development of the library through supplemental service offerings like our **Managed Model Registry** and **Cloud Optimizer API**.

For the full legal text, please see the [LICENSE.md](LICENSE.md) file.
