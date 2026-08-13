"""Stateless Receiver V1 Cloud API for Cloud Run.

The Android upload body is streamed into the synchronous Google Cloud Storage
adapter through a bounded in-memory queue.  No request body is written to the
container filesystem or accumulated in full.
"""

from __future__ import annotations

import asyncio
import json
import logging
import signal
import time
import uuid
from collections.abc import Awaitable, Callable, Mapping
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol
from urllib.parse import quote

from google.api_core.exceptions import (
    DeadlineExceeded,
    GoogleAPICallError,
    ServiceUnavailable,
    TooManyRequests,
)
from hypercorn.asyncio import serve
from hypercorn.config import Config as HypercornConfig
from sqlalchemy.exc import OperationalError, TimeoutError as PoolTimeoutError
from starlette.applications import Starlette
from starlette.exceptions import HTTPException
from starlette.requests import ClientDisconnect, Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from airvoice.receiver_v1 import V1Error, etag_for, normalize_sha256
from airvoice.server.auth import CloudPrincipal
from airvoice.server.contracts import (
    MAX_V1_JSON_BYTES,
    NOTE_FOLDERS,
    UPLOAD_MEDIA_TYPES,
    UPLOAD_STATUS_ALREADY_EXISTS,
    UPLOAD_STATUS_CREATED,
    is_safe_leaf_name,
)
from airvoice.server.ports import ReceiverV1Persistence
from airvoice.server.runtime import (
    CloudApiSettings,
    CloudRuntime,
    build_cloud_runtime,
)
from airvoice.server.stream_bridge import stream_to_sync

logger = logging.getLogger("airvoice.cloud_api")


class CloudAuthenticator(Protocol):
    def authenticate(self, authorization: str | None) -> CloudPrincipal:
        """Resolve a bearer header or raise a V1 error."""


class ReadinessProbe(Protocol):
    def ping(self) -> None:
        """Raise when the backing database is unavailable."""


class Closable(Protocol):
    def close(self) -> None:
        """Release process-level resources."""


@dataclass
class CloudApiServices:
    receiver: ReceiverV1Persistence
    authenticator: CloudAuthenticator
    readiness: ReadinessProbe
    closer: Closable | None = None

    @classmethod
    def from_runtime(cls, runtime: CloudRuntime) -> CloudApiServices:
        return cls(
            receiver=runtime.receiver,
            authenticator=runtime.authenticator,
            readiness=runtime.data,
            closer=runtime,
        )


def _request_id(headers: Mapping[str, str]) -> str:
    supplied = headers.get("x-request-id", "").strip()
    if (
        not supplied
        or len(supplied) > 128
        or any(ord(character) < 33 or ord(character) > 126 for character in supplied)
    ):
        return str(uuid.uuid4())
    return supplied


def _trace_id(headers: Mapping[str, str]) -> str | None:
    candidate = headers.get("x-cloud-trace-context", "").partition("/")[0].strip()
    if len(candidate) == 32 and all(
        character in "0123456789abcdefABCDEF" for character in candidate
    ):
        return candidate.lower()
    return None


def _state(request: Request) -> dict[str, Any]:
    return request.scope.setdefault("state", {})


def _json(
    request: Request,
    payload: dict[str, object] | list[object],
    *,
    status: int = 200,
    headers: Mapping[str, str] | None = None,
) -> JSONResponse:
    if isinstance(payload, dict) and "requestId" not in payload:
        payload = {**payload, "requestId": _state(request)["request_id"]}
    return JSONResponse(payload, status_code=status, headers=dict(headers or {}))


def _error_response(request: Request, error: V1Error) -> JSONResponse:
    _state(request)["error_code"] = error.code
    headers: dict[str, str] = {}
    if error.status == 401:
        headers["WWW-Authenticate"] = "Bearer"
    if error.status in {429, 503, 504}:
        headers["Retry-After"] = "5"
    return _json(
        request,
        {"error": {"code": error.code, "message": error.message}},
        status=error.status,
        headers=headers,
    )


