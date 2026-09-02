"""
LookSee full landmark clustering ECS worker.

The worker cooperates with the DynamoDB control record:

{
  "landmarkId": "__CLUSTER_STATE__",
  "status": "READY|QUEUED|UPDATING|ERROR",
  "revision": "<last fully completed mapping revision>",
  "activeRunId": "<ECS launch id while queued/running>",
  "rerunRequested": true|false,
  "leaseExpiresAt": 1234567890
}

The snapshot Lambda may publish mappings only while this record is READY.
"""

from __future__ import annotations

import math
import os
import signal
import time
import uuid
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any

import boto3
import numpy as np
from boto3.dynamodb.conditions import Attr
from botocore.exceptions import ClientError
from sklearn.cluster import KMeans


# -----------------------------
# Configuration and DynamoDB
# -----------------------------
AWS_REGION = os.environ.get("AWS_REGION", "us-east-1")

LANDMARKS_TABLE_NAME = os.environ.get(
    "LANDMARKS_TABLE",
    "LookSeeLandmarks",
)
MAPPINGS_TABLE_NAME = os.environ.get(
    "MAPPINGS_TABLE",
    "LookSeeClusterMappings",
)
MAX_CLUSTER_SIZE = int(os.environ.get("MAX_CLUSTER_SIZE", "50"))

CLUSTER_STATE_LANDMARK_ID = os.environ.get(
    "CLUSTER_STATE_LANDMARK_ID",
    "__CLUSTER_STATE__",
)
CLUSTERING_RUN_ID = os.environ.get("CLUSTERING_RUN_ID", "").strip()
CLUSTER_LOCK_LEASE_SECONDS = int(
    os.environ.get("CLUSTER_LOCK_LEASE_SECONDS", "1800")
)

dynamodb = boto3.resource("dynamodb", region_name=AWS_REGION)
landmarks_table = dynamodb.Table(LANDMARKS_TABLE_NAME)
mappings_table = dynamodb.Table(MAPPINGS_TABLE_NAME)


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def new_mapping_revision(pass_number: int) -> str:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return (
        f"cluster-revision-{timestamp}-"
        f"p{pass_number}-{uuid.uuid4().hex[:8]}"
    )


def is_conditional_failure(error: ClientError) -> bool:
    return (
        error.response.get("Error", {}).get("Code")
        == "ConditionalCheckFailedException"
    )


def claim_queued_run(run_id: str) -> bool:
    """
    Transition this exact ECS launch from QUEUED to UPDATING.
    """
    now_epoch = int(time.time())
    now_iso = utc_now_iso()

    try:
        mappings_table.update_item(
            Key={"landmarkId": CLUSTER_STATE_LANDMARK_ID},
            UpdateExpression=(
                "SET #status = :updating, "
                "startedAt = :now_iso, "
                "updatedAt = :now_iso, "
                "leaseExpiresAt = :lease_expires "
                "REMOVE queuedAtEpoch"
            ),
            ConditionExpression=(
                "#status = :queued AND activeRunId = :run_id"
            ),
            ExpressionAttributeNames={
                "#status": "status",
            },
            ExpressionAttributeValues={
                ":queued": "QUEUED",
                ":updating": "UPDATING",
                ":run_id": run_id,
                ":now_iso": now_iso,
                ":lease_expires": (
                    now_epoch + CLUSTER_LOCK_LEASE_SECONDS
                ),
            },
        )
        return True
    except ClientError as error:
        if is_conditional_failure(error):
            return False
        raise


def extend_lease(run_id: str) -> None:
    now_epoch = int(time.time())
    mappings_table.update_item(
        Key={"landmarkId": CLUSTER_STATE_LANDMARK_ID},
        UpdateExpression=(
            "SET leaseExpiresAt = :lease_expires, "
            "updatedAt = :now_iso"
        ),
        ConditionExpression=(
            "#status = :updating AND activeRunId = :run_id"
        ),
        ExpressionAttributeNames={
            "#status": "status",
        },
        ExpressionAttributeValues={
            ":updating": "UPDATING",
            ":run_id": run_id,
            ":lease_expires": (
                now_epoch + CLUSTER_LOCK_LEASE_SECONDS
            ),
            ":now_iso": utc_now_iso(),
        },
    )


