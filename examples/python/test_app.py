#!/usr/bin/env python3
"""
aiOrka Python Test App
Runs the same scenarios as the Kotlin test app using the ctypes bindings.

Setup:
    1. Build native lib:  bash scripts/build_bindings.sh
    2. Install package:   pip install -e bindings/python/
    3. Fill in .env
    4. Run:               python examples/python/test_app.py
"""

import json
import os
import sys
import time
from pathlib import Path
from typing import Any

# ── Resolve repo root and load .env ───────────────────────────────────────────

def find_repo_root() -> Path:
    here = Path(__file__).resolve().parent
    for candidate in [here, here.parent, here.parent.parent]:
        if (candidate / ".env").exists() or (candidate / "examples").is_dir():
            return candidate
    return here.parent.parent


def load_env(root: Path) -> dict[str, str]:
    env_file = root / ".env"
    if not env_file.exists():
        return {}
    result = {}
    for line in env_file.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        result[k.strip()] = v.strip()
    return result


def load_config(root: Path, env: dict[str, str]) -> str:
    config_path = root / "examples" / "aiOrka.yaml"
    if not config_path.exists():
        sys.exit(f"Cannot find {config_path} — run from the repo root")
    yaml = config_path.read_text()
    endpoint = env.get("OLLAMA_ENDPOINT", "http://localhost:11434")
    return yaml.replace("http://localhost:11434", endpoint)


# ── Banner / output helpers ───────────────────────────────────────────────────

def banner(title: str) -> str:
    bar = "═" * 60
    return f"\n{bar}\n  {title}\n{bar}"


def active_keys(env: dict[str, str]) -> str:
    providers = ["ANTHROPIC", "OPENAI", "GEMINI", "DEEPSEEK"]
    found = [p.lower() for p in providers if env.get(f"{p}_API_KEY", "").strip()]
    return ", ".join(found) if found else "none (local Ollama only)"


# ── Test runner ───────────────────────────────────────────────────────────────

class TestResult:
    def __init__(self, name: str, passed: bool, detail: str):
        self.name = name
        self.passed = passed
        self.detail = detail


def run_test(name: str, fn) -> TestResult:
    print(f"Running: {name} ... ", end="", flush=True)
    try:
        detail = fn()
        print("PASS")
        return TestResult(name, True, detail)
    except Exception as e:
        msg = str(e)[:120]
        print(f"FAIL  {msg}")
        return TestResult(name, False, msg)


def print_summary(results: list[TestResult]) -> None:
    print(banner("Results"))
    for r in results:
        mark = "✓ PASS" if r.passed else "✗ FAIL"
        indent = "\n       "
        detail = r.detail.replace("\n", indent)
        print(f"{mark}  {r.name}")
        print(f"       {detail}")
    passed = sum(1 for r in results if r.passed)
    print(f"\n{passed}/{len(results)} tests passed")


# ── Individual tests ──────────────────────────────────────────────────────────

def test_health(orka) -> str:
    snapshot = orka.health()
    lines = []
    for pid, status in snapshot.items():
        mark = "✓" if status.alive else "✗"
        latency = f"{status.latency_ms}ms" if status.latency_ms is not None else "no data"
        lines.append(f"   {mark} {pid}  failures={status.failures}  latency={latency}")
    return f"{len(snapshot)} providers registered:\n" + "\n".join(lines)


def test_policy(orka, policy_id: str, prompt: str) -> str:
    from aiorka import Message
    resp = orka.execute(policy_id, [Message.user(prompt)])
    preview = resp.content[:120].replace("\n", " ")
    return f"via {resp.provider_id} ({resp.model_used}) {resp.duration_ms}ms\n   \"{preview}\""


