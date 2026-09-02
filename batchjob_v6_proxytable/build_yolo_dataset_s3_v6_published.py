import os
import json
import hashlib
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Dict, List, Optional, Tuple
from collections import defaultdict

import boto3
from boto3.dynamodb.conditions import Key

s3 = boto3.client("s3")
dynamodb = boto3.resource("dynamodb")

# ---------------------------------------------------------------------------
# Environment config
# ---------------------------------------------------------------------------

BUCKET = os.environ.get("BUCKET", "looksee-models")

# Positive class dataset root.
# Production default: dataset/
# Neg-test example: Neg-dataset/
SRC_PREFIX = os.environ.get("SRC_PREFIX", "dataset/")

# YOLO output root.
# Production default: dataset-yolo/
# Neg-test example: dataset-yolo-negtest/
DEST_PREFIX = os.environ.get("DEST_PREFIX", "dataset-yolo/")

TRAINING_RUN_ID = os.environ.get("TRAINING_RUN_ID", "")
TRAIN_SPLIT = float(os.environ.get("TRAIN_SPLIT", "0.8"))
CLEAR_DEST = os.environ.get("CLEAR_DEST", "true").lower() == "true"

# ---------------------------------------------------------------------------
# Global negatives
# ---------------------------------------------------------------------------

# Kept for backward compatibility with the old batch job.
# Old behavior:
#   NEG_PREFIX=dataset/negatives/
#   images are read from dataset/negatives/images/
INCLUDE_NEGATIVES = os.environ.get("INCLUDE_NEGATIVES", "true").lower() == "true"
NEG_PREFIX = os.environ.get("NEG_PREFIX", f"{SRC_PREFIX.rstrip('/')}/negatives/")

# New preferred explicit global negative root.
# New behavior:
#   GLOBAL_NEG_PREFIX=Neg-dataset/negatives/global/
#   images are read from Neg-dataset/negatives/global/images/
GLOBAL_NEG_PREFIX = os.environ.get(
    "GLOBAL_NEG_PREFIX",
    f"{SRC_PREFIX.rstrip('/')}/negatives/global/"
)

NEG_RATIO = float(os.environ.get("NEG_RATIO", "1.0"))
NEG_MAX = int(os.environ.get("NEG_MAX", "500"))

# If true, and no global negatives are found at GLOBAL_NEG_PREFIX/images/,
# the job will try the legacy NEG_PREFIX/images/ path.
ALLOW_LEGACY_NEGATIVE_FALLBACK = (
    os.environ.get("ALLOW_LEGACY_NEGATIVE_FALLBACK", "true").lower() == "true"
)

# ---------------------------------------------------------------------------
# Landmark-specific hard negatives
# ---------------------------------------------------------------------------

INCLUDE_HARD_NEGATIVES = (
    os.environ.get("INCLUDE_HARD_NEGATIVES", "false").lower() == "true"
)

# New hard-negative root.
# Example:
#   Neg-dataset/negatives/by-landmark/
# Then per landmark:
#   Neg-dataset/negatives/by-landmark/Painting_A/images/
#   Neg-dataset/negatives/by-landmark/Painting_A/labels/
HARD_NEG_ROOT = os.environ.get(
    "HARD_NEG_ROOT",
    f"{SRC_PREFIX.rstrip('/')}/negatives/by-landmark/"
)

# Hard negatives are selected per landmark/class.
# desired per landmark = min(HARD_NEG_MAX_PER_LANDMARK, class_positive_count * HARD_NEG_RATIO)
HARD_NEG_RATIO = float(os.environ.get("HARD_NEG_RATIO", "0.25"))
HARD_NEG_MAX_PER_LANDMARK = int(os.environ.get("HARD_NEG_MAX_PER_LANDMARK", "25"))

# ---------------------------------------------------------------------------
# Balancing (Capping and Boosting)
# ---------------------------------------------------------------------------

BALANCE_STRATEGY = os.environ.get("BALANCE_STRATEGY", "none").lower()

# CAPPING: Maximum number of images allowed per class (cuts down huge classes like Treadmills)
MAX_IMAGES_PER_CLASS = int(os.environ.get("MAX_IMAGES_PER_CLASS", "500"))

# BOOSTING: Minimum number of images per class (upsamples tiny classes by copying them)
MIN_IMAGES_PER_CLASS = int(os.environ.get("MIN_IMAGES_PER_CLASS", "400"))

# ---------------------------------------------------------------------------
# DynamoDB
# ---------------------------------------------------------------------------

LANDMARKS_TABLE = os.environ.get("LANDMARKS_TABLE", "LookSeeLandmarks")
PUBLISHED_CLUSTER_MAPPINGS_TABLE = os.environ.get(
    "PUBLISHED_CLUSTER_MAPPINGS_TABLE",
    "LookSeePublishedClusterMappings"
)

MAPPING_VERSION = os.environ.get("MAPPING_VERSION", "").strip()
SOURCE_MAPPING_REVISION = os.environ.get(
    "SOURCE_MAPPING_REVISION",
    ""
).strip()
ONLY_DIRTY = os.environ.get("ONLY_DIRTY", "false").lower() == "true"

SNAPSHOT_METADATA_LANDMARK_ID = "__METADATA__"

NEGATIVE_NAMES = {"negative", "negatives"}
IMAGE_EXTS = (".jpg", ".jpeg", ".png")
LANDMARK_MANIFEST_SCHEMA_VERSION = 2


# ---------------------------------------------------------------------------
# S3 helpers
# ---------------------------------------------------------------------------

def normalize_prefix(prefix: str) -> str:
    return f"{prefix.strip('/')}/" if prefix else ""


