# @TASK PA-T1 - LAN 수신기 (모드1: 같은 WiFi 에서 폰 -> PC 직접 전송)
# @SPEC docs/HANDOFF.md#3-개발-계획
# @TEST tests/test_receiver.py
"""안드로이드 앱이 녹음 파일을 직접 올리는 로컬 HTTP 수신기.

앱(`Sync.kt`)이 이미 기대하는 계약을 그대로 구현한다::

    PUT  /upload/{user}/{filename}   오디오 원본 바이트
    GET  /notes/{user}               -> [{"name": ..., "folder": ..., "content": ...}]
    PUT  /notes/{user}/{name}        편집한 마크다운 본문
    GET  /health                     연결 확인 (인증 불필요)

모든 요청은 ``Authorization: Bearer <RECEIVER_TOKEN>`` 이 필요하다(``/health``
제외). 같은 WiFi 라도 다른 기기가 붙을 수 있으므로 토큰이 비어 있으면 서버는
아예 뜨지 않는다.

수신기는 파이프라인을 조율하지 않는다. 받은 파일을 ``INGEST_DIR`` 에 놓기만
하면 기존 :func:`thinktank.main.run_pipeline` 이 상태머신 기반으로 알아서
집어간다. 다만 :func:`thinktank.ingest.scan_ingest_folder` 는 최종 파일명이
보이면 전송 완료로 간주하므로, 업로드 중에는 스캔 대상이 아닌 임시 이름
(``.thinktank.{name}.part``)으로 받고 완료 시점에 :func:`os.replace` 로
원자적 교체한다 (Syncthing 이 하던 것과 같은 보장).

여러 사람을 받을 수 있다. ``~/.thinktank/users.json`` 이 있으면 사용자마다
수집 폴더·볼트·DB 를 따로 쓰고, 없으면 기존 ``.env`` 단일 사용자로 동작한다.

**권한은 토큰으로 판단한다.** 경로의 ``{user}`` 는 클라이언트가 마음대로 보낼 수
있으므로 신뢰하지 않는다 — 다중 사용자 모드에서 토큰 주인과 다르면 거절한다.
"""

from __future__ import annotations

import argparse
import contextlib
import json
import logging
import os
import secrets
import shutil
import sqlite3
import ssl
import subprocess
import sys
import threading
import time
import uuid
from collections.abc import Callable
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote

from thinktank.config import Settings, load_settings
from thinktank.ingest import AUDIO_EXTENSIONS
from thinktank.adapters.local_receiver import LocalReceiverV1Adapter
from thinktank.receiver_v1 import (
    V1Error,
    etag_for,
    normalize_sha256,
)
from thinktank.server.contracts import (
    MAX_V1_UPLOAD_BYTES,
    UPLOAD_STATUS_ALREADY_EXISTS,
    UPLOAD_STATUS_CREATED,
    is_safe_leaf_name,
)
from thinktank.server.ports import ReceiverV1Persistence
from thinktank.users import is_multi_user, load_users
from thinktank.web_dashboard import build_dashboard_summary

logger = logging.getLogger(__name__)

DEFAULT_HOST = "0.0.0.0"  # noqa: S104 - LAN 의 폰이 붙어야 하므로 의도적

def _is_daily_important(path: Path) -> bool:
    """데일리 구분 노트 중 '중요'·'일정'만 (아이디어·기타·색인 제외)."""
    return path.stem.endswith("_중요") or path.stem.endswith("_일정")


# 폰으로 내려보낼 노트: (폴더, 상한, 파일명 필터). 필터 None = 폴더 전부.
#
# - 주제 노트(20-notes)는 폰에 보내지 않는다. 1000+개라 목록을 독식하고, 볼트에만
#   남겨 허브(1 wiki)의 [[링크]]로 접근한다.
# - wiki(허브)·ideas(창발) 가 가장 중요 → 넉넉히 전부.
# - daily 는 '중요'·'일정' 구분 노트만(아이디어·기타·색인 제외).
# - archive 는 원본 전사와 앱에서 보관한 일반 노트를 함께 최근 100개까지
#   휴대폰의 읽기 전용 보관함으로 보여 준다. 원래 폴더로 되살리는 동기화가 아니라
#   ``90-archive`` 소속으로 내려오므로, 보관 동작이 되돌려지는 일은 없다.
NoteFilter = Callable[[Path], bool]
NOTE_SPECS: list[tuple[str, int, NoteFilter | None]] = [
    ("1 wiki", 100, None),
    ("30-ideas", 200, None),
    ("10-daily", 60, _is_daily_important),
    ("90-archive", 100, None),
]
NOTE_FOLDERS = [folder for folder, _limit, _match in NOTE_SPECS]

# 폰에서 삭제한 노트가 가는 곳. 볼트에서 지우지 않는 이유는 파이프라인 산출물이라
# 재생성이 안 되기 때문이다(원본 오디오는 보관 기간 후 폐기된다).
ARCHIVE_DIR = "90-archive"

# 업로드 1건 상한. 22시간 연속 녹음(m4a)도 수백 MB 수준이라 넉넉하다.
MAX_UPLOAD_BYTES = MAX_V1_UPLOAD_BYTES
_CHUNK_SIZE = 64 * 1024


# 윈도우 예약 장치명. 확장자를 붙여도(CON.m4a) 여전히 장치를 가리킨다.
def _is_safe_name(name: str) -> bool:
    """경로 구분자/상위 탐색/윈도우 특수 이름이 없는 순수 파일명인지 검사한다.

    윈도우 고유 함정까지 막는다:

    - ``:`` — ``rec.m4a:hidden`` 은 대체 데이터 스트림(ADS)을 만든다. 디렉터리
      목록에는 ``rec.m4a`` 만 보이고 데이터가 숨는다.
    - 예약 장치명(``CON``/``NUL``/``COM1``...) — 파일이 아니라 장치로 열린다.
    - 앞뒤 점/공백 — 윈도우가 조용히 잘라내므로 다른 파일과 충돌시킬 수 있다.
      선행 점을 막으면 내부 임시 파일(``.thinktank.*.part``) 흉내도 함께 막힌다.
    - 제어문자 — 로그를 오염시키고 터미널 이스케이프로 악용될 수 있다.
    """
    return is_safe_leaf_name(name)


