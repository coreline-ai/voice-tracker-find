# @TASK - 증분 정규화
# @SPEC docs/HANDOFF.md#4-M
# @TEST tests/test_vault_incremental.py
from __future__ import annotations

import json
from pathlib import Path

from thinktank.cluster import TopicClaim, assign_clusters, build_prompt
from thinktank.vault_apply import parse_index_clusters
from thinktank.vault_incremental import normalize_incremental
from thinktank.vault_index import (
    INDEX_SUBDIR,
    VAULT_INDEX_FILENAME,
    _write_lf,
    render_vault_index,
    scan_topic_notes,
)


def _make_vault(tmp_path: Path) -> Path:
    vault = tmp_path / "vault"
    (vault / "20-notes").mkdir(parents=True)
    (vault / INDEX_SUBDIR).mkdir(parents=True)
    return vault


def _write_topic(
    vault: Path, topic_id: str, claim: str, cluster: str | None = None
) -> None:
    fm = ["---", "type: topic", "date: 2026-07-20"]
    if cluster is not None:
        fm.append(f"cluster: {cluster}")
    fm.append("tags: [#t]")
    fm.append("---")
    body = f"# {topic_id}\n\n## 2026-07-20\n- {claim}\n"
    (vault / "20-notes" / f"{topic_id}.md").write_text(
        "\n".join(fm) + "\n" + body, encoding="utf-8"
    )


def _build_index(vault: Path, cluster_of: dict[str, str]) -> None:
    records = [r for r in scan_topic_notes(vault) if r.id in cluster_of]
    _write_lf(
        vault / INDEX_SUBDIR / VAULT_INDEX_FILENAME,
        render_vault_index(records, cluster_of, "2026-07-20 00:00"),
    )


def test_새_주제만_배정하고_기존_배정은_보존한다(tmp_path: Path) -> None:
    vault = _make_vault(tmp_path)
    _write_topic(vault, "aaa", "가 주제", cluster="가")
    _write_topic(vault, "bbb", "나 분할 주제", cluster="설비-HMI")  # 수동 분할 결과
    _build_index(vault, {"aaa": "가", "bbb": "설비-HMI"})
    _write_topic(vault, "ccc", "새 주제")  # 인덱스에 없음(새 노트)

    def fake_llm(_prompt: str) -> str:
        return json.dumps({"ccc": "가"})

    stats = normalize_incremental(vault, fake_llm, now="2026-07-21 00:00")

    assert stats["new"] == 1
    mapping = parse_index_clusters(
        (vault / INDEX_SUBDIR / VAULT_INDEX_FILENAME).read_text(encoding="utf-8")
    )
    assert mapping["ccc"] == "가"  # 새 주제 배정됨
    assert mapping["bbb"] == "설비-HMI"  # 수동 분할 그대로 보존
    assert mapping["aaa"] == "가"
    # 새 노트 frontmatter 에 cluster 주입됨
    assert "cluster: 가" in (vault / "20-notes" / "ccc.md").read_text(encoding="utf-8")


def test_새_주제가_없으면_LLM_을_부르지_않는다(tmp_path: Path) -> None:
    vault = _make_vault(tmp_path)
    _write_topic(vault, "aaa", "가 주제", cluster="가")
    _build_index(vault, {"aaa": "가"})

    calls: list[str] = []

    def fake_llm(prompt: str) -> str:
        calls.append(prompt)
        return "{}"

    stats = normalize_incremental(vault, fake_llm, now="2026-07-21 00:00")

    assert stats == {"new": 0, "hubs": 0}
    assert calls == []


def test_미분류_주제는_다시_시도한다(tmp_path: Path) -> None:
    vault = _make_vault(tmp_path)
    _write_topic(vault, "aaa", "가 주제", cluster="가")
    _write_topic(vault, "ddd", "붕 뜬 주제", cluster="미분류")
    _build_index(vault, {"aaa": "가", "ddd": "미분류"})

    def fake_llm(_prompt: str) -> str:
        return json.dumps({"ddd": "가"})  # 이번엔 배정 성공

    stats = normalize_incremental(vault, fake_llm, now="2026-07-21 00:00")

    assert stats["new"] == 1
    mapping = parse_index_clusters(
        (vault / INDEX_SUBDIR / VAULT_INDEX_FILENAME).read_text(encoding="utf-8")
    )
    assert mapping["ddd"] == "가"  # 미분류에서 벗어남


def test_seed_클러스터가_프롬프트에_재사용_후보로_들어간다() -> None:
    # 증분의 핵심: 기존 클러스터명을 첫 배치부터 재사용 후보로 준다.
    prompt = build_prompt([TopicClaim("x", "어떤 주제")], ["가", "설비-HMI"])
    assert "가, 설비-HMI" in prompt

    captured: list[str] = []

    def fake_llm(prompt: str) -> str:
        captured.append(prompt)
        return json.dumps({"x": "가"})

    assign_clusters(
        [TopicClaim("x", "어떤 주제")], fake_llm, seed_clusters=["가", "설비-HMI"]
    )
    assert "가, 설비-HMI" in captured[0]  # 첫 호출부터 기존 클러스터가 프롬프트에 있음