def _mapped_error(exc: BaseException) -> V1Error:
    if isinstance(exc, V1Error):
        return exc
    if isinstance(exc, ClientDisconnect):
        return V1Error(
            400,
            "CLIENT_DISCONNECTED",
            "Client disconnected before the request completed",
        )
    if isinstance(exc, (asyncio.TimeoutError, DeadlineExceeded)):
        return V1Error(
            504,
            "REQUEST_TIMEOUT",
            "The request did not complete before its deadline",
        )
    if isinstance(exc, TooManyRequests):
        return V1Error(
            429,
            "SERVICE_BUSY",
            "The service is temporarily busy",
        )
    if isinstance(
        exc,
        (PoolTimeoutError, OperationalError, ServiceUnavailable),
    ):
        return V1Error(
            503,
            "SERVICE_UNAVAILABLE",
            "A required service is temporarily unavailable",
        )
    if isinstance(exc, GoogleAPICallError):
        return V1Error(
            503,
            "STORAGE_UNAVAILABLE",
            "Object storage is temporarily unavailable",
        )
    return V1Error(
        500,
        "INTERNAL_ERROR",
        "The request could not be completed",
    )


def _content_length(request: Request, *, maximum: int) -> int:
    raw = request.headers.get("content-length")
    if raw is None:
        raise V1Error(411, "CONTENT_LENGTH_REQUIRED", "Content-Length is required")
    try:
        length = int(raw)
    except ValueError as exc:
        raise V1Error(
            400,
            "INVALID_CONTENT_LENGTH",
            "Content-Length must be an integer",
        ) from exc
    if length < 0:
        raise V1Error(
            400,
            "INVALID_CONTENT_LENGTH",
            "Content-Length cannot be negative",
        )
    if length > maximum:
        raise V1Error(413, "UPLOAD_TOO_LARGE", "Request body is too large")
    return length


async def _json_body(request: Request) -> dict[str, object]:
    media_type = request.headers.get("content-type", "").partition(";")[0].strip()
    if media_type.lower() != "application/json":
        raise V1Error(
            415,
            "UNSUPPORTED_MEDIA_TYPE",
            "Content-Type must be application/json",
        )
    length = _content_length(request, maximum=MAX_V1_JSON_BYTES)
    body = await request.body()
    if len(body) != length:
        raise V1Error(
            400,
            "CONTENT_LENGTH_MISMATCH",
            "Request body does not match Content-Length",
        )
    try:
        value = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise V1Error(
            400,
            "INVALID_JSON",
            "Request body must be valid JSON",
        ) from exc
    if not isinstance(value, dict):
        raise V1Error(400, "INVALID_JSON", "JSON body must be an object")
    return value


def _uuid_header(request: Request, name: str, required_code: str) -> str:
    value = request.headers.get(name, "").strip()
    if not value:
        raise V1Error(400, required_code, f"{name} is required")
    try:
        return str(uuid.UUID(value))
    except ValueError as exc:
        code = f"INVALID_{required_code.removesuffix('_REQUIRED')}"
        raise V1Error(400, code, f"{name} must be a UUID") from exc


def _note_id(value: str) -> str:
    try:
        return str(uuid.UUID(value))
    except ValueError as exc:
        raise V1Error(400, "INVALID_NOTE_ID", "noteId must be a UUID") from exc


