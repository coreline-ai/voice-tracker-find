# @TASK PB-T3 - 주제 정규화 임베딩 층
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_embed.py
from __future__ import annotations

import pytest

from thinktank.embed import EmbedError, cosine, load_embedder


def test_같은_벡터의_코사인은_1() -> None:
    assert cosine([1.0, 2.0, 3.0], [1.0, 2.0, 3.0]) == pytest.approx(1.0)


def test_직교_벡터의_코사인은_0() -> None:
    assert cosine([1.0, 0.0], [0.0, 1.0]) == pytest.approx(0.0)


def test_0벡터는_0을_돌려준다() -> None:
    # 나눗셈에서 터지지 않아야 한다.
    assert cosine([0.0, 0.0], [1.0, 2.0]) == 0.0


def test_서버가_없으면_명확히_실패한다() -> None:
    # 닫힌 포트로 보내 즉시 실패시킨다.
    embed = load_embedder(host="http://127.0.0.1:1", timeout=1.0)

    with pytest.raises(EmbedError, match="임베딩 서버"):
        embed("아무 텍스트")
