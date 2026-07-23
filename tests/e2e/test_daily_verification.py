# @TASK P3-S1-V - 데일리 노트 연결점 검증 (통합 관점)
# @SPEC docs/planning/06-tasks.md#P3-S1-V
# @TEST tests/e2e/test_daily_verification.py
"""추출(extract) -> DB(extracted_json) -> 데일리 노트 -> 주제 노트로 이어지는
연결점을 검증한다.

이미 구현된 각 모듈(extract.py/db.py/notes/daily.py/topics.py)의 단위 동작은
각자의 단위 테스트(tests/test_extract.py, tests/test_db.py, tests/test_daily_note.py,
tests/test_topics.py)에서 검증됐다. 이 파일은 모듈 간 계약이 어댑터 없이 그대로
맞물리는지(통합 관점)를 검증한다. Claude API는 호출하지 않고 fake extract_fn과
tmp_path(볼트) + tmp DB로 대체한다. 근거: specs/screens/daily-note.yaml.
"""

from __future__ import annotations

import re
from datetime import UTC, datetime
from pathlib import Path

import pytest

from tests.vault_text import daily_all_text
from thinktank.db import (
    Status,
    get_daily_stats,
    get_recordings,
    init_db,
    insert_recording,
    update_recording_status,
)
from thinktank.extract import ExtractedItem, extract_items, items_from_json
from thinktank.notes.daily import render_daily_note, write_daily_note
from thinktank.notes.renderer import normalize_filename, render_wikilink
from thinktank.topics import TOPICS_SUBDIR, merge_topics


@pytest.fixture
def db_path(tmp_path: Path) -> Path:
    """초기화된 임시 DB 파일 경로."""
    path = tmp_path / "pipeline.db"
    init_db(path)
    return path


@pytest.fixture
def vault_path(tmp_path: Path) -> Path:
    """Obsidian 볼트 루트로 사용할 임시 폴더."""
    path = tmp_path / "vault"
    path.mkdir()
    return path


def _sample_items() -> list[ExtractedItem]:
    """4개 카테고리 + topics/tags/event_at을 모두 포함하는 추출 항목 샘플."""
    return [
        ExtractedItem(
            category="idea",
            text="사용자 입력 없이 자동으로 정리되면 좋겠다",
            topics=["AI 기반 자동 정리"],
            tags=["#design", "#feature"],
            event_at=None,
        ),
        ExtractedItem(
            category="schedule",
            text="상담",
            topics=[],
            tags=[],
            event_at="2025-01-16T14:30:00",
        ),
        ExtractedItem(
            category="schedule",
            text="회의",
            topics=[],
            tags=[],
            event_at="2025-01-16T09:00:00",
        ),
        ExtractedItem(
            category="important",
            text="성능 개선이 최우선",
            topics=["고객 피드백"],
            tags=[],
            event_at=None,
        ),
        ExtractedItem(
            category="etc",
            text="잡담 메모",
            topics=[],
            tags=[],
            event_at=None,
        ),
    ]


def _fake_extract_fn(_chunk: str) -> list[ExtractedItem]:
    """extract_fn 대체용 fake (Claude API 미호출)."""
    return _sample_items()


def _failing_extract_fn(_chunk: str) -> list[ExtractedItem]:
    raise RuntimeError("추출 실패 (fake)")


def _transition_to_transcribed(db_path: Path, recording_id: int) -> None:
    """pending 레코딩을 transcribed 상태까지 상태 전이도를 따라 이동시킨다."""
    update_recording_status(db_path, recording_id, Status.SYNCED, "2025-01-15 08:00:00")
    update_recording_status(
        db_path, recording_id, Status.VAD_DONE, "2025-01-15 08:05:00"
    )
    update_recording_status(
        db_path, recording_id, Status.TRANSCRIBED, "2025-01-15 08:10:00"
    )


@pytest.fixture
def organized_note(db_path: Path, vault_path: Path) -> dict:
    """실제 파이프라인 흐름(extract_items -> organized 전이 -> get_daily_stats ->
    items_from_json -> write_daily_note)으로 데일리 노트를 생성해 반환한다.

    각 단계는 어댑터/변환 코드 없이 실제 함수를 순서대로 호출한다.
    """
    recording = insert_recording(
        db_path, source_path="/audio/file_1.m4a", filename="file_1.m4a"
    )
    _transition_to_transcribed(db_path, recording.id)

    extract_items(recording.id, "전사문 텍스트", db_path, _fake_extract_fn)
    update_recording_status(
        db_path, recording.id, Status.ORGANIZED, "2025-01-15 08:20:00"
    )

    date_str = "2025-01-15"
    sources = ["file_1.m4a"]
    stats = get_daily_stats(db_path, date=date_str)
    [row] = get_recordings(db_path, Status.ORGANIZED)
    items = items_from_json(row.extracted_json)

    note_path = write_daily_note(date_str, items, stats, sources, vault_path)
    assert note_path is not None
    return {
        "vault_path": vault_path,
        "date_str": date_str,
        "sources": sources,
        "note_path": note_path,
        "content": note_path.read_text(encoding="utf-8"),
        # 항목 본문은 구분별 노트에 있으므로, 내용 검증은 이쪽을 본다.
        "all_text": daily_all_text(vault_path, date_str),
        "items": items,
        "stats": stats,
    }


