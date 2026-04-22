// Package aiorka provides Go bindings for the aiOrka native shared library.
//
// Quick start:
//
//	orka, err := aiorka.New(aiorka.Config{})
//	if err != nil { log.Fatal(err) }
//	defer orka.Close()
//
//	orka.SetKey("ANTHROPIC_API_KEY", "sk-ant-...")
//
//	resp, err := orka.Execute("fast-chat", []aiorka.Message{
//	    {Role: "user", Content: "Hello!"},
//	})
//	if err != nil { log.Fatal(err) }
//	fmt.Println(resp.Content)
package aiorka

/*
#cgo LDFLAGS: -laiorka
#include <stdlib.h>

// Forward declarations matching the exported C API.
void* aiorka_create(const char* config_yaml);
char* aiorka_execute(void* handle, const char* policy_id, const char* messages_json);
void  aiorka_set_key(void* handle, const char* env_var_name, const char* key_value);
char* aiorka_last_error();
void  aiorka_free_string(char* ptr);
char* aiorka_health(void* handle);
void  aiorka_destroy(void* handle);
char* aiorka_version();
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"unsafe"
)

// ── Public types ──────────────────────────────────────────────────────────────

// Message is a single turn in a conversation.
type Message struct {
	Role    string `json:"role"`    // "user" | "assistant" | "system"
	Content string `json:"content"`
}

// Response is the provider's reply to an Execute call.
type Response struct {
	Content    string  `json:"content"`
	ProviderID string  `json:"provider_id"`
	ModelUsed  string  `json:"model_used"`
	DurationMs int64   `json:"duration_ms"`
	TokensUsed *int    `json:"tokens_used,omitempty"`
	Cost       *float64 `json:"cost,omitempty"`
}

// ProviderHealth is the health snapshot for a single provider.
type ProviderHealth struct {
	Alive     bool   `json:"alive"`
	Failures  int    `json:"failures"`
	LatencyMs *int64 `json:"latency_ms,omitempty"`
}

// Config controls how the AiOrka instance is initialized.
type Config struct {
	// ConfigYAML is the full content of an aiOrka.yaml file.
	// Leave empty to use the library's bundled defaults.
	ConfigYAML string
}

// AiOrkaError is returned when the native library signals a failure.
type AiOrkaError struct {
	Message string
}

func (e *AiOrkaError) Error() string { return "aiorka: " + e.Message }

// ── Client ────────────────────────────────────────────────────────────────────

// Client is the main aiOrka client. Obtain one with New; release it with Close.
type Client struct {
	handle unsafe.Pointer
}

// New creates and initializes an aiOrka client.
//
// An optional YAML string can be passed via cfg.ConfigYAML; pass an empty
// Config{} to use the library defaults.
func New(cfg Config) (*Client, error) {
	var rawYaml *C.char
	if cfg.ConfigYAML != "" {
		rawYaml = C.CString(cfg.ConfigYAML)
		defer C.free(unsafe.Pointer(rawYaml))
	}
	handle := C.aiorka_create(rawYaml)
	if handle == nil {
		return nil, lastError("initialization failed")
	}
	return &Client{handle: handle}, nil
}

// Close releases all resources held by the client. Safe to call multiple times.
func (c *Client) Close() {
	if c.handle != nil {
		C.aiorka_destroy(c.handle)
		c.handle = nil
	}
}

// SetKey injects an API key by environment variable name. This is the
// preferred way to supply keys in sandboxed environments.
//
//	client.SetKey("ANTHROPIC_API_KEY", "sk-ant-...")
func (c *Client) SetKey(envVarName, keyValue string) {
	name := C.CString(envVarName)
	val := C.CString(keyValue)
	defer C.free(unsafe.Pointer(name))
	defer C.free(unsafe.Pointer(val))
	C.aiorka_set_key(c.handle, name, val)
}

// Execute sends messages to the named policy and returns the provider's reply.
func (c *Client) Execute(policyID string, messages []Message) (*Response, error) {
	msgsJSON, err := json.Marshal(messages)
	if err != nil {
		return nil, fmt.Errorf("aiorka: marshal messages: %w", err)
	}

	cPolicy := C.CString(policyID)
	cMsgs := C.CString(string(msgsJSON))
	defer C.free(unsafe.Pointer(cPolicy))
	defer C.free(unsafe.Pointer(cMsgs))

	raw := C.aiorka_execute(c.handle, cPolicy, cMsgs)
	if raw == nil {
		return nil, lastError("execute failed")
	}
	defer C.aiorka_free_string(raw)

	var resp Response
	if err := json.Unmarshal([]byte(C.GoString(raw)), &resp); err != nil {
		return nil, fmt.Errorf("aiorka: parse response: %w", err)
	}
	return &resp, nil
}

// Health returns the current health snapshot for all configured providers.
func (c *Client) Health() (map[string]ProviderHealth, error) {
	raw := C.aiorka_health(c.handle)
	if raw == nil {
		return nil, lastError("health check failed")
	}
	defer C.aiorka_free_string(raw)

	var result map[string]ProviderHealth
	if err := json.Unmarshal([]byte(C.GoString(raw)), &result); err != nil {
		return nil, fmt.Errorf("aiorka: parse health: %w", err)
	}
	return result, nil
}

// Version returns the native library version string.
func Version() string {
	raw := C.aiorka_version()
	defer C.aiorka_free_string(raw)
	return C.GoString(raw)
}

// ── Internal helpers ──────────────────────────────────────────────────────────

func lastError(fallback string) *AiOrkaError {
	raw := C.aiorka_last_error()
	if raw == nil {
		return &AiOrkaError{Message: fallback}
	}
	defer C.aiorka_free_string(raw)
	return &AiOrkaError{Message: C.GoString(raw)}
}