# 업로드 후 남겨둘 최소 여유 공간. 수집 폴더가 꽉 차면 파이프라인의 임시 파일
# 생성까지 함께 실패하므로 여유를 두고 미리 거절한다.
MIN_FREE_BYTES = 2 * 1024 * 1024 * 1024


def _has_room_for(directory: Path, incoming: int) -> bool:
    """업로드를 받아도 최소 여유 공간이 남는지 확인한다."""
    try:
        free = shutil.disk_usage(directory).free
    except OSError:
        return True  # 확인 불가면 막지 않는다(기존 동작 유지).
    return free - incoming >= MIN_FREE_BYTES


def _by_mtime_desc(paths: list[Path]) -> list[Path]:
    return sorted(paths, key=lambda path: path.stat().st_mtime, reverse=True)


def _collect_notes(vault: Path) -> list[tuple[str, Path]]:
    """폰에 내려보낼 노트를 (폴더명, 경로) 로 폴더별 상한을 지켜 모은다.

    폴더명을 함께 돌려주는 이유: 앱이 폴더 트리(접기/펴기)를 그리려면 각 노트가
    어느 폴더 소속인지 알아야 한다. 폴더별로 이미 최근 수정순이라 전체를 다시
    정렬하지 않는다(앱이 폴더 단위로 다시 묶는다).
    """
    found: list[tuple[str, Path]] = []
    for dir_name, limit, name_filter in NOTE_SPECS:
        directory = vault / dir_name
        if not directory.is_dir():
            continue
        files = [path for path in directory.glob("*.md") if path.is_file()]
        if name_filter is not None:
            files = [path for path in files if name_filter(path)]
        found.extend((dir_name, path) for path in _by_mtime_desc(files)[:limit])
    return found


def _archive_note(vault: Path, source: Path) -> Path:
    """노트를 90-archive 로 옮기고 옮겨진 경로를 반환한다.

    같은 이름이 이미 있으면 뒤에 번호를 붙인다 — 덮어쓰면 예전에 보관한 노트가
    소리 없이 사라진다.
    """
    archive = vault / ARCHIVE_DIR
    archive.mkdir(parents=True, exist_ok=True)

    target = archive / source.name
    counter = 2
    while target.exists():
        target = archive / f"{source.stem}_{counter}{source.suffix}"
        counter += 1

    os.replace(source, target)
    return target


def _find_note(vault: Path, name: str) -> Path | None:
    """이름이 일치하는 기존 노트를 찾는다 (없으면 None).

    이미 있는 노트만 덮어쓰게 해서, 앱이 볼트 아무 곳에나 새 파일을 만들지
    못하도록 막는다.
    """
    for dir_name in NOTE_FOLDERS:
        candidate = vault / dir_name / name
        if candidate.is_file():
            return candidate
    return None


# 업로드 후 파이프라인 자동 트리거 -----------------------------------------
# 업로드는 버스트로 들어온다(앱 자동동기화가 여러 청크를 몰아 올림). 파일마다
# 돌리지 않고 마지막 업로드 후 잠잠해지면 한 번 돌린다(디바운스). main.py 의
# PipelineLock 이 야간 작업·이전 트리거와 겹치지 않게 막으므로 중복 실행은 안전하다.
PROCESS_DEBOUNCE_SECONDS = 150.0
_REPO_ROOT = Path(__file__).resolve().parents[2]
_TRIGGER_LOG = Path("~/.thinktank/pipeline-trigger.log").expanduser()


def _spawn_pipeline(user_name: str) -> None:
    """해당 사용자의 파이프라인을 별도 프로세스로 띄운다(수신기를 막지 않음)."""
    try:
        _TRIGGER_LOG.parent.mkdir(parents=True, exist_ok=True)
        with _TRIGGER_LOG.open("a", encoding="utf-8") as log:
            subprocess.Popen(  # noqa: S603 - 고정 명령, user_name 은 users.json 로 검증됨
                [sys.executable, "-m", "thinktank.main", "--user", user_name],
                cwd=str(_REPO_ROOT),
                stdout=log,
                stderr=subprocess.STDOUT,
            )
        logger.info("업로드 후 파이프라인 트리거: --user %s", user_name)
    except Exception as exc:  # noqa: BLE001 - 트리거 실패가 업로드를 막으면 안 됨
        logger.warning("파이프라인 트리거 실패(%s): %s", user_name, exc)


def _schedule_process(server, user_name: str) -> None:  # noqa: ANN001 - Server 동적 속성
    """업로드 버스트가 끝난 뒤 한 번만 파이프라인을 돌리도록 디바운스 예약한다."""
    if not getattr(server, "auto_process", False):
        return
    with server.process_lock:
        old = server.process_timers.get(user_name)
        if old is not None:
            old.cancel()
        timer = threading.Timer(
            PROCESS_DEBOUNCE_SECONDS, _spawn_pipeline, args=(user_name,)
        )
        timer.daemon = True
        server.process_timers[user_name] = timer
        timer.start()