def list_objects(bucket: str, prefix: str) -> List[str]:
    paginator = s3.get_paginator("list_objects_v2")
    keys = []

    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            key = obj["Key"]
            # Avoid folder-marker objects.
            if not key.endswith("/"):
                keys.append(key)

    return keys


def delete_prefix(bucket: str, prefix: str):
    keys = list_objects(bucket, prefix)

    if not keys:
        print(f"No existing objects to delete under {prefix}")
        return

    print(f"Deleting existing objects under {prefix} ({len(keys)} objects)")

    for i in range(0, len(keys), 1000):
        chunk = keys[i:i + 1000]
        s3.delete_objects(
            Bucket=bucket,
            Delete={"Objects": [{"Key": k} for k in chunk]}
        )


def copy_object(bucket: str, src_key: str, dest_key: str, content_type: str = None):
    extra = {}

    if content_type:
        extra["ContentType"] = content_type
        extra["MetadataDirective"] = "REPLACE"

    s3.copy_object(
        Bucket=bucket,
        Key=dest_key,
        CopySource={"Bucket": bucket, "Key": src_key},
        **extra
    )


def read_text(bucket: str, key: str) -> str:
    obj = s3.get_object(Bucket=bucket, Key=key)
    return obj["Body"].read().decode("utf-8")


def write_text(bucket: str, key: str, text: str, content_type: str = "text/plain"):
    s3.put_object(
        Bucket=bucket,
        Key=key,
        Body=text.encode("utf-8"),
        ContentType=content_type
    )


# ---------------------------------------------------------------------------
# DynamoDB helpers
# ---------------------------------------------------------------------------

def label_to_s3_folder(label: str) -> str:
    """
    Convert a landmark label to its S3 folder name.

    This matches the current behavior from your existing batch job:
      spaces -> underscores

    Important:
    If other parts of your pipeline sanitize punctuation differently,
    this should eventually be centralized into one shared sanitizer.
    """
    return label.replace(" ", "_")


def scan_all(table, scan_kwargs: dict) -> List[dict]:
    items = []

    while True:
        response = table.scan(**scan_kwargs)
        items.extend(response.get("Items", []))

        last = response.get("LastEvaluatedKey")
        if not last:
            break

        scan_kwargs["ExclusiveStartKey"] = last

    return items


def coerce_optional_float(value, field_name: str, landmark_id: str) -> Optional[float]:
    """
    Convert a DynamoDB numeric value into a normal Python float.

    boto3 returns DynamoDB Number attributes as Decimal. Missing values are
    preserved as None so unmapped/stale landmark rows do not fail the scan.
    Required coordinate validation happens after the cluster mapping is joined.
    """
    if value is None:
        return None

    if isinstance(value, bool):
        raise ValueError(
            f"Landmark {landmark_id!r} has invalid {field_name}: {value!r}. "
            "Expected a numeric coordinate."
        )

    try:
        if isinstance(value, Decimal):
            numeric = float(value)
        else:
            numeric = float(Decimal(str(value)))
    except (InvalidOperation, TypeError, ValueError, OverflowError) as exc:
        raise ValueError(
            f"Landmark {landmark_id!r} has invalid {field_name}: {value!r}. "
            "Expected a numeric coordinate."
        ) from exc

    if numeric != numeric or numeric in (float("inf"), float("-inf")):
        raise ValueError(
            f"Landmark {landmark_id!r} has non-finite {field_name}: {value!r}."
        )

    return numeric


def validate_required_coordinates(metadata: dict) -> Tuple[float, float]:
    """
    Require valid WGS84 latitude/longitude for a mapped trainable landmark.

    Schema version 2 guarantees that every class entry can be used later for
    geographic class filtering. We intentionally fail instead of substituting
    0,0 or silently omitting a coordinate.
    """
    landmark_id = metadata.get("landmarkId", "<unknown>")
    label = metadata.get("label", "")
    raw_latitude = metadata.get("latitude")
    raw_longitude = metadata.get("longitude")

    if raw_latitude is None or raw_longitude is None:
        missing = []
        if raw_latitude is None:
            missing.append("latitude")
        if raw_longitude is None:
            missing.append("longitude")

        raise ValueError(
            "Cannot build landmark-manifest schemaVersion=2 because a mapped "
            "landmark is missing required coordinates. "
            f"landmarkId={landmark_id!r}, label={label!r}, missing={missing!r}."
        )

    latitude = coerce_optional_float(
        raw_latitude,
        field_name="latitude",
        landmark_id=landmark_id
    )
    longitude = coerce_optional_float(
        raw_longitude,
        field_name="longitude",
        landmark_id=landmark_id
    )

    # The None case was handled above, but keep this explicit for type safety.
    if latitude is None or longitude is None:
        raise ValueError(
            f"Landmark {landmark_id!r} ({label!r}) is missing required coordinates."
        )

    if not -90.0 <= latitude <= 90.0:
        raise ValueError(
            f"Landmark {landmark_id!r} ({label!r}) has latitude "
            f"outside [-90, 90]: {latitude}."
        )

    if not -180.0 <= longitude <= 180.0:
        raise ValueError(
            f"Landmark {landmark_id!r} ({label!r}) has longitude "
            f"outside [-180, 180]: {longitude}."
        )

    return latitude, longitude


