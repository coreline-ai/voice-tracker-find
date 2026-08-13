# @TASK PA-T1 - LAN 수신기 (모드1: 같은 WiFi 에서 폰 -> PC 직접 전송)
# @SPEC docs/HANDOFF.md#3-개발-계획
# @TEST tests/test_receiver.py
from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request
from collections.abc import Iterator
from dataclasses import dataclass
from http.server import ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import quote

import pytest

import airvoice.receiver as receiver_mod
from airvoice.receiver import _Handler, _is_safe_name, create_server, ensure_cert


def _make_user(name: str, token: str, ingest_dir: Path, vault: Path):
    """테스트용 User (경로만 다르고 나머지 설정은 기본값)."""
    from dataclasses import replace as _replace

    from airvoice.config import Settings
    from airvoice.users import User

    settings = Settings(
        claude_api_key="test-key",
        ingest_dir=ingest_dir,
        obsidian_vault=vault,
        db_path=ingest_dir.parent / f"{name or 'default'}.db",
        temp_dir=ingest_dir.parent / f"{name or 'default'}-temp",
        whisper_model="large-v3",
        vad_sample_rate=16000,
        vad_threshold=0.5,
        retention_days=7,
    )
    return User(name=name, token=token, settings=_replace(settings))


TOKEN = "test-token"
USER = "user1"


@dataclass(frozen=True)
class Response:
    """테스트에서 다루기 쉬운 형태의 HTTP 응답."""

    status: int
    body: bytes

    def json(self) -> object:
        return json.loads(self.body.decode("utf-8"))

    def text(self) -> str:
        return self.body.decode("utf-8")


@dataclass(frozen=True)
class Fixture:
    """수신기 + 관련 경로 묶음."""

    base_url: str
    ingest_dir: Path
    vault: Path


def _request(
    url: str,
    *,
    method: str = "GET",
    data: bytes | None = None,
    token: str | None = TOKEN,
) -> Response:
    request = urllib.request.Request(url, data=data, method=method)  # noqa: S310
    if token is not None:
        request.add_header("Authorization", f"Bearer {token}")
    if data is not None:
        request.add_header("Content-Length", str(len(data)))
    try:
        with urllib.request.urlopen(request, timeout=10) as response:  # noqa: S310
            return Response(response.status, response.read())
    except urllib.error.HTTPError as exc:
        return Response(exc.code, exc.read())


