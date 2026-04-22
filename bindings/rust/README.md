# aiOrka Rust Bindings

Safe Rust wrapper around the [aiOrka](../../README.md) native shared library.

## Requirements

- Rust 1.75+ (stable)
- The compiled native library (`libaiorka.so` / `libaiorka.dylib` / `aiorka.dll`)

## Build the native library

```bash
bash bindings/python/scripts/build_native.sh
```

## Usage

Add to `Cargo.toml`:

```toml
[dependencies]
aiorka = { path = "../path/to/bindings/rust" }
```

Set the library search path and build:

```bash
export AIORKA_LIB_DIR=/path/to/libdir
cargo build
```

```rust
use aiorka::{Client, Config, Message};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut client = Client::new(Config::default())?;
    client.set_key("ANTHROPIC_API_KEY", "sk-ant-...");

    let response = client.execute(
        "fast-chat",
        &[Message::user("Hello from Rust!")],
    )?;

    println!("{}", response.content);
    println!("Provider: {}, {}ms", response.provider_id, response.duration_ms);
    Ok(())
}
```

## Environment variables

| Variable | Purpose |
|---|---|
| `AIORKA_LIB_DIR` | Directory containing the native library (used at build time by `build.rs`) |
| `AIORKA_TEST_ENABLED` | Set to `"1"` to enable integration tests that require the library |
