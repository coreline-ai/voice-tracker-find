"""Deterministic Cloud Tasks publication for PostgreSQL outbox events."""

from __future__ import annotations

import json
from typing import Any

from google.api_core.exceptions import AlreadyExists
from google.cloud import tasks_v2

from thinktank.server.ports import OutboxRecord


def deterministic_task_name(queue_path: str, event_id: str) -> str:
    """Return one stable Cloud Tasks resource name per outbox event."""
    return f"{queue_path}/tasks/outbox-{event_id}"


class CloudTasksOutboxPublisher:
    """Publish an outbox event once; duplicate create is equivalent to success."""

    def __init__(
        self,
        *,
        client: Any,
        queue_path: str,
        worker_url: str,
        service_account_email: str,
        audience: str | None = None,
    ) -> None:
        self.client = client
        self.queue_path = queue_path.rstrip("/")
        self.worker_url = worker_url
        self.service_account_email = service_account_email
        self.audience = audience or worker_url

    def publish(self, event: OutboxRecord) -> str:
        task_name = deterministic_task_name(self.queue_path, event.event_id)
        body = json.dumps(
            {
                "eventId": event.event_id,
                "eventKey": event.event_key,
                "eventType": event.event_type,
                "payload": event.payload,
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode()
        task = {
            "name": task_name,
            "http_request": {
                "http_method": tasks_v2.HttpMethod.POST,
                "url": self.worker_url,
                "headers": {"Content-Type": "application/json"},
                "body": body,
                "oidc_token": {
                    "service_account_email": self.service_account_email,
                    "audience": self.audience,
                },
            },
        }
        try:
            self.client.create_task(parent=self.queue_path, task=task)
        except AlreadyExists:
            pass
        return task_name
