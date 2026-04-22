//! Raw `extern "C"` declarations — the exact C API surface exported by libaiorka.
//! All pointers returned by the library must be freed with `aiorka_free_string`.

use std::ffi::{c_char, c_void};

extern "C" {
    /// Create an AiOrka instance. `config_yaml` may be null (use bundled defaults).
    /// Returns an opaque handle, or null on error.
    pub fn aiorka_create(config_yaml: *const c_char) -> *mut c_void;

    /// Execute the named policy with the given JSON message array.
    /// Returns a heap-allocated JSON string (caller must free), or null on error.
    pub fn aiorka_execute(
        handle: *mut c_void,
        policy_id: *const c_char,
        messages_json: *const c_char,
    ) -> *mut c_char;

    /// Inject an API key at runtime.
    pub fn aiorka_set_key(
        handle: *mut c_void,
        env_var_name: *const c_char,
        key_value: *const c_char,
    );

    /// Return the last error as a heap-allocated string, or null if none.
    pub fn aiorka_last_error() -> *mut c_char;

    /// Free a string previously returned by any library function.
    pub fn aiorka_free_string(ptr: *mut c_char);

    /// Return the health snapshot JSON. Caller must free.
    pub fn aiorka_health(handle: *mut c_void) -> *mut c_char;

    /// Destroy the handle and release all resources.
    pub fn aiorka_destroy(handle: *mut c_void);

    /// Return the library version string. Caller must free.
    pub fn aiorka_version() -> *mut c_char;
}
