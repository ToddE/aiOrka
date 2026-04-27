//! aiOrka Rust Test App
//! Runs the same scenarios as the Kotlin, Python, and Go test apps.
//!
//! Setup:
//!   bash scripts/build_bindings.sh
//!   AIORKA_LIB_DIR=$(pwd)/bindings/rust cargo run --manifest-path examples/rust/Cargo.toml

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::process;

use aiorka::{Client, Config, Message};

// ── .env loader ───────────────────────────────────────────────────────────────

fn load_env() -> HashMap<String, String> {
    let mut dir = std::env::current_dir().unwrap_or_default();
    for _ in 0..4 {
        let path = dir.join(".env");
        if let Ok(content) = fs::read_to_string(&path) {
            return content
                .lines()
                .filter(|l| !l.trim().is_empty() && !l.trim_start().starts_with('#') && l.contains('='))
                .map(|l| {
                    let idx = l.find('=').unwrap();
                    (l[..idx].trim().to_string(), l[idx + 1..].trim().to_string())
                })
                .collect();
        }
        if !dir.pop() {
            break;
        }
    }
    HashMap::new()
}

fn load_config(env: &HashMap<String, String>) -> String {
    let cwd = std::env::current_dir().unwrap_or_default();
    let candidates = [
        cwd.join("examples").join("aiOrka.yaml"),
        cwd.join("..").join("aiOrka.yaml"),
        cwd.join("aiOrka.yaml"),
    ];
    for path in &candidates {
        if let Ok(content) = fs::read_to_string(path) {
            let endpoint = env
                .get("SELFHOSTED_ENDPOINT")
                .map(String::as_str)
                .unwrap_or("http://localhost:11434");
            return content.replace("http://localhost:11434", endpoint);
        }
    }
    eprintln!("Cannot find examples/aiOrka.yaml — run from the repo root");
    process::exit(1);
}

fn active_keys(env: &HashMap<String, String>) -> String {
    let found: Vec<_> = ["ANTHROPIC", "OPENAI", "GEMINI", "DEEPSEEK", "QWEN"]
        .iter()
        .filter(|&&p| !env.get(&format!("{}_API_KEY", p)).map(String::as_str).unwrap_or("").is_empty())
        .map(|p| p.to_lowercase())
        .collect();
    if found.is_empty() {
        "none (self-hosted only)".into()
    } else {
        found.join(", ")
    }
}

// ── Test harness ──────────────────────────────────────────────────────────────

struct TestResult {
    name: String,
    passed: bool,
    detail: String,
}

fn run_test(name: &str, f: impl FnOnce() -> Result<String, aiorka::Error>) -> TestResult {
    print!("Running: {} ... ", name);
    match f() {
        Ok(detail) => {
            println!("PASS");
            TestResult { name: name.into(), passed: true, detail }
        }
        Err(e) => {
            let msg = e.0.chars().take(120).collect::<String>();
            println!("FAIL  {}", msg);
            TestResult { name: name.into(), passed: false, detail: msg }
        }
    }
}

fn print_summary(results: &[TestResult]) {
    println!("{}", banner("Results"));
    for r in results {
        let mark = if r.passed { "✓ PASS" } else { "✗ FAIL" };
        let detail = r.detail.replace('\n', "\n       ");
        println!("{}  {}\n       {}", mark, r.name, detail);
    }
    let passed = results.iter().filter(|r| r.passed).count();
    println!("\n{}/{} tests passed", passed, results.len());
}

fn banner(title: &str) -> String {
    let bar = "═".repeat(60);
    format!("\n{}\n  {}\n{}", bar, title, bar)
}

// ── Individual tests ──────────────────────────────────────────────────────────

fn test_health(client: &Client) -> Result<String, aiorka::Error> {
    let snapshot = client.health()?;
    let mut lines = Vec::new();
    for (id, s) in &snapshot {
        let mark = if s.alive { "✓" } else { "✗" };
        let latency = s.latency_ms.map(|ms| format!("{}ms", ms)).unwrap_or_else(|| "no data".into());
        lines.push(format!("   {} {}  failures={}  latency={}", mark, id, s.failures, latency));
    }
    Ok(format!("{} providers registered:\n{}", snapshot.len(), lines.join("\n")))
}

fn test_policy(client: &Client, policy_id: &str, prompt: &str) -> Result<String, aiorka::Error> {
    let resp = client.execute(policy_id, &[Message::user(prompt)])?;
    let preview: String = resp.content.chars().take(120).collect();
    Ok(format!("via {} ({}) {}ms\n   {:?}", resp.provider_id, resp.model_used, resp.duration_ms, preview))
}

