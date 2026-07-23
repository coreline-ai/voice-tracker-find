# @TASK P3-S1-T1 - 데일리 노트 생성 테스트 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P3-S1-T1
# @TEST tests/test_daily_note.py
from pathlib import Path

from thinktank.extract import ExtractedItem
from thinktank.notes.daily import (
    render_daily_note,
    render_daily_section,
    section_items,
    write_daily_note,
)


def _item(
    category: str,
    text: str,
    topics: list[str] | None = None,
    tags: list[str] | None = None,
    event_at: str | None = None,
) -> ExtractedItem:
    return ExtractedItem(
        category=category,
        text=text,
        topics=topics or [],
        tags=tags or [],
        event_at=event_at,
    )


def _sample_items() -> list[ExtractedItem]:
    return [
        _item(
            "idea",
            "사용자 입력 없이 모든 게 자동...",
            topics=["AI 기반 자동 정리"],
            tags=["#design", "#feature"],
        ),
        _item(
            "idea",
            "내 생각의 패턴을 분석하면...",
            topics=["메타 분석"],
            tags=["#research", "#meta"],
        ),
        _item(
            "schedule",
            "상담",
            topics=[],
            event_at="2025-01-16T14:30:00",
        ),
        _item(
            "schedule",
            "회의",
            topics=[],
            event_at="2025-01-16T09:00:00",
        ),
        _item(
            "important",
            "성능 개선이 최우선",
            topics=["고객 피드백"],
        ),
        _item("etc", "잡담 메모"),
    ]


DATE_STR = "2025-01-15"
STATS_SUCCESS = {"success_count": 12, "fail_count": 0}
SOURCES = ["file_1.m4a", "file_2.m4a"]


class TestRenderDailyNote:
    def test_returns_none_for_empty_items(self):
        assert render_daily_note(DATE_STR, [], STATS_SUCCESS, SOURCES) is None

    def test_includes_frontmatter_required_fields(self):
        result = render_daily_note(DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES)
        assert result is not None
        assert "type: daily" in result
        assert "date: 2025-01-15" in result
        assert "sources: [file_1.m4a, file_2.m4a]" in result

    def test_frontmatter_wrapped_in_dashes(self):
        result = render_daily_note(DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES)
        assert result.count("---") == 2

    def test_processing_status_success(self):
        result = render_daily_note(DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES)
        assert "## 처리 결과" in result
        assert "✅ 12건 처리 / 0건 실패" in result

    def test_processing_status_failure(self):
        stats = {"success_count": 8, "fail_count": 2}
        result = render_daily_note(DATE_STR, _sample_items(), stats, SOURCES)
        assert "⚠️ 8건 처리 / 2건 실패 (재시도 대기)" in result

    def test_본체는_구분별_노트로_링크한다(self):
        """본체는 색인이다 — 항목 본문은 구분별 노트에 있다."""
        result = render_daily_note(DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES)
        assert "## 구분" in result
        assert "- [[2025-01-15_아이디어]] — 2건" in result
        assert "- [[2025-01-15_일정]] — 2건" in result
        assert "- [[2025-01-15_중요]] — 1건" in result
        assert "- [[2025-01-15_기타]] — 1건" in result

    def test_본체에_항목_본문이_중복되지_않는다(self):
        result = render_daily_note(DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES)
        assert "잡담 메모" not in result
        assert "성능 개선이 최우선" not in result

    def test_항목이_없는_구분은_링크하지_않는다(self):
        items = [_item("idea", "아이디어만 있음", topics=["design"])]
        result = render_daily_note(DATE_STR, items, STATS_SUCCESS, SOURCES)
        assert "_아이디어]]" in result
        assert "_일정]]" not in result
        assert "_중요]]" not in result
        assert "_기타]]" not in result

    def test_reference_section_lists_unique_topics_in_order(self):
        result = render_daily_note(DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES)
        assert "## 참고" in result
        ref_line = result.splitlines()[-1]
        assert ref_line == "[[ai-기반-자동-정리]] [[메타-분석]] [[고객-피드백]]"

    def test_reference_section_omitted_when_no_topics(self):
        items = [_item("etc", "주제 없는 메모")]
        result = render_daily_note(DATE_STR, items, STATS_SUCCESS, SOURCES)
        assert "## 참고" not in result

    def test_duplicate_topics_deduplicated_in_reference(self):
        items = [
            _item("idea", "첫번째", topics=["design"]),
            _item("important", "두번째", topics=["design"]),
        ]
        result = render_daily_note(DATE_STR, items, STATS_SUCCESS, SOURCES)
        ref_line = result.splitlines()[-1]
        assert ref_line == "[[design]]"