class _Handler(BaseHTTPRequestHandler):
    """앱 계약(`Sync.kt`)에 대응하는 요청 핸들러."""

    server_version = "thinktank-receiver"
    protocol_version = "HTTP/1.1"

    # 소켓 연산별 상한. 없으면(기본 None) 연결만 열고 데이터를 안 보내는
    # 클라이언트가 스레드를 무기한 점유한다(ThreadingHTTPServer 는 연결마다
    # 스레드를 만들고 상한이 없어 LAN 에서 고갈시킬 수 있다).
    timeout = 120

    # --- 공통 ---------------------------------------------------------

    def log_message(self, format: str, *args: object) -> None:  # noqa: A002
        """기본 stderr 출력 대신 로거로 보낸다."""
        logger.debug("%s - %s", self.address_string(), format % args)

    def _send(
        self,
        code: int,
        body: bytes = b"",
        content_type: str = "text/plain; charset=utf-8",
        headers: dict[str, str] | None = None,
    ) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _request_id(self) -> str:
        """Return a safe correlation id for the current request."""
        cached = getattr(self, "_v1_request_id", None)
        if cached is not None:
            return cached
        supplied = self.headers.get("X-Request-ID", "").strip()
        if (
            not supplied
            or len(supplied) > 128
            or any(ord(ch) < 33 or ord(ch) > 126 for ch in supplied)
        ):
            supplied = str(uuid.uuid4())
        self._v1_request_id = supplied
        return supplied

    def _send_v1_json(
        self,
        code: int,
        payload: dict[str, object] | list[object],
        *,
        headers: dict[str, str] | None = None,
    ) -> None:
        request_id = self._request_id()
        if isinstance(payload, dict) and "requestId" not in payload:
            payload = {**payload, "requestId": request_id}
        response_headers = {"X-Request-ID": request_id, **(headers or {})}
        self._send(
            code,
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
                "utf-8"
            ),
            "application/json; charset=utf-8",
            response_headers,
        )

    def _send_v1_error(self, error: V1Error) -> None:
        self._send_v1_json(
            error.status,
            {
                "error": {
                    "code": error.code,
                    "message": error.message,
                }
            },
        )

    def _handle_dashboard_asset(self, segments: list[str]) -> None:
        """Serve the dependency-free dashboard from the repository web folder."""
        if len(segments) == 1:
            asset_name = "index.html"
        elif len(segments) == 2:
            asset_name = segments[1]
        else:
            self._send(404, b"not found")
            return

        asset_types = {
            "index.html": "text/html; charset=utf-8",
            "styles.css": "text/css; charset=utf-8",
            "app.js": "text/javascript; charset=utf-8",
        }
        content_type = asset_types.get(asset_name)
        if content_type is None:
            self._send(404, b"not found")
            return

        asset = Path(__file__).resolve().parents[2] / "web" / "dashboard" / asset_name
        try:
            body = asset.read_bytes()
        except OSError:
            self._send(404, b"dashboard asset not found")
            return
        self._send(
            200,
            body,
            content_type,
            headers={
                "Cache-Control": "no-cache",
                "Content-Security-Policy": (
                    "default-src 'self'; connect-src 'self'; style-src 'self'; "
                    "script-src 'self'; img-src 'self' data:; media-src 'self' blob:"
                ),
                "X-Content-Type-Options": "nosniff",
            },
        )

    def _resolve_user(self, supplied: str):  # noqa: ANN202 - User (순환 import 회피)
        """토큰으로 사용자를 찾는다 (없으면 None).

        **경로의 {user} 가 아니라 토큰이 사용자를 결정한다.** 경로는 클라이언트가
        마음대로 보낼 수 있어서, 그걸 믿으면 남의 수집 폴더와 볼트에 접근할 수 있다.
        """
        if not supplied:
            return None
        for user in self.server.users:
            if secrets.compare_digest(supplied, user.token):
                return user
        return None

    def _authorized(self):  # noqa: ANN201 - User | None
        header = self.headers.get("Authorization", "")
        prefix = "Bearer "
        if not header.startswith(prefix):
            return None
        return self._resolve_user(header[len(prefix) :].strip())

    def _user_for(self, segments: list[str]):  # noqa: ANN202
        """인증하고, 경로의 {user} 가 토큰 주인과 일치하는지 확인한다.

        불일치면 None. 다중 사용자 모드에서만 검사한다 — 단일 사용자 설정은
        경로의 {user} 를 지금까지처럼 무시해 기존 앱 설정이 계속 동작한다.
        """
        user = self._authorized()
        if user is None:
            return None
        if self.server.multi_user and len(segments) >= 2 and segments[1] != user.name:
            logger.warning(
                "경로의 사용자(%s)가 토큰 주인(%s)과 다릅니다", segments[1], user.name
            )
            return None
        return user

    def _v1_user_for(self, supplied_name: str):  # noqa: ANN202
        """Resolve a V1 principal without trusting the path user segment."""
        user = self._authorized()
        if user is None:
            return None
        if self.server.multi_user and supplied_name != user.name:
            logger.warning(
                "V1 경로의 사용자(%s)가 토큰 주인(%s)과 다릅니다",
                supplied_name,
                user.name,
            )
            return None
        return user

    def _authorized_for_download(self) -> bool:
        """헤더 또는 쿼리스트링의 토큰을 받는다.

        폰 브라우저로 APK 를 받을 때는 Authorization 헤더를 붙일 수 없어서
        ``?token=`` 을 허용한다. 파일 하나를 내려주는 용도로만 쓴다 — 다른
        엔드포인트는 헤더만 받는다(주소창/기록에 토큰이 남지 않도록).
        """
        if self._authorized() is not None:
            return True
        _, _, query = self.path.partition("?")
        supplied = parse_qs(query).get("token", [""])[0].strip()
        return self._resolve_user(supplied) is not None

    def _segments(self) -> list[str]:
        """경로를 세그먼트로 나눈 뒤 각각 디코딩한다.

        분리 후에 unquote 하므로 ``%2F`` 로 세그먼트를 새로 만들 수 없다.
        """
        path = self.path.split("?", 1)[0]
        return [unquote(part) for part in path.strip("/").split("/") if part]

    def _body_length(self) -> int | None:
        """Content-Length 를 검증해 반환한다 (문제가 있으면 응답 후 None)."""
        raw = self.headers.get("Content-Length")
        if raw is None:
            self._send(411, "Content-Length 가 필요합니다".encode())
            return None
        try:
            length = int(raw)
        except ValueError:
            self._send(400, "Content-Length 형식 오류".encode())
            return None
        if length < 0 or length > MAX_UPLOAD_BYTES:
            self._send(413, "업로드가 너무 큽니다".encode())
            return None
        return length

    def _v1_body_length(self) -> int:
        raw = self.headers.get("Content-Length")
        if raw is None:
            raise V1Error(411, "CONTENT_LENGTH_REQUIRED", "Content-Length is required")
        try:
            length = int(raw)
        except ValueError as exc:
            raise V1Error(
                400, "INVALID_CONTENT_LENGTH", "Content-Length must be an integer"
            ) from exc
        if length < 0:
            raise V1Error(
                400, "INVALID_CONTENT_LENGTH", "Content-Length cannot be negative"
            )
        if length > MAX_UPLOAD_BYTES:
            raise V1Error(413, "UPLOAD_TOO_LARGE", "Request body is too large")
        return length

    def _v1_json_body(self, length: int) -> dict[str, object]:
        body = self._read_body(length)
        try:
            value = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise V1Error(400, "INVALID_JSON", "Request body must be valid JSON") from exc
        if not isinstance(value, dict):
            raise V1Error(400, "INVALID_JSON", "JSON body must be an object")
        return value

    def _read_body(self, length: int) -> bytes:
        return self.rfile.read(length) if length else b""

    def _drain(self, length: int) -> None:
        """본문을 읽어 버린다 (연결 재사용을 위해 소비는 해야 한다)."""
        remaining = length
        while remaining > 0:
            chunk = self.rfile.read(min(_CHUNK_SIZE, remaining))
            if not chunk:
                return
            remaining -= len(chunk)

    # --- 라우팅 -------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 규약
        segments = self._segments()
        if segments[:1] == ["dashboard"]:
            self._handle_dashboard_asset(segments)
            return
        if segments[:2] == ["api", "v1"]:
            self._handle_v1_get(segments[2:])
            return
        if segments == ["health"]:
            self._send(200, b"ok")
            return
        if segments == ["apk"]:
            self._handle_apk()
            return
        if segments == ["apk", "info"]:
            self._handle_apk_info()
            return
        user = self._user_for(segments)
        if user is None:
            self._send(401, b"unauthorized")
            return
        if len(segments) == 2 and segments[0] == "notes":
            self._handle_notes_list(user)
            return
        self._send(404, b"not found")

    def do_PUT(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 규약
        segments = self._segments()
        if segments[:2] == ["api", "v1"]:
            self._handle_v1_put(segments[2:])
            return
        user = self._user_for(segments)
        if user is None:
            self._send(401, b"unauthorized")
            return
        if len(segments) == 3 and segments[0] == "upload":
            self._handle_upload(user, segments[2])
            return
        if len(segments) == 3 and segments[0] == "notes":
            self._handle_note_put(user, segments[2])
            return
        self._send(404, b"not found")

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 규약
        segments = self._segments()
        if segments[:2] == ["api", "v1"]:
            self._handle_v1_post(segments[2:])
            return
        self._send(404, b"not found")

    def do_DELETE(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 규약
        segments = self._segments()
        if segments[:2] == ["api", "v1"]:
            self._handle_v1_delete(segments[2:])
            return
        user = self._user_for(segments)
        if user is None:
            self._send(401, b"unauthorized")
            return
        if len(segments) == 3 and segments[0] == "notes":
            self._handle_note_delete(user, segments[2])
            return
        self._send(404, b"not found")

    # --- V1 라우팅 ----------------------------------------------------

    def _v1_authorize(self, supplied_name: str):  # noqa: ANN202
        user = self._v1_user_for(supplied_name)
        if user is None:
            raise V1Error(401, "UNAUTHORIZED", "A valid Bearer token is required")
        return user

    def _handle_v1_get(self, segments: list[str]) -> None:
        try:
            if segments == ["health"]:
                self._send_v1_json(
                    200, {"status": "ok", "apiVersion": "v1"}
                )
                return
            if segments == ["dashboard", "summary"]:
                user = self._authorized()
                if user is None:
                    raise V1Error(
                        401, "UNAUTHORIZED", "A valid Bearer token is required"
                    )
                summary = build_dashboard_summary(self.server, user, NOTE_FOLDERS)
                self._send_v1_json(
                    200,
                    summary,
                    headers={"Cache-Control": "no-store"},
                )
                return
            if len(segments) == 4 and segments[:2] == ["dashboard", "notes"]:
                user = self._authorized()
                if user is None:
                    raise V1Error(
                        401, "UNAUTHORIZED", "A valid Bearer token is required"
                    )
                self._handle_dashboard_note(user, segments[2], segments[3])
                return
            if len(segments) == 3 and segments[:2] == ["dashboard", "audio"]:
                user = self._authorized()
                if user is None:
                    raise V1Error(
                        401, "UNAUTHORIZED", "A valid Bearer token is required"
                    )
                self._handle_dashboard_audio(user, segments[2])
                return
            if segments == ["apk", "info"]:
                if self._authorized() is None:
                    raise V1Error(
                        401, "UNAUTHORIZED", "A valid Bearer token is required"
                    )
                apk = self.server.apk_path
                if apk is None:
                    raise V1Error(404, "APK_NOT_FOUND", "APK is not configured")
                self._send_v1_json(200, self.server.v1_state.apk_info(apk))
                return
            if segments == ["apk"]:
                if self._authorized() is None:
                    raise V1Error(
                        401, "UNAUTHORIZED", "A valid Bearer token is required"
                    )
                self._handle_v1_apk_download()
                return
            if len(segments) == 2 and segments[0] == "notes":
                user = self._v1_authorize(segments[1])
                notes = self.server.v1_state.list_notes(
                    user, _collect_notes(user.settings.obsidian_vault)
                )
                self._send_v1_json(200, {"notes": notes})
                return
            if len(segments) == 3 and segments[0] == "notes":
                user = self._v1_authorize(segments[1])
                note = self.server.v1_state.get_note(user, segments[2])
                self._send_v1_json(
                    200, note, headers={"ETag": etag_for(note["revision"])}
                )
                return
            raise V1Error(404, "NOT_FOUND", "Endpoint does not exist")
        except V1Error as exc:
            self._send_v1_error(exc)
        except OSError:
            logger.exception("V1 GET 처리 실패 requestId=%s", self._request_id())
            self._send_v1_error(
                V1Error(500, "INTERNAL_ERROR", "The request could not be completed")
            )

    def _handle_v1_put(self, segments: list[str]) -> None:
        try:
            if len(segments) == 3 and segments[0] == "upload":
                self._handle_v1_upload(segments[1], segments[2])
                return
            if len(segments) == 3 and segments[0] == "notes":
                user = self._v1_authorize(segments[1])
                length = self._v1_body_length()
                body = self._v1_json_body(length)
                content = body.get("content")
                if not isinstance(content, str):
                    raise V1Error(
                        400, "INVALID_NOTE_CONTENT", "content must be a string"
                    )
                note = self.server.v1_state.update_note(
                    user,
                    segments[2],
                    content=content,
                    if_match=self.headers.get("If-Match"),
                )
                self._send_v1_json(
                    200, note, headers={"ETag": etag_for(note["revision"])}
                )
                return
            raise V1Error(404, "NOT_FOUND", "Endpoint does not exist")
        except V1Error as exc:
            self._send_v1_error(exc)
        except (OSError, sqlite3.Error):
            logger.exception("V1 PUT 처리 실패 requestId=%s", self._request_id())
            self._send_v1_error(
                V1Error(500, "INTERNAL_ERROR", "The request could not be completed")
            )

    def _handle_v1_post(self, segments: list[str]) -> None:
        try:
            if len(segments) == 2 and segments[0] == "notes":
                user = self._v1_authorize(segments[1])
                length = self._v1_body_length()
                body = self._v1_json_body(length)
                folder, name, content = (
                    body.get("folder"),
                    body.get("name"),
                    body.get("content", ""),
                )
                if not all(isinstance(value, str) for value in (folder, name, content)):
                    raise V1Error(
                        400,
                        "INVALID_NOTE",
                        "folder, name and content must be strings",
                    )
                if not _is_safe_name(name):
                    raise V1Error(400, "INVALID_NOTE_NAME", "Note name is invalid")
                note = self.server.v1_state.create_note(
                    user, folder=folder, name=name, content=content
                )
                self._send_v1_json(
                    201,
                    note,
                    headers={
                        "ETag": etag_for(note["revision"]),
                        "Location": f"/api/v1/notes/{segments[1]}/{note['id']}",
                    },
                )
                return
            raise V1Error(404, "NOT_FOUND", "Endpoint does not exist")
        except V1Error as exc:
            self._send_v1_error(exc)
        except (OSError, sqlite3.Error):
            logger.exception("V1 POST 처리 실패 requestId=%s", self._request_id())
            self._send_v1_error(
                V1Error(500, "INTERNAL_ERROR", "The request could not be completed")
            )

    def _handle_v1_delete(self, segments: list[str]) -> None:
        try:
            if len(segments) == 3 and segments[0] == "notes":
                user = self._v1_authorize(segments[1])
                archived = self.server.v1_state.archive_note(
                    user,
                    segments[2],
                    if_match=self.headers.get("If-Match"),
                    archive_dir=ARCHIVE_DIR,
                )
                self._send_v1_json(200, archived)
                return
            raise V1Error(404, "NOT_FOUND", "Endpoint does not exist")
        except V1Error as exc:
            self._send_v1_error(exc)
        except (OSError, sqlite3.Error):
            logger.exception("V1 DELETE 처리 실패 requestId=%s", self._request_id())
            self._send_v1_error(
                V1Error(500, "INTERNAL_ERROR", "The request could not be completed")
            )

    def _handle_v1_upload(self, supplied_user: str, filename: str) -> None:
        user = self._v1_authorize(supplied_user)
        if not _is_safe_name(filename):
            raise V1Error(400, "INVALID_FILENAME", "Filename is invalid")
        if Path(filename).suffix.lower() not in AUDIO_EXTENSIONS:
            raise V1Error(400, "UNSUPPORTED_AUDIO", "Audio extension is not supported")
        length = self._v1_body_length()
        if length == 0:
            raise V1Error(400, "EMPTY_UPLOAD", "Audio upload cannot be empty")
        declared_hash = normalize_sha256(
            self.headers.get("X-Content-SHA256")
            or self.headers.get("X-SHA256")
            or self.headers.get("Digest")
        )
        idempotency_key = self.headers.get("Idempotency-Key", "").strip()
        recording_id = self.headers.get("X-Recording-ID", "").strip()
        chunk_id = self.headers.get("X-Chunk-ID", "").strip()
        if declared_hash is None:
            self._drain(length)
            raise V1Error(
                400, "CONTENT_SHA256_REQUIRED", "X-Content-SHA256 is required"
            )
        if not idempotency_key:
            self._drain(length)
            raise V1Error(
                400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required"
            )
        try:
            uuid.UUID(idempotency_key)
        except ValueError as exc:
            self._drain(length)
            raise V1Error(
                400,
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key must be a UUID",
            ) from exc
        if len(idempotency_key) > 200 or any(
            ord(ch) < 33 or ord(ch) > 126 for ch in idempotency_key
        ):
            self._drain(length)
            raise V1Error(
                400, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key is invalid"
            )
        for value, code, label in (
            (recording_id, "RECORDING_ID_REQUIRED", "X-Recording-ID"),
            (chunk_id, "CHUNK_ID_REQUIRED", "X-Chunk-ID"),
        ):
            if not value:
                self._drain(length)
                raise V1Error(400, code, f"{label} is required")
            try:
                uuid.UUID(value)
            except ValueError as exc:
                self._drain(length)
                invalid_code = f"INVALID_{code.removesuffix('_REQUIRED')}"
                raise V1Error(
                    400,
                    invalid_code,
                    f"{label} must be a UUID",
                ) from exc
        try:
            self.server.v1_state.ensure_upload_capacity(user, length)
        except V1Error:
            self._drain(length)
            raise
        receipt, created = self.server.v1_state.receive_upload(
            user=user,
            filename=filename,
            length=length,
            declared_sha256=declared_hash,
            idempotency_key=idempotency_key,
            recording_id=recording_id,
            chunk_id=chunk_id,
            source=self.rfile,
        )
        if created:
            _schedule_process(self.server, user.name)
        payload = {
            **receipt.as_dict(),
            "status": (
                UPLOAD_STATUS_CREATED if created else UPLOAD_STATUS_ALREADY_EXISTS
            ),
        }
        self._send_v1_json(201 if created else 200, payload)

    def _handle_v1_apk_download(self) -> None:
        apk: Path | None = self.server.apk_path
        if apk is None or not apk.is_file():
            raise V1Error(404, "APK_NOT_FOUND", "APK is not configured")
        request_id = self._request_id()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", f'attachment; filename="{apk.name}"')
        self.send_header("Content-Length", str(apk.stat().st_size))
        self.send_header("X-Request-ID", request_id)
        self.end_headers()
        with apk.open("rb") as handle:
            shutil.copyfileobj(handle, self.wfile, length=_CHUNK_SIZE)

    def _dashboard_file(
        self, root: Path, filename: str, *, error_code: str, error_message: str
    ) -> Path:
        """안전한 leaf filename을 현재 사용자 디렉터리 안의 파일로 해석한다."""
        if not _is_safe_name(filename):
            raise V1Error(400, error_code, error_message)
        try:
            resolved_root = root.resolve()
            candidate = (resolved_root / filename).resolve()
        except OSError as exc:
            raise V1Error(404, "DASHBOARD_FILE_NOT_FOUND", "File was not found") from exc
        if resolved_root not in candidate.parents or not candidate.is_file():
            raise V1Error(404, "DASHBOARD_FILE_NOT_FOUND", "File was not found")
        return candidate

    def _handle_dashboard_note(self, user, folder: str, name: str) -> None:  # noqa: ANN001
        """선택한 사용자 범위의 UTF-8 Markdown만 대시보드에 반환한다."""
        if folder not in NOTE_FOLDERS:
            raise V1Error(400, "DASHBOARD_FOLDER_INVALID", "Note folder is not exposed")
        if not name.endswith(".md"):
            raise V1Error(400, "DASHBOARD_NOTE_INVALID", "Note name must end with .md")
        path = self._dashboard_file(
            user.settings.obsidian_vault / folder,
            name,
            error_code="DASHBOARD_NOTE_INVALID",
            error_message="Note name is invalid",
        )
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            raise V1Error(500, "DASHBOARD_NOTE_UNREADABLE", "Note could not be decoded") from exc
        self._send_v1_json(
            200,
            {"folder": folder, "name": name, "content": content},
            headers={"Cache-Control": "no-store"},
        )

    def _handle_dashboard_audio(self, user, filename: str) -> None:  # noqa: ANN001
        """선택한 사용자 inbox의 M4A만 bearer 인증으로 스트리밍한다."""
        if Path(filename).suffix.lower() != ".m4a":
            raise V1Error(
                400, "DASHBOARD_AUDIO_INVALID", "Only .m4a audio can be played"
            )
        path = self._dashboard_file(
            user.settings.ingest_dir,
            filename,
            error_code="DASHBOARD_AUDIO_INVALID",
            error_message="Audio filename is invalid",
        )
        request_id = self._request_id()
        self.send_response(200)
        self.send_header("Content-Type", "audio/mp4")
        self.send_header("Content-Length", str(path.stat().st_size))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Request-ID", request_id)
        self.end_headers()
        with path.open("rb") as handle:
            shutil.copyfileobj(handle, self.wfile, length=_CHUNK_SIZE)

    # --- 핸들러 -------------------------------------------------------

    def _handle_upload(self, user, filename: str) -> None:  # noqa: ANN001
        if not _is_safe_name(filename):
            self._send(400, "파일명이 올바르지 않습니다".encode())
            return
        if Path(filename).suffix.lower() not in AUDIO_EXTENSIONS:
            self._send(400, "지원하지 않는 오디오 형식입니다".encode())
            return

        length = self._body_length()
        if length is None:
            return

        ingest_dir: Path = user.settings.ingest_dir
        if not _has_room_for(ingest_dir, length):
            self._drain(length)
            logger.warning("디스크 여유 부족으로 업로드 거부: %s", filename)
            self._send(507, "디스크 여유 공간이 부족합니다".encode())
            return

        target = ingest_dir / filename
        if target.exists():
            # 앱이 재시도한 경우. 이미 처리 중일 수 있으므로 덮어쓰지 않는다.
            self._drain(length)
            self._send(200, "이미 업로드됨".encode())
            return

        part = ingest_dir / f".thinktank.{filename}.part"
        remaining = length
        try:
            with part.open("wb") as handle:
                while remaining > 0:
                    chunk = self.rfile.read(min(_CHUNK_SIZE, remaining))
                    if not chunk:
                        raise OSError("업로드가 도중에 끊겼습니다")
                    handle.write(chunk)
                    remaining -= len(chunk)
            os.replace(part, target)
        except OSError as exc:
            part.unlink(missing_ok=True)
            logger.warning("업로드 실패 %s: %s", filename, exc)
            self._send(500, "업로드 실패".encode())
            return

        logger.info("업로드 완료 %s (%d bytes)", filename, length)
        _schedule_process(self.server, user.name)
        self._send(201, b"ok")

    def _handle_apk(self) -> None:
        """설치 파일을 내려준다 (폰 브라우저로 바로 받게 하기 위한 것).

        OneDrive 를 거치면 클라우드 동기화를 기다려야 한다. 같은 WiFi 에 이미
        인증된 연결이 있으니 그 위로 바로 보낸다.
        """
        if not self._authorized_for_download():
            self._send(401, b"unauthorized")
            return
        apk: Path | None = self.server.apk_path
        if apk is None or not apk.is_file():
            self._send(404, "설치 파일이 설정되어 있지 않습니다".encode())
            return

        data = apk.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", f'attachment; filename="{apk.name}"')
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        logger.info("APK 전송 %s (%d bytes)", apk.name, len(data))

    def _handle_apk_info(self) -> None:
        """APK 옆의 버전 메모를 그대로 보여준다 (어떤 빌드인지 폰에서 확인용)."""
        if not self._authorized_for_download():
            self._send(401, b"unauthorized")
            return
        apk: Path | None = self.server.apk_path
        if apk is None or not apk.is_file():
            self._send(404, "설치 파일이 설정되어 있지 않습니다".encode())
            return
        notes = apk.with_suffix(".version.txt")
        text = (
            notes.read_text(encoding="utf-8")
            if notes.is_file()
            else f"{apk.name} ({apk.stat().st_size} bytes)"
        )
        self._send(200, text.encode("utf-8"))

    def _handle_notes_list(self, user) -> None:  # noqa: ANN001
        notes = []
        for folder, path in _collect_notes(user.settings.obsidian_vault):
            try:
                notes.append(
                    {
                        "name": path.name,
                        "folder": folder,
                        "content": path.read_text(encoding="utf-8"),
                    }
                )
            except OSError as exc:
                logger.warning("노트 읽기 실패 %s: %s", path.name, exc)
        body = json.dumps(notes, ensure_ascii=False).encode("utf-8")
        self._send(200, body, "application/json; charset=utf-8")

    def _handle_note_put(self, user, name: str) -> None:  # noqa: ANN001
        if not _is_safe_name(name) or not name.endswith(".md"):
            self._send(400, "노트 이름이 올바르지 않습니다".encode())
            return

        length = self._body_length()
        if length is None:
            return

        target = _find_note(user.settings.obsidian_vault, name)
        if target is None:
            self._drain(length)
            self._send(404, "없는 노트입니다".encode())
            return

        content = self._read_body(length)
        try:
            target.write_text(content.decode("utf-8"), encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            logger.warning("노트 저장 실패 %s: %s", name, exc)
            self._send(500, "노트 저장 실패".encode())
            return

        logger.info("노트 갱신 %s", name)
        self._send(200, b"ok")

    def _handle_note_delete(self, user, name: str) -> None:  # noqa: ANN001
        if not _is_safe_name(name) or not name.endswith(".md"):
            self._send(400, "노트 이름이 올바르지 않습니다".encode())
            return

        vault: Path = user.settings.obsidian_vault
        target = _find_note(vault, name)
        if target is None:
            self._send(404, "없는 노트입니다".encode())
            return

        try:
            moved = _archive_note(vault, target)
        except OSError as exc:
            logger.warning("노트 보관 실패 %s: %s", name, exc)
            self._send(500, "노트 보관 실패".encode())
            return

        logger.info("노트 보관 %s -> %s", name, moved.parent.name)
        self._send(200, b"archived")


def create_server(
    *,
    users: list,
    host: str = DEFAULT_HOST,
    port: int = 8765,
    certfile: str | Path | None = None,
    apk_path: str | Path | None = None,
    state_db: str | Path | None = None,
    v1_persistence: ReceiverV1Persistence | None = None,
) -> ThreadingHTTPServer:
    """수신기 서버를 만든다 (아직 listen 만 하고 serve 는 호출자가 시작).

    Args:
        users: :class:`thinktank.users.User` 목록. 사용자마다 토큰과 전용 경로
            (수집 폴더/볼트/DB)를 갖는다. 토큰으로 사용자를 판별하므로 경로의
            ``{user}`` 는 검증용일 뿐 권한 판단에 쓰지 않는다.
        host: 바인딩 주소. 기본값은 LAN 전체(폰이 붙어야 하므로).
        port: 바인딩 포트. 0 이면 임시 포트(테스트용).
        certfile: 인증서+개인키가 담긴 PEM 경로. 주면 HTTPS 로 뜬다.
            None 이면 평문 HTTP (앱이 아직 피닝을 모를 때의 기본값).
        apk_path: 폰이 내려받을 APK 경로.
        state_db: V1 upload receipt/note identity SQLite 경로. 생략하면 첫
            사용자의 pipeline DB 옆 ``receiver-v1.sqlite3`` 를 사용한다.
        v1_persistence: cloud/test persistence 구현. 생략하면 local
            file/SQLite adapter를 만든다. ``state_db``와 함께 줄 수 없다.

    Returns:
        설정이 주입된 :class:`ThreadingHTTPServer`.

    Raises:
        ValueError: 사용자가 없거나 토큰이 빈 사용자가 있을 때.
    """
    if v1_persistence is not None and state_db is not None:
        raise ValueError("v1_persistence와 state_db는 함께 지정할 수 없습니다.")
    use_local_persistence = v1_persistence is None
    if v1_persistence is not None and not isinstance(
        v1_persistence, ReceiverV1Persistence
    ):
        raise TypeError("v1_persistence가 ReceiverV1Persistence 계약을 구현하지 않습니다.")
    if not users:
        raise ValueError("사용자가 최소 1명 필요합니다.")
    for user in users:
        if not user.token.strip():
            raise ValueError(
                f"사용자 {user.name or '(기본)'!r} 에 토큰이 없습니다. "
                "인증 없이 네트워크에 바인딩할 수 없습니다."
            )
        if use_local_persistence:
            user.settings.ingest_dir.mkdir(parents=True, exist_ok=True)

    server = ThreadingHTTPServer((host, port), _Handler)
    server.server_started_at = time.time()
    server.users = users
    server.multi_user = is_multi_user(users)
    server.apk_path = Path(apk_path).expanduser() if apk_path else None
    if v1_persistence is None:
        if state_db is None:
            configured = os.environ.get("RECEIVER_STATE_DB", "").strip()
            state_db = (
                Path(configured).expanduser()
                if configured
                else users[0].settings.db_path.with_name("receiver-v1.sqlite3")
            )
        v1_persistence = LocalReceiverV1Adapter(
            state_db,
            NOTE_FOLDERS,
            ingest_directories=[user.settings.ingest_dir for user in users],
        )
    server.v1_state = v1_persistence
    server.tls_enabled = bool(certfile)
    # 업로드 후 파이프라인 자동 트리거(디바운스 상태 + 스위치).
    # RECEIVER_AUTO_PROCESS=0 으로 끄면 예전처럼 야간/수동만 처리한다.
    server.process_lock = threading.Lock()
    server.process_timers = {}
    _auto = os.environ.get("RECEIVER_AUTO_PROCESS", "1").strip().lower()
    server.auto_process = use_local_persistence and _auto not in (
        "0",
        "false",
        "no",
        "",
    )

    if certfile:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(certfile)
        server.socket = context.wrap_socket(server.socket, server_side=True)

    return server


# --- TLS 인증서 -------------------------------------------------------
#
# 앱이 공개키(SPKI) 지문을 피닝하므로 CA 신뢰 사슬이 필요 없다 — 사용자가 폰에
# 인증서를 설치하지 않아도 된다. DHCP 로 PC 의 IP 가 바뀌면 인증서를 다시 발급해야
# 하는데, 그때 **개인키를 재사용**하므로 앱에 넣어둔 핀은 그대로 유효하다.

CERT_VALID_DAYS = 3650


def _spki_pin(cert) -> str:  # noqa: ANN001 - cryptography 지연 import
    """okhttp CertificatePinner 형식의 공개키 핀(``sha256/...``)을 만든다."""
    import base64
    import hashlib

    from cryptography.hazmat.primitives import serialization

    spki = cert.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return "sha256/" + base64.b64encode(hashlib.sha256(spki).digest()).decode()


def _cert_covers(cert, host: str) -> bool:  # noqa: ANN001
    """인증서의 SAN 이 이 주소를 포함하는지 확인한다."""
    import ipaddress

    from cryptography import x509

    try:
        san = cert.extensions.get_extension_for_class(
            x509.SubjectAlternativeName
        ).value
    except x509.ExtensionNotFound:
        return False
    try:
        return ipaddress.ip_address(host) in san.get_values_for_type(x509.IPAddress)
    except ValueError:
        return host in san.get_values_for_type(x509.DNSName)


def ensure_cert(certfile: str | Path, host: str) -> str:
    """자체서명 인증서를 준비하고 앱에 넣을 SPKI 핀을 반환한다.

    파일이 이미 있고 ``host`` 를 포함하면 그대로 쓴다. IP 가 바뀌어 재발급이
    필요하면 **기존 개인키를 재사용**해 다시 발급하므로 앱의 핀은 유지된다.

    Args:
        certfile: 인증서+개인키를 담을 PEM 경로.
        host: 폰이 접속할 주소(보통 PC 의 LAN IP).

    Returns:
        ``sha256/<base64>`` 형식의 okhttp 핀 문자열.

    Raises:
        RuntimeError: cryptography 미설치.
    """
    try:
        import datetime
        import ipaddress

        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import NameOID
    except ImportError as exc:  # pragma: no cover - 설치 안내 경로
        raise RuntimeError(
            "TLS 인증서 생성에는 cryptography 가 필요합니다: "
            'uv pip install "thinktank[tls]"'
        ) from exc

    path = Path(certfile).expanduser()
    key = None
    if path.exists():
        data = path.read_bytes()
        key = serialization.load_pem_private_key(data, password=None)
        existing = x509.load_pem_x509_certificate(data)
        if _cert_covers(existing, host):
            return _spki_pin(existing)
        logger.info("인증서에 %s 가 없어 재발급합니다 (개인키는 유지 = 핀 유지)", host)

    if key is None:
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "thinktank-receiver")])
    alt: list[x509.GeneralName] = [x509.DNSName("localhost")]
    alt.append(x509.IPAddress(ipaddress.ip_address("127.0.0.1")))
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        alt.append(x509.DNSName(host))
    else:
        if address not in (ipaddress.ip_address("127.0.0.1"),):
            alt.append(x509.IPAddress(address))

    now = datetime.datetime.now(datetime.UTC)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=CERT_VALID_DAYS))
        .add_extension(x509.SubjectAlternativeName(alt), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
        + cert.public_bytes(serialization.Encoding.PEM)
    )
    # 주의: 윈도우에서 os.chmod 는 읽기전용 플래그만 바꾸고 group/other 권한을
    # 없애지 못한다(실측 -rw-r--r--). 즉 이 파일은 같은 PC 의 다른 사용자에게
    # 읽힐 수 있다. 단일 사용자 PC 전제라 감수하지만, 다중 사용자 환경에 배포할
    # 때는 ACL(icacls)로 제한해야 한다.
    with contextlib.suppress(OSError):
        os.chmod(path, 0o600)
    logger.info("인증서 발급: %s (SAN=%s)", path, host)
    return _spki_pin(cert)


