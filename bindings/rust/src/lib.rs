//! aiOrka — policy-driven AI orchestration.
//!
//! # Quick start
//!
//! ```no_run
//! use aiorka::{Client, Config, Message};
//!
//! let mut client = Client::new(Config::default()).unwrap();
//! client.set_key("ANTHROPIC_API_KEY", "sk-ant-...");
//!
//! let response = client.execute(
//!     "fast-chat",
//!     &[Message::user("Hello!")],
//! ).unwrap();
//!
//! println!("{}", response.content);
//! ```

pub mod sys;

use std::ffi::{CStr, CString, c_void};
use std::ptr;

use serde::{Deserialize, Serialize};

// ── Public types ──────────────────────────────────────────────────────────────

/// A single conversation turn.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    pub role: String,
    pub content: String,
}

impl Message {
    pub fn user(content: impl Into<String>) -> Self {
        Self { role: "user".into(), content: content.into() }
    }
    pub fn assistant(content: impl Into<String>) -> Self {
        Self { role: "assistant".into(), content: content.into() }
    }
    pub fn system(content: impl Into<String>) -> Self {
        Self { role: "system".into(), content: content.into() }
    }
}

/// The provider's reply.
#[derive(Debug, Clone, Deserialize)]
pub struct Response {
    pub content: String,
    pub provider_id: String,
    pub model_used: String,
    pub duration_ms: i64,
    pub tokens_used: Option<i32>,
    pub cost: Option<f64>,
}

/// Health snapshot for a single provider.
#[derive(Debug, Clone, Deserialize)]
pub struct ProviderHealth {
    pub alive: bool,
    pub failures: i32,
    pub latency_ms: Option<i64>,
}

/// Configuration for [`Client::new`].
#[derive(Debug, Default, Clone)]
pub struct Config {
    /// Full content of an `aiOrka.yaml` file.
    /// Leave `None` to use the library's bundled defaults.
    pub config_yaml: Option<String>,
}

/// Error type for all aiOrka operations.
#[derive(Debug)]
pub struct Error(pub String);

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "aiorka: {}", self.0)
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

// ── Client ────────────────────────────────────────────────────────────────────

/// The main aiOrka client. Drop to release the underlying native handle.
pub struct Client {
    handle: *mut c_void,
}

// The Kotlin runtime and the C API surface are thread-safe for concurrent reads.
// Writes (set_key, execute) are synchronized inside the native library.
unsafe impl Send for Client {}
unsafe impl Sync for Client {}

impl Client {
    /// Create a new client. Pass `Config::default()` to use bundled defaults.
    pub fn new(cfg: Config) -> Result<Self> {
        let raw_yaml = cfg.config_yaml
            .as_deref()
            .map(|s| CString::new(s).expect("config YAML must not contain null bytes"));

        let handle = unsafe {
            sys::aiorka_create(
                raw_yaml.as_ref().map_or(ptr::null(), |cs| cs.as_ptr()),
            )
        };

        if handle.is_null() {
            Err(last_error("initialization failed"))
        } else {
            Ok(Client { handle })
        }
    }

    /// Inject an API key at runtime.
    ///
    /// ```no_run
    /// client.set_key("ANTHROPIC_API_KEY", "sk-ant-...");
    /// ```
    pub fn set_key(&mut self, env_var_name: &str, key_value: &str) {
        let name = CString::new(env_var_name).expect("env var name must not contain nulls");
        let val = CString::new(key_value).expect("key value must not contain nulls");
        unsafe { sys::aiorka_set_key(self.handle, name.as_ptr(), val.as_ptr()) };
    }

    /// Execute the named policy and return the provider's response.
    pub fn execute(&self, policy_id: &str, messages: &[Message]) -> Result<Response> {
        let messages_json = serde_json::to_string(messages)
            .map_err(|e| Error(format!("serialize messages: {e}")))?;

        let c_policy = CString::new(policy_id).expect("policy_id must not contain nulls");
        let c_msgs = CString::new(messages_json).expect("messages JSON must not contain nulls");

        let raw = unsafe {
            sys::aiorka_execute(self.handle, c_policy.as_ptr(), c_msgs.as_ptr())
        };

        if raw.is_null() {
            return Err(last_error("execute failed"));
        }
        let json_str = unsafe { CStr::from_ptr(raw).to_string_lossy().into_owned() };
        unsafe { sys::aiorka_free_string(raw) };

        serde_json::from_str(&json_str).map_err(|e| Error(format!("parse response: {e}")))
    }

    /// Return health snapshots for all configured providers.
    pub fn health(&self) -> Result<std::collections::HashMap<String, ProviderHealth>> {
        let raw = unsafe { sys::aiorka_health(self.handle) };
        if raw.is_null() {
            return Err(last_error("health check failed"));
        }
        let json_str = unsafe { CStr::from_ptr(raw).to_string_lossy().into_owned() };
        unsafe { sys::aiorka_free_string(raw) };
        serde_json::from_str(&json_str).map_err(|e| Error(format!("parse health: {e}")))
    }
}

impl Drop for Client {
    fn drop(&mut self) {
        if !self.handle.is_null() {
            unsafe { sys::aiorka_destroy(self.handle) };
            self.handle = ptr::null_mut();
        }
    }
}

/// Return the native library version string.
pub fn version() -> String {
    let raw = unsafe { sys::aiorka_version() };
    let s = unsafe { CStr::from_ptr(raw).to_string_lossy().into_owned() };
    unsafe { sys::aiorka_free_string(raw) };
    s
}

// ── Internal helpers ──────────────────────────────────────────────────────────

fn last_error(fallback: &str) -> Error {
    let raw = unsafe { sys::aiorka_last_error() };
    if raw.is_null() {
        return Error(fallback.to_string());
    }
    let msg = unsafe { CStr::from_ptr(raw).to_string_lossy().into_owned() };
    unsafe { sys::aiorka_free_string(raw) };
    Error(msg)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn native_available() -> bool {
        std::env::var("AIORKA_TEST_ENABLED").as_deref() == Ok("1")
    }

    #[test]
    fn test_version() {
        if !native_available() { return; }
        let v = version();
        assert!(!v.is_empty(), "version must not be empty");
    }

    #[test]
    fn test_new_and_drop() {
        if !native_available() { return; }
        let client = Client::new(Config::default()).expect("create client");
        drop(client); // exercises Drop
    }

    #[test]
    fn test_set_key_and_health() {
        if !native_available() { return; }
        let mut client = Client::new(Config::default()).expect("create client");
        client.set_key("ANTHROPIC_API_KEY", "test-key");
        let health = client.health().expect("health snapshot");
        assert!(!health.is_empty(), "should have at least one provider");
    }

    #[test]
    fn test_message_constructors() {
        let u = Message::user("hello");
        assert_eq!(u.role, "user");
        let a = Message::assistant("hi");
        assert_eq!(a.role, "assistant");
        let s = Message::system("be helpful");
        assert_eq!(s.role, "system");
    }
}
