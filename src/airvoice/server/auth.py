"""Cloud bearer authentication without retaining token plaintext."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from airvoice.receiver_v1 import V1Error
from airvoice.server.security import token_digest

MAX_BEARER_TOKEN_LENGTH = 4096


class TokenDigestLookup(Protocol):
    def resolve_token_digest(self, digest: str) -> str | None:
        """Return the active token owner, or ``None``."""


@dataclass(frozen=True)
class CloudPrincipal:
    """Minimal principal understood by the existing persistence adapter."""

    name: str


class BearerTokenAuthenticator:
    """Resolve opaque bearer tokens through a peppered HMAC digest."""

    def __init__(self, lookup: TokenDigestLookup, *, pepper: bytes) -> None:
        if len(pepper) < 32:
            raise ValueError("token pepper must be at least 32 bytes")
        self.lookup = lookup
        self.pepper = pepper

    def authenticate(self, authorization: str | None) -> CloudPrincipal:
        if authorization is None:
            raise self._unauthorized()
        parts = authorization.strip().split()
        if len(parts) != 2 or parts[0].lower() != "bearer":
            raise self._unauthorized()
        supplied = parts[1]
        if not supplied or len(supplied) > MAX_BEARER_TOKEN_LENGTH:
            raise self._unauthorized()
        digest = token_digest(supplied, pepper=self.pepper)
        user_id = self.lookup.resolve_token_digest(digest)
        if user_id is None:
            raise self._unauthorized()
        return CloudPrincipal(name=user_id)

    @staticmethod
    def _unauthorized() -> V1Error:
        return V1Error(
            401,
            "UNAUTHORIZED",
            "A valid Bearer token is required",
        )