def serve(settings: Settings, *, host: str = DEFAULT_HOST) -> None:
    """설정으로 수신기를 띄우고 Ctrl+C 까지 블로킹한다."""
    users = load_users(settings)
    server = create_server(
        users=users,
        host=host,
        port=settings.receiver_port,
        certfile=settings.receiver_cert or None,
        apk_path=settings.receiver_apk or None,
    )
    bound_host, bound_port = server.server_address[0], server.server_address[1]
    scheme = "https" if settings.receiver_cert else "http"
    if is_multi_user(users):
        logger.info(
            "수신기 시작: %s://%s:%s (사용자 %d명: %s)",
            scheme,
            bound_host,
            bound_port,
            len(users),
            ", ".join(u.name for u in users),
        )
        for user in users:
            logger.info("  %s -> %s", user.name, user.settings.ingest_dir)
    else:
        logger.info(
            "수신기 시작: %s://%s:%s (수집 경로 %s)",
            scheme,
            bound_host,
            bound_port,
            settings.ingest_dir,
        )
    if scheme == "http":
        logger.warning(
            "평문 HTTP 입니다 — 녹음과 토큰이 WiFi 에 암호화 없이 흐릅니다. "
            "앱이 피닝을 지원하면 RECEIVER_CERT 로 TLS 를 켜세요."
        )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("수신기 종료")
    finally:
        server.shutdown()
        server.server_close()


def main() -> None:
    """`python -m thinktank.receiver` 진입점."""
    parser = argparse.ArgumentParser(description="thinktank LAN 수신기")
    parser.add_argument("--host", default=DEFAULT_HOST, help="바인딩 주소")
    parser.add_argument("--port", type=int, default=None, help="바인딩 포트")
    parser.add_argument(
        "--make-cert",
        metavar="PEM",
        help="자체서명 인증서를 준비하고 앱에 넣을 공개키 핀을 출력한 뒤 종료",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s"
    )
    if args.make_cert:
        pin = ensure_cert(args.make_cert, args.host)
        print(f"인증서: {args.make_cert}")
        print(f"앱에 넣을 공개키 핀: {pin}")
        return

    settings = load_settings()
    if args.port is not None:
        settings = replace(settings, receiver_port=args.port)
    serve(settings, host=args.host)


if __name__ == "__main__":
    main()
