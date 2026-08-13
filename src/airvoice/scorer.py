# @TASK PB-T3 - 주제 정규화 하이브리드 스코어러
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_scorer.py
"""구조 × 의미 하이브리드 연결 후보 점수.

zettel-connect 의 핵심 설계를 따른다(MANUAL §2-3). 임베딩(의미)만 쓰면 표면적
유사성에 속는다 — 실측으로 '민준이-태권도'가 '투자'와 0.9 로 붙었다. 구조 축
(같은 클러스터/태그/이웃 여부)을 함께 보면, 태권도는 투자와 클러스터·태그·링크가
겹치지 않아 구조 점수 0 이 되고, 하이브리드 최종 점수가 눌린다.

    최종 = 구조 × α + 의미 × (1-α)     (α 기본 0.6, 구조 우선)

구조 점수 규칙(zettel-connect MANUAL §3-3):
    같은 클러스터        +0.4
    태그 교집합(비례)   +0.3 (전부 겹치면 최대)
    1-홉 이웃            +0.2
    2-홉 이웃            +0.1
    이미 연결됨          -0.5 (이미 이어진 건 다시 추천하지 않기 위한 강한 페널티)
"""

from __future__ import annotations

from dataclasses import dataclass, field

DEFAULT_ALPHA = 0.6  # 구조 가중치. MANUAL 권장 초기값(성숙하면 0.5로 낮춤).

_W_CLUSTER = 0.4
_W_TAGS = 0.3
_W_HOP1 = 0.2
_W_HOP2 = 0.1
_PENALTY_LINKED = -0.5


@dataclass(frozen=True)
class Node:
    """점수 계산 대상 노트(주제)의 구조 정보.

    본문은 필요 없다 — 인덱스(claim+메타)만으로 점수를 낸다(토큰 절약).
    """

    key: str
    cluster: str = ""
    tags: frozenset[str] = field(default_factory=frozenset)
    links: frozenset[str] = field(default_factory=frozenset)


def structural_score(seed: Node, candidate: Node, *, two_hop: frozenset[str]) -> float:
    """seed 기준 candidate 의 구조 점수.

    Args:
        seed: 기준 노트.
        candidate: 후보 노트.
        two_hop: seed 의 2-홉 이웃 key 집합(1-홉 이웃들의 이웃). 호출자가 그래프
            에서 미리 계산해 넘긴다.

    Returns:
        구조 점수(음수 가능).
    """
    score = 0.0
    if seed.cluster and candidate.cluster and seed.cluster == candidate.cluster:
        score += _W_CLUSTER

    if seed.tags and candidate.tags:
        overlap = len(seed.tags & candidate.tags) / len(seed.tags | candidate.tags)
        score += _W_TAGS * overlap

    if candidate.key in seed.links:
        score += _W_HOP1
        # 이미 연결된 후보에는 페널티 — "아직 안 된 연결"을 찾기 위함.
        score += _PENALTY_LINKED
    elif candidate.key in two_hop:
        score += _W_HOP2

    return score


def hybrid_score(
    structural: float, semantic: float, *, alpha: float = DEFAULT_ALPHA
) -> float:
    """구조·의미 점수를 하이브리드로 합친다.

    Args:
        structural: 구조 점수(structural_score 결과).
        semantic: 의미 점수(임베딩 코사인, 0~1로 본다).
        alpha: 구조 가중치(0~1). 클수록 구조 우선.

    Returns:
        최종 점수.
    """
    return structural * alpha + semantic * (1 - alpha)


@dataclass(frozen=True)
class Candidate:
    """점수가 매겨진 후보."""

    key: str
    score: float
    structural: float
    semantic: float


def rank_candidates(
    seed: Node,
    candidates: list[Node],
    semantic_scores: dict[str, float],
    *,
    alpha: float = DEFAULT_ALPHA,
    top_k: int = 7,
) -> list[Candidate]:
    """후보들을 하이브리드 점수로 정렬해 상위 top_k 를 돌려준다.

    Args:
        seed: 기준 노트.
        candidates: seed 를 제외한 후보 노트 목록.
        semantic_scores: candidate.key -> 의미 점수(seed 와의 임베딩 코사인).
        alpha: 구조 가중치.
        top_k: 반환 개수(zettel-connect 기본 7).

    Returns:
        점수 내림차순 상위 top_k Candidate.
    """
    # 2-홉 이웃: seed 의 1-홉 이웃들이 가리키는 key (seed 자신·1-홉 제외).
    by_key = {node.key: node for node in candidates}
    two_hop: set[str] = set()
    for hop1_key in seed.links:
        neighbor = by_key.get(hop1_key)
        if neighbor:
            two_hop |= neighbor.links
    two_hop -= seed.links
    two_hop.discard(seed.key)
    two_hop_frozen = frozenset(two_hop)

    ranked = [
        Candidate(
            key=node.key,
            structural=(s := structural_score(seed, node, two_hop=two_hop_frozen)),
            semantic=(sem := semantic_scores.get(node.key, 0.0)),
            score=hybrid_score(s, sem, alpha=alpha),
        )
        for node in candidates
        if node.key != seed.key
    ]
    ranked.sort(key=lambda c: c.score, reverse=True)
    return ranked[:top_k]
