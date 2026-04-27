"""Unit tests for Python-side model helpers (no native library required)."""

import json
import pytest
from aiorka.models import Message, OrkaResponse, ProviderHealth


def test_message_user_factory():
    m = Message.user("hello")
    assert m.role == "user"
    assert m.content == "hello"


def test_message_assistant_factory():
    m = Message.assistant("hi there")
    assert m.role == "assistant"
    assert m.content == "hi there"


def test_message_system_factory():
    m = Message.system("You are helpful.")
    assert m.role == "system"


def test_messages_serialise_to_json():
    msgs = [Message.user("ping"), Message.assistant("pong")]
    payload = json.dumps([{"role": m.role, "content": m.content} for m in msgs])
    parsed = json.loads(payload)
    assert parsed[0] == {"role": "user", "content": "ping"}
    assert parsed[1] == {"role": "assistant", "content": "pong"}


def test_orka_response_optional_fields():
    r = OrkaResponse(
        content="Hello",
        provider_id="selfhosted-local",
        model_used="llama3",
        duration_ms=120,
    )
    assert r.tokens_used is None
    assert r.cost is None


def test_provider_health_fields():
    h = ProviderHealth(alive=True, failures=0, latency_ms=45)
    assert h.alive is True
    assert h.latency_ms == 45
