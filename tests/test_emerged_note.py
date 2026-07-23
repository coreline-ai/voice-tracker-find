# @TASK P4-S3-T1 - 창발 노트 생성 테스트 (TDD RED)
# @SPEC docs/planning/06-tasks.md#P4-S3-T1
# @TEST tests/test_emerged_note.py
from pathlib import Path

from thinktank.emerge import EmergedIdea, Evidence
from thinktank.notes.emerged import render_emerged_note, write_emerged_notes


def _idea(**overrides) -> EmergedIdea:
    defaults: dict = dict(
        title="사용자 맞춤형 자동 UI 생성",
        date="2025-01-15",
        sequence=1,
        tags=["#design", "#feature"],
        insight="업무 패턴을 학습해 최적 UI를 자동 생성한다.",
        examples=["개발자는 코드 에디터 중심 UI", "마케터는 분석 대시보드 중심 UI"],
        next_steps=["사용자 행동 로깅 추가", "ML 모델로 패턴 분류"],
        evidence=[
            Evidence(topic="design", mention_count=15),
            Evidence(topic="feature", mention_count=12),
        ],
    )
    defaults.update(overrides)
    return EmergedIdea(**defaults)


class TestRenderEmergedNoteFrontmatter:
    def test_includes_type_and_date(self):
        result = render_emerged_note(_idea())
        assert "type: emerged_idea" in result
        assert "date: 2025-01-15" in result

    def test_frontmatter_wrapped_in_dashes(self):
        result = render_emerged_note(_idea())
        assert result.count("---") == 2

    def test_tags_rendered(self):
        result = render_emerged_note(_idea(tags=["#design", "#feature"]))
        assert "tags: [#design, #feature]" in result

    def test_tags_omitted_when_empty(self):
        result = render_emerged_note(_idea(tags=[]))
        assert "tags:" not in result

    def test_sources_rendered_as_evidence_wikilinks(self):
        result = render_emerged_note(
            _idea(
                evidence=[
                    Evidence(topic="design", mention_count=15),
                    Evidence(topic="feature", mention_count=12),
                ]
            )
        )
        assert "sources: [[[design]], [[feature]]]" in result

    def test_sources_omitted_when_no_evidence(self):
        result = render_emerged_note(_idea(evidence=[]))
        assert "sources:" not in result


class TestRenderEmergedNoteBody:
    def test_title_heading(self):
        result = render_emerged_note(_idea(title="새로운 아이디어"))
        assert "# 새로운 아이디어" in result

    def test_insight_section_contains_text(self):
        result = render_emerged_note(
            _idea(insight="업무 패턴을 학습해 최적 UI를 자동 생성한다.")
        )
        assert "## 통찰" in result
        assert "업무 패턴을 학습해 최적 UI를 자동 생성한다." in result

    def test_insight_section_contains_evidence_wikilinks(self):
        result = render_emerged_note(
            _idea(
                evidence=[
                    Evidence(topic="design", mention_count=15),
                    Evidence(topic="feature", mention_count=12),
                ]
            )
        )
        insight_idx = result.index("## 통찰")
        examples_idx = result.index("## 구체적 사례")
        insight_block = result[insight_idx:examples_idx]
        assert "[[design]]" in insight_block
        assert "[[feature]]" in insight_block

    def test_examples_section_as_bullets(self):
        result = render_emerged_note(_idea(examples=["사례 하나", "사례 둘"]))
        assert "## 구체적 사례" in result
        assert "- 사례 하나" in result
        assert "- 사례 둘" in result

    def test_next_steps_section_as_numbered_list(self):
        result = render_emerged_note(_idea(next_steps=["로깅 추가", "패턴 분류"]))
        assert "## 다음 스텝" in result
        assert "1. 로깅 추가" in result
        assert "2. 패턴 분류" in result

    def test_evidence_section_lists_topic_and_count(self):
        result = render_emerged_note(
            _idea(
                evidence=[
                    Evidence(topic="design", mention_count=15),
                    Evidence(topic="feature", mention_count=12),
                ]
            )
        )
        assert "## 근거 노트" in result
        assert "- [[design]]: 15개 항목" in result
        assert "- [[feature]]: 12개 항목" in result

    def test_section_order(self):
        result = render_emerged_note(_idea())
        assert (
            result.index("## 통찰")
            < result.index("## 구체적 사례")
            < result.index("## 다음 스텝")
            < result.index("## 근거 노트")
        )

    def test_empty_evidence_produces_no_evidence_lines(self):
        result = render_emerged_note(_idea(evidence=[]))
        assert "## 근거 노트" in result
        assert "개 항목" not in result