# ---------------------------------------------------------------------------
# 검증 항목 1: Field Coverage - extracted_items 5필드가 노트에 반영되고,
# recordings 통계(get_daily_stats)가 처리 결과 헤더에 반영되는가.
# ---------------------------------------------------------------------------


class TestFieldCoverage:
    def test_all_five_extracted_item_fields_reflected_in_note(self, organized_note):
        content = organized_note["all_text"]

        # category -> 구분별 노트 (색인은 링크, 본문은 각 노트의 H1)
        assert "# 2025-01-15 아이디어" in content
        assert "# 2025-01-15 일정" in content
        assert "# 2025-01-15 중요" in content
        assert "# 2025-01-15 기타" in content
        # text
        assert "사용자 입력 없이 자동으로 정리되면 좋겠다" in content
        assert "상담" in content
        assert "성능 개선이 최우선" in content
        assert "잡담 메모" in content
        # topics -> 위키링크 (daily.py는 normalize_filename() 슬러그로 링크를
        # 생성해 topics.merge_topics()가 저장하는 20-notes/{slug}.md 파일명과
        # 일치시킨다 - Defect B 수정 반영)
        assert render_wikilink(normalize_filename("AI 기반 자동 정리")) in content
        assert render_wikilink(normalize_filename("고객 피드백")) in content
        # tags
        assert "#design" in content
        assert "#feature" in content
        # event_at -> 시간순 prefix
        assert "2025-01-16 09:00 회의" in content
        assert "2025-01-16 14:30 상담" in content

    def test_success_stats_from_get_daily_stats_reflected_in_header(
        self, organized_note
    ):
        assert organized_note["stats"] == {"success_count": 1, "fail_count": 0}
        assert "✅ 1건 처리 / 0건 실패" in organized_note["content"]

    def test_failure_stats_from_get_daily_stats_not_reflected(self, db_path: Path):
        """결함(Defect A): db.update_recording_status 는 EXTRACTED_FAILED 전이 시
        extracted_at 컬럼만 갱신하고 organized_at 은 그대로 NULL로 남긴다
        (db.py _STATUS_TIMESTAMP_COLUMN 참조). 그런데 get_daily_stats()의
        fail_count 집계 조건은 `status = extracted_failed AND
        DATE(organized_at) = date` 이므로, 실제 파이프라인에서 발생한 추출
        실패는 organized_at 이 NULL이라 이 조건을 절대 만족하지 못해 fail_count
        가 항상 0으로 집계된다."""
        recording = insert_recording(
            db_path, source_path="/audio/fail.m4a", filename="fail.m4a"
        )
        _transition_to_transcribed(db_path, recording.id)

        with pytest.raises(RuntimeError):
            extract_items(recording.id, "전사문 텍스트", db_path, _failing_extract_fn)

        today_str = datetime.now(UTC).strftime("%Y-%m-%d")
        stats = get_daily_stats(db_path, date=today_str)

        assert stats["fail_count"] == 1, (
            "결함(Defect A): get_daily_stats()가 실제 파이프라인에서 발생한 "
            f"EXTRACTED_FAILED 레코딩을 집계하지 못함 (반환값: {stats}). "
            "organized_at 이 아닌 extracted_at/status 기준으로 실패 건수를 "
            "집계하도록 수정이 필요하다."
        )


# ---------------------------------------------------------------------------
# 검증 항목 2: DB 통합 흐름 - extracted_json 저장 -> items_from_json 복원 ->
# write_daily_note 가 어댑터 없이 연결되는가.
# ---------------------------------------------------------------------------


class TestDbIntegrationFlow:
    def test_extracted_json_round_trip_feeds_write_daily_note_without_adapter(
        self, organized_note
    ):
        items = organized_note["items"]
        assert items, "DB에서 items_from_json으로 복원된 항목이 있어야 한다."
        assert all(isinstance(item, ExtractedItem) for item in items)

        # DB에서 복원한 items/stats를 변환 없이 그대로 render_daily_note에 전달해도
        # 동일한 결과가 나와야 한다 (어댑터 불필요).
        expected = render_daily_note(
            organized_note["date_str"],
            items,
            organized_note["stats"],
            organized_note["sources"],
        )
        assert organized_note["content"] == expected
        assert organized_note["note_path"].exists()


# ---------------------------------------------------------------------------
# 검증 항목 3: specs/screens/daily-note.yaml 의 tests 시나리오 5개 반영.
# ---------------------------------------------------------------------------


