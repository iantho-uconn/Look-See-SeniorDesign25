"""
LookSee Snapshot Cluster Mappings Lambda

Creates an immutable, versioned copy of the current READY working mappings.

Expected input:
{
  "trainingRunId": "uuid-or-run-id",
  "mappingVersion": "optional; defaults to trainingRunId"
}

Environment variables:
- CLUSTER_MAPPINGS_TABLE=LookSeeClusterMappings
- PUBLISHED_CLUSTER_MAPPINGS_TABLE=LookSeePublishedClusterMappings
- CLUSTER_STATE_LANDMARK_ID=__CLUSTER_STATE__

Published table key:
- partition key: mappingVersion (String)
- sort key: landmarkId (String)
"""

from __future__ import annotations

import math
import os
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any

import boto3


AWS_REGION = os.environ.get("AWS_REGION", "us-east-1")
CLUSTER_MAPPINGS_TABLE = os.environ.get(
    "CLUSTER_MAPPINGS_TABLE",
    "LookSeeClusterMappings",
)
PUBLISHED_CLUSTER_MAPPINGS_TABLE = os.environ.get(
    "PUBLISHED_CLUSTER_MAPPINGS_TABLE",
    "LookSeePublishedClusterMappings",
)
CLUSTER_STATE_LANDMARK_ID = os.environ.get(
    "CLUSTER_STATE_LANDMARK_ID",
    "__CLUSTER_STATE__",
)
SNAPSHOT_METADATA_LANDMARK_ID = "__METADATA__"

dynamodb = boto3.resource("dynamodb", region_name=AWS_REGION)
working_table = dynamodb.Table(CLUSTER_MAPPINGS_TABLE)
published_table = dynamodb.Table(PUBLISHED_CLUSTER_MAPPINGS_TABLE)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def require_nonempty_string(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field_name} must be a non-empty string")
    return value.strip()


def normalize_cluster_id(raw_value: Any) -> str:
    if raw_value is None or isinstance(raw_value, bool):
        raise ValueError("clusterId is missing or invalid")

    if isinstance(raw_value, Decimal):
        if raw_value == raw_value.to_integral_value():
            return str(int(raw_value))
        return format(raw_value, "f")

    if isinstance(raw_value, int):
        return str(raw_value)

    if isinstance(raw_value, float):
        if not math.isfinite(raw_value):
            raise ValueError("clusterId must be finite")
        return str(int(raw_value)) if raw_value.is_integer() else str(raw_value)

    value = str(raw_value).strip()
    for prefix in ("cluster-", "cluster_"):
        if value.lower().startswith(prefix):
            value = value[len(prefix):]
            break

    if not value:
        raise ValueError("clusterId is empty")

    return value


def read_working_state() -> dict[str, Any]:
    response = working_table.get_item(
        Key={"landmarkId": CLUSTER_STATE_LANDMARK_ID},
        ConsistentRead=True,
    )
    item = response.get("Item")

    if not item:
        raise RuntimeError(
            "Working mapping state item is missing. Initialize "
            f"{CLUSTER_STATE_LANDMARK_ID} before snapshotting."
        )

    if item.get("status") != "READY":
        raise RuntimeError(
            "Cluster mappings are not snapshot-safe; "
            f"current status is {item.get('status')!r}."
        )

    revision = item.get("revision")
    if not isinstance(revision, str) or not revision.strip():
        raise RuntimeError("Working mapping state has no published revision.")

    return item


def scan_working_mappings() -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    kwargs: dict[str, Any] = {
        "ConsistentRead": True,
        "ProjectionExpression": "landmarkId, clusterId",
    }

    while True:
        response = working_table.scan(**kwargs)

        for item in response.get("Items", []):
            if item.get("landmarkId") == CLUSTER_STATE_LANDMARK_ID:
                continue
            items.append(item)

        last_key = response.get("LastEvaluatedKey")
        if not last_key:
            break

        kwargs["ExclusiveStartKey"] = last_key

    return items


