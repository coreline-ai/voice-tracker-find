# @TASK PB-T3 - 주제 정규화 하이브리드 스코어러
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_scorer.py
from __future__ import annotations

from airvoice.scorer import (
    Node,
    hybrid_score,
    rank_candidates,
    structural_score,
)


def _node(key, cluster="", tags=(), links=()):  # noqa: ANN001, ANN202
    return Node(key=key, cluster=cluster, tags=frozenset(tags), links=frozenset(links))


# --- 구조 점수 ---------------------------------------------------------


def test_같은_클러스터면_점수가_오른다() -> None:
    seed = _node("투자", cluster="재테크")
    same = _node("투자-전략", cluster="재테크")
    diff = _node("육아", cluster="가정")

    assert structural_score(seed, same, two_hop=frozenset()) > structural_score(
        seed, diff, two_hop=frozenset()
    )


def test_태그가_겹칠수록_점수가_오른다() -> None:
    seed = _node("투자", tags=["주식", "리스크"])
    much = _node("투자-전략", tags=["주식", "리스크"])
    little = _node("투자-잡담", tags=["주식", "날씨"])

    assert structural_score(seed, much, two_hop=frozenset()) > structural_score(
        seed, little, two_hop=frozenset()
    )


def test_이미_연결된_후보는_페널티를_받는다() -> None:
    seed = _node("투자", links=["투자-전략"])
    linked = _node("투자-전략")
    unlinked = _node("투자-심리")

    # 이미 연결된 것은 다시 추천하지 않도록 오히려 낮아야 한다.
    assert structural_score(seed, linked, two_hop=frozenset()) < structural_score(
        seed, unlinked, two_hop=frozenset()
    )


# --- 하이브리드 (핵심: 태권도 오탐 차단) --------------------------------


def test_하이브리드가_의미_오탐을_구조로_누른다() -> None:
    """실측 재현: 임베딩상 '태권도'가 '투자'와 0.9(오탐)여도, 구조가 안 겹치면
    최종 점수가 진짜 관련 주제보다 낮아야 한다."""
    seed = _node("투자", cluster="재테크", tags=["주식"])
    real = _node("투자-전략", cluster="재테크", tags=["주식"])  # 구조 겹침
    false = _node("민준이-태권도", cluster="가정", tags=["육아"])  # 구조 안 겹침

    # 임베딩만 보면 태권도가 더 높다(오탐 상황).
    semantic = {"투자-전략": 0.78, "민준이-태권도": 0.90}

    ranked = rank_candidates(seed, [real, false], semantic, alpha=0.6)

    assert ranked[0].key == "투자-전략", "구조 축이 의미 오탐을 못 눌렀다"
    top = {c.key: c.score for c in ranked}
    assert top["투자-전략"] > top["민준이-태권도"]


def test_알파가_1이면_구조만_본다() -> None:
    assert hybrid_score(structural=0.4, semantic=0.9, alpha=1.0) == 0.4


def test_알파가_0이면_의미만_본다() -> None:
    assert hybrid_score(structural=0.4, semantic=0.9, alpha=0.0) == 0.9


# --- 순위/top_k --------------------------------------------------------


def test_상위_k개만_점수순으로_돌려준다() -> None:
    seed = _node("s", cluster="c")
    nodes = [_node(f"n{i}", cluster="c") for i in range(10)]
    semantic = {f"n{i}": i / 10 for i in range(10)}

    ranked = rank_candidates(seed, nodes, semantic, top_k=3)

    assert len(ranked) == 3
    assert [c.key for c in ranked] == ["n9", "n8", "n7"]


def test_자기_자신은_후보에서_빠진다() -> None:
    seed = _node("s", cluster="c")
    nodes = [_node("s", cluster="c"), _node("other", cluster="c")]

    ranked = rank_candidates(seed, nodes, {"s": 1.0, "other": 0.5})

    assert all(c.key != "s" for c in ranked)


def test_2홉_이웃은_1홉보다_약하게_가산된다() -> None:
    # seed -> a -> b : b는 2홉. c는 무관.
    seed = _node("seed", links=["a"])
    a = _node("a", links=["b"])
    b = _node("b")
    c = _node("c")

    ranked = rank_candidates(seed, [a, b, c], {}, alpha=1.0)
    scores = {cand.key: cand.score for cand in ranked}

    assert scores["b"] > scores["c"], "2홉 이웃이 무관 노트보다 높아야 한다"
