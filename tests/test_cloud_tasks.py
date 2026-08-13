"""Cloud Tasks outbox publisher idempotency and OIDC contract tests."""

from __future__ import annotations

import json

import pytest

pytest.importorskip("google.cloud.tasks_v2")

from google.api_core.exceptions import AlreadyExists

from airvoice.adapters.cloud_tasks import (
    CloudTasksOutboxPublisher,
    deterministic_task_name,
)
from airvoice.server.ports import OutboxRecord


class FakeTasksClient:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []
        self.raise_duplicate = False

    def create_task(self, *, parent: str, task: dict[str, object]) -> None:
        self.calls.append({"parent": parent, "task": task})
        if self.raise_duplicate:
            raise AlreadyExists("task exists")


def test_outbox_task_name_and_oidc_request_are_deterministic() -> None:
    client = FakeTasksClient()
    queue = "projects/test/locations/asia-northeast3/queues/recordings"
    event = OutboxRecord(
        event_id="11111111-1111-4111-8111-111111111111",
        event_key="upload.received:upload-1",
        event_type="upload.received",
        payload={"uploadId": "upload-1"},
    )
    publisher = CloudTasksOutboxPublisher(
        client=client,
        queue_path=queue,
        worker_url="https://worker.example.com/tasks/upload",
        service_account_email="tasks@example.iam.gserviceaccount.com",
    )

    first = publisher.publish(event)
    client.raise_duplicate = True
    replay = publisher.publish(event)

    expected = deterministic_task_name(queue, event.event_id)
    assert first == replay == expected
    task = client.calls[0]["task"]
    assert isinstance(task, dict)
    assert task["name"] == expected
    request = task["http_request"]
    assert isinstance(request, dict)
    assert request["oidc_token"] == {
        "service_account_email": "tasks@example.iam.gserviceaccount.com",
        "audience": "https://worker.example.com/tasks/upload",
    }
    body = json.loads(request["body"])
    assert body["eventKey"] == event.event_key
    assert "token" not in body
