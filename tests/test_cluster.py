# @TASK PB-T4 - 주제 클러스터 배정
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_cluster.py
from __future__ import annotations

import json

from airvoice.cluster import (
    TopicClaim,
    assign_clusters,
    build_prompt,
)


def _tc(topic: str, claim: str = "") -> TopicClaim:
    return TopicClaim(topic=topic, claim=claim or f"{topic} 관련 문장")


def test_프롬프트에_주제와_claim이_들어간다() -> None:
    prompt = build_prompt([_tc("투자", "손절 매도로 평단을 낮춘다")], [])

    assert "투자" in prompt
    assert "손절 매도로 평단을 낮춘다" in prompt
    assert "JSON" in prompt


def test_기존_클러스터를_프롬프트에_전달한다() -> None:
    prompt = build_prompt([_tc("투자")], existing_clusters=["재테크", "육아"])

    assert "재테크" in prompt
    assert "육아" in prompt


def test_LLM_응답대로_클러스터를_배정한다() -> None:
    topics = [_tc("투자"), _tc("투자-전략"), _tc("민준이-태권도")]

    def fake_llm(_prompt: str) -> str:
        return json.dumps(
            {"투자": "재테크", "투자-전략": "재테크", "민준이-태권도": "육아"},
            ensure_ascii=False,
        )

    mapping = assign_clusters(topics, fake_llm)

    assert mapping["투자"] == "재테크"
    assert mapping["투자-전략"] == "재테크"
    assert mapping["민준이-태권도"] == "육아"


def test_배치를_나눠_호출하고_이전_클러스터를_넘긴다() -> None:
    topics = [_tc(f"t{i}") for i in range(5)]
    seen_prompts: list[str] = []

    def fake_llm(prompt: str) -> str:
        seen_prompts.append(prompt)
        # 첫 배치는 '재테크', 두 번째 배치도 같은 클러스터로 모여야 한다.
        return json.dumps({f"t{i}": "재테크" for i in range(5)}, ensure_ascii=False)

    assign_clusters(topics, fake_llm, batch_size=2)

    assert len(seen_prompts) == 3, "5개 / 배치2 = 3배치"
    # 두 번째 배치부터는 앞서 만든 '재테크' 클러스터가 프롬프트에 실려야 한다.
    assert "재테크" in seen_prompts[1]


def test_응답에서_빠진_주제는_미분류() -> None:
    def fake_llm(_prompt: str) -> str:
        return json.dumps({"투자": "재테크"}, ensure_ascii=False)  # 태권도 누락

    mapping = assign_clusters([_tc("투자"), _tc("민준이-태권도")], fake_llm)

    assert mapping["투자"] == "재테크"
    assert mapping["민준이-태권도"] == "미분류"


def test_망가진_JSON_응답은_배치_전체를_미분류로(caplog) -> None:  # noqa: ANN001
    def fake_llm(_prompt: str) -> str:
        return "죄송합니다 JSON 을 못 만들겠습니다"

    mapping = assign_clusters([_tc("투자"), _tc("육아")], fake_llm)

    assert mapping["투자"] == "미분류"
    assert mapping["육아"] == "미분류"


def test_코드펜스로_감싼_응답도_파싱한다() -> None:
    def fake_llm(_prompt: str) -> str:
        return '```json\n{"투자": "재테크"}\n```'

    mapping = assign_clusters([_tc("투자")], fake_llm)

    assert mapping["투자"] == "재테크"


def test_설명이_섞인_응답에서도_JSON을_뽑는다() -> None:
    def fake_llm(_prompt: str) -> str:
        return '분류 결과입니다:\n{"투자": "재테크"}\n이상입니다.'

    mapping = assign_clusters([_tc("투자")], fake_llm)

    assert mapping["투자"] == "재테크"
