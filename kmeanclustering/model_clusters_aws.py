import os
import math
from datetime import datetime, timezone

import boto3
import numpy as np
from sklearn.cluster import KMeans
from boto3.dynamodb.conditions import Attr

# -----------------------------
# DynamoDB Setup
# -----------------------------
dynamodb = boto3.resource("dynamodb")

LANDMARKS_TABLE_NAME = os.environ.get("LANDMARKS_TABLE", "LookSeeLandmarks")
MAPPINGS_TABLE_NAME = os.environ.get("MAPPINGS_TABLE", "LookSeeClusterMappings")
MAX_CLUSTER_SIZE = int(os.environ.get("MAX_CLUSTER_SIZE", "50"))

landmarks_table = dynamodb.Table(LANDMARKS_TABLE_NAME)
mappings_table = dynamodb.Table(MAPPINGS_TABLE_NAME)


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def new_clustering_run_id() -> str:
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"cluster-run-{ts}"


def load_all_landmarks():
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

    coords = []
    landmark_ids = []

    for item in items:
        landmark_id = item.get("landmarkId")
        lat = item.get("latitude")
        lon = item.get("longitude")

        if not landmark_id or lat is None or lon is None:
            continue

        try:
            coords.append([float(lat), float(lon)])
            landmark_ids.append(landmark_id)
        except (TypeError, ValueError):
            print(f"Skipping landmark with invalid coordinates: {landmark_id}")

    if not coords:
        return np.empty((0, 2)), []

    return np.array(coords, dtype=float), landmark_ids


def cluster_all_points(points, max_size):
    total = len(points)

    if total == 0:
        return np.array([], dtype=int), 0

    if total <= max_size:
        labels = np.zeros(total, dtype=int)
        return labels, 1

    k = math.ceil(total / max_size)
    print(f"Running full KMeans with total={total}, K={k}, max_size={max_size}")

    kmeans = KMeans(n_clusters=k, random_state=42, n_init=10)
    labels = kmeans.fit_predict(points)

    return labels, k


def get_existing_mapping(landmark_id: str):
    response = mappings_table.get_item(Key={"landmarkId": landmark_id})
    return response.get("Item")


def upsert_mapping(landmark_id: str, cluster_id: int, clustering_run_id: str):
    existing = get_existing_mapping(landmark_id)
    previous_cluster_id = existing.get("clusterId") if existing else None

    if previous_cluster_id is None:
        is_dirty = True
    else:
        try:
            is_dirty = int(previous_cluster_id) != int(cluster_id)
        except (TypeError, ValueError):
            is_dirty = True

    item = {
        "landmarkId": landmark_id,
        "clusterId": int(cluster_id),
        "clusteringRunId": clustering_run_id,
        "assignmentUpdatedAt": utc_now_iso(),
        "isDirtyForTraining": is_dirty,
        "lastModelVersionId": existing.get("lastModelVersionId", "") if existing else "",
        "lastTrainingRunId": existing.get("lastTrainingRunId", "") if existing else "",
    }

    mappings_table.put_item(Item=item)

    print(
        f"Upserted mapping: landmarkId={landmark_id}, "
        f"clusterId={cluster_id}, dirty={is_dirty}"
    )


def run_full_clustering():
    coords, landmark_ids = load_all_landmarks()

    if len(landmark_ids) == 0:
        print("No active landmarks found. Nothing to cluster.")
        return {
            "clusteringRunId": None,
            "clusterCount": 0,
            "landmarkCount": 0,
        }

    clustering_run_id = new_clustering_run_id()
    labels, cluster_count = cluster_all_points(coords, MAX_CLUSTER_SIZE)

    for landmark_id, label in zip(landmark_ids, labels):
        upsert_mapping(
            landmark_id=landmark_id,
            cluster_id=int(label),
            clustering_run_id=clustering_run_id,
        )

    print(
        f"Finished clustering run {clustering_run_id}: "
        f"{len(landmark_ids)} landmarks, {cluster_count} clusters"
    )

    return {
        "clusteringRunId": clustering_run_id,
        "clusterCount": int(cluster_count),
        "landmarkCount": len(landmark_ids),
    }


if __name__ == "__main__":
    result = run_full_clustering()
    print(result)