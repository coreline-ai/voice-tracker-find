"""Age-gated compensation for objects left without a committed receipt."""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class ObjectCandidate:
    object_key: str
    generation: str
    updated_at: dt.datetime


@dataclass(frozen=True)
class SweepReport:
    scanned: int
    deleted: int
    referenced: int
    recent: int


class ObjectCatalog(Protocol):
    def iter_candidates(self, prefix: str) -> list[ObjectCandidate]:
        """List immutable object generations under a controlled prefix."""

    def delete_if_generation(self, object_key: str, generation: str) -> bool:
        """Delete only when the candidate generation is still current."""


class ReceiptObjectIndex(Protocol):
    def object_is_referenced(self, object_key: str, generation: str) -> bool:
        """Return whether a committed receipt owns this object generation."""


def sweep_orphans(
    *,
    catalog: ObjectCatalog,
    receipts: ReceiptObjectIndex,
    prefixes: tuple[str, ...] = ("users/", ".staging/"),
    minimum_age: dt.timedelta = dt.timedelta(hours=24),
    now: dt.datetime | None = None,
) -> SweepReport:
    """Delete old unreferenced generations without racing active uploads."""
    current = now or dt.datetime.now(dt.UTC)
    if current.tzinfo is None:
        raise ValueError("now must be timezone-aware")
    cutoff = current - minimum_age
    scanned = deleted = referenced = recent = 0
    for prefix in prefixes:
        for candidate in catalog.iter_candidates(prefix):
            scanned += 1
            updated = candidate.updated_at
            if updated.tzinfo is None:
                updated = updated.replace(tzinfo=dt.UTC)
            if updated > cutoff:
                recent += 1
                continue
            if receipts.object_is_referenced(
                candidate.object_key,
                candidate.generation,
            ):
                referenced += 1
                continue
            if catalog.delete_if_generation(
                candidate.object_key,
                candidate.generation,
            ):
                deleted += 1
    return SweepReport(
        scanned=scanned,
        deleted=deleted,
        referenced=referenced,
        recent=recent,
    )