def query_published_mapping_snapshot(
    mapping_version: str
) -> Tuple[List[dict], dict]:
    """
    Load one immutable published mapping partition.

    The packager intentionally never reads LookSeeClusterMappings. Every
    training run must consume the exact mappingVersion created for that run.
    """
    if not mapping_version:
        raise ValueError(
            "MAPPING_VERSION is required. The dataset packager must be "
            "started by a pipeline that first snapshots cluster mappings."
        )

    table = dynamodb.Table(PUBLISHED_CLUSTER_MAPPINGS_TABLE)
    items: List[dict] = []

    query_kwargs = {
        "KeyConditionExpression": Key("mappingVersion").eq(mapping_version),
        "ConsistentRead": True
    }

    while True:
        response = table.query(**query_kwargs)
        items.extend(response.get("Items", []))

        last = response.get("LastEvaluatedKey")
        if not last:
            break

        query_kwargs["ExclusiveStartKey"] = last

    metadata = next(
        (
            item
            for item in items
            if item.get("landmarkId") == SNAPSHOT_METADATA_LANDMARK_ID
        ),
        None
    )

    if not metadata:
        raise ValueError(
            f"Published mapping version {mapping_version!r} has no "
            f"{SNAPSHOT_METADATA_LANDMARK_ID} record."
        )

    if metadata.get("status") != "READY":
        raise ValueError(
            f"Published mapping version {mapping_version!r} is not READY; "
            f"status={metadata.get('status')!r}."
        )

    metadata_revision = str(metadata.get("sourceRevision", "")).strip()

    if not metadata_revision:
        raise ValueError(
            f"Published mapping version {mapping_version!r} has no "
            "sourceRevision."
        )

    if (
        SOURCE_MAPPING_REVISION
        and metadata_revision != SOURCE_MAPPING_REVISION
    ):
        raise ValueError(
            "Published mapping revision does not match the revision supplied "
            "by Step Functions. "
            f"expected={SOURCE_MAPPING_REVISION!r}, "
            f"actual={metadata_revision!r}."
        )

    mapping_items = [
        item
        for item in items
        if item.get("landmarkId") != SNAPSHOT_METADATA_LANDMARK_ID
        and item.get("recordType") in (None, "LANDMARK_MAPPING")
    ]

    expected_count = int(metadata.get("mappingCount", 0) or 0)

    if len(mapping_items) != expected_count:
        raise ValueError(
            "Published mapping partition is incomplete. "
            f"mappingVersion={mapping_version!r}, "
            f"metadataCount={expected_count}, "
            f"loadedCount={len(mapping_items)}."
        )

    return mapping_items, metadata


def load_cluster_metadata() -> Tuple[Dict[str, int], Dict[str, dict], dict]:
    """
    Join canonical landmark records to one immutable published mapping version.

    ONLY_DIRTY semantics:
      - Dirty state is read from the published snapshot, never from the live
        mappings table.
      - If any landmark in a cluster was dirty when the snapshot was created,
        the whole cluster is packaged because a cluster model contains every
        class assigned to that cluster.
    """
    landmarks_table = dynamodb.Table(LANDMARKS_TABLE)

    mapping_items, snapshot_metadata = query_published_mapping_snapshot(
        MAPPING_VERSION
    )

    print(
        "Loaded published mapping snapshot: "
        f"mappingVersion={MAPPING_VERSION}, "
        f"sourceRevision={snapshot_metadata.get('sourceRevision')}, "
        f"mappingCount={len(mapping_items)}"
    )

    if ONLY_DIRTY:
        missing_dirty_state = [
            item.get("landmarkId")
            for item in mapping_items
            if "isDirtyForTraining" not in item
        ]

        if missing_dirty_state:
            raise ValueError(
                "ONLY_DIRTY=true requires snapshots created by the v2 "
                "snapshot Lambda, which copies isDirtyForTraining. "
                "Create a new mappingVersion after deploying snapshot v2. "
                f"Missing dirty state for {len(missing_dirty_state)} item(s)."
            )

        dirty_cluster_ids = {
            int(item["clusterId"])
            for item in mapping_items
            if bool(item.get("isDirtyForTraining"))
        }

        print(
            "ONLY_DIRTY=true; packaging complete cluster(s) containing at "
            f"least one dirty landmark: {sorted(dirty_cluster_ids)}"
        )

        mapping_items = [
            item
            for item in mapping_items
            if int(item["clusterId"]) in dirty_cluster_ids
        ]

    print("Loading landmarks from DynamoDB...")

    landmark_items = scan_all(
        landmarks_table,
        {
            "ProjectionExpression": (
                "landmarkId, #lbl, shortDescription, latitude, longitude, "
                "isActive, createdAt, updatedAt"
            ),
            "ExpressionAttributeNames": {"#lbl": "label"}
        }
    )

    landmark_id_to_metadata: Dict[str, dict] = {}
    duplicate_folder_records: Dict[str, List[str]] = defaultdict(list)

    for item in landmark_items:
        landmark_id = item.get("landmarkId")
        label = str(item.get("label", "")).strip()
        short_description = str(item.get("shortDescription", "")).strip()

        if not landmark_id or not label:
            continue

        if label.lower() in NEGATIVE_NAMES:
            continue

        folder = label_to_s3_folder(label)

        landmark_id_to_metadata[landmark_id] = {
            "landmarkId": landmark_id,
            "label": label,
            "shortDescription": short_description,
            "latitude": item.get("latitude"),
            "longitude": item.get("longitude"),
            "folderName": folder,
            "isActive": item.get("isActive"),
            "createdAt": item.get("createdAt"),
            "updatedAt": item.get("updatedAt")
        }
        duplicate_folder_records[folder].append(landmark_id)

        if not short_description:
            print(
                f"WARNING: Landmark {landmark_id} ({label}) has no "
                "shortDescription; the generated landmark manifest will "
                "contain an empty string."
            )

    duplicate_folder_count = sum(
        1 for ids in duplicate_folder_records.values() if len(set(ids)) > 1
    )

    print(f"Loaded {len(landmark_id_to_metadata)} usable landmarks from DynamoDB")

    if duplicate_folder_count:
        print(
            f"WARNING: Found {duplicate_folder_count} duplicate dataset folder "
            "name(s) in LookSeeLandmarks. They will be evaluated after joining "
            "against the published mapping snapshot."
        )

    mapped_candidates_by_folder: Dict[str, List[dict]] = defaultdict(list)
    missing_landmarks: List[str] = []

    for item in mapping_items:
        landmark_id = item.get("landmarkId")
        cluster_id = item.get("clusterId")

        if not landmark_id or cluster_id is None:
            raise ValueError(
                "Published mapping contains an invalid landmark mapping: "
                f"{item!r}"
            )

        metadata = landmark_id_to_metadata.get(landmark_id)

        if not metadata:
            missing_landmarks.append(str(landmark_id))
            continue

        mapped_candidates_by_folder[metadata["folderName"]].append({
            "clusterId": int(cluster_id),
            "metadata": metadata
        })

    if missing_landmarks:
        raise ValueError(
            "The published mapping snapshot references landmark records that "
            "cannot be resolved in LookSeeLandmarks. Training is stopped "
            "instead of silently producing an incomplete model. "
            f"landmarkIds={sorted(missing_landmarks)!r}"
        )

    folder_to_cluster: Dict[str, int] = {}
    folder_to_landmark: Dict[str, dict] = {}

    for folder, raw_candidates in mapped_candidates_by_folder.items():
        unique_candidates: Dict[Tuple[str, int], dict] = {}

        for candidate in raw_candidates:
            metadata = candidate["metadata"]
            key = (metadata["landmarkId"], candidate["clusterId"])
            unique_candidates[key] = candidate

        candidates = list(unique_candidates.values())

        if len(candidates) == 1:
            chosen = candidates[0]
        else:
            active_candidates = [
                candidate
                for candidate in candidates
                if candidate["metadata"].get("isActive") is True
            ]

            if len(active_candidates) == 1:
                chosen = active_candidates[0]
                ignored_ids = [
                    candidate["metadata"]["landmarkId"]
                    for candidate in candidates
                    if candidate is not chosen
                ]
                print(
                    "WARNING: Duplicate mapped dataset folder resolved using "
                    "the only active landmark: "
                    f"folder={folder!r}, selectedLandmarkId="
                    f"{chosen['metadata']['landmarkId']!r}, "
                    f"ignoredLandmarkIds={ignored_ids!r}."
                )
            else:
                details = [
                    {
                        "landmarkId": candidate["metadata"]["landmarkId"],
                        "clusterId": candidate["clusterId"],
                        "isActive": candidate["metadata"].get("isActive"),
                        "label": candidate["metadata"].get("label", "")
                    }
                    for candidate in sorted(
                        candidates,
                        key=lambda value: (
                            value["clusterId"],
                            value["metadata"]["landmarkId"]
                        )
                    )
                ]

                raise ValueError(
                    "Ambiguous mapped dataset folder name. Multiple published "
                    "landmark mappings collapse to the same class folder, and "
                    "there is not exactly one active record to select. "
                    f"folder={folder!r}, candidates={details!r}."
                )

        chosen_metadata = chosen["metadata"]
        latitude, longitude = validate_required_coordinates(chosen_metadata)

        chosen_metadata["latitude"] = latitude
        chosen_metadata["longitude"] = longitude

        folder_to_cluster[folder] = chosen["clusterId"]
        folder_to_landmark[folder] = chosen_metadata

    print(
        f"Resolved {len(folder_to_cluster)} folder->cluster mappings from "
        f"published mappingVersion={MAPPING_VERSION}"
    )

    return folder_to_cluster, folder_to_landmark, snapshot_metadata