class TestDailyYamlScenarios:
    def test_scenario_auto_generation_with_one_or_more_processed(
        self, organized_note
    ):
        """시나리오 1: 당일 처리 건수 1건 이상 -> 파일 생성 + 헤더 + 카테고리 분류."""
        expected_path = organized_note["vault_path"] / "10-daily" / "2025-01-15.md"
        assert organized_note["note_path"] == expected_path
        assert expected_path.exists()

        assert "✅ 1건 처리 / 0건 실패" in organized_note["content"]
        content = organized_note["all_text"]
        assert "# 2025-01-15 아이디어" in content
        assert "# 2025-01-15 일정" in content

    def test_scenario_idea_section_item_format(self, organized_note):
        """시나리오 2: '[[주제]]: 텍스트 #태그' 형식 (주제는 slug로 렌더링됨,
        Defect B 수정 반영)."""
        content = organized_note["all_text"]
        assert "# 2025-01-15 아이디어" in content
        topic_link = render_wikilink(normalize_filename("AI 기반 자동 정리"))
        assert (
            f"- {topic_link}: 사용자 입력 없이 자동으로 정리되면 좋겠다 "
            "#design #feature" in content
        )

    def test_scenario_schedule_section_sorted_by_event_at(self, organized_note):
        """시나리오 3: event_at 기준 시간순 정렬."""
        content = organized_note["all_text"]
        first_idx = content.index("2025-01-16 09:00 회의")
        second_idx = content.index("2025-01-16 14:30 상담")
        assert first_idx < second_idx

    def test_scenario_backlink_target_matches_merge_topics_output(
        self, db_path: Path, vault_path: Path
    ):
        """시나리오 4: 백링크 클릭 -> 20-notes/{topic}.md 로 이동.

        결함(Defect B): topics.merge_topics() 는 normalize_filename() 슬러그로
        파일을 저장하지만(20-notes/{slug}.md), notes/daily.py 의
        render_wikilink() 는 topic 원본 문자열을 그대로 사용해 두 모듈이 만든
        링크 이름과 실제 파일명이 어긋난다."""
        items = [
            ExtractedItem(
                category="idea",
                text="자동 정리 아이디어",
                topics=["AI 기반 자동 정리"],
                tags=[],
                event_at=None,
            )
        ]
        merge_topics(items, vault_path, "2025-01-15")
        write_daily_note(
            "2025-01-15", items, {"success_count": 1, "fail_count": 0}, [], vault_path
        )

        daily_content = (vault_path / "10-daily" / "2025-01-15.md").read_text(
            encoding="utf-8"
        )
        # '## 구분'의 구분 노트 링크([[YYYY-MM-DD_아이디어]])는 주제가 아니라
        # 같은 폴더의 노트를 가리키므로 제외한다.
        links = [
            target
            for target in re.findall(r"\[\[(.+?)\]\]", daily_content)
            if not re.match(r"^\d{4}-\d{2}-\d{2}_", target)
        ]
        assert links, "데일리 노트에 주제 위키링크가 없습니다."

        for link_target in links:
            expected_note = vault_path / TOPICS_SUBDIR / f"{link_target}.md"
            assert expected_note.exists(), (
                f"결함(Defect B): 데일리 노트의 '[[{link_target}]]' 링크가 가리키는 "
                f"{expected_note} 파일이 존재하지 않음. merge_topics()는 "
                f"normalize_filename('{link_target}') = "
                f"'{normalize_filename(link_target)}' 슬러그로 파일을 저장하지만 "
                "daily.py의 위키링크는 원본 topic 문자열을 그대로 사용한다."
            )

    def test_scenario_failure_status_shows_warning_header(self, db_path: Path):
        """시나리오 5: fail_count > 0 -> '⚠️ N건 처리 / M건 실패 (재시도 대기)'.

        Defect A로 인해 get_daily_stats()가 실제 파이프라인에서는 fail_count를
        1 이상으로 반환하지 못하므로, 이 시나리오는 실제 데이터로는 트리거되지
        않는다."""
        recording = insert_recording(
            db_path, source_path="/audio/fail2.m4a", filename="fail2.m4a"
        )
        _transition_to_transcribed(db_path, recording.id)

        with pytest.raises(RuntimeError):
            extract_items(recording.id, "전사문 텍스트", db_path, _failing_extract_fn)

        today_str = datetime.now(UTC).strftime("%Y-%m-%d")
        stats = get_daily_stats(db_path, date=today_str)

        items = [
            ExtractedItem(
                category="etc", text="처리 실패 로그", topics=[], tags=[], event_at=None
            )
        ]
        content = render_daily_note(today_str, items, stats, [])

        assert "⚠️" in content, (
            f"결함(Defect A): get_daily_stats() 반환값 {stats}의 fail_count가 "
            "항상 0이 되어 '⚠️' 실패 헤더가 실제 파이프라인 데이터로는 렌더링되지 "
            "않는다."
        )


# ---------------------------------------------------------------------------
# 검증 항목 4: 생성 조건 - 처리 0건이면 데일리 노트 파일을 만들지 않는다.
# ---------------------------------------------------------------------------


class TestCreationCondition:
    def test_zero_items_creates_no_file(self, vault_path: Path):
        result = write_daily_note(
            "2025-01-15", [], {"success_count": 0, "fail_count": 0}, [], vault_path
        )
        assert result is None
        assert not (vault_path / "10-daily").exists()
