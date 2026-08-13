"""Credential derivation helpers that never persist bearer token plaintext."""

from __future__ import annotations

import hashlib
import hmac


def token_digest(token: str, *, pepper: bytes) -> str:
    """Return a keyed SHA-256 digest suitable for token lookup and rotation."""
    if not token:
        raise ValueError("token must not be empty")
    if len(pepper) < 32:
        raise ValueError("token pepper must be at least 32 bytes")
    return hmac.new(pepper, token.encode("utf-8"), hashlib.sha256).hexdigest()


def token_digest_matches(token: str, expected: str, *, pepper: bytes) -> bool:
    """Compare a presented token to a persisted digest in constant time."""
    actual = token_digest(token, pepper=pepper)
    return hmac.compare_digest(actual, expected)