# ---------------------------------------------------------------------------
# Dataset helpers
# ---------------------------------------------------------------------------

def stable_split(name: str, train_split: float) -> str:
    digest = hashlib.md5(name.encode("utf-8")).hexdigest()
    value = int(digest[:8], 16) / 0xFFFFFFFF
    return "train" if value < train_split else "val"


def deterministic_sample(keys: List[str], k: int, salt: str) -> List[str]:
    if k <= 0 or k >= len(keys):
        return keys

    def score(x: str) -> str:
        return hashlib.md5((salt + "|" + x).encode("utf-8")).hexdigest()

    return sorted(keys, key=score)[:k]


def safe_name(prefix: str, filename: str) -> str:
    """
    Prevent filename collisions across landmark folders and negative sources.
    """
    return f"{prefix}__{filename}"


def image_keys_under(bucket: str, img_prefix: str) -> List[str]:
    keys = list_objects(bucket, img_prefix)
    return sorted([k for k in keys if k.lower().endswith(IMAGE_EXTS)])


def image_keys_for_class(bucket: str, src_prefix: str, class_name: str) -> List[str]:
    img_prefix = f"{normalize_prefix(src_prefix)}{class_name}/images/"
    return image_keys_under(bucket, img_prefix)


def label_key_for_image(src_prefix: str, class_name: str, image_key: str) -> str:
    filename = image_key.rsplit("/", 1)[-1]
    stem = os.path.splitext(filename)[0]
    return f"{normalize_prefix(src_prefix)}{class_name}/labels/{stem}.txt"


def rewrite_label_contents(label_text: str, class_id: int) -> str:
    """
    Rewrites YOLO class IDs to the cluster-local class ID.

    Expected input line:
      class_id x_center y_center width height

    If a line is malformed, it is ignored.
    """
    new_lines = []

    for line in label_text.splitlines():
        parts = line.strip().split()

        if len(parts) == 5:
            parts[0] = str(class_id)
            new_lines.append(" ".join(parts))

    return "\n".join(new_lines)


