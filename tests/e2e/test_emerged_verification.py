# @TASK P4-S3-V - 창발 노트 연결점 검증 (통합 관점)
# @SPEC docs/planning/06-tasks.md#P4-S3-V
# @TEST tests/e2e/test_emerged_verification.py
"""emerge.run_emerge / notes.emerged.write_emerged_notes / topics.merge_topics 로
이어지는 창발 노트 연결점을 검증한다.

각 모듈의 단위 동작은 이미 tests/test_emerge.py, tests/test_emerged_note.py,
tests/test_topics.py 에서 검증됐다. 이 파일은 specs/screens/emerged-idea.yaml 의
data_requirements(emerged_ideas 8필드)와 tests 시나리오 4개, 근거 노트 [[링크]]가
topics.merge_topics 가 실제 생성한 20-notes/{slug}.md 파일과 정합하는지(공백/대문자
주제명으로 Phase 3 깨진 링크 결함의 창발 노트 버전을 재현), 파일명 시퀀스/멱등성,
min_topics 미달 시 조기 반환을 통합 관점에서 검증한다. LLM 호출 없이 fake
emerge_fn을 주입하고 tmp_path 볼트를 사용한다.
"""

from __future__ import annotations

import re
from dataclasses import fields as dataclass_fields
from pathlib import Path

from thinktank.emerge import EmergedIdea, Evidence, count_topic_items, run_emerge
from thinktank.extract import ExtractedItem
from thinktank.notes.emerged import write_emerged_notes
from thinktank.notes.renderer import normalize_filename
from thinktank.topics import merge_topics


def _item(text: str, topics: list[str], **overrides) -> ExtractedItem:
    defaults: dict = dict(
        category="idea",
        text=text,
        topics=topics,
        tags=[],
        event_at=None,
    )
    defaults.update(overrides)
    return ExtractedItem(**defaults)


def _raw_idea(**overrides) -> dict:
    defaults = dict(
        title="아이디어",
        insight="통찰",
        examples=["사례1"],
        next_steps=["액션1"],
        evidence=[{"topic": "design", "mention_count": 5}],
    )
    defaults.update(overrides)
    return defaults


def _seed_real_topics(
    tmp_path: Path, topic_names: list[str], date: str = "2025-01-15"
) -> None:
    """merge_topics 를 실제로 호출해 20-notes/{slug}.md 를 생성한다 (fake 아님)."""
    for name in topic_names:
        merge_topics([_item(f"{name} 관련 항목", [name])], tmp_path, date)


def _extract_wikilinks(content: str) -> list[str]:
    return re.findall(r"\[\[(.+?)\]\]", content)


def _title_from_topic_body(body: str) -> str:
    """scan_topic_notes 가 반환한 본문에서 표시용 제목(`# 제목`)을 추출한다."""
    match = re.search(r"^# (.+)$", body, re.MULTILINE)
    assert match is not None, "주제 노트 본문에 제목(H1)이 없습니다."
    return match.group(1)


# ---------------------------------------------------------------------------
# 검증 항목 1: Field Coverage - emerged_ideas.[title, date, sequence, tags,
# insight, examples, next_steps, evidence] 8개 필드가 모두 run_emerge 반환값과
# 렌더링된 노트에 반영되는가.
# ---------------------------------------------------------------------------


