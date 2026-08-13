"""One-shot Cloud API user token issue/revoke administration."""

from __future__ import annotations

import argparse
import json
import os
import secrets
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import sqlalchemy as sa

from airvoice.adapters.postgres import PostgresDataStore
from airvoice.server.runtime import CloudApiConfigurationError, load_token_pepper
from airvoice.server.security import token_digest


@dataclass(frozen=True)
class IssuedToken:
    token_id: str
    user_id: str
    version: int
    token: str
    expires_at: datetime

    def as_dict(self) -> dict[str, object]:
        return {
            "tokenId": self.token_id,
            "userId": self.user_id,
            "version": self.version,
            "token": self.token,
            "expiresAt": self.expires_at.isoformat().replace("+00:00", "Z"),
        }


def issue_token(
    data: PostgresDataStore,
    *,
    user_id: str,
    version: int,
    expires_in_days: int,
    pepper: bytes,
    token_factory: Callable[[int], str] = secrets.token_urlsafe,
) -> IssuedToken:
    """Create one opaque token and persist only its keyed digest."""
    normalized_user = user_id.strip()
    if not normalized_user or len(normalized_user) > 128:
        raise ValueError("user_id must contain 1..128 characters")
    if version < 1:
        raise ValueError("version must be positive")
    if expires_in_days < 1 or expires_in_days > 3650:
        raise ValueError("expires_in_days must be between 1 and 3650")
    plaintext = token_factory(32)
    expires_at = datetime.now(UTC) + timedelta(days=expires_in_days)
    data.provision_user(normalized_user)
    token_id = data.store_token_digest(
        user_id=normalized_user,
        digest=token_digest(plaintext, pepper=pepper),
        version=version,
        expires_at=expires_at,
    )
    return IssuedToken(
        token_id=str(token_id),
        user_id=normalized_user,
        version=version,
        token=plaintext,
        expires_at=expires_at,
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Issue or revoke AI R Voice Cloud API bearer tokens.",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    issue = commands.add_parser("issue-token")
    issue.add_argument("--user-id", required=True)
    issue.add_argument("--version", type=int, required=True)
    issue.add_argument("--expires-in-days", type=int, default=90)
    revoke = commands.add_parser("revoke-token")
    revoke.add_argument("--token-id", required=True)
    return parser


def main(
    argv: Sequence[str] | None = None,
    *,
    environ: Mapping[str, str] | None = None,
) -> int:
    arguments = _parser().parse_args(argv)
    values = os.environ if environ is None else environ
    database_url = values.get("DATABASE_URL", "").strip()
    if not database_url:
        raise CloudApiConfigurationError("DATABASE_URL is required")
    pepper = load_token_pepper(values)
    engine = sa.create_engine(database_url, pool_pre_ping=True)
    data = PostgresDataStore(engine)
    try:
        if arguments.command == "issue-token":
            issued = issue_token(
                data,
                user_id=arguments.user_id,
                version=arguments.version,
                expires_in_days=arguments.expires_in_days,
                pepper=pepper,
            )
            print(  # noqa: T201 - intentional one-time credential handoff
                json.dumps(issued.as_dict(), separators=(",", ":"))
            )
            return 0
        revoked = data.revoke_token(arguments.token_id)
        print(  # noqa: T201 - administrative machine-readable result
            json.dumps(
                {"tokenId": arguments.token_id, "revoked": revoked},
                separators=(",", ":"),
            )
        )
        return 0 if revoked else 1
    finally:
        engine.dispose()


if __name__ == "__main__":
    raise SystemExit(main())