fn test_fallback(env: &HashMap<String, String>, config_yaml: &str, prompt: &str) -> Result<String, aiorka::Error> {
    let mut client = Client::new(Config { config_yaml: Some(config_yaml.into()) })?;
    client.set_key("ANTHROPIC_API_KEY", "sk-ant-INVALID-FOR-FALLBACK-TEST");
    for k in &["OPENAI_API_KEY", "GEMINI_API_KEY", "DEEPSEEK_API_KEY", "QWEN_API_KEY"] {
        if let Some(v) = env.get(*k) {
            if !v.is_empty() {
                client.set_key(k, v);
            }
        }
    }
    let resp = client.execute("test-fallback", &[Message::user(prompt)])?;
    let preview: String = resp.content.chars().take(120).collect();
    Ok(format!(
        "skipped anthropic-sonnet (bad key) → landed on {} ({}ms)\n   {:?}",
        resp.provider_id, resp.duration_ms, preview
    ))
}

fn test_multi_turn(client: &Client) -> Result<String, aiorka::Error> {
    let mut messages = vec![
        Message::system("You are a concise assistant. Keep replies under 15 words."),
        Message::user("My favourite colour is indigo. Remember that."),
    ];
    let t1 = client.execute("test-chat", &messages)?;
    messages.push(Message::assistant(&t1.content));
    messages.push(Message::user("What is my favourite colour?"));
    let t2 = client.execute("test-chat", &messages)?;
    let remembered = t2.content.to_lowercase().contains("indigo");
    let preview: String = t2.content.chars().take(120).collect();
    Ok(format!(
        "context preserved={}  via {}\n   Turn 2: {:?}",
        remembered, t2.provider_id, preview
    ))
}

fn test_key_injection(env: &HashMap<String, String>, config_yaml: &str, prompt: &str) -> Result<String, aiorka::Error> {
    let key = env.get("GEMINI_API_KEY").map(String::as_str).unwrap_or("").trim().to_string();
    if key.is_empty() {
        return Ok("SKIPPED — GEMINI_API_KEY not set".into());
    }
    // No keys at construction
    let mut client = Client::new(Config { config_yaml: Some(config_yaml.into()) })?;
    client.set_key("GEMINI_API_KEY", &key);
    let resp = client.execute("test-chat", &[Message::user(prompt)])?;
    Ok(format!("key injected after init → {} responded in {}ms", resp.provider_id, resp.duration_ms))
}

// ── Main ──────────────────────────────────────────────────────────────────────

fn main() {
    println!("{}", banner("aiOrka Rust Test App"));

    let env = load_env();
    let config_yaml = load_config(&env);
    let prompt = env
        .get("AIORKA_TEST_PROMPT")
        .map(String::as_str)
        .unwrap_or("Hello! Reply in exactly five words.")
        .to_string();

    println!("Library : aiorka {}", aiorka::version());
    println!("Config  : examples/aiOrka.yaml");
    println!(
        "Endpoint: {}",
        env.get("SELFHOSTED_ENDPOINT").map(String::as_str).unwrap_or("http://localhost:11434")
    );
    println!("Keys    : {}\n", active_keys(&env));

    let mut client = match Client::new(Config { config_yaml: Some(config_yaml.clone()) }) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("Failed to initialize: {}", e);
            process::exit(1);
        }
    };
    for k in &["ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "DEEPSEEK_API_KEY", "QWEN_API_KEY"] {
        if let Some(v) = env.get(*k) {
            if !v.is_empty() {
                client.set_key(k, v);
            }
        }
    }

    let results = vec![
        run_test("1. Health Snapshot",    || test_health(&client)),
        run_test("2. Local Inference",    || test_policy(&client, "test-local", &prompt)),
        run_test("3. General Chat",       || test_policy(&client, "test-chat", &prompt)),
        run_test("4. Reasoning",          || test_policy(&client, "test-reasoning", &prompt)),
        run_test("5. Fallback Chain",     || test_fallback(&env, &config_yaml, &prompt)),
        run_test("6. Multi-turn",         || test_multi_turn(&client)),
        run_test("7. Runtime Key Inject", || test_key_injection(&env, &config_yaml, &prompt)),
    ];

    drop(client);
    print_summary(&results);

    if results.iter().any(|r| !r.passed) {
        process::exit(1);
    }
}