class RequestContextMiddleware:
    """Apply deadlines, correlation headers, and one redacted JSON access log."""

    def __init__(
        self,
        app: Any,
        *,
        timeout_seconds: int,
        google_cloud_project: str | None,
    ) -> None:
        self.app = app
        self.timeout_seconds = timeout_seconds
        self.google_cloud_project = google_cloud_project

    async def __call__(self, scope, receive, send) -> None:  # noqa: ANN001
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = {
            key.decode("latin-1").lower(): value.decode("latin-1")
            for key, value in scope.get("headers", ())
        }
        state = scope.setdefault("state", {})
        state["request_id"] = _request_id(headers)
        trace_id = _trace_id(headers)
        if trace_id is not None:
            state["trace_id"] = trace_id
        started = False
        status = 500
        started_at = time.monotonic()

        async def correlated_send(message) -> None:  # noqa: ANN001
            nonlocal started, status
            if message["type"] == "http.response.start":
                started = True
                status = int(message["status"])
                response_headers = list(message.get("headers", ()))
                if not any(
                    key.lower() == b"x-request-id" for key, _ in response_headers
                ):
                    response_headers.append(
                        (b"x-request-id", state["request_id"].encode("ascii"))
                    )
                message["headers"] = response_headers
            await send(message)

        try:
            await asyncio.wait_for(
                self.app(scope, receive, correlated_send),
                timeout=self.timeout_seconds,
            )
        except asyncio.TimeoutError as exc:
            if started:
                raise
            request = Request(scope, receive=receive)
            response = _error_response(request, _mapped_error(exc))
            status = response.status_code
            await response(scope, receive, correlated_send)
        finally:
            event: dict[str, object] = {
                "severity": "INFO" if status < 500 else "ERROR",
                "event": "http_request",
                "requestId": state["request_id"],
                "method": scope["method"],
                "path": scope["path"],
                "status": status,
                "durationMs": round((time.monotonic() - started_at) * 1000, 3),
            }
            for source, target in (
                ("user_id", "userId"),
                ("error_code", "errorCode"),
            ):
                if source in state:
                    event[target] = state[source]
            if trace_id is not None:
                event["traceId"] = trace_id
                if self.google_cloud_project:
                    event["logging.googleapis.com/trace"] = (
                        f"projects/{self.google_cloud_project}/traces/{trace_id}"
                    )
            logger.log(
                logging.INFO if status < 500 else logging.ERROR,
                json.dumps(event, ensure_ascii=False, separators=(",", ":")),
            )


