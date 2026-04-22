"""Public data classes for the aiOrka Python API."""

from dataclasses import dataclass


@dataclass
class Message:
    role: str    # "user" | "assistant" | "system"
    content: str

    @staticmethod
    def user(text: str) -> "Message":
        return Message(role="user", content=text)

    @staticmethod
    def assistant(text: str) -> "Message":
        return Message(role="assistant", content=text)

    @staticmethod
    def system(text: str) -> "Message":
        return Message(role="system", content=text)


@dataclass
class OrkaResponse:
    content: str
    provider_id: str
    model_used: str
    duration_ms: int
    tokens_used: int | None = None
    cost: float | None = None


@dataclass
class ProviderHealth:
    alive: bool
    failures: int
    latency_ms: int | None
