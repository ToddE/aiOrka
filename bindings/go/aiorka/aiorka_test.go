package aiorka_test

// Integration tests — require a compiled libaiorka.so on the library path.
// Run with: CGO_LDFLAGS="-L/path/to/lib" go test ./...

import (
	"os"
	"testing"
)

func skipIfNoLib(t *testing.T) {
	t.Helper()
	// A cheap way to detect whether the library is available without
	// attempting to link and crash: we try to create an instance and skip
	// if it panics or errors with a load-time failure.
	// In practice CI sets AIORKA_TEST_ENABLED=1 only when the lib is built.
	if os.Getenv("AIORKA_TEST_ENABLED") != "1" {
		t.Skip("AIORKA_TEST_ENABLED not set — skipping native library tests")
	}
}

func TestVersion(t *testing.T) {
	skipIfNoLib(t)
	v := Version()
	if v == "" {
		t.Fatal("Version() returned empty string")
	}
	t.Logf("aiorka version: %s", v)
}

func TestNewAndClose(t *testing.T) {
	skipIfNoLib(t)
	client, err := New(Config{})
	if err != nil {
		t.Fatalf("New() error: %v", err)
	}
	client.Close()
	client.Close() // second close must be safe
}

func TestSetKeyAndHealth(t *testing.T) {
	skipIfNoLib(t)
	client, err := New(Config{})
	if err != nil {
		t.Fatalf("New() error: %v", err)
	}
	defer client.Close()

	// SetKey must not panic
	client.SetKey("ANTHROPIC_API_KEY", "test-key")

	health, err := client.Health()
	if err != nil {
		t.Fatalf("Health() error: %v", err)
	}
	t.Logf("providers: %+v", health)
}