def create_cloud_app(
    services: CloudApiServices,
    *,
    settings: CloudApiSettings,
) -> Any:
    async def authorize(
        request: Request,
        supplied_user: str | None = None,
    ) -> CloudPrincipal:
        principal = await asyncio.to_thread(
            services.authenticator.authenticate,
            request.headers.get("authorization"),
        )
        _state(request)["user_id"] = principal.name
        if supplied_user is not None and supplied_user != principal.name:
            raise V1Error(
                403,
                "USER_PATH_MISMATCH",
                "Authenticated user does not match the request path",
            )
        return principal

    async def guarded(
        request: Request,
        operation: Callable[[], Awaitable[Response]],
    ) -> Response:
        try:
            return await operation()
        except BaseException as exc:
            if isinstance(exc, (KeyboardInterrupt, SystemExit, asyncio.CancelledError)):
                raise
            mapped = _mapped_error(exc)
            if mapped.status == 500:
                logger.error(
                    json.dumps(
                        {
                            "severity": "ERROR",
                            "event": "unhandled_exception",
                            "requestId": _state(request)["request_id"],
                            "exceptionType": type(exc).__name__,
                        },
                        separators=(",", ":"),
                    )
                )
            return _error_response(request, mapped)

    async def health(request: Request) -> Response:
        async def operation() -> Response:
            return _json(request, {"status": "ok", "apiVersion": "v1"})

        return await guarded(request, operation)

    async def ready(request: Request) -> Response:
        async def operation() -> Response:
            await asyncio.to_thread(services.readiness.ping)
            return _json(request, {"status": "ready", "apiVersion": "v1"})

        return await guarded(request, operation)

    async def upload(request: Request) -> Response:
        async def operation() -> Response:
            supplied_user = request.path_params["user_id"]
            principal = await authorize(request, supplied_user)
            filename = request.path_params["filename"]
            if len(filename) > 255 or not is_safe_leaf_name(filename):
                raise V1Error(400, "INVALID_FILENAME", "Filename is invalid")
            extension = Path(filename).suffix.lower()
            expected_type = UPLOAD_MEDIA_TYPES.get(extension)
            if expected_type is None:
                raise V1Error(
                    400,
                    "UNSUPPORTED_AUDIO",
                    "Audio extension is not supported",
                )
            media_type = (
                request.headers.get("content-type", "").partition(";")[0].strip().lower()
            )
            if media_type != expected_type:
                raise V1Error(
                    415,
                    "UNSUPPORTED_MEDIA_TYPE",
                    f"Content-Type must be {expected_type}",
                )
            length = _content_length(request, maximum=settings.max_upload_bytes)
            if length == 0:
                raise V1Error(
                    400,
                    "EMPTY_UPLOAD",
                    "Audio upload cannot be empty",
                )
            declared_hash = normalize_sha256(
                request.headers.get("x-content-sha256")
                or request.headers.get("x-sha256")
                or request.headers.get("digest")
            )
            if declared_hash is None:
                raise V1Error(
                    400,
                    "CONTENT_SHA256_REQUIRED",
                    "X-Content-SHA256 is required",
                )
            idempotency_key = _uuid_header(
                request,
                "Idempotency-Key",
                "IDEMPOTENCY_KEY_REQUIRED",
            )
            recording_id = _uuid_header(
                request,
                "X-Recording-ID",
                "RECORDING_ID_REQUIRED",
            )
            chunk_id = _uuid_header(
                request,
                "X-Chunk-ID",
                "CHUNK_ID_REQUIRED",
            )
            await asyncio.to_thread(
                services.receiver.ensure_upload_capacity,
                principal,
                length,
            )

            def consume(source) -> tuple[Any, bool]:  # noqa: ANN001
                return services.receiver.receive_upload(
                    user=principal,
                    filename=filename,
                    length=length,
                    declared_sha256=declared_hash,
                    idempotency_key=idempotency_key,
                    recording_id=recording_id,
                    chunk_id=chunk_id,
                    source=source,
                )

            (receipt, created), peak_bytes = await stream_to_sync(
                request.stream(),
                length=length,
                consume=consume,
                queue_chunks=settings.stream_queue_chunks,
            )
            _state(request)["stream_peak_bytes"] = peak_bytes
            payload = {
                **receipt.as_dict(),
                "status": (
                    UPLOAD_STATUS_CREATED
                    if created
                    else UPLOAD_STATUS_ALREADY_EXISTS
                ),
            }
            return _json(request, payload, status=201 if created else 200)

        return await guarded(request, operation)

    async def list_notes(request: Request) -> Response:
        async def operation() -> Response:
            principal = await authorize(request, request.path_params["user_id"])
            notes = await asyncio.to_thread(
                services.receiver.list_notes,
                principal,
                [],
            )
            return _json(request, {"notes": notes})

        return await guarded(request, operation)

    async def get_note(request: Request) -> Response:
        async def operation() -> Response:
            principal = await authorize(request, request.path_params["user_id"])
            note = await asyncio.to_thread(
                services.receiver.get_note,
                principal,
                _note_id(request.path_params["note_id"]),
            )
            return _json(
                request,
                note,
                headers={"ETag": etag_for(str(note["revision"]))},
            )

        return await guarded(request, operation)

    async def create_note(request: Request) -> Response:
        async def operation() -> Response:
            principal = await authorize(request, request.path_params["user_id"])
            body = await _json_body(request)
            folder = body.get("folder")
            name = body.get("name")
            content = body.get("content", "")
            if not all(isinstance(value, str) for value in (folder, name, content)):
                raise V1Error(
                    400,
                    "INVALID_NOTE",
                    "folder, name and content must be strings",
                )
            assert isinstance(folder, str)
            assert isinstance(name, str)
            assert isinstance(content, str)
            if folder not in NOTE_FOLDERS:
                raise V1Error(
                    400,
                    "INVALID_NOTE_FOLDER",
                    "folder is not exposed to the mobile client",
                )
            if (
                len(name) > 255
                or not name.endswith(".md")
                or not is_safe_leaf_name(name)
            ):
                raise V1Error(400, "INVALID_NOTE_NAME", "Note name is invalid")
            note = await asyncio.to_thread(
                services.receiver.create_note,
                principal,
                folder=folder,
                name=name,
                content=content,
            )
            location = (
                f"/api/v1/notes/{quote(principal.name, safe='')}/{note['id']}"
            )
            return _json(
                request,
                note,
                status=201,
                headers={
                    "ETag": etag_for(str(note["revision"])),
                    "Location": location,
                },
            )

        return await guarded(request, operation)

    async def update_note(request: Request) -> Response:
        async def operation() -> Response:
            principal = await authorize(request, request.path_params["user_id"])
            body = await _json_body(request)
            content = body.get("content")
            if not isinstance(content, str):
                raise V1Error(
                    400,
                    "INVALID_NOTE_CONTENT",
                    "content must be a string",
                )
            note = await asyncio.to_thread(
                services.receiver.update_note,
                principal,
                _note_id(request.path_params["note_id"]),
                content=content,
                if_match=request.headers.get("if-match"),
            )
            return _json(
                request,
                note,
                headers={"ETag": etag_for(str(note["revision"]))},
            )

        return await guarded(request, operation)

    async def delete_note(request: Request) -> Response:
        async def operation() -> Response:
            principal = await authorize(request, request.path_params["user_id"])
            archived = await asyncio.to_thread(
                services.receiver.archive_note,
                principal,
                _note_id(request.path_params["note_id"]),
                if_match=request.headers.get("if-match"),
                archive_dir="90-archive",
            )
            return _json(request, archived)

        return await guarded(request, operation)

    async def apk_unavailable(request: Request) -> Response:
        async def operation() -> Response:
            await authorize(request)
            raise V1Error(
                404,
                "APK_NOT_FOUND",
                "APK is not configured for the cloud service",
            )

        return await guarded(request, operation)

    async def http_error(request: Request, exc: HTTPException) -> Response:
        if exc.status_code == 405:
            error = V1Error(
                405,
                "METHOD_NOT_ALLOWED",
                "HTTP method is not allowed for this endpoint",
            )
        else:
            error = V1Error(404, "NOT_FOUND", "Endpoint does not exist")
        return _error_response(request, error)

    @asynccontextmanager
    async def lifespan(_: Starlette):
        try:
            yield
        finally:
            if services.closer is not None:
                await asyncio.to_thread(services.closer.close)

    application = Starlette(
        debug=False,
        lifespan=lifespan,
        routes=[
            Route("/api/v1/health", health, methods=["GET"]),
            Route("/api/v1/ready", ready, methods=["GET"]),
            Route(
                "/api/v1/upload/{user_id}/{filename}",
                upload,
                methods=["PUT"],
            ),
            Route(
                "/api/v1/notes/{user_id}",
                list_notes,
                methods=["GET"],
            ),
            Route(
                "/api/v1/notes/{user_id}",
                create_note,
                methods=["POST"],
            ),
            Route(
                "/api/v1/notes/{user_id}/{note_id}",
                get_note,
                methods=["GET"],
            ),
            Route(
                "/api/v1/notes/{user_id}/{note_id}",
                update_note,
                methods=["PUT"],
            ),
            Route(
                "/api/v1/notes/{user_id}/{note_id}",
                delete_note,
                methods=["DELETE"],
            ),
            Route("/api/v1/apk/info", apk_unavailable, methods=["GET"]),
            Route("/api/v1/apk", apk_unavailable, methods=["GET"]),
        ],
        exception_handlers={HTTPException: http_error},
    )
    return RequestContextMiddleware(
        application,
        timeout_seconds=settings.request_timeout_seconds,
        google_cloud_project=settings.google_cloud_project,
    )