class TestWriteDailyNote:
    def test_creates_file_in_daily_folder(self, tmp_path: Path):
        path = write_daily_note(
            DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES, tmp_path
        )
        assert path == tmp_path / "10-daily" / "2025-01-15.md"
        assert path.exists()

    def test_creates_daily_folder_if_missing(self, tmp_path: Path):
        vault = tmp_path / "vault"
        write_daily_note(DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES, vault)
        assert (vault / "10-daily").is_dir()

    def test_written_content_matches_render(self, tmp_path: Path):
        items = _sample_items()
        path = write_daily_note(DATE_STR, items, STATS_SUCCESS, SOURCES, tmp_path)
        assert path.read_text(encoding="utf-8") == render_daily_note(
            DATE_STR, items, STATS_SUCCESS, SOURCES
        )

    def test_returns_none_and_creates_no_file_when_items_empty(self, tmp_path: Path):
        path = write_daily_note(DATE_STR, [], STATS_SUCCESS, SOURCES, tmp_path)
        assert path is None
        assert not (tmp_path / "10-daily").exists()

    def test_does_not_touch_other_vault_folders(self, tmp_path: Path):
        write_daily_note(DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES, tmp_path)
        assert list(tmp_path.iterdir()) == [tmp_path / "10-daily"]


class TestRenderDailySection:
    """구분별 노트 (10-daily/YYYY-MM-DD_아이디어.md 등)."""

    def test_h1_제목이_있다(self):
        """앱 목록에서 파일명 아래에 보여줄 제목이 필요하다.
        일일 노트 본체에는 H1 이 없어 여기서 생긴다."""
        items = section_items(_sample_items(), "idea")
        result = render_daily_section(DATE_STR, "idea", items)
        assert result.splitlines()[result.splitlines().index("") + 1].startswith("# ")
        assert "# 2025-01-15 아이디어" in result

    def test_frontmatter_타입과_구분(self):
        items = section_items(_sample_items(), "idea")
        result = render_daily_section(DATE_STR, "idea", items)
        assert "type: daily_section" in result
        assert "category: 아이디어" in result

    def test_항목_본문이_들어간다(self):
        items = section_items(_sample_items(), "idea")
        result = render_daily_section(DATE_STR, "idea", items)
        assert (
            "- [[ai-기반-자동-정리]]: 사용자 입력 없이 모든 게 자동... #design #feature"
            in result
        )

    def test_일정은_시각순으로_정렬된다(self):
        items = section_items(_sample_items(), "schedule")
        result = render_daily_section(DATE_STR, "schedule", items)
        assert result.index("- 2025-01-16 09:00 회의") < result.index(
            "- 2025-01-16 14:30 상담"
        )


class TestWriteDailySections:
    """write_daily_note 가 구분별 파일까지 만든다."""

    def test_구분별_파일이_생성된다(self, tmp_path: Path):
        write_daily_note(DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES, tmp_path)

        daily = tmp_path / "10-daily"
        assert (daily / "2025-01-15_아이디어.md").is_file()
        assert (daily / "2025-01-15_일정.md").is_file()
        assert (daily / "2025-01-15_중요.md").is_file()
        assert (daily / "2025-01-15_기타.md").is_file()

    def test_항목이_없는_구분은_파일을_안_만든다(self, tmp_path: Path):
        items = [_item("idea", "아이디어만 있음", topics=["design"])]

        write_daily_note(DATE_STR, items, STATS_SUCCESS, SOURCES, tmp_path)

        daily = tmp_path / "10-daily"
        assert (daily / "2025-01-15_아이디어.md").is_file()
        assert not (daily / "2025-01-15_일정.md").exists()

    def test_비게_된_구분의_이전_파일은_지운다(self, tmp_path: Path):
        # 재실행에서 항목이 사라지면 내용 없는 노트가 목록에 남으면 안 된다.
        write_daily_note(DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES, tmp_path)
        stale = tmp_path / "10-daily" / "2025-01-15_기타.md"
        assert stale.is_file()

        write_daily_note(
            DATE_STR,
            [_item("idea", "아이디어만 남음", topics=["design"])],
            STATS_SUCCESS,
            SOURCES,
            tmp_path,
        )

        assert not stale.exists()

    def test_색인의_링크가_실제_파일과_일치한다(self, tmp_path: Path):
        index = write_daily_note(
            DATE_STR, _sample_items(), STATS_SUCCESS, SOURCES, tmp_path
        )

        content = index.read_text(encoding="utf-8")
        for line in content.splitlines():
            if line.startswith("- [[") and "] —" not in line:
                stem = line[len("- [[") : line.index("]]")]
                assert (tmp_path / "10-daily" / f"{stem}.md").is_file(), (
                    f"색인이 없는 파일을 가리킨다: {stem}"
                )