def load_all_landmarks() -> tuple[np.ndarray, list[str]]:
    response = landmarks_table.scan(
        FilterExpression=Attr("isActive").eq(True)
    )
    items = response.get("Items", [])

    while "LastEvaluatedKey" in response:
        response = landmarks_table.scan(
            FilterExpression=Attr("isActive").eq(True),
            ExclusiveStartKey=response["LastEvaluatedKey"],
        )
        items.extend(response.get("Items", []))

    coords: list[list[float]] = []
    landmark_ids: list[str] = []

    for item in items:
        landmark_id = item.get("landmarkId")
        lat = item.get("latitude")
        lon = item.get("longitude")

        if not landmark_id or lat is None or lon is None:
            print(
                "Skipping active landmark with missing identity/coordinates: "
                f"{landmark_id!r}"
            )
            continue

        try:
            coords.append([float(lat), float(lon)])
            landmark_ids.append(str(landmark_id))
        except (TypeError, ValueError):
            print(
                "Skipping landmark with invalid coordinates: "
                f"{landmark_id}"
            )

    if not coords:
        return np.empty((0, 2)), []

    return np.array(coords, dtype=float), landmark_ids


def cluster_all_points(
    points: np.ndarray,
    max_size: int,
) -> tuple[np.ndarray, int]:
    total = len(points)

    if total == 0:
        return np.array([], dtype=int), 0

    if total <= max_size:
        return np.zeros(total, dtype=int), 1

    cluster_count = math.ceil(total / max_size)
    print(
        "Running full KMeans with "
        f"total={total}, K={cluster_count}, "
        f"target_max_size={max_size}"
    )

    kmeans = KMeans(
        n_clusters=cluster_count,
        random_state=42,
        n_init=10,
    )
    labels = kmeans.fit_predict(points)

    return labels, cluster_count


def scan_existing_mappings() -> dict[str, dict[str, Any]]:
    existing: dict[str, dict[str, Any]] = {}
    scan_kwargs: dict[str, Any] = {
        "ConsistentRead": True,
    }

    while True:
        response = mappings_table.scan(**scan_kwargs)

        for item in response.get("Items", []):
            landmark_id = item.get("landmarkId")

            if landmark_id == CLUSTER_STATE_LANDMARK_ID:
                continue

            if (
                isinstance(landmark_id, str)
                and landmark_id.startswith("__")
            ):
                print(
                    f"Skipping reserved mappings-table item: {landmark_id}"
                )
                continue

            if not landmark_id:
                print(
                    "Ignoring malformed mapping item without landmarkId: "
                    f"{item}"
                )
                continue

            existing[str(landmark_id)] = item

        last_key = response.get("LastEvaluatedKey")
        if not last_key:
            break

        scan_kwargs["ExclusiveStartKey"] = last_key

    return existing


def cluster_id_changed(
    previous_cluster_id: Any,
    new_cluster_id: int,
) -> bool:
    if previous_cluster_id is None:
        return True

    try:
        return int(previous_cluster_id) != int(new_cluster_id)
    except (TypeError, ValueError):
        return True


def preserve_dirty_flag(
    existing: dict[str, Any] | None,
    new_cluster_id: int,
) -> bool:
    if not existing:
        return True

    already_dirty = existing.get("isDirtyForTraining", False)

    if isinstance(already_dirty, Decimal):
        already_dirty = already_dirty != 0

    return bool(already_dirty) or cluster_id_changed(
        existing.get("clusterId"),
        new_cluster_id,
    )


def build_mapping_items(
    landmark_ids: list[str],
    labels: np.ndarray,
    existing_mappings: dict[str, dict[str, Any]],
    mapping_revision: str,
) -> list[dict[str, Any]]:
    assignment_time = utc_now_iso()
    mapping_items: list[dict[str, Any]] = []

    for landmark_id, label in zip(landmark_ids, labels):
        cluster_id = int(label)
        existing = existing_mappings.get(landmark_id)

        mapping_items.append(
            {
                "landmarkId": landmark_id,
                "clusterId": cluster_id,
                "clusteringRunId": mapping_revision,
                "mappingRevision": mapping_revision,
                "assignmentUpdatedAt": assignment_time,
                "isDirtyForTraining": preserve_dirty_flag(
                    existing,
                    cluster_id,
                ),
                "lastModelVersionId": (
                    existing.get("lastModelVersionId", "")
                    if existing
                    else ""
                ),
                "lastTrainingRunId": (
                    existing.get("lastTrainingRunId", "")
                    if existing
                    else ""
                ),
            }
        )

    return mapping_items


