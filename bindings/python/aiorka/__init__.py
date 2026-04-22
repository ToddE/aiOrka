"""
aiOrka — policy-driven AI orchestration for Python.

Quick start::

    from aiorka import AiOrka, Message

    with AiOrka() as orka:
        orka.set_key("ANTHROPIC_API_KEY", "sk-ant-...")
        response = orka.execute("fast-chat", [Message.user("Hello!")])
        print(response.content)

Configuration (optional)::

    yaml = open("my-config.yaml").read()
    with AiOrka(config_yaml=yaml) as orka:
        ...
"""

import json
from typing import Any

from .models import Message, OrkaResponse, ProviderHealth
from . import _ffi

__all__ = ["AiOrka", "Message", "OrkaResponse", "ProviderHealth", "__version__"]


def __version__() -> str:
    return _ffi.lib_version()


class AiOrkaError(Exception):
    """Raised when the native library returns an error."""


class AiOrka:
    """
    Pythonic wrapper around the native aiOrka shared library.

    Can be used as a context manager (recommended) or manually with
    :meth:`close`. The underlying native handle is created on construction
    and released on :meth:`close` / ``__exit__``.
    """

    def __init__(self, config_yaml: str | None = None) -> None:
        """
        Create and initialize an AiOrka instance.

        Args:
            config_yaml: Optional YAML string for your ``aiOrka.yaml``.
                         Pass ``None`` to use the library's bundled defaults.

        Raises:
            AiOrkaError: If native initialization fails.
        """
        handle = _ffi.lib_create(config_yaml)
        if not handle:
            err = _ffi.lib_last_error() or "Unknown error during initialization"
            raise AiOrkaError(err)
        self._handle = handle

    # ── Lifecycle ──────────────────────────────────────────────────────────────

    def close(self) -> None:
        """Release the native handle. Safe to call multiple times."""
        if self._handle:
            _ffi.lib_destroy(self._handle)
            self._handle = 0

    def __enter__(self) -> "AiOrka":
        return self

    def __exit__(self, *_: Any) -> None:
        self.close()

    def __del__(self) -> None:
        self.close()

    # ── Core API ───────────────────────────────────────────────────────────────

    def set_key(self, env_var_name: str, key_value: str) -> None:
        """
        Inject an API key at runtime by environment variable name.

        Equivalent to setting the env var before initialization, but works
        in sandboxed environments where env mutation is not possible.

        Args:
            env_var_name: The env var the provider reads (e.g. ``"ANTHROPIC_API_KEY"``).
            key_value:    The secret key value.
        """
        _ffi.lib_set_key(self._handle, env_var_name, key_value)

    def execute(self, policy_id: str, messages: list[Message]) -> OrkaResponse:
        """
        Execute a request using the named policy.

        Args:
            policy_id: Name of the policy to run (e.g. ``"fast-chat"``).
            messages:  Conversation history as a list of :class:`Message`.

        Returns:
            :class:`OrkaResponse` with the provider's reply.

        Raises:
            AiOrkaError: On provider failure or unknown policy.
        """
        messages_json = json.dumps(
            [{"role": m.role, "content": m.content} for m in messages]
        )
        raw = _ffi.lib_execute(self._handle, policy_id, messages_json)
        if raw is None:
            err = _ffi.lib_last_error() or "Execution failed"
            raise AiOrkaError(err)
        data = json.loads(raw)
        return OrkaResponse(
            content=data["content"],
            provider_id=data["provider_id"],
            model_used=data["model_used"],
            duration_ms=data["duration_ms"],
            tokens_used=data.get("tokens_used"),
            cost=data.get("cost"),
        )

    def health(self) -> dict[str, ProviderHealth]:
        """
        Return a snapshot of the health state for all configured providers.

        Returns:
            Dict mapping provider ID → :class:`ProviderHealth`.

        Raises:
            AiOrkaError: If the native call fails.
        """
        raw = _ffi.lib_health(self._handle)
        if raw is None:
            err = _ffi.lib_last_error() or "Health check failed"
            raise AiOrkaError(err)
        data = json.loads(raw)
        return {
            pid: ProviderHealth(
                alive=v["alive"],
                failures=v["failures"],
                latency_ms=v.get("latency_ms"),
            )
            for pid, v in data.items()
        }
