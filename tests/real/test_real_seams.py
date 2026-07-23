# @HARNESS Level 2 실물 이음새 - pytest 진입점
"""`pytest -m real` 로 실물 어댑터 스모크를 돌린다.

scripts/smoke_real.py 의 단계 함수를 그대로 재사용한다(검증 로직 단일 출처).
전제(라이브러리/입력/API키)가 없으면 각 테스트가 자동 skip 하므로, 평소
`pytest` 실행에서는 조용히 건너뛴다. 실물 검증 시에만 명시적으로:

    THINKTANK_REAL_AUDIO=path/to/clip.m4a CLAUDE_API_KEY=... pytest -m real
"""

from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.real

# scripts/smoke_real.py 를 모듈로 로드 (검증 로직을 중복하지 않기 위해).
_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "smoke_real.py"
_spec = importlib.util.spec_from_file_location("smoke_real", _SCRIPT)
assert _spec is not None and _spec.loader is not None
smoke_real = importlib.util.module_from_spec(_spec)
# `from __future__ import annotations` + dataclass 는 타입 해석 시 sys.modules 에서
# 자기 모듈을 찾으므로, exec 전에 등록해야 한다 (미등록 시 AttributeError).
sys.modules[_spec.name] = smoke_real
_spec.loader.exec_module(smoke_real)


def _require_audio() -> Path:
    raw = os.environ.get("THINKTANK_REAL_AUDIO")
    if not raw:
        pytest.skip("THINKTANK_REAL_AUDIO 미설정 (실물 오디오 경로 필요)")
    path = Path(raw).expanduser()
    if not path.is_file():
        pytest.skip(f"오디오 파일 없음: {path}")
    return path


def _api_key() -> str | None:
    return os.environ.get("CLAUDE_API_KEY", "").strip() or None


def _assert(result) -> None:
    # 백엔드 없음(claude 로그인/키 둘 다 없음)이면 stage 가 SKIP 을 돌려준다.
    if result.status == "SKIP":
        pytest.skip(result.detail)
    assert result.status in {"PASS", "WARN"}, result.detail


def test_real_vad(tmp_path: Path) -> None:
    _assert(smoke_real.stage_vad(_require_audio(), tmp_path))


def test_real_transcribe(tmp_path: Path) -> None:
    _assert(smoke_real.stage_transcribe(_require_audio(), tmp_path))


def test_real_extract() -> None:
    # backend="auto": 키 있으면 API, 없으면 claude 로그인 사용 (둘 다 없으면 SKIP)
    _assert(smoke_real.stage_extract(smoke_real._SAMPLE_TRANSCRIPT, _api_key()))


def test_real_emerge(tmp_path: Path) -> None:
    _assert(smoke_real.stage_emerge(tmp_path, _api_key()))