class TestFieldCoverage:
    def test_emerged_idea_dataclass_has_exactly_the_eight_spec_fields(self):
        field_names = {f.name for f in dataclass_fields(EmergedIdea)}
        assert field_names == {
            "title",
            "date",
            "sequence",
            "tags",
            "insight",
            "examples",
            "next_steps",
            "evidence",
        }

    def test_all_eight_fields_reflected_in_run_emerge_and_rendered_note(
        self, tmp_path: Path
    ):
        _seed_real_topics(tmp_path, ["design", "feature", "ai-features"])

        def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
            return [
                _raw_idea(
                    title="사용자 맞춤형 자동 UI",
                    insight="주제 조합에서 발견한 통찰",
                    examples=["사례 하나", "사례 둘"],
                    next_steps=["로깅 추가", "패턴 분류"],
                    evidence=[
                        {"topic": "design", "mention_count": 5},
                        {"topic": "feature", "mention_count": 3},
                    ],
                )
            ]

        [idea] = run_emerge(tmp_path, fake_emerge, date="2025-01-15")

        # run_emerge 반환값 (8필드)
        assert idea.title == "사용자 맞춤형 자동 UI"
        assert idea.date == "2025-01-15"
        assert idea.sequence == 1
        assert idea.tags == ["#design", "#feature"]
        assert idea.insight == "주제 조합에서 발견한 통찰"
        assert idea.examples == ["사례 하나", "사례 둘"]
        assert idea.next_steps == ["로깅 추가", "패턴 분류"]
        assert idea.evidence == [
            Evidence(topic="design", mention_count=5),
            Evidence(topic="feature", mention_count=3),
        ]

        [note_path] = write_emerged_notes([idea], tmp_path)
        content = note_path.read_text(encoding="utf-8")

        assert "# 사용자 맞춤형 자동 UI" in content  # title
        assert "date: 2025-01-15" in content  # date
        assert note_path.name == "2025-01-15_idea_1.md"  # sequence (파일명)
        assert "tags: [#design, #feature]" in content  # tags
        assert "주제 조합에서 발견한 통찰" in content  # insight
        assert "- 사례 하나" in content and "- 사례 둘" in content  # examples
        assert "1. 로깅 추가" in content and "2. 패턴 분류" in content  # next_steps
        assert "[[design]]: 5개 항목" in content  # evidence
        assert "[[feature]]: 3개 항목" in content  # evidence


# ---------------------------------------------------------------------------
# 검증 항목 2: specs/screens/emerged-idea.yaml tests 시나리오 4개.
# ---------------------------------------------------------------------------


class TestScreenSpecScenarios:
    def test_scenario_1_idea_file_created_with_sequence_in_filename(
        self, tmp_path: Path
    ):
        """시나리오: 아이디어 파일 정상 생성."""
        _seed_real_topics(tmp_path, ["design", "feature", "ai-features"])

        def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
            return [_raw_idea(title="첫번째"), _raw_idea(title="두번째")]

        ideas = run_emerge(tmp_path, fake_emerge, date="2025-01-15")
        paths = write_emerged_notes(ideas, tmp_path)

        assert paths == [
            tmp_path / "30-ideas" / "2025-01-15_idea_1.md",
            tmp_path / "30-ideas" / "2025-01-15_idea_2.md",
        ]
        assert all(p.exists() for p in paths)

    def test_scenario_2_title_and_insight_rendered_as_markdown(self, tmp_path: Path):
        """시나리오: 아이디어 제목 및 통찰 표시."""
        _seed_real_topics(tmp_path, ["design", "feature", "ai-features"])

        def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
            return [_raw_idea(title="새로운 아이디어", insight="핵심 통찰 문장")]

        [idea] = run_emerge(tmp_path, fake_emerge, date="2025-01-15")
        [path] = write_emerged_notes([idea], tmp_path)
        content = path.read_text(encoding="utf-8")

        assert "# 새로운 아이디어" in content
        insight_idx = content.index("## 통찰")
        examples_idx = content.index("## 구체적 사례")
        assert "핵심 통찰 문장" in content[insight_idx:examples_idx]

    def test_scenario_3_backlink_navigates_to_real_evidence_note(self, tmp_path: Path):
        """시나리오: 백링크를 통한 근거 노트 탐색.

        insight 섹션의 [[topic]] 이 실제로 존재하는 20-notes/{topic}.md 로
        이동 가능해야 한다 (merge_topics 가 실제로 생성한 파일).
        """
        _seed_real_topics(tmp_path, ["design", "feature", "ai-features"])

        def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
            return [
                _raw_idea(
                    evidence=[
                        {"topic": "design", "mention_count": 5},
                        {"topic": "feature", "mention_count": 2},
                    ]
                )
            ]

        [idea] = run_emerge(tmp_path, fake_emerge, date="2025-01-15")
        [path] = write_emerged_notes([idea], tmp_path)
        content = path.read_text(encoding="utf-8")

        insight_idx = content.index("## 통찰")
        examples_idx = content.index("## 구체적 사례")
        insight_block = content[insight_idx:examples_idx]

        for link in _extract_wikilinks(insight_block):
            assert (tmp_path / "20-notes" / f"{link}.md").exists(), (
                f"insight 섹션의 [[{link}]] 이 실제 주제 노트 파일과 연결되지 않습니다."
            )

    def test_scenario_4_evidence_section_shows_topics_and_mention_counts(
        self, tmp_path: Path
    ):
        """시나리오: 근거 노트 목록 및 언급 빈도 표시."""
        _seed_real_topics(tmp_path, ["design", "feature", "ai-features"])

        def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
            return [
                _raw_idea(
                    evidence=[
                        {"topic": "design", "mention_count": 15},
                        {"topic": "feature", "mention_count": 12},
                    ]
                )
            ]

        [idea] = run_emerge(tmp_path, fake_emerge, date="2025-01-15")
        [path] = write_emerged_notes([idea], tmp_path)
        content = path.read_text(encoding="utf-8")

        assert "## 근거 노트" in content
        assert "- [[design]]: 15개 항목" in content
        assert "- [[feature]]: 12개 항목" in content