def build_hypercorn_config(settings: CloudApiSettings) -> HypercornConfig:
    """Build an h2c-capable configuration with no container TLS material."""
    config = HypercornConfig()
    config.bind = [f"0.0.0.0:{settings.port}"]
    config.certfile = None
    config.keyfile = None
    config.graceful_timeout = settings.graceful_timeout_seconds
    config.shutdown_timeout = settings.graceful_timeout_seconds
    config.include_server_header = False
    config.accesslog = None
    config.errorlog = "-"
    config.h2_max_concurrent_streams = 100
    return config


def configure_json_logging() -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger.handlers.clear()
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    logger.propagate = False


async def serve_cloud_api(
    *,
    settings: CloudApiSettings | None = None,
) -> None:
    active_settings = settings or CloudApiSettings.from_env()
    runtime = build_cloud_runtime(active_settings)
    application = create_cloud_app(
        CloudApiServices.from_runtime(runtime),
        settings=active_settings,
    )
    shutdown_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for handled_signal in (signal.SIGTERM, signal.SIGINT):
        try:
            loop.add_signal_handler(handled_signal, shutdown_event.set)
        except NotImplementedError:
            signal.signal(
                handled_signal,
                lambda *_: loop.call_soon_threadsafe(shutdown_event.set),
            )
    await serve(
        application,
        build_hypercorn_config(active_settings),
        shutdown_trigger=shutdown_event.wait,
    )


def main() -> None:
    configure_json_logging()
    asyncio.run(serve_cloud_api())


if __name__ == "__main__":
    main()