def test_fallback(env: dict[str, str], config_yaml: str, prompt: str) -> str:
    from aiorka import AiOrka, Message
    # Inject bad Anthropic key so first provider in chain fails
    with AiOrka(config_yaml=config_yaml) as orka:
        orka.set_key("ANTHROPIC_API_KEY", "sk-ant-INVALID-FOR-FALLBACK-TEST")
        for k in ["OPENAI_API_KEY", "GEMINI_API_KEY", "DEEPSEEK_API_KEY"]:
            if env.get(k, "").strip():
                orka.set_key(k, env[k])
        resp = orka.execute("test-fallback", [Message.user(prompt)])
    preview = resp.content[:120].replace("\n", " ")
    return (
        f"skipped anthropic-sonnet (bad key) → landed on {resp.provider_id} ({resp.duration_ms}ms)\n"
        f"   \"{preview}\""
    )


def test_multi_turn(orka) -> str:
    from aiorka import Message
    messages = [
        Message("system", "You are a concise assistant. Keep replies under 15 words."),
        Message.user("My favourite colour is indigo. Remember that."),
    ]
    t1 = orka.execute("test-chat", messages)
    messages.append(Message("assistant", t1.content))
    messages.append(Message.user("What is my favourite colour?"))
    t2 = orka.execute("test-chat", messages)
    remembered = "indigo" in t2.content.lower()
    preview = t2.content[:120].replace("\n", " ")
    return f"context preserved={remembered}  via {t2.provider_id}\n   Turn 2: \"{preview}\""


def test_key_injection(env: dict[str, str], config_yaml: str, prompt: str) -> str:
    from aiorka import AiOrka, Message
    key = env.get("GEMINI_API_KEY", "").strip()
    if not key:
        return "SKIPPED — GEMINI_API_KEY not set"
    # Create with NO keys, then inject
    with AiOrka(config_yaml=config_yaml) as orka:
        orka.set_key("GEMINI_API_KEY", key)
        resp = orka.execute("test-chat", [Message.user(prompt)])
    return f"key injected after init → {resp.provider_id} responded in {resp.duration_ms}ms"


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    print(banner("aiOrka Python Test App"))

    root = find_repo_root()
    env = load_env(root)
    config_yaml = load_config(root, env)
    prompt = env.get("AIORKA_TEST_PROMPT", "Hello! Reply in exactly five words.")

    # Add the bindings/python directory to the path if not installed
    bindings_path = root / "bindings" / "python"
    if bindings_path.exists() and str(bindings_path) not in sys.path:
        sys.path.insert(0, str(bindings_path))

    try:
        from aiorka import AiOrka, Message, __version__
    except ImportError as e:
        sys.exit(
            f"Cannot import aiorka: {e}\n"
            "Run: bash scripts/build_bindings.sh && pip install -e bindings/python/"
        )

    print(f"Library : aiorka {__version__()}")
    print(f"Config  : {root / 'examples' / 'aiOrka.yaml'}")
    print(f"Endpoint: {env.get('OLLAMA_ENDPOINT', 'http://localhost:11434')}")
    print(f"Keys    : {active_keys(env)}\n")

    with AiOrka(config_yaml=config_yaml) as orka:
        for k in ["ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "DEEPSEEK_API_KEY"]:
            if env.get(k, "").strip():
                orka.set_key(k, env[k])

        results = [
            run_test("1. Health Snapshot",    lambda: test_health(orka)),
            run_test("2. Local Inference",    lambda: test_policy(orka, "test-local", prompt)),
            run_test("3. General Chat",       lambda: test_policy(orka, "test-chat", prompt)),
            run_test("4. Reasoning",          lambda: test_policy(orka, "test-reasoning", prompt)),
            run_test("5. Fallback Chain",     lambda: test_fallback(env, config_yaml, prompt)),
            run_test("6. Multi-turn",         lambda: test_multi_turn(orka)),
            run_test("7. Runtime Key Inject", lambda: test_key_injection(env, config_yaml, prompt)),
        ]

    print_summary(results)
    sys.exit(0 if all(r.passed for r in results) else 1)


if __name__ == "__main__":
    main()
