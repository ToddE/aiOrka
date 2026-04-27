// aiOrka Go Test App
// Runs the same scenarios as the Kotlin and Python test apps using cgo bindings.
//
// Setup:
//
//	bash scripts/build_bindings.sh
//	export CGO_LDFLAGS="-L$(pwd)/bindings/go -Wl,-rpath,$(pwd)/bindings/go"
//	go run examples/go/main.go
package main

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/your-org/aiorka-go/aiorka"
)

// ── .env loader ───────────────────────────────────────────────────────────────

func loadEnv() map[string]string {
	result := make(map[string]string)
	// Walk up from CWD looking for .env
	dir, _ := os.Getwd()
	for i := 0; i < 4; i++ {
		path := filepath.Join(dir, ".env")
		f, err := os.Open(path)
		if err == nil {
			scanner := bufio.NewScanner(f)
			for scanner.Scan() {
				line := strings.TrimSpace(scanner.Text())
				if line == "" || strings.HasPrefix(line, "#") || !strings.Contains(line, "=") {
					continue
				}
				idx := strings.Index(line, "=")
				result[strings.TrimSpace(line[:idx])] = strings.TrimSpace(line[idx+1:])
			}
			f.Close()
			return result
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	return result
}

func loadConfig(env map[string]string) string {
	dir, _ := os.Getwd()
	candidates := []string{
		filepath.Join(dir, "examples", "aiOrka.yaml"),
		filepath.Join(dir, "..", "aiOrka.yaml"),
		filepath.Join(dir, "aiOrka.yaml"),
	}
	for _, p := range candidates {
		data, err := os.ReadFile(p)
		if err == nil {
			endpoint := env["SELFHOSTED_ENDPOINT"]
			if endpoint == "" {
				endpoint = "http://localhost:11434"
			}
			return strings.ReplaceAll(string(data), "http://localhost:11434", endpoint)
		}
	}
	fmt.Fprintln(os.Stderr, "Cannot find examples/aiOrka.yaml — run from the repo root")
	os.Exit(1)
	return ""
}

func activeKeys(env map[string]string) string {
	providers := []string{"ANTHROPIC", "OPENAI", "GEMINI", "DEEPSEEK"}
	var found []string
	for _, p := range providers {
		if strings.TrimSpace(env[p+"_API_KEY"]) != "" {
			found = append(found, strings.ToLower(p))
		}
	}
	if len(found) == 0 {
		return "none (self-hosted only)"
	}
	return strings.Join(found, ", ")
}

// ── Output helpers ────────────────────────────────────────────────────────────

func banner(title string) string {
	bar := strings.Repeat("═", 60)
	return fmt.Sprintf("\n%s\n  %s\n%s", bar, title, bar)
}

// ── Test harness ──────────────────────────────────────────────────────────────

type result struct {
	name   string
	passed bool
	detail string
}

func runTest(name string, fn func() (string, error)) result {
	fmt.Printf("Running: %s ... ", name)
	detail, err := fn()
	if err != nil {
		msg := err.Error()
		if len(msg) > 120 {
			msg = msg[:120]
		}
		fmt.Printf("FAIL  %s\n", msg)
		return result{name, false, msg}
	}
	fmt.Println("PASS")
	return result{name, true, detail}
}

func printSummary(results []result) {
	fmt.Println(banner("Results"))
	for _, r := range results {
		mark := "✓ PASS"
		if !r.passed {
			mark = "✗ FAIL"
		}
		detail := strings.ReplaceAll(r.detail, "\n", "\n       ")
		fmt.Printf("%s  %s\n       %s\n", mark, r.name, detail)
	}
	passed := 0
	for _, r := range results {
		if r.passed {
			passed++
		}
	}
	fmt.Printf("\n%d/%d tests passed\n", passed, len(results))
}

// ── Individual tests ──────────────────────────────────────────────────────────

func testHealth(client *aiorka.Client) (string, error) {
	snapshot, err := client.Health()
	if err != nil {
		return "", err
	}
	var lines []string
	for id, s := range snapshot {
		mark := "✓"
		if !s.Alive {
			mark = "✗"
		}
		latency := "no data"
		if s.LatencyMs != nil {
			latency = fmt.Sprintf("%dms", *s.LatencyMs)
		}
		lines = append(lines, fmt.Sprintf("   %s %s  failures=%d  latency=%s", mark, id, s.Failures, latency))
	}
	return fmt.Sprintf("%d providers registered:\n%s", len(snapshot), strings.Join(lines, "\n")), nil
}

func testPolicy(client *aiorka.Client, policyID, prompt string) (string, error) {
	resp, err := client.Execute(policyID, []aiorka.Message{{Role: "user", Content: prompt}})
	if err != nil {
		return "", err
	}
	preview := resp.Content
	if len(preview) > 120 {
		preview = preview[:120]
	}
	return fmt.Sprintf("via %s (%s) %dms\n   %q", resp.ProviderID, resp.ModelUsed, resp.DurationMs, preview), nil
}

func testFallback(env map[string]string, configYAML, prompt string) (string, error) {
	client, err := aiorka.New(aiorka.Config{ConfigYAML: configYAML})
	if err != nil {
		return "", err
	}
	defer client.Close()

	client.SetKey("ANTHROPIC_API_KEY", "sk-ant-INVALID-FOR-FALLBACK-TEST")
	for _, k := range []string{"OPENAI_API_KEY", "GEMINI_API_KEY", "DEEPSEEK_API_KEY"} {
		if v := strings.TrimSpace(env[k]); v != "" {
			client.SetKey(k, v)
		}
	}

	resp, err := client.Execute("test-fallback", []aiorka.Message{{Role: "user", Content: prompt}})
	if err != nil {
		return "", err
	}
	preview := resp.Content
	if len(preview) > 120 {
		preview = preview[:120]
	}
	return fmt.Sprintf("skipped anthropic-sonnet (bad key) → landed on %s (%dms)\n   %q",
		resp.ProviderID, resp.DurationMs, preview), nil
}

func testMultiTurn(client *aiorka.Client) (string, error) {
	messages := []aiorka.Message{
		{Role: "system", Content: "You are a concise assistant. Keep replies under 15 words."},
		{Role: "user", Content: "My favourite colour is indigo. Remember that."},
	}
	t1, err := client.Execute("test-chat", messages)
	if err != nil {
		return "", err
	}
	messages = append(messages,
		aiorka.Message{Role: "assistant", Content: t1.Content},
		aiorka.Message{Role: "user", Content: "What is my favourite colour?"},
	)
	t2, err := client.Execute("test-chat", messages)
	if err != nil {
		return "", err
	}
	remembered := strings.Contains(strings.ToLower(t2.Content), "indigo")
	preview := t2.Content
	if len(preview) > 120 {
		preview = preview[:120]
	}
	return fmt.Sprintf("context preserved=%v  via %s\n   Turn 2: %q", remembered, t2.ProviderID, preview), nil
}

func testKeyInjection(env map[string]string, configYAML, prompt string) (string, error) {
	key := strings.TrimSpace(env["GEMINI_API_KEY"])
	if key == "" {
		return "SKIPPED — GEMINI_API_KEY not set", nil
	}
	// No keys at construction time
	client, err := aiorka.New(aiorka.Config{ConfigYAML: configYAML})
	if err != nil {
		return "", err
	}
	defer client.Close()

	client.SetKey("GEMINI_API_KEY", key)
	resp, err := client.Execute("test-chat", []aiorka.Message{{Role: "user", Content: prompt}})
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("key injected after init → %s responded in %dms", resp.ProviderID, resp.DurationMs), nil
}

// ── Main ──────────────────────────────────────────────────────────────────────

func main() {
	fmt.Println(banner("aiOrka Go Test App"))

	env := loadEnv()
	configYAML := loadConfig(env)
	prompt := env["AIORKA_TEST_PROMPT"]
	if prompt == "" {
		prompt = "Hello! Reply in exactly five words."
	}

	fmt.Printf("Library : aiorka %s\n", aiorka.Version())
	fmt.Printf("Platform: %s/%s\n", runtime.GOOS, runtime.GOARCH)
	fmt.Printf("Config  : examples/aiOrka.yaml\n")
	fmt.Printf("Endpoint: %s\n", func() string {
		if ep := env["SELFHOSTED_ENDPOINT"]; ep != "" {
			return ep
		}
		return "http://localhost:11434"
	}())
	fmt.Printf("Keys    : %s\n\n", activeKeys(env))

	client, err := aiorka.New(aiorka.Config{ConfigYAML: configYAML})
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to initialize: %v\n", err)
		os.Exit(1)
	}
	for _, k := range []string{"ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "DEEPSEEK_API_KEY"} {
		if v := strings.TrimSpace(env[k]); v != "" {
			client.SetKey(k, v)
		}
	}

	results := []result{
		runTest("1. Health Snapshot",    func() (string, error) { return testHealth(client) }),
		runTest("2. Local Inference",    func() (string, error) { return testPolicy(client, "test-local", prompt) }),
		runTest("3. General Chat",       func() (string, error) { return testPolicy(client, "test-chat", prompt) }),
		runTest("4. Reasoning",          func() (string, error) { return testPolicy(client, "test-reasoning", prompt) }),
		runTest("5. Fallback Chain",     func() (string, error) { return testFallback(env, configYAML, prompt) }),
		runTest("6. Multi-turn",         func() (string, error) { return testMultiTurn(client) }),
		runTest("7. Runtime Key Inject", func() (string, error) { return testKeyInjection(env, configYAML, prompt) }),
	}

	client.Close()
	printSummary(results)

	for _, r := range results {
		if !r.passed {
			os.Exit(1)
		}
	}
}
