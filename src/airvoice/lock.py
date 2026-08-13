# @TASK PB-T2 - 파이프라인 동시 실행 방지
# @SPEC docs/HANDOFF.md#4-L
# @TEST tests/test_lock.py
"""사용자별 파이프라인 실행 잠금.

같은 사용자를 두 프로세스가 동시에 처리하면 상태 전이가 서로 어긋난다(실제로
야간 작업과 수동 실행이 겹쳐 ``'vad_done' -> VAD_DONE 전이가 없습니다`` 에러가
났다). 상태머신이 잘못된 전이를 거부해 데이터가 깨지진 않았지만, 처리가 중간에
꼬이고 로그가 오염된다.

작업 스케줄러의 ``MultipleInstances IgnoreNew`` 는 **작업끼리만** 막는다. 손으로
``python -m airvoice.main`` 을 돌리는 것은 못 막으므로 파이프라인 자체에 잠금이
필요하다.

PID 파일이 아니라 **OS 파일 잠금**을 쓴다. PID 방식은 프로세스가 죽으면 잠금이
남아서 "죽은 잠금"을 판별하는 코드가 또 필요하지만, OS 잠금은 프로세스가 어떻게
끝나든(크래시 포함) 커널이 풀어준다.

잠금 단위는 사용자별(DB 경로 기준)이다. 서로 다른 사용자는 DB 도 수집 폴더도
달라서 동시에 처리해도 안전하다.
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path
from types import TracebackType

logger = logging.getLogger(__name__)


class AlreadyRunning(RuntimeError):
    """같은 사용자의 파이프라인이 이미 돌고 있을 때."""


def _lock_file_for(db_path: str | Path) -> Path:
    """DB 옆에 잠금 파일을 둔다 (사용자마다 DB 가 다르므로 자동으로 사용자별)."""
    path = Path(db_path).expanduser()
    return path.with_name(path.name + ".lock")


class PipelineLock:
    """사용자 1명분의 파이프라인 실행 잠금 (컨텍스트 매니저).

    Raises:
        AlreadyRunning: 다른 프로세스가 이미 이 사용자를 처리 중일 때.
    """

    def __init__(self, db_path: str | Path) -> None:
        self.path = _lock_file_for(db_path)
        self._handle = None

    def __enter__(self) -> PipelineLock:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        # a+ 로 열어 파일이 없으면 만들고, 있으면 내용을 건드리지 않는다.
        handle = self.path.open("a+")
        try:
            _acquire(handle)
        except OSError as exc:
            handle.close()
            raise AlreadyRunning(
                f"이미 실행 중입니다 (잠금 파일: {self.path}). "
                "야간 작업이 도는 중이면 끝나기를 기다리세요."
            ) from exc
        self._handle = handle
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        if self._handle is None:
            return
        try:
            _release(self._handle)
        finally:
            self._handle.close()
            self._handle = None


if sys.platform == "win32":
    import msvcrt

    def _acquire(handle) -> None:  # noqa: ANN001
        handle.seek(0)
        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)

    def _release(handle) -> None:  # noqa: ANN001
        handle.seek(0)
        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)

else:  # pragma: no cover - 개발/운영 환경은 윈도우
    import fcntl

    def _acquire(handle) -> None:  # noqa: ANN001
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)

    def _release(handle) -> None:  # noqa: ANN001
        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
