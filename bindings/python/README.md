# aiOrka Python Bindings

Python wrapper for the [aiOrka](../../README.md) native shared library.
Uses `ctypes` — no C compiler required at install time.

## Requirements

- Python 3.11+
- The compiled native library (`libaiorka.so` / `libaiorka.dylib` / `aiorka.dll`)

## Build the native library

```bash
# From the repo root — builds for the current host platform
bash bindings/python/scripts/build_native.sh
```

The script runs the Gradle task and copies the output into `aiorka/`.

## Install

```bash
pip install bindings/python/
# or, for development
pip install -e bindings/python/[dev]
```

## Quick start

```python
from aiorka import AiOrka, Message

with AiOrka() as orka:
    orka.set_key("ANTHROPIC_API_KEY", "sk-ant-...")
    response = orka.execute("fast-chat", [Message.user("Hello!")])
    print(response.content)
    print(f"Provider: {response.provider_id}, {response.duration_ms}ms")
```

## Custom config

```python
config = open("my-aiOrka.yaml").read()
with AiOrka(config_yaml=config) as orka:
    orka.set_key("OPENAI_API_KEY", "sk-...")
    response = orka.execute("high-quality", [
        Message.system("You are a concise assistant."),
        Message.user("Explain transformers in one sentence."),
    ])
```

## Health check

```python
with AiOrka() as orka:
    for provider_id, status in orka.health().items():
        print(f"{provider_id}: alive={status.alive}, latency={status.latency_ms}ms")
```

## Library path resolution

The native library is found in this order:

1. `AIORKA_LIB_PATH` environment variable (absolute path to the `.so`/`.dylib`/`.dll`)
2. System library search path (`LD_LIBRARY_PATH` / `DYLD_LIBRARY_PATH` / `PATH`)
3. The `aiorka/` package directory (bundled wheel distribution)