def compute_cap(class_to_keys: Dict[str, List[str]]) -> int:
    counts = [len(v) for v in class_to_keys.values() if v]

    if not counts:
        return 0

    if BALANCE_STRATEGY == "none":
        # No balancing down to smallest class.
        # Optional max cap only.
        return MAX_IMAGES_PER_CLASS if MAX_IMAGES_PER_CLASS > 0 else 0

    if BALANCE_STRATEGY == "fixed":
        # Fixed cap per class if provided.
        # Otherwise fallback to min count.
        return MAX_IMAGES_PER_CLASS if MAX_IMAGES_PER_CLASS > 0 else min(counts)

    if BALANCE_STRATEGY == "min":
        # Existing behavior:
        # cap all classes to smallest class count.
        cap = min(counts)

        if MAX_IMAGES_PER_CLASS > 0:
            cap = min(cap, MAX_IMAGES_PER_CLASS)

        return cap

    print(f"WARNING: Unknown BALANCE_STRATEGY={BALANCE_STRATEGY}; no cap applied.")
    return 0


def process_class(
    bucket: str,
    src_prefix: str,
    dest_prefix: str,
    class_name: str,
    class_id: int,
    keys: List[str]
) -> Tuple[int, int]:
    """
    Copies positive class images and writes rewritten labels.
    If the class has fewer than MIN_IMAGES_PER_CLASS, it boosts (duplicates) 
    the images to reach the threshold, ensuring copies stay in the same train/val split.
    """
    if not keys:
        return 0, 0
        
    target_count = len(keys)
    is_boosting = False
    
    # Check if we need to apply the Boosting logic
    if target_count < MIN_IMAGES_PER_CLASS:
        target_count = MIN_IMAGES_PER_CLASS
        is_boosting = True
        print(f"  [{class_name}] 🚀 BOOSTING: Duplicating {len(keys)} frames to reach {target_count}")
    else:
        print(f"  [{class_name}] class_id={class_id}, selected_images={len(keys)}")

    count = 0
    missing_label_count = 0

    # Loop until we hit the target count (which handles both standard copying and boosting)
    for i in range(target_count):
        original_index = i % len(keys)
        copy_number = i // len(keys)
        image_key = keys[original_index]

        original_filename = image_key.rsplit("/", 1)[-1]
        
        # VERY IMPORTANT: Determine the split using the ORIGINAL filename.
        # This guarantees that the original image and all its copies go into 
        # the same train/val folder, preventing data leakage!
        split = stable_split(f"{class_name}/{original_filename}", TRAIN_SPLIT)

        # If this is a duplicate loop, append _copyX to the filename so S3 doesn't overwrite it
        if copy_number > 0:
            name_part, ext_part = os.path.splitext(original_filename)
            filename = f"{name_part}_copy{copy_number}{ext_part}"
        else:
            filename = original_filename

        out_filename = safe_name(class_name, filename)
        out_stem = os.path.splitext(out_filename)[0]

        dest_img_key = f"{dest_prefix}images/{split}/{out_filename}"
        dest_lbl_key = f"{dest_prefix}labels/{split}/{out_stem}.txt"

        copy_object(bucket, image_key, dest_img_key, content_type="image/jpeg")

        src_lbl_key = label_key_for_image(src_prefix, class_name, image_key)

        try:
            original_label = read_text(bucket, src_lbl_key)
        except Exception as exc:
            # Only print warning on the first pass so we don't spam the logs for copies
            if copy_number == 0:
                print(f"    WARNING: Missing/unreadable label for {image_key}: {src_lbl_key} ({exc})")
            original_label = ""
            missing_label_count += 1

        rewritten = rewrite_label_contents(original_label, class_id)
        write_text(bucket, dest_lbl_key, rewritten, content_type="text/plain")

        count += 1

    return count, missing_label_count


# ---------------------------------------------------------------------------
# Negative helpers
# ---------------------------------------------------------------------------

def copy_negative_images(
    bucket: str,
    dest_prefix: str,
    keys: List[str],
    split_salt_prefix: str,
    output_name_prefix: str
) -> int:
    """
    Copies negative images into YOLO output and writes empty label files.

    Negative YOLO labels should be present but empty:
      images/train/foo.jpg
      labels/train/foo.txt  ← empty
    """
    count = 0

    for image_key in keys:
        filename = image_key.rsplit("/", 1)[-1]
        split = stable_split(f"{split_salt_prefix}/{filename}", TRAIN_SPLIT)

        out_filename = safe_name(output_name_prefix, filename)
        out_stem = os.path.splitext(out_filename)[0]

        dest_img_key = f"{dest_prefix}images/{split}/{out_filename}"
        dest_lbl_key = f"{dest_prefix}labels/{split}/{out_stem}.txt"

        copy_object(bucket, image_key, dest_img_key, content_type="image/jpeg")
        write_text(bucket, dest_lbl_key, "", content_type="text/plain")

        count += 1

    return count


def process_global_negatives(
    bucket: str,
    dest_prefix: str,
    total_pos: int,
    cluster_id: int
) -> int:
    if not INCLUDE_NEGATIVES:
        print("  Global negatives disabled.")
        return 0

    global_img_prefix = f"{normalize_prefix(GLOBAL_NEG_PREFIX)}images/"
    neg_keys = image_keys_under(bucket, global_img_prefix)

    if not neg_keys and ALLOW_LEGACY_NEGATIVE_FALLBACK:
        legacy_img_prefix = f"{normalize_prefix(NEG_PREFIX)}images/"
        print(
            f"  No global negatives found at s3://{bucket}/{global_img_prefix}; "
            f"trying legacy path s3://{bucket}/{legacy_img_prefix}"
        )
        neg_keys = image_keys_under(bucket, legacy_img_prefix)

    if not neg_keys:
        print(f"  No global negatives found.")
        return 0

    desired = min(NEG_MAX, int(total_pos * NEG_RATIO))

    if desired <= 0:
        print(f"  Global negatives desired=0 (total_pos={total_pos}, NEG_RATIO={NEG_RATIO})")
        return 0

    chosen = deterministic_sample(
        neg_keys,
        desired,
        salt=f"global-negatives|cluster-{cluster_id}|{dest_prefix}"
    )

    print(
        f"  Adding {len(chosen)} global negatives "
        f"(desired={desired}, available={len(neg_keys)})"
    )

    return copy_negative_images(
        bucket=bucket,
        dest_prefix=dest_prefix,
        keys=chosen,
        split_salt_prefix="global-negatives",
        output_name_prefix="gneg"
    )


