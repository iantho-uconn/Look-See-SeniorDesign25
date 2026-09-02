"""
Helpers for the Lambda/job that rewrites LookSeeClusterMappings.

Protocol:
1. Call begin_mapping_update() before changing any landmark mappings.
2. Rewrite all mapping items.
3. Call complete_mapping_update(revision) only after every write succeeds.
4. Call fail_mapping_update(revision, error) if the rewrite fails.

The snapshot Lambda refuses to snapshot while status != READY and verifies
that the revision remains unchanged before and after its scan.
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from typing import Any

import boto3
from botocore.exceptions import ClientError


AWS_REGION = os.environ.get("AWS_REGION", "us-east-1")
CLUSTER_MAPPINGS_TABLE = os.environ.get(
    "CLUSTER_MAPPINGS_TABLE",
    "LookSeeClusterMappings",
)
CLUSTER_STATE_LANDMARK_ID = os.environ.get(
    "CLUSTER_STATE_LANDMARK_ID",
    "__CLUSTER_STATE__",
)

dynamodb = boto3.resource("dynamodb", region_name=AWS_REGION)
mapping_table = dynamodb.Table(CLUSTER_MAPPINGS_TABLE)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def begin_mapping_update() -> str:
    """
    Acquire the working-mapping update lock and return the new revision.

    A second clustering job cannot begin while another job is UPDATING.
    """
    revision = str(uuid.uuid4())

    mapping_table.update_item(
        Key={"landmarkId": CLUSTER_STATE_LANDMARK_ID},
        UpdateExpression=(
            "SET #status = :updating, "
            "pendingRevision = :revision, "
            "updatedAt = :updated_at"
        ),
        ConditionExpression=(
            "attribute_not_exists(#status) OR #status IN (:ready, :error)"
        ),
        ExpressionAttributeNames={
            "#status": "status",
        },
        ExpressionAttributeValues={
            ":updating": "UPDATING",
            ":ready": "READY",
            ":error": "ERROR",
            ":revision": revision,
            ":updated_at": utc_now(),
        },
    )

    return revision


def complete_mapping_update(revision: str) -> None:
    """
    Publish the completed working revision after all mapping writes succeed.
    """
    mapping_table.update_item(
        Key={"landmarkId": CLUSTER_STATE_LANDMARK_ID},
        UpdateExpression=(
            "SET #status = :ready, "
            "revision = :revision, "
            "updatedAt = :updated_at "
            "REMOVE pendingRevision, lastError"
        ),
        ConditionExpression=(
            "#status = :updating AND pendingRevision = :revision"
        ),
        ExpressionAttributeNames={
            "#status": "status",
        },
        ExpressionAttributeValues={
            ":ready": "READY",
            ":updating": "UPDATING",
            ":revision": revision,
            ":updated_at": utc_now(),
        },
    )


def fail_mapping_update(revision: str, error: Any) -> None:
    """
    Mark the current rewrite as failed without falsely publishing its revision.
    """
    try:
        mapping_table.update_item(
            Key={"landmarkId": CLUSTER_STATE_LANDMARK_ID},
            UpdateExpression=(
                "SET #status = :error, "
                "lastError = :last_error, "
                "updatedAt = :updated_at "
                "REMOVE pendingRevision"
            ),
            ConditionExpression=(
                "#status = :updating AND pendingRevision = :revision"
            ),
            ExpressionAttributeNames={
                "#status": "status",
            },
            ExpressionAttributeValues={
                ":error": "ERROR",
                ":updating": "UPDATING",
                ":revision": revision,
                ":last_error": str(error)[:1000],
                ":updated_at": utc_now(),
            },
        )
    except ClientError:
        # Preserve the original clustering exception.
        pass