class TestWriteEmergedNotes:
    def test_empty_list_creates_no_folder_and_returns_empty(self, tmp_path: Path):
        paths = write_emerged_notes([], tmp_path)
        assert paths == []
        assert not (tmp_path / "30-ideas").exists()

    def test_creates_folder_if_missing(self, tmp_path: Path):
        vault = tmp_path / "vault"
        write_emerged_notes([_idea()], vault)
        assert (vault / "30-ideas").is_dir()

    def test_creates_file_with_expected_name(self, tmp_path: Path):
        paths = write_emerged_notes([_idea(sequence=1)], tmp_path)
        assert paths == [tmp_path / "30-ideas" / "2025-01-15_idea_1.md"]
        assert paths[0].exists()

    def test_written_content_matches_render(self, tmp_path: Path):
        idea = _idea(sequence=1)
        paths = write_emerged_notes([idea], tmp_path)
        assert paths[0].read_text(encoding="utf-8") == render_emerged_note(idea)

    def test_multiple_ideas_same_date_get_sequential_filenames(self, tmp_path: Path):
        ideas = [
            _idea(title="첫째", sequence=1),
            _idea(title="둘째", sequence=2),
        ]
        paths = write_emerged_notes(ideas, tmp_path)
        assert paths == [
            tmp_path / "30-ideas" / "2025-01-15_idea_1.md",
            tmp_path / "30-ideas" / "2025-01-15_idea_2.md",
        ]

    def test_does_not_touch_other_vault_folders(self, tmp_path: Path):
        write_emerged_notes([_idea()], tmp_path)
        assert list(tmp_path.iterdir()) == [tmp_path / "30-ideas"]

    def test_existing_idea_files_for_date_shift_new_sequence_forward(
        self, tmp_path: Path
    ):
        """같은 날짜에 이미 idea 파일이 있으면 sequence를 이어서 부여하고
        기존 파일을 덮어쓰지 않는다."""
        ideas_dir = tmp_path / "30-ideas"
        ideas_dir.mkdir(parents=True)
        existing_path = ideas_dir / "2025-01-15_idea_1.md"
        existing_path.write_text("이전 실행 결과", encoding="utf-8")

        new_idea = _idea(title="새 아이디어", sequence=1)
        paths = write_emerged_notes([new_idea], tmp_path)

        assert paths == [ideas_dir / "2025-01-15_idea_2.md"]
        assert existing_path.read_text(encoding="utf-8") == "이전 실행 결과"

    def test_rerun_with_identical_content_is_idempotent(self, tmp_path: Path):
        """동일 내용으로 재실행하면 새 파일을 만들지 않고 기존 파일을 재사용한다."""
        idea = _idea(sequence=1)
        first_paths = write_emerged_notes([idea], tmp_path)
        second_paths = write_emerged_notes([idea], tmp_path)

        assert first_paths == second_paths
        ideas_dir = tmp_path / "30-ideas"
        assert list(ideas_dir.glob("*.md")) == [ideas_dir / "2025-01-15_idea_1.md"]

    def test_different_dates_track_sequence_independently(self, tmp_path: Path):
        ideas = [
            _idea(date="2025-01-15", sequence=1),
            _idea(date="2025-01-16", sequence=1),
        ]
        paths = write_emerged_notes(ideas, tmp_path)
        assert paths == [
            tmp_path / "30-ideas" / "2025-01-15_idea_1.md",
            tmp_path / "30-ideas" / "2025-01-16_idea_1.md",
        ]