@pytest.fixture
def receiver(tmp_path: Path) -> Iterator[Fixture]:
    """127.0.0.1 임시 포트에 실제로 뜬 수신기."""
    ingest_dir = tmp_path / "inbox"
    vault = tmp_path / "vault"
    (vault / "10-daily").mkdir(parents=True)
    (vault / "30-ideas").mkdir(parents=True)

    server: ThreadingHTTPServer = create_server(
        users=[_make_user("", TOKEN, ingest_dir, vault)],
        host="127.0.0.1",
        port=0,
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address[0], server.server_address[1]
    try:
        yield Fixture(f"http://{host}:{port}", ingest_dir, vault)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def _upload_url(fixture: Fixture, filename: str) -> str:
    return f"{fixture.base_url}/upload/{USER}/{quote(filename)}"


# --- 기동 -------------------------------------------------------------


def test_빈_토큰이면_서버가_뜨지_않는다(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="토큰이 없습니다"):
        create_server(
            users=[_make_user("", "   ", tmp_path / "inbox", tmp_path / "vault")],
            host="127.0.0.1",
            port=0,
        )


def test_수집_폴더가_없으면_생성한다(tmp_path: Path) -> None:
    ingest_dir = tmp_path / "nested" / "inbox"
    server = create_server(
        users=[_make_user("", TOKEN, ingest_dir, tmp_path / "vault")],
        host="127.0.0.1",
        port=0,
    )
    server.server_close()
    assert ingest_dir.is_dir()


# --- 업로드 -----------------------------------------------------------


def test_업로드하면_수집_폴더에_저장된다(receiver: Fixture) -> None:
    audio = b"fake-m4a-bytes" * 1000

    response = _request(
        _upload_url(receiver, "rec_20260719_101500.m4a"), method="PUT", data=audio
    )

    assert response.status == 201
    saved = receiver.ingest_dir / "rec_20260719_101500.m4a"
    assert saved.read_bytes() == audio


def test_업로드_후_임시파일이_남지_않는다(receiver: Fixture) -> None:
    _request(_upload_url(receiver, "rec.m4a"), method="PUT", data=b"bytes")

    leftovers = [path.name for path in receiver.ingest_dir.iterdir()]
    assert leftovers == ["rec.m4a"]


def test_한글_파일명도_업로드된다(receiver: Fixture) -> None:
    filename = "음성 260719_155512.m4a"

    response = _request(_upload_url(receiver, filename), method="PUT", data=b"bytes")

    assert response.status == 201
    assert (receiver.ingest_dir / filename).is_file()


def test_토큰이_없으면_업로드가_거부된다(receiver: Fixture) -> None:
    response = _request(
        _upload_url(receiver, "rec.m4a"), method="PUT", data=b"x", token=None
    )

    assert response.status == 401
    assert not (receiver.ingest_dir / "rec.m4a").exists()


def test_틀린_토큰이면_업로드가_거부된다(receiver: Fixture) -> None:
    response = _request(
        _upload_url(receiver, "rec.m4a"), method="PUT", data=b"x", token="wrong"
    )

    assert response.status == 401


def test_경로_탈출_파일명은_거부된다(receiver: Fixture) -> None:
    response = _request(
        f"{receiver.base_url}/upload/{USER}/{quote('../evil.m4a', safe='')}",
        method="PUT",
        data=b"x",
    )

    assert response.status == 400
    assert not (receiver.ingest_dir.parent / "evil.m4a").exists()


def test_오디오가_아닌_확장자는_거부된다(receiver: Fixture) -> None:
    response = _request(_upload_url(receiver, "note.md"), method="PUT", data=b"x")

    assert response.status == 400


@pytest.mark.parametrize(
    ("label", "name"),
    [
        ("ADS(콜론)", "rec.m4a:hidden.m4a"),
        ("예약어 CON", "CON.m4a"),
        ("예약어 NUL", "NUL.m4a"),
        ("예약어 COM1", "COM1.m4a"),
        ("후행 점", "rec.m4a."),
        ("후행 공백", "rec.m4a "),
        ("선행 점", ".rec.m4a"),
        ("제어문자", "rec\x08.m4a"),
        ("개행", "rec\n.m4a"),
        ("백슬래시", "a\\b.m4a"),
        ("드라이브 상대경로", "C:rec.m4a"),
    ],
)
def test_윈도우_위험_파일명은_거부된다(label: str, name: str) -> None:
    assert not _is_safe_name(name), f"{label}: {name!r} 이 통과했다"


def test_정상_파일명은_통과한다() -> None:
    assert _is_safe_name("rec_20260719_101500.m4a")
    assert _is_safe_name("음성 260719_101500.m4a")


def test_ADS_이름은_업로드가_거부되고_파일도_안_생긴다(receiver: Fixture) -> None:
    response = _request(
        f"{receiver.base_url}/upload/{USER}/{quote('rec.m4a:hidden.m4a', safe='')}",
        method="PUT",
        data=b"x",
    )

    assert response.status == 400
    assert list(receiver.ingest_dir.iterdir()) == []


def test_디스크_여유가_없으면_507(receiver: Fixture, monkeypatch) -> None:  # noqa: ANN001
    monkeypatch.setattr(
        "airvoice.receiver._has_room_for", lambda directory, incoming: False
    )

    response = _request(_upload_url(receiver, "rec.m4a"), method="PUT", data=b"x")

    assert response.status == 507
    assert not (receiver.ingest_dir / "rec.m4a").exists()


def test_소켓_타임아웃이_설정되어_있다() -> None:
    # None 이면 느린 연결이 스레드를 무기한 점유한다.
    assert _Handler.timeout is not None
    assert _Handler.timeout > 0


def test_같은_파일을_다시_올리면_덮어쓰지_않는다(receiver: Fixture) -> None:
    url = _upload_url(receiver, "rec.m4a")
    _request(url, method="PUT", data=b"original")

    response = _request(url, method="PUT", data=b"replaced")

    assert response.status == 200
    assert (receiver.ingest_dir / "rec.m4a").read_bytes() == b"original"


# --- 업로드 후 파이프라인 자동 트리거 --------------------------------------


def test_업로드가_파이프라인_처리를_예약한다(receiver: Fixture, monkeypatch) -> None:  # noqa: ANN001
    scheduled: list[str] = []
    monkeypatch.setattr(
        receiver_mod, "_schedule_process", lambda server, name: scheduled.append(name)
    )

    _request(_upload_url(receiver, "rec.m4a"), method="PUT", data=b"audio")

    assert scheduled == [""]  # 단일 사용자 픽스처의 이름은 빈 문자열


def test_이미_업로드된_파일은_재예약하지_않는다(receiver: Fixture, monkeypatch) -> None:  # noqa: ANN001
    scheduled: list[str] = []
    monkeypatch.setattr(
        receiver_mod, "_schedule_process", lambda server, name: scheduled.append(name)
    )
    url = _upload_url(receiver, "rec.m4a")
    _request(url, method="PUT", data=b"audio")

    _request(url, method="PUT", data=b"audio")  # 두 번째는 "이미 업로드됨"

    assert scheduled == [""]  # 예약은 첫 업로드 한 번뿐


def test_처리_예약은_디바운스로_한_번만_돌린다(monkeypatch) -> None:  # noqa: ANN001
    spawned: list[str] = []
    monkeypatch.setattr(receiver_mod, "_spawn_pipeline", spawned.append)
    monkeypatch.setattr(receiver_mod, "PROCESS_DEBOUNCE_SECONDS", 0.05)
    server = SimpleNamespace(
        auto_process=True, process_lock=threading.Lock(), process_timers={}
    )

    receiver_mod._schedule_process(server, "me")
    receiver_mod._schedule_process(server, "me")  # 앞 타이머를 취소하고 다시 예약
    time.sleep(0.25)

    assert spawned == ["me"]  # 버스트라도 한 번만


def test_자동처리_꺼지면_예약하지_않는다(monkeypatch) -> None:  # noqa: ANN001
    spawned: list[str] = []
    monkeypatch.setattr(receiver_mod, "_spawn_pipeline", spawned.append)
    monkeypatch.setattr(receiver_mod, "PROCESS_DEBOUNCE_SECONDS", 0.05)
    server = SimpleNamespace(
        auto_process=False, process_lock=threading.Lock(), process_timers={}
    )

    receiver_mod._schedule_process(server, "me")
    time.sleep(0.15)

    assert spawned == []


# --- 노트 -------------------------------------------------------------


def test_노트_목록을_이름과_본문으로_돌려준다(receiver: Fixture) -> None:
    (receiver.vault / "10-daily" / "2026-07-19_중요.md").write_text(
        "# 오늘\n내용", encoding="utf-8"
    )
    (receiver.vault / "30-ideas" / "창발-1.md").write_text("아이디어", encoding="utf-8")

    response = _request(f"{receiver.base_url}/notes/{USER}")

    assert response.status == 200
    notes = response.json()
    assert {note["name"] for note in notes} == {"2026-07-19_중요.md", "창발-1.md"}
    by_name = {note["name"]: note["content"] for note in notes}
    assert by_name["2026-07-19_중요.md"] == "# 오늘\n내용"


def test_노트마다_소속_폴더를_알려준다(receiver: Fixture) -> None:
    # 앱이 폴더 트리를 그리려면 각 노트의 폴더가 필요하다.
    _write(receiver.vault / "10-daily" / "2026-07-19_중요.md")
    _write(receiver.vault / "1 wiki" / "재테크.md")

    notes = _request(f"{receiver.base_url}/notes/{USER}").json()

    folder_of = {note["name"]: note["folder"] for note in notes}
    assert folder_of["2026-07-19_중요.md"] == "10-daily"
    assert folder_of["재테크.md"] == "1 wiki"


def test_클러스터_허브를_내려받는다(receiver: Fixture) -> None:
    # 주제 정규화 산출물(1 wiki 허브)이 폰의 진입점이다.
    _write(receiver.vault / "1 wiki" / "재테크.md", "# 재테크")

    notes = _request(f"{receiver.base_url}/notes/{USER}").json()

    hub = next(note for note in notes if note["name"] == "재테크.md")
    assert hub["folder"] == "1 wiki"


def test_노트가_없으면_빈_목록(receiver: Fixture) -> None:
    response = _request(f"{receiver.base_url}/notes/{USER}")

    assert response.status == 200
    assert response.json() == []


def test_토큰이_없으면_노트를_못_받는다(receiver: Fixture) -> None:
    response = _request(f"{receiver.base_url}/notes/{USER}", token=None)

    assert response.status == 401


def test_편집한_노트를_되돌려_저장한다(receiver: Fixture) -> None:
    note = receiver.vault / "10-daily" / "2026-07-19.md"
    note.write_text("원본", encoding="utf-8")

    response = _request(
        f"{receiver.base_url}/notes/{USER}/{quote('2026-07-19.md')}",
        method="PUT",
        data="수정됨".encode(),
    )

    assert response.status == 200
    assert note.read_text(encoding="utf-8") == "수정됨"


def test_없는_노트에_쓰면_404(receiver: Fixture) -> None:
    response = _request(
        f"{receiver.base_url}/notes/{USER}/{quote('없는노트.md')}",
        method="PUT",
        data=b"x",
    )

    assert response.status == 404


def test_볼트_밖으로_쓰려는_노트는_거부된다(receiver: Fixture) -> None:
    response = _request(
        f"{receiver.base_url}/notes/{USER}/{quote('../../evil.md', safe='')}",
        method="PUT",
        data=b"x",
    )

    assert response.status == 400


# --- 헬스체크 ---------------------------------------------------------


def test_헬스체크는_토큰_없이_된다(receiver: Fixture) -> None:
    response = _request(f"{receiver.base_url}/health", token=None)

    assert response.status == 200
    assert response.text() == "ok"


def test_모르는_경로는_404(receiver: Fixture) -> None:
    response = _request(f"{receiver.base_url}/nope")

    assert response.status == 404


# --- TLS --------------------------------------------------------------


def test_인증서_발급하면_핀을_돌려준다(tmp_path: Path) -> None:
    pin = ensure_cert(tmp_path / "cert.pem", "127.0.0.1")

    assert pin.startswith("sha256/")
    assert (tmp_path / "cert.pem").is_file()


def test_같은_주소면_인증서를_재사용한다(tmp_path: Path) -> None:
    cert = tmp_path / "cert.pem"
    first = ensure_cert(cert, "127.0.0.1")
    before = cert.read_bytes()

    second = ensure_cert(cert, "127.0.0.1")

    assert first == second
    assert cert.read_bytes() == before


def test_IP가_바뀌어_재발급해도_핀은_유지된다(tmp_path: Path) -> None:
    # DHCP 로 PC IP 가 바뀌면 인증서를 다시 발급해야 하는데, 개인키를 재사용하므로
    # 앱에 넣어둔 핀은 그대로 유효해야 한다. 아니면 IP 가 바뀔 때마다 사용자가
    # 폰 설정을 고쳐야 한다.
    cert = tmp_path / "cert.pem"
    pin_before = ensure_cert(cert, "192.168.0.10")
    body_before = cert.read_bytes()

    pin_after = ensure_cert(cert, "192.168.0.20")

    assert pin_after == pin_before, "재발급으로 핀이 바뀌면 앱 피닝이 깨진다"
    assert cert.read_bytes() != body_before, "인증서 자체는 갱신되어야 한다"


def test_TLS로_뜨면_암호화된_연결로_동작한다(tmp_path: Path) -> None:
    import ssl

    cert = tmp_path / "cert.pem"
    ensure_cert(cert, "127.0.0.1")
    ingest = tmp_path / "inbox"
    vault = tmp_path / "vault"
    vault.mkdir()

    server = create_server(
        users=[_make_user("", TOKEN, ingest, vault)],
        host="127.0.0.1",
        port=0,
        certfile=cert,
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address[0], server.server_address[1]
    try:
        context = ssl.create_default_context(cafile=str(cert))
        base = f"https://{host}:{port}"

        with urllib.request.urlopen(  # noqa: S310
            f"{base}/health", timeout=10, context=context
        ) as response:
            assert response.status == 200
            assert response.read() == b"ok"

        request = urllib.request.Request(  # noqa: S310
            f"{base}/upload/{USER}/rec.m4a", data=b"encrypted-bytes", method="PUT"
        )
        request.add_header("Authorization", f"Bearer {TOKEN}")
        with urllib.request.urlopen(  # noqa: S310
            request, timeout=10, context=context
        ) as response:
            assert response.status == 201
        assert (ingest / "rec.m4a").read_bytes() == b"encrypted-bytes"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_평문_클라이언트는_TLS_서버에_못_붙는다(tmp_path: Path) -> None:
    cert = tmp_path / "cert.pem"
    ensure_cert(cert, "127.0.0.1")
    vault = tmp_path / "vault"
    vault.mkdir()

    server = create_server(
        users=[_make_user("", TOKEN, tmp_path / "inbox", vault)],
        host="127.0.0.1",
        port=0,
        certfile=cert,
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address[0], server.server_address[1]
    try:
        # 거부 방식은 플랫폼마다 다르다(URLError / ConnectionResetError).
        # 둘 다 OSError 하위이므로 "연결이 성립하지 않는다"만 확인한다.
        with pytest.raises(OSError):
            urllib.request.urlopen(  # noqa: S310
                f"http://{host}:{port}/health", timeout=10
            )
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


# --- 노트 범위 / 삭제 -------------------------------------------------


def _write(path: Path, text: str = "내용") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def test_주제_노트는_폰에_보내지_않는다(receiver: Fixture) -> None:
    # 주제 노트(20-notes)는 볼트에만 남기고 폰엔 안 보낸다 — 허브로 접근한다.
    _write(receiver.vault / "20-notes" / "어떤-주제.md", "# 어떤 주제")
    _write(receiver.vault / "30-ideas" / "창발-1.md")

    notes = _request(f"{receiver.base_url}/notes/{USER}").json()
    names = {note["name"] for note in notes}

    assert "어떤-주제.md" not in names
    assert "창발-1.md" in names  # 다른 폴더는 정상 전송


def test_데일리는_중요_일정_구분만_보낸다(receiver: Fixture) -> None:
    for suffix in ("중요", "일정", "아이디어", "기타"):
        _write(receiver.vault / "10-daily" / f"2026-07-19_{suffix}.md")
    _write(receiver.vault / "10-daily" / "2026-07-19.md")  # 색인

    notes = _request(f"{receiver.base_url}/notes/{USER}").json()
    names = {note["name"] for note in notes}

    assert names == {"2026-07-19_중요.md", "2026-07-19_일정.md"}


def test_폴더_상한을_넘지_않는다(receiver: Fixture, monkeypatch) -> None:  # noqa: ANN001
    monkeypatch.setattr(receiver_mod, "NOTE_SPECS", [("30-ideas", 3, None)])
    for i in range(5):
        _write(receiver.vault / "30-ideas" / f"창발-{i}.md")

    notes = _request(f"{receiver.base_url}/notes/{USER}").json()

    assert len(notes) == 3


def test_노트를_삭제하면_아카이브로_옮겨진다(receiver: Fixture) -> None:
    note = _write(receiver.vault / "10-daily" / "2026-07-19.md", "본문")

    response = _request(
        f"{receiver.base_url}/notes/{USER}/{quote('2026-07-19.md')}", method="DELETE"
    )

    assert response.status == 200
    assert not note.exists(), "원본이 남아 있으면 다음 동기화에 되살아난다"
    archived = receiver.vault / "90-archive" / "2026-07-19.md"
    assert archived.read_text(encoding="utf-8") == "본문"


def test_삭제한_노트는_활성_폴더에서_사라지고_아카이브로_보인다(
    receiver: Fixture,
) -> None:
    _write(receiver.vault / "10-daily" / "2026-07-19.md")

    _request(
        f"{receiver.base_url}/notes/{USER}/{quote('2026-07-19.md')}", method="DELETE"
    )

    notes = _request(f"{receiver.base_url}/notes/{USER}").json()
    archived = [note for note in notes if note["name"] == "2026-07-19.md"]

    assert len(archived) == 1
    assert archived[0]["folder"] == "90-archive"


def test_아카이브에_같은_이름이_있으면_덮어쓰지_않는다(receiver: Fixture) -> None:
    _write(receiver.vault / "90-archive" / "2026-07-19.md", "먼저 보관된 것")
    _write(receiver.vault / "10-daily" / "2026-07-19.md", "새로 보관할 것")

    _request(
        f"{receiver.base_url}/notes/{USER}/{quote('2026-07-19.md')}", method="DELETE"
    )

    archive = receiver.vault / "90-archive"
    assert (archive / "2026-07-19.md").read_text(encoding="utf-8") == "먼저 보관된 것"
    assert (archive / "2026-07-19_2.md").read_text(encoding="utf-8") == "새로 보관할 것"


def test_없는_노트_삭제는_404(receiver: Fixture) -> None:
    response = _request(
        f"{receiver.base_url}/notes/{USER}/{quote('없는것.md')}", method="DELETE"
    )

    assert response.status == 404


def test_토큰_없이는_삭제할_수_없다(receiver: Fixture) -> None:
    note = _write(receiver.vault / "10-daily" / "2026-07-19.md")

    response = _request(
        f"{receiver.base_url}/notes/{USER}/{quote('2026-07-19.md')}",
        method="DELETE",
        token=None,
    )

    assert response.status == 401
    assert note.exists()


# --- APK 배포 --------------------------------------------------------


@pytest.fixture
def receiver_with_apk(tmp_path: Path) -> Iterator[Fixture]:
    """APK 경로가 설정된 수신기."""
    vault = tmp_path / "vault"
    (vault / "10-daily").mkdir(parents=True)
    apk = tmp_path / "ai-r-voice.apk"
    apk.write_bytes(b"PK\x03\x04fake-apk")
    apk.with_suffix(".version.txt").write_text("버전 0.1.15", encoding="utf-8")

    server = create_server(
        users=[_make_user("", TOKEN, tmp_path / "inbox", vault)],
        host="127.0.0.1",
        port=0,
        apk_path=apk,
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address[0], server.server_address[1]
    try:
        yield Fixture(f"http://{host}:{port}", tmp_path / "inbox", vault)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_apk를_내려받는다(receiver_with_apk: Fixture) -> None:
    response = _request(f"{receiver_with_apk.base_url}/apk")

    assert response.status == 200
    assert response.body == b"PK\x03\x04fake-apk"


def test_쿼리_토큰으로_apk를_받을_수_없다(receiver_with_apk: Fixture) -> None:
    response = _request(
        f"{receiver_with_apk.base_url}/apk?token={TOKEN}", token=None
    )

    assert response.status == 401

def test_토큰_없이는_apk를_못_받는다(receiver_with_apk: Fixture) -> None:
    response = _request(f"{receiver_with_apk.base_url}/apk", token=None)

    assert response.status == 401


def test_틀린_쿼리_토큰은_거부된다(receiver_with_apk: Fixture) -> None:
    response = _request(
        f"{receiver_with_apk.base_url}/apk?token=wrong", token=None
    )

    assert response.status == 401


def test_apk_정보를_보여준다(receiver_with_apk: Fixture) -> None:
    response = _request(f"{receiver_with_apk.base_url}/apk/info")

    assert response.status == 200
    assert "0.1.15" in response.text()


def test_apk가_설정되지_않으면_404(receiver: Fixture) -> None:
    response = _request(f"{receiver.base_url}/apk")

    assert response.status == 404


def test_쿼리_토큰은_다른_엔드포인트에는_안_통한다(receiver: Fixture) -> None:
    # 토큰이 주소창/기록에 남으므로 파일 다운로드에만 허용한다.
    response = _request(f"{receiver.base_url}/notes/{USER}?token={TOKEN}", token=None)

    assert response.status == 401


# --- 다중 사용자 격리 -------------------------------------------------
#
# 여기가 깨지면 한 사람의 토큰으로 다른 사람의 녹음과 노트에 접근할 수 있다.

TOKEN_B = "test-token-b"


@dataclass(frozen=True)
class TwoUsers:
    base_url: str
    a_ingest: Path
    a_vault: Path
    b_ingest: Path
    b_vault: Path


@pytest.fixture
def two_users(tmp_path: Path) -> Iterator[TwoUsers]:
    a_ingest, a_vault = tmp_path / "a-inbox", tmp_path / "a-vault"
    b_ingest, b_vault = tmp_path / "b-inbox", tmp_path / "b-vault"
    for vault in (a_vault, b_vault):
        (vault / "10-daily").mkdir(parents=True)

    server = create_server(
        users=[
            _make_user("alice", TOKEN, a_ingest, a_vault),
            _make_user("bob", TOKEN_B, b_ingest, b_vault),
        ],
        host="127.0.0.1",
        port=0,
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address[0], server.server_address[1]
    try:
        yield TwoUsers(f"http://{host}:{port}", a_ingest, a_vault, b_ingest, b_vault)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_각자의_수집_폴더로_들어간다(two_users: TwoUsers) -> None:
    _request(
        f"{two_users.base_url}/upload/alice/rec.m4a", method="PUT", data=b"alice-audio"
    )
    _request(
        f"{two_users.base_url}/upload/bob/rec.m4a",
        method="PUT",
        data=b"bob-audio",
        token=TOKEN_B,
    )

    # 같은 파일명이어도 서로 덮어쓰지 않는다 — 앱이 시각만으로 이름을 만들어
    # 두 폰이 같은 초에 청크를 시작하면 파일명이 겹친다.
    assert (two_users.a_ingest / "rec.m4a").read_bytes() == b"alice-audio"
    assert (two_users.b_ingest / "rec.m4a").read_bytes() == b"bob-audio"


def test_남의_경로로_업로드하면_거부된다(two_users: TwoUsers) -> None:
    # alice 토큰으로 bob 경로에 업로드 시도.
    response = _request(
        f"{two_users.base_url}/upload/bob/evil.m4a", method="PUT", data=b"x"
    )

    assert response.status == 401
    assert not (two_users.b_ingest / "evil.m4a").exists()
    assert not (two_users.a_ingest / "evil.m4a").exists()


def test_남의_노트는_보이지_않는다(two_users: TwoUsers) -> None:
    _write(two_users.a_vault / "30-ideas" / "alice.md", "A")
    _write(two_users.b_vault / "30-ideas" / "bob.md", "B")

    a_notes = _request(f"{two_users.base_url}/notes/alice").json()
    b_notes = _request(f"{two_users.base_url}/notes/bob", token=TOKEN_B).json()

    assert {n["name"] for n in a_notes} == {"alice.md"}
    assert {n["name"] for n in b_notes} == {"bob.md"}


def test_남의_노트_목록을_토큰으로_넘겨봐도_막힌다(two_users: TwoUsers) -> None:
    (two_users.b_vault / "10-daily" / "bob.md").write_text("B", encoding="utf-8")

    response = _request(f"{two_users.base_url}/notes/bob")  # alice 토큰

    assert response.status == 401


def test_남의_노트는_삭제할_수_없다(two_users: TwoUsers) -> None:
    note = two_users.b_vault / "10-daily" / "bob.md"
    note.write_text("B", encoding="utf-8")

    response = _request(
        f"{two_users.base_url}/notes/bob/{quote('bob.md')}", method="DELETE"
    )

    assert response.status == 401
    assert note.exists()


def test_같은_이름의_노트라도_자기_볼트만_수정된다(two_users: TwoUsers) -> None:
    a_note = two_users.a_vault / "10-daily" / "같은이름.md"
    b_note = two_users.b_vault / "10-daily" / "같은이름.md"
    a_note.write_text("A 원본", encoding="utf-8")
    b_note.write_text("B 원본", encoding="utf-8")

    _request(
        f"{two_users.base_url}/notes/alice/{quote('같은이름.md')}",
        method="PUT",
        data="A 수정".encode(),
    )

    assert a_note.read_text(encoding="utf-8") == "A 수정"
    assert b_note.read_text(encoding="utf-8") == "B 원본"