def hard_negative_keys_for_landmark(bucket: str, landmark_folder: str) -> List[str]:
    img_prefix = f"{normalize_prefix(HARD_NEG_ROOT)}{landmark_folder}/images/"
    return image_keys_under(bucket, img_prefix)


def process_hard_negatives_for_cluster(
    bucket: str,
    dest_prefix: str,
    cluster_id: int,
    folders: List[str],
    per_class_positive_counts: Dict[str, int]
) -> Tuple[int, Dict[str, int]]:
    """
    Pulls by-landmark hard negatives only for landmarks included in this cluster.

    Returns:
      (total_hard_negative_count, per_landmark_hard_negative_counts)
    """
    if not INCLUDE_HARD_NEGATIVES:
        print("  Hard negatives disabled.")
        return 0, {}

    total_hard_negatives = 0
    per_landmark_counts: Dict[str, int] = {}

    for folder in folders:
        available_keys = hard_negative_keys_for_landmark(bucket, folder)

        if not available_keys:
            print(f"  [{folder}] no hard negatives found.")
            per_landmark_counts[folder] = 0
            continue

        class_pos_count = int(per_class_positive_counts.get(folder, 0) or 0)
        desired = min(
            HARD_NEG_MAX_PER_LANDMARK,
            int(class_pos_count * HARD_NEG_RATIO)
        )

        if desired <= 0:
            print(
                f"  [{folder}] hard negatives desired=0 "
                f"(class_pos_count={class_pos_count}, HARD_NEG_RATIO={HARD_NEG_RATIO})"
            )
            per_landmark_counts[folder] = 0
            continue

        chosen = deterministic_sample(
            available_keys,
            desired,
            salt=f"hard-negatives|cluster-{cluster_id}|{folder}|{dest_prefix}"
        )

        print(
            f"  [{folder}] adding {len(chosen)} hard negatives "
            f"(desired={desired}, available={len(available_keys)})"
        )

        copied = copy_negative_images(
            bucket=bucket,
            dest_prefix=dest_prefix,
            keys=chosen,
            split_salt_prefix=f"hard-negatives/{folder}",
            output_name_prefix=f"hneg_{folder}"
        )

        per_landmark_counts[folder] = copied
        total_hard_negatives += copied

    return total_hard_negatives, per_landmark_counts


# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------

def write_manifest(
    bucket: str,
    run_dest_root: str,
    training_run_id: str,
    mapping_version: str,
    source_mapping_revision: str,
    published_mapping_count: int,
    cluster_summaries: List[dict],
    skipped_clusters: List[dict]
):
    manifest = {
        "trainingRunId": training_run_id,
        "mappingVersion": mapping_version,
        "sourceMappingRevision": source_mapping_revision,
        "publishedMappingCount": published_mapping_count,
        "sourceDatasetPrefix": SRC_PREFIX,
        "outputDatasetPrefix": run_dest_root,
        "balanceStrategy": BALANCE_STRATEGY,
        "maxImagesPerClass": MAX_IMAGES_PER_CLASS,
        "minImagesPerClass": MIN_IMAGES_PER_CLASS,
        "includeGlobalNegatives": INCLUDE_NEGATIVES,
        "globalNegPrefix": GLOBAL_NEG_PREFIX,
        "negRatio": NEG_RATIO,
        "negMax": NEG_MAX,
        "includeHardNegatives": INCLUDE_HARD_NEGATIVES,
        "hardNegRoot": HARD_NEG_ROOT,
        "hardNegRatio": HARD_NEG_RATIO,
        "hardNegMaxPerLandmark": HARD_NEG_MAX_PER_LANDMARK,
        "includedClusters": cluster_summaries,
        "skippedClusters": skipped_clusters
    }

    manifest_key = f"{run_dest_root}manifest.json"

    write_text(
        bucket,
        manifest_key,
        json.dumps(manifest, indent=2, ensure_ascii=False),
        content_type="application/json"
    )

    print(f"Wrote manifest.json → s3://{bucket}/{manifest_key}")


def write_data_yaml(bucket: str, dest_prefix: str, class_names: List[str]):
    yaml_text = "\n".join([
        "train: images/train",
        "val: images/val",
        "",
        f"nc: {len(class_names)}",
        f"names: {json.dumps(class_names)}"
    ])

    write_text(
        bucket,
        f"{dest_prefix}data.yaml",
        yaml_text,
        content_type="text/yaml"
    )

    print(f"  Wrote data.yaml ({len(class_names)} classes)")