def write_complete_mapping_set(
    mapping_items: list[dict[str, Any]],
    existing_mappings: dict[str, dict[str, Any]],
) -> int:
    """
    Replace current active mappings and delete stale/inactive mappings.

    The state remains UPDATING until all batch writes finish.
    """
    active_landmark_ids = {
        item["landmarkId"]
        for item in mapping_items
    }
    stale_landmark_ids = (
        set(existing_mappings.keys()) - active_landmark_ids
    )

    with mappings_table.batch_writer(
        overwrite_by_pkeys=["landmarkId"]
    ) as batch:
        for item in mapping_items:
            batch.put_item(Item=item)

        for landmark_id in sorted(stale_landmark_ids):
            batch.delete_item(
                Key={"landmarkId": landmark_id}
            )

    for item in mapping_items:
        print(
            "Upserted mapping: "
            f"landmarkId={item['landmarkId']}, "
            f"clusterId={item['clusterId']}, "
            f"dirty={item['isDirtyForTraining']}"
        )

    for landmark_id in sorted(stale_landmark_ids):
        print(f"Deleted stale mapping: landmarkId={landmark_id}")

    return len(stale_landmark_ids)


def run_clustering_pass(
    run_id: str,
    pass_number: int,
) -> dict[str, Any]:
    extend_lease(run_id)

    coords, landmark_ids = load_all_landmarks()
    labels, cluster_count = cluster_all_points(
        coords,
        MAX_CLUSTER_SIZE,
    )

    extend_lease(run_id)

    existing_mappings = scan_existing_mappings()
    mapping_revision = new_mapping_revision(pass_number)

    mapping_items = build_mapping_items(
        landmark_ids=landmark_ids,
        labels=labels,
        existing_mappings=existing_mappings,
        mapping_revision=mapping_revision,
    )

    stale_mapping_count = write_complete_mapping_set(
        mapping_items=mapping_items,
        existing_mappings=existing_mappings,
    )

    print(
        f"Finished clustering pass {pass_number}: "
        f"revision={mapping_revision}, "
        f"{len(landmark_ids)} landmarks, "
        f"{cluster_count} clusters, "
        f"{stale_mapping_count} stale mappings removed"
    )

    return {
        "mappingRevision": mapping_revision,
        "clusterCount": int(cluster_count),
        "landmarkCount": len(landmark_ids),
        "staleMappingCount": stale_mapping_count,
        "passNumber": pass_number,
    }