# ---------------------------------------------------------------------------
# 검증 항목 3: 근거 노트 링크 정합 - run_emerge(fake emerge_fn) ->
# write_emerged_notes 산출 노트의 [[링크]]가 merge_topics 가 실제 생성한
# 20-notes/{slug}.md 파일과 일치하는가. Phase 3(test_topic_verification.py
# TestDailyNoteLinkConsistency)에서 잡은 깨진 링크 결함(daily.py가 topics 원본
# 문자열을 슬러그화 없이 그대로 위키링크에 씀)의 창발 노트 버전을 공백/대문자
# 주제명으로 재현해 검증한다. run_emerge는 evidence.topic을
# normalize_filename()으로 정규화하므로(emerge.py:268), LLM이 노트 본문에 쓰인
# 원래 표시 제목(공백/대문자 포함)을 그대로 반환하더라도 실제 파일 slug와
# 일치해야 한다.
# ---------------------------------------------------------------------------


class TestEvidenceLinkConsistencyWithTopicNotes:
    def test_evidence_wikilink_matches_real_topic_note_file_for_multiword_topics(
        self, tmp_path: Path
    ):
        """결함 재현 시도: 공백/대문자가 섞인 주제명 (예: "AI Features")."""
        topic_names = ["AI Features", "Design Pattern", "User Research"]
        _seed_real_topics(tmp_path, topic_names)

        # 실제 20-notes/*.md 파일명(슬러그)을 기준값으로 확보.
        expected_slugs = {normalize_filename(name) for name in topic_names}
        actual_note_stems = {p.stem for p in (tmp_path / "20-notes").glob("*.md")}
        assert actual_note_stems == expected_slugs  # 사전 조건 확인

        def fake_emerge_like_real_llm(
            prior_ideas: str, topics: dict[str, str]
        ) -> list[dict]:
            """실제 LLM처럼 노트 본문의 표시 제목(원본 대소문자/공백)을 evidence로
            반환한다 (scan_topic_notes 가 넘기는 dict 키는 슬러그이지만, 본문에는
            원래 표시 제목이 그대로 남아 있으므로 LLM이 이를 참조할 수 있다)."""
            return [
                _raw_idea(
                    evidence=[
                        {
                            "topic": _title_from_topic_body(body),
                            "mention_count": count_topic_items(body),
                        }
                        for body in topics.values()
                    ]
                )
            ]

        [idea] = run_emerge(tmp_path, fake_emerge_like_real_llm, date="2025-01-15")

        # run_emerge가 evidence.topic을 정규화했는지 확인 (원본 표시 제목과 다름).
        raw_display_names = {name for name in topic_names}
        normalized_evidence_topics = {ev.topic for ev in idea.evidence}
        assert normalized_evidence_topics == expected_slugs
        assert normalized_evidence_topics.isdisjoint(raw_display_names)

        [path] = write_emerged_notes([idea], tmp_path)
        content = path.read_text(encoding="utf-8")

        evidence_idx = content.index("## 근거 노트")
        evidence_block = content[evidence_idx:]
        evidence_links = _extract_wikilinks(evidence_block)

        assert set(evidence_links) == expected_slugs
        for link in evidence_links:
            note_path = tmp_path / "20-notes" / f"{link}.md"
            assert note_path.exists(), (
                f"결함: 창발 노트의 근거 링크 '[[{link}]]' 가 merge_topics 가 "
                f"생성한 실제 주제 노트 파일과 연결되지 않습니다 (Phase 3 "
                "daily.py 결함의 창발 노트 버전)."
            )

        # frontmatter sources 도 동일한 정규화된 슬러그로 반영되어야 한다.
        # (sources: [[[slug1]], [[slug2]]] 형식이라 바깥 리스트 대괄호와 위키링크
        # 대괄호가 인접해 일반 wikilink 정규식으로는 안전히 분리 추출할 수 없으므로
        # 슬러그별 포함 여부로 확인한다.)
        frontmatter_block = content.split("---", 2)[1]
        assert "sources:" in frontmatter_block
        for slug in expected_slugs:
            assert f"[[{slug}]]" in frontmatter_block