def write_landmark_manifest(
    bucket: str,
    dest_prefix: str,
    cluster_id: int,
    training_run_id: str,
    mapping_version: str,
    source_mapping_revision: str,
    class_names: List[str],
    folder_to_landmark: Dict[str, dict],
    per_class_positive_counts: Dict[str, int]
) -> str:
    """
    Write the app-facing class-index lookup beside data.yaml.

    class_names is the same ordered list passed to write_data_yaml(), so each
    enumerate() index is guaranteed to match the YOLO class ID used in the
    rewritten labels and in the trained model output.
    """
    landmarks: Dict[str, dict] = {}

    for class_index, folder in enumerate(class_names):
        metadata = folder_to_landmark.get(folder)

        if not metadata:
            raise ValueError(
                f"Cannot generate landmark manifest for cluster {cluster_id}: "
                f"no landmark metadata exists for class folder {folder!r}."
            )

        latitude, longitude = validate_required_coordinates(metadata)

        landmarks[str(class_index)] = {
            "classIndex": class_index,
            "landmarkId": metadata["landmarkId"],
            "datasetClassName": folder,
            "label": metadata["label"],
            "shortDescription": metadata.get("shortDescription", ""),
            "latitude": latitude,
            "longitude": longitude,
            "positiveImageCount": int(per_class_positive_counts.get(folder, 0) or 0)
        }

    manifest = {
        "schemaVersion": LANDMARK_MANIFEST_SCHEMA_VERSION,
        "coordinateSystem": "WGS84",
        "clusterId": cluster_id,
        "trainingRunId": training_run_id,
        "mappingVersion": mapping_version,
        "sourceMappingRevision": source_mapping_revision,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "classCount": len(class_names),
        "landmarks": landmarks
    }

    manifest_key = f"{dest_prefix}landmark-manifest.json"

    write_text(
        bucket,
        manifest_key,
        json.dumps(manifest, indent=2, ensure_ascii=False),
        content_type="application/json"
    )

    print(
        f"  Wrote landmark-manifest.json ({len(class_names)} class mappings) "
        f"→ s3://{bucket}/{manifest_key}"
    )

    return manifest_key


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    src_prefix = normalize_prefix(SRC_PREFIX)
    dest_prefix = normalize_prefix(DEST_PREFIX)

    print("=== LookSee YOLO Dataset Packager (With Boosting & Capping) ===")
    print(f"BUCKET={BUCKET}")
    print(f"SRC_PREFIX={src_prefix}")
    print(f"DEST_PREFIX={dest_prefix}")
    print(f"TRAINING_RUN_ID={TRAINING_RUN_ID}")
    print(f"MAPPING_VERSION={MAPPING_VERSION}")
    print(f"SOURCE_MAPPING_REVISION={SOURCE_MAPPING_REVISION}")
    print(f"ONLY_DIRTY={ONLY_DIRTY}")
    print(f"PUBLISHED_CLUSTER_MAPPINGS_TABLE={PUBLISHED_CLUSTER_MAPPINGS_TABLE}")
    print(f"TRAIN_SPLIT={TRAIN_SPLIT}")
    print(f"CLEAR_DEST={CLEAR_DEST}")
    print(
        "LANDMARK_MANIFEST_SCHEMA_VERSION="
        f"{LANDMARK_MANIFEST_SCHEMA_VERSION}"
    )

    print("\n--- Global negatives ---")
    print(f"INCLUDE_NEGATIVES={INCLUDE_NEGATIVES}")
    print(f"GLOBAL_NEG_PREFIX={normalize_prefix(GLOBAL_NEG_PREFIX)}")
    print(f"NEG_PREFIX={normalize_prefix(NEG_PREFIX)}")
    print(f"NEG_RATIO={NEG_RATIO}")
    print(f"NEG_MAX={NEG_MAX}")
    print(f"ALLOW_LEGACY_NEGATIVE_FALLBACK={ALLOW_LEGACY_NEGATIVE_FALLBACK}")

    print("\n--- Hard negatives ---")
    print(f"INCLUDE_HARD_NEGATIVES={INCLUDE_HARD_NEGATIVES}")
    print(f"HARD_NEG_ROOT={normalize_prefix(HARD_NEG_ROOT)}")
    print(f"HARD_NEG_RATIO={HARD_NEG_RATIO}")
    print(f"HARD_NEG_MAX_PER_LANDMARK={HARD_NEG_MAX_PER_LANDMARK}")

    print("\n--- Balancing ---")
    print(f"BALANCE_STRATEGY={BALANCE_STRATEGY}")
    print(f"MAX_IMAGES_PER_CLASS={MAX_IMAGES_PER_CLASS}")
    print(f"MIN_IMAGES_PER_CLASS={MIN_IMAGES_PER_CLASS}")

    # Build run-level dest root:
    #   dataset-yolo-negtest/{trainingRunId}/
    # or:
    #   dataset-yolo-negtest/
    run_dest_root = (
        f"{dest_prefix.rstrip('/')}/{TRAINING_RUN_ID}/"
        if TRAINING_RUN_ID
        else dest_prefix
    )

    if CLEAR_DEST:
        delete_prefix(BUCKET, run_dest_root)

    folder_to_cluster, folder_to_landmark, snapshot_metadata = (
        load_cluster_metadata()
    )
    source_mapping_revision = str(
        snapshot_metadata["sourceRevision"]
    )
    published_mapping_count = int(
        snapshot_metadata.get("mappingCount", 0) or 0
    )

    cluster_to_folders: Dict[int, List[str]] = defaultdict(list)

    for folder, cluster_id in folder_to_cluster.items():
        cluster_to_folders[int(cluster_id)].append(folder)

    if not cluster_to_folders:
        print("No cluster mappings found — nothing to package.")
        write_manifest(
            bucket=BUCKET,
            run_dest_root=run_dest_root,
            training_run_id=TRAINING_RUN_ID,
            mapping_version=MAPPING_VERSION,
            source_mapping_revision=source_mapping_revision,
            published_mapping_count=published_mapping_count,
            cluster_summaries=[],
            skipped_clusters=[{"reason": "no_cluster_mappings"}]
        )
        return

    print(f"\nFound {len(cluster_to_folders)} clusters: {sorted(cluster_to_folders.keys())}")

    cluster_summaries = []
    skipped_clusters = []

    for cluster_id in sorted(cluster_to_folders.keys()):
        folders = sorted(cluster_to_folders[cluster_id])
        cluster_dest = f"{run_dest_root}cluster-{cluster_id}/"

        print(f"\n=== Cluster {cluster_id} -> {cluster_dest} ===")
        print(f"  Landmarks: {folders}")

        class_id_map = {folder: idx for idx, folder in enumerate(folders)}

        class_to_keys: Dict[str, List[str]] = {}
        discovered_positive_counts: Dict[str, int] = {}

        for folder in folders:
            keys = image_keys_for_class(BUCKET, src_prefix, folder)
            discovered_positive_counts[folder] = len(keys)

            if keys:
                class_to_keys[folder] = keys
                print(f"  [{folder}] discovered positive images={len(keys)}")
            else:
                print(f"  WARNING: No positive images found for '{folder}' at s3://{BUCKET}/{src_prefix}{folder}/images/")

        cap = compute_cap(class_to_keys)

        if cap > 0:
            print(f"  Cap per class: {cap} (strategy={BALANCE_STRATEGY})")
        else:
            print("  No per-class cap applied.")

        total_pos = 0
        total_missing_labels = 0
        per_class_positive_counts: Dict[str, int] = {}
        per_class_missing_label_counts: Dict[str, int] = {}

        for folder in folders:
            class_id = class_id_map[folder]
            keys = class_to_keys.get(folder, [])

            if not keys:
                per_class_positive_counts[folder] = 0
                per_class_missing_label_counts[folder] = 0
                continue

            selected_keys = keys

            if cap > 0:
                selected_keys = deterministic_sample(
                    keys,
                    cap,
                    salt=f"class|{folder}|{cluster_dest}"
                )

            processed_count, missing_label_count = process_class(
                bucket=BUCKET,
                src_prefix=src_prefix,
                dest_prefix=cluster_dest,
                class_name=folder,
                class_id=class_id,
                keys=selected_keys
            )

            per_class_positive_counts[folder] = processed_count
            per_class_missing_label_counts[folder] = missing_label_count
            total_pos += processed_count
            total_missing_labels += missing_label_count

        if total_pos <= 0:
            skipped_clusters.append({
                "clusterId": cluster_id,
                "reason": "no_positive_images",
                "classNames": folders,
                "discoveredPositiveCounts": discovered_positive_counts
            })

            print(f"  WARNING: Cluster {cluster_id} has no positive images. Skipping this cluster completely to prevent SageMaker crashes.")
            continue # <--- THIS IS THE MISSING PIECE!

        global_negative_count = process_global_negatives(
            bucket=BUCKET,
            dest_prefix=cluster_dest,
            total_pos=total_pos,
            cluster_id=cluster_id
        )

        hard_negative_count, per_landmark_hard_negative_counts = process_hard_negatives_for_cluster(
            bucket=BUCKET,
            dest_prefix=cluster_dest,
            cluster_id=cluster_id,
            folders=folders,
            per_class_positive_counts=per_class_positive_counts
        )

        write_data_yaml(BUCKET, cluster_dest, folders)

        landmark_manifest_key = write_landmark_manifest(
            bucket=BUCKET,
            dest_prefix=cluster_dest,
            cluster_id=cluster_id,
            training_run_id=TRAINING_RUN_ID,
            mapping_version=MAPPING_VERSION,
            source_mapping_revision=source_mapping_revision,
            class_names=folders,
            folder_to_landmark=folder_to_landmark,
            per_class_positive_counts=per_class_positive_counts
        )

        total_image_count = total_pos + global_negative_count + hard_negative_count

        cluster_summary = {
            "clusterId": cluster_id,
            "trainingRunId": TRAINING_RUN_ID,
            "mappingVersion": MAPPING_VERSION,
            "sourceMappingRevision": source_mapping_revision,
            "datasetPrefix": cluster_dest,
            "classNames": folders,
            "landmarkManifestKey": landmark_manifest_key,
            "landmarkManifestSchemaVersion": LANDMARK_MANIFEST_SCHEMA_VERSION,

            # Legacy field used by the current finalizer/training pipeline.
            # Keep this as positive image count for backward compatibility.
            "imageCount": total_pos,

            # New richer manifest fields.
            "positiveImageCount": total_pos,
            "globalNegativeCount": global_negative_count,
            "hardNegativeCount": hard_negative_count,
            "totalImageCount": total_image_count,

            "discoveredPositiveCounts": discovered_positive_counts,
            "perClassPositiveCounts": per_class_positive_counts,
            "perClassMissingLabelCounts": per_class_missing_label_counts,
            "missingLabelCount": total_missing_labels,
            "perLandmarkHardNegativeCounts": per_landmark_hard_negative_counts,

            "balanceStrategy": BALANCE_STRATEGY,
            "capPerClass": cap,
            "globalNegPrefix": normalize_prefix(GLOBAL_NEG_PREFIX),
            "hardNegRoot": normalize_prefix(HARD_NEG_ROOT)
        }

        cluster_summaries.append(cluster_summary)

        print(f"  Cluster {cluster_id} summary:")
        print(f"    positiveImageCount={total_pos}")
        print(f"    globalNegativeCount={global_negative_count}")
        print(f"    hardNegativeCount={hard_negative_count}")
        print(f"    totalImageCount={total_image_count}")
        print(f"    missingLabelCount={total_missing_labels}")

    write_manifest(
        bucket=BUCKET,
        run_dest_root=run_dest_root,
        training_run_id=TRAINING_RUN_ID,
        mapping_version=MAPPING_VERSION,
        source_mapping_revision=source_mapping_revision,
        published_mapping_count=published_mapping_count,
        cluster_summaries=cluster_summaries,
        skipped_clusters=skipped_clusters
    )

    print("\nAll clusters packaged successfully.")


if __name__ == "__main__":
    main()