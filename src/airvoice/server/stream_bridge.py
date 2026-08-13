"""Bounded async-request to synchronous-reader bridge for object uploads."""

from __future__ import annotations

import asyncio
import io
import queue
import threading
from collections.abc import AsyncIterator, Callable
from contextlib import suppress
from dataclasses import dataclass
from typing import BinaryIO, TypeVar

from airvoice.receiver_v1 import V1Error

STREAM_CHUNK_SIZE = 256 * 1024
_END = object()
T = TypeVar("T")


@dataclass(frozen=True)
class _Abort:
    error: BaseException


class BoundedStreamReader(io.RawIOBase):
    """A blocking file reader backed by a fixed-size producer queue."""

    def __init__(self, *, queue_chunks: int) -> None:
        if queue_chunks < 1:
            raise ValueError("queue_chunks must be positive")
        self._queue: queue.Queue[bytes | _Abort | object] = queue.Queue(
            maxsize=queue_chunks
        )
        self._buffer = bytearray()
        self._eof = False
        self._consumer_done = threading.Event()
        self._metrics_lock = threading.Lock()
        self._queued_bytes = 0
        self._abort_error: BaseException | None = None
        self.peak_buffered_bytes = 0

    def readable(self) -> bool:
        return True

    @property
    def consumer_done(self) -> bool:
        return self._consumer_done.is_set()

    def mark_consumer_done(self) -> None:
        self._consumer_done.set()

    @property
    def cancelled(self) -> bool:
        with self._metrics_lock:
            return self._abort_error is not None

    def raise_if_cancelled(self) -> None:
        with self._metrics_lock:
            error = self._abort_error
        if error is not None:
            raise error

    def feed_nowait(self, chunk: bytes) -> None:
        with self._metrics_lock:
            self._queue.put_nowait(chunk)
            self._queued_bytes += len(chunk)
            self.peak_buffered_bytes = max(
                self.peak_buffered_bytes,
                self._queued_bytes + len(self._buffer),
            )

    def finish_nowait(self) -> None:
        self._queue.put_nowait(_END)

    def abort(self, error: BaseException) -> None:
        """Wake the reader even when the producer queue is currently full."""
        with self._metrics_lock:
            if self._abort_error is None:
                self._abort_error = error
        marker = _Abort(error)
        while True:
            try:
                self._queue.put_nowait(marker)
                return
            except queue.Full:
                try:
                    discarded = self._queue.get_nowait()
                except queue.Empty:
                    continue
                if isinstance(discarded, bytes):
                    with self._metrics_lock:
                        self._queued_bytes -= len(discarded)

    def read(self, size: int = -1) -> bytes:
        if size == 0:
            return b""
        try:
            while not self._eof and (size < 0 or len(self._buffer) < size):
                item = self._queue.get()
                if item is _END:
                    self._eof = True
                    break
                if isinstance(item, _Abort):
                    raise item.error
                assert isinstance(item, bytes)
                with self._metrics_lock:
                    self._queued_bytes -= len(item)
                self._buffer.extend(item)
            if size < 0 or size >= len(self._buffer):
                result = bytes(self._buffer)
                self._buffer.clear()
                return result
            result = bytes(self._buffer[:size])
            del self._buffer[:size]
            return result
        except BaseException:
            self._consumer_done.set()
            raise


async def _put(
    reader: BoundedStreamReader,
    item: bytes | None,
    worker: asyncio.Task[T],
) -> None:
    while True:
        if worker.done():
            await worker
            if item is None:
                return
            raise RuntimeError("stream consumer completed before request body")
        try:
            if item is None:
                reader.finish_nowait()
            else:
                reader.feed_nowait(item)
            return
        except queue.Full:
            await asyncio.sleep(0.001)


async def stream_to_sync(
    chunks: AsyncIterator[bytes],
    *,
    length: int,
    consume: Callable[[BinaryIO], T],
    queue_chunks: int = 4,
    chunk_size: int = STREAM_CHUNK_SIZE,
) -> tuple[T, int]:
    """Feed an async body into a sync consumer without disk or whole-body buffering.

    The returned integer is the observed peak queued byte count, useful for
    proving the configured memory bound in tests and diagnostics.
    """
    reader = BoundedStreamReader(queue_chunks=queue_chunks)

    def run_consumer() -> T:
        try:
            return consume(reader)
        finally:
            reader.mark_consumer_done()

    worker = asyncio.create_task(asyncio.to_thread(run_consumer))
    received = 0
    try:
        async for incoming in chunks:
            if not incoming:
                continue
            received += len(incoming)
            if received > length:
                raise V1Error(
                    400,
                    "CONTENT_LENGTH_MISMATCH",
                    "Request body exceeds Content-Length",
                )
            for offset in range(0, len(incoming), chunk_size):
                await _put(
                    reader,
                    bytes(incoming[offset : offset + chunk_size]),
                    worker,
                )
        await _put(reader, None, worker)
        result = await asyncio.shield(worker)
        if received != length:
            raise V1Error(
                400,
                "INCOMPLETE_BODY",
                "Upload ended before Content-Length",
            )
        return result, reader.peak_buffered_bytes
    except BaseException as exc:
        reader.abort(exc)
        if not worker.done():
            with suppress(BaseException):
                await asyncio.wait_for(asyncio.shield(worker), timeout=5)
        raise