def finish_if_quiet(
    run_id: str,
    pass_result: dict[str, Any],
) -> bool:
    """
    Atomically publish READY only if no new trigger requested another pass.

    Returns True when the run is complete. Returns False when the current ECS
    task should immediately perform another full clustering pass.
    """
    now_iso = utc_now_iso()

    try:
        mappings_table.update_item(
            Key={"landmarkId": CLUSTER_STATE_LANDMARK_ID},
            UpdateExpression=(
                "SET #status = :ready, "
                "revision = :revision, "
                "lastClusteringRunId = :run_id, "
                "lastClusterCount = :cluster_count, "
                "lastLandmarkCount = :landmark_count, "
                "lastStaleMappingCount = :stale_count, "
                "completedAt = :now_iso, "
                "updatedAt = :now_iso "
                "REMOVE activeRunId, leaseExpiresAt, "
                "rerunRequested, rerunRequestedAt"
            ),
            ConditionExpression=(
                "#status = :updating "
                "AND activeRunId = :run_id "
                "AND ("
                "attribute_not_exists(rerunRequested) "
                "OR rerunRequested = :false"
                ")"
            ),
            ExpressionAttributeNames={
                "#status": "status",
            },
            ExpressionAttributeValues={
                ":ready": "READY",
                ":updating": "UPDATING",
                ":run_id": run_id,
                ":revision": pass_result["mappingRevision"],
                ":cluster_count": pass_result["clusterCount"],
                ":landmark_count": pass_result["landmarkCount"],
                ":stale_count": pass_result["staleMappingCount"],
                ":now_iso": now_iso,
                ":false": False,
            },
        )
        return True
    except ClientError as error:
        if not is_conditional_failure(error):
            raise

    response = mappings_table.get_item(
        Key={"landmarkId": CLUSTER_STATE_LANDMARK_ID},
        ConsistentRead=True,
    )
    state = response.get("Item", {})

    if (
        state.get("status") != "UPDATING"
        or state.get("activeRunId") != run_id
    ):
        raise RuntimeError(
            "Lost ownership of the clustering state while completing. "
            f"Current state: {state}"
        )

    if not bool(state.get("rerunRequested")):
        raise RuntimeError(
            "Completion condition failed without a rerun request. "
            f"Current state: {state}"
        )

    mappings_table.update_item(
        Key={"landmarkId": CLUSTER_STATE_LANDMARK_ID},
        UpdateExpression=(
            "SET rerunRequested = :false, "
            "leaseExpiresAt = :lease_expires, "
            "updatedAt = :now_iso "
            "REMOVE rerunRequestedAt"
        ),
        ConditionExpression=(
            "#status = :updating "
            "AND activeRunId = :run_id "
            "AND rerunRequested = :true"
        ),
        ExpressionAttributeNames={
            "#status": "status",
        },
        ExpressionAttributeValues={
            ":updating": "UPDATING",
            ":run_id": run_id,
            ":true": True,
            ":false": False,
            ":lease_expires": (
                int(time.time()) + CLUSTER_LOCK_LEASE_SECONDS
            ),
            ":now_iso": utc_now_iso(),
        },
    )

    return False


def mark_run_failed(run_id: str, error: BaseException) -> None:
    try:
        mappings_table.update_item(
            Key={"landmarkId": CLUSTER_STATE_LANDMARK_ID},
            UpdateExpression=(
                "SET #status = :error, "
                "failedAt = :now_iso, "
                "updatedAt = :now_iso, "
                "lastError = :last_error "
                "REMOVE activeRunId, leaseExpiresAt"
            ),
            ConditionExpression=(
                "#status IN (:queued, :updating) "
                "AND activeRunId = :run_id"
            ),
            ExpressionAttributeNames={
                "#status": "status",
            },
            ExpressionAttributeValues={
                ":queued": "QUEUED",
                ":updating": "UPDATING",
                ":error": "ERROR",
                ":run_id": run_id,
                ":now_iso": utc_now_iso(),
                ":last_error": str(error)[:1000],
            },
        )
    except ClientError as state_error:
        if not is_conditional_failure(state_error):
            print(
                "Failed to mark clustering state ERROR: "
                f"{state_error}"
            )


class TerminationRequested(RuntimeError):
    pass


def handle_termination_signal(
    signum: int,
    frame: Any,
) -> None:
    raise TerminationRequested(
        f"Container received termination signal {signum}"
    )


def main() -> dict[str, Any]:
    if not CLUSTERING_RUN_ID:
        raise RuntimeError(
            "CLUSTERING_RUN_ID was not provided by the trigger Lambda."
        )

    if not claim_queued_run(CLUSTERING_RUN_ID):
        print(
            "This ECS task no longer owns the queued clustering run. "
            "Another task may have replaced a stale queue entry. Exiting."
        )
        return {
            "started": False,
            "reason": "queue_not_owned",
            "runId": CLUSTERING_RUN_ID,
        }

    pass_number = 0
    last_result: dict[str, Any] = {}

    try:
        while True:
            pass_number += 1
            last_result = run_clustering_pass(
                run_id=CLUSTERING_RUN_ID,
                pass_number=pass_number,
            )

            if finish_if_quiet(
                CLUSTERING_RUN_ID,
                last_result,
            ):
                break

            print(
                "Another landmark INSERT arrived during clustering. "
                "Running one more coalesced pass."
            )

    except BaseException as error:
        mark_run_failed(CLUSTERING_RUN_ID, error)
        raise

    result = {
        "started": True,
        "runId": CLUSTERING_RUN_ID,
        "passes": pass_number,
        **last_result,
    }
    print(result)
    return result


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, handle_termination_signal)
    signal.signal(signal.SIGINT, handle_termination_signal)
    main()