# ---------------------------------------------------------------------------
# 검증 항목 4: 파일명 시퀀스 - 같은 날 기존 idea 파일이 있으면 덮어쓰지 않고
# 다음 번호를 사용하며, 동일 내용 재실행은 멱등적으로 기존 파일을 재사용한다.
# ---------------------------------------------------------------------------


class TestFilenameSequencing:
    def test_existing_idea_file_for_date_not_overwritten_sequence_continues(
        self, tmp_path: Path
    ):
        ideas_dir = tmp_path / "30-ideas"
        ideas_dir.mkdir(parents=True)
        existing_path = ideas_dir / "2025-01-15_idea_1.md"
        existing_path.write_text("이전 실행 결과 (수동 작성)", encoding="utf-8")

        # 마지막 창발(2025-01-15) 이후 신규 항목이 있어야 증분 창발이 실행되므로
        # 주제는 그 다음날(01-16)로 시드한다. 새 아이디어는 date=2025-01-15 로
        # 부여해 같은 날 기존 파일(_idea_1) 다음 시퀀스(_idea_2)로 저장되는지 본다.
        _seed_real_topics(tmp_path, ["design", "feature", "ai-features"], "2025-01-16")

        def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
            return [_raw_idea(title="새 아이디어")]

        ideas = run_emerge(tmp_path, fake_emerge, date="2025-01-15")
        paths = write_emerged_notes(ideas, tmp_path)

        assert paths == [ideas_dir / "2025-01-15_idea_2.md"]
        assert existing_path.read_text(encoding="utf-8") == "이전 실행 결과 (수동 작성)"

    def test_rerun_with_identical_content_is_idempotent_end_to_end(
        self, tmp_path: Path
    ):
        _seed_real_topics(tmp_path, ["design", "feature", "ai-features"])

        def fake_emerge(prior_ideas: str, topics: dict[str, str]) -> list[dict]:
            return [_raw_idea(title="반복 아이디어")]

        ideas_dir = tmp_path / "30-ideas"

        ideas_first = run_emerge(tmp_path, fake_emerge, date="2025-01-15")
        first_paths = write_emerged_notes(ideas_first, tmp_path)
        assert first_paths == [ideas_dir / "2025-01-15_idea_1.md"]

        # 재실행 시 마지막 창발(2025-01-15) 이후 신규 항목이 없어 증분 창발이
        # 아무것도 만들지 않는다 → 중복 노트 없음(증분 멱등성).
        ideas_second = run_emerge(tmp_path, fake_emerge, date="2025-01-15")
        second_paths = write_emerged_notes(ideas_second, tmp_path)

        assert ideas_second == []
        assert second_paths == []
        assert list(ideas_dir.glob("*.md")) == [ideas_dir / "2025-01-15_idea_1.md"]


# ---------------------------------------------------------------------------
# 검증 항목 5: min_topics 미달 - 주제 노트가 min_topics 미만이면 빈 목록을
# 반환하고 emerge_fn을 호출하지 않으며, 30-ideas/ 폴더도 생성하지 않는다.
# ---------------------------------------------------------------------------


class TestMinTopicsThreshold:
    def test_below_min_topics_returns_empty_skips_api_and_creates_no_folder(
        self, tmp_path: Path
    ):
        _seed_real_topics(tmp_path, ["design", "feature"])  # 2개 < 기본 min 3

        def fake_emerge_should_not_be_called(
            prior_ideas: str, topics: dict[str, str]
        ) -> list[dict]:
            raise AssertionError("주제 수 부족 시 emerge_fn이 호출되면 안 됨")

        ideas = run_emerge(
            tmp_path, fake_emerge_should_not_be_called, date="2025-01-15"
        )
        assert ideas == []

        paths = write_emerged_notes(ideas, tmp_path)
        assert paths == []
        assert not (tmp_path / "30-ideas").exists()