def get_existing_metadata(mapping_version: str) -> dict[str, Any] | None:
    response = published_table.get_item(
        Key={
            "mappingVersion": mapping_version,
            "landmarkId": SNAPSHOT_METADATA_LANDMARK_ID,
        },
        ConsistentRead=True,
    )
    return response.get("Item")


def snapshot_handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    training_run_id = require_nonempty_string(
        event.get("trainingRunId"),
        "trainingRunId",
    )
    mapping_version = require_nonempty_string(
        event.get("mappingVersion") or training_run_id,
        "mappingVersion",
    )

    existing_metadata = get_existing_metadata(mapping_version)
    if existing_metadata and existing_metadata.get("status") == "READY":
        if existing_metadata.get("trainingRunId") != training_run_id:
            raise RuntimeError(
                "mappingVersion already belongs to a different trainingRunId"
            )

        return {
            "ok": True,
            "idempotent": True,
            "mappingVersion": mapping_version,
            "trainingRunId": training_run_id,
            "sourceRevision": existing_metadata["sourceRevision"],
            "mappingCount": int(existing_metadata.get("mappingCount", 0)),
            "status": "READY",
        }

    before = read_working_state()
    source_revision = before["revision"]
    snapshot_started_at = utc_now()

    if existing_metadata:
        existing_revision = existing_metadata.get("sourceRevision")
        if existing_revision and existing_revision != source_revision:
            raise RuntimeError(
                "A partial snapshot exists for this mappingVersion but was "
                "built from a different working revision. Use a new "
                "mappingVersion or clean up the failed partition."
            )

    published_table.put_item(
        Item={
            "mappingVersion": mapping_version,
            "landmarkId": SNAPSHOT_METADATA_LANDMARK_ID,
            "recordType": "SNAPSHOT_METADATA",
            "status": "BUILDING",
            "trainingRunId": training_run_id,
            "sourceRevision": source_revision,
            "snapshotStartedAt": snapshot_started_at,
        }
    )

    source_items = scan_working_mappings()

    # A strongly consistent Scan still is not a table-wide snapshot.
    # The READY/revision check prevents us from publishing if the clustering
    # writer changed state during the scan.
    after = read_working_state()

    if after["revision"] != source_revision:
        raise RuntimeError(
            "Cluster mapping revision changed during snapshot. "
            "No snapshot was published; retry with a new training run."
        )

    published_at = utc_now()
    normalized_items: list[dict[str, Any]] = []

    for item in source_items:
        landmark_id = require_nonempty_string(
            item.get("landmarkId"),
            "landmarkId",
        )
        cluster_id = normalize_cluster_id(item.get("clusterId"))

        normalized_items.append(
            {
                "mappingVersion": mapping_version,
                "landmarkId": landmark_id,
                "recordType": "LANDMARK_MAPPING",
                "clusterId": cluster_id,
                "trainingRunId": training_run_id,
                "sourceRevision": source_revision,
                "publishedAt": published_at,
            }
        )

    # Table.batch_writer buffers writes and retries unprocessed items.
    with published_table.batch_writer(
        overwrite_by_pkeys=["mappingVersion", "landmarkId"]
    ) as batch:
        for item in normalized_items:
            batch.put_item(Item=item)

    published_table.put_item(
        Item={
            "mappingVersion": mapping_version,
            "landmarkId": SNAPSHOT_METADATA_LANDMARK_ID,
            "recordType": "SNAPSHOT_METADATA",
            "status": "READY",
            "trainingRunId": training_run_id,
            "sourceRevision": source_revision,
            "mappingCount": len(normalized_items),
            "snapshotStartedAt": snapshot_started_at,
            "publishedAt": published_at,
        }
    )

    return {
        "ok": True,
        "idempotent": False,
        "mappingVersion": mapping_version,
        "trainingRunId": training_run_id,
        "sourceRevision": source_revision,
        "mappingCount": len(normalized_items),
        "status": "READY",
    }


lambda_handler = snapshot_handler
