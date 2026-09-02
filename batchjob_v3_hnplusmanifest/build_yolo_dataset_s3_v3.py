import os
import json
import hashlib
from datetime import datetime, timezone
from typing import Dict, List, Tuple
from collections import defaultdict
import boto3
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
# Balancing
# ---------------------------------------------------------------------------
# Current production behavior was often "min", which means every class in a
# cluster is capped to the smallest class count. That can make image counts
# surprisingly low if one class has very few images.
#
# Recommended for this neg-test:
#   BALANCE_STRATEGY=none
#   MAX_IMAGES_PER_CLASS=800
#
# Behavior:
#   none  -> no equalizing to smallest class; optional max cap only
#   fixed -> cap every class to MAX_IMAGES_PER_CLASS, or min(counts) if max not set
#   min   -> cap every class to smallest class count, optionally bounded by max
BALANCE_STRATEGY = os.environ.get("BALANCE_STRATEGY", "none").lower()
MAX_IMAGES_PER_CLASS = int(os.environ.get("MAX_IMAGES_PER_CLASS", "800"))
# ---------------------------------------------------------------------------
# DynamoDB
# ---------------------------------------------------------------------------
LANDMARKS_TABLE = os.environ.get("LANDMARKS_TABLE", "LookSeeLandmarks")
CLUSTER_MAPPINGS_TABLE = os.environ.get("CLUSTER_MAPPINGS_TABLE", "LookSeeClusterMappings")
NEGATIVE_NAMES = {"negative", "negatives"}
IMAGE_EXTS = (".jpg", ".jpeg", ".png")
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
def load_cluster_metadata() -> Tuple[Dict[str, int], Dict[str, dict]]:
    """
    Join canonical landmark records to cluster assignments.
    Returns:
      folder_to_cluster:
        {landmark_folder_name: cluster_id}
      folder_to_landmark:
        {
          landmark_folder_name: {
            "landmarkId": str,
            "label": str,
            "shortDescription": str,
            "folderName": str,
            "isActive": bool | None
          }
        }
    Duplicate labels are not rejected while scanning LookSeeLandmarks because
    stale or unmapped landmark records do not affect training. Ambiguity is
    checked only after joining against LookSeeClusterMappings.
    Resolution rules for duplicate mapped folder names:
      1. One mapped landmark -> use it.
      2. Multiple mapped landmarks, but exactly one isActive=True -> use the
         active landmark and log a warning.
      3. Otherwise -> fail, because the class-to-landmark mapping is genuinely
         ambiguous and should be corrected in DynamoDB.
    """
    landmarks_table = dynamodb.Table(LANDMARKS_TABLE)
    mappings_table = dynamodb.Table(CLUSTER_MAPPINGS_TABLE)
    print("Loading landmarks from DynamoDB...")
    landmark_items = scan_all(
        landmarks_table,
        {
            "ProjectionExpression": (
                "landmarkId, #lbl, shortDescription, isActive, "
                "createdAt, updatedAt"
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
            "folderName": folder,
            "isActive": item.get("isActive"),
            "createdAt": item.get("createdAt"),
            "updatedAt": item.get("updatedAt")
        }
        duplicate_folder_records[folder].append(landmark_id)
        if not short_description:
            print(
                f"WARNING: Landmark {landmark_id} ({label}) has no shortDescription; "
                "the generated landmark manifest will contain an empty string."
            )
    duplicate_folder_count = sum(
        1 for ids in duplicate_folder_records.values() if len(set(ids)) > 1
    )
    print(f"Loaded {len(landmark_id_to_metadata)} usable landmarks from DynamoDB")
    if duplicate_folder_count:
        print(
            f"WARNING: Found {duplicate_folder_count} duplicate dataset folder "
            "name(s) in LookSeeLandmarks. They will be evaluated after joining "
            "against LookSeeClusterMappings."
        )
    print("Loading cluster mappings from DynamoDB...")
    mapping_items = scan_all(
        mappings_table,
        {
            "ProjectionExpression": "landmarkId, clusterId"
        }
    )
    # A duplicate label only matters if multiple matching landmark IDs are
    # actually mapped into the trainable cluster set.
    mapped_candidates_by_folder: Dict[str, List[dict]] = defaultdict(list)
    for item in mapping_items:
        landmark_id = item.get("landmarkId")
        cluster_id = item.get("clusterId")
        if not landmark_id or cluster_id is None:
            continue
        metadata = landmark_id_to_metadata.get(landmark_id)
        if not metadata:
            print(
                f"WARNING: Cluster mapping references landmarkId={landmark_id!r}, "
                "but no usable LookSeeLandmarks record was found."
            )
            continue
        mapped_candidates_by_folder[metadata["folderName"]].append({
            "clusterId": int(cluster_id),
            "metadata": metadata
        })
    folder_to_cluster: Dict[str, int] = {}
    folder_to_landmark: Dict[str, dict] = {}
    for folder, raw_candidates in mapped_candidates_by_folder.items():
        # Deduplicate repeated mapping rows for the same landmark and cluster.
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
                    "WARNING: Duplicate mapped dataset folder resolved using the "
                    "only active landmark: "
                    f"folder={folder!r}, selectedLandmarkId="
                    f"{chosen['metadata']['landmarkId']!r}, ignoredLandmarkIds="
                    f"{ignored_ids!r}."
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
                    "Ambiguous mapped dataset folder name. Multiple landmark "
                    "records that collapse to the same class folder are present "
                    "in LookSeeClusterMappings, and there is not exactly one "
                    "active record to select. "
                    f"folder={folder!r}, candidates={details!r}. "
                    "Remove the stale cluster mapping, deactivate the stale "
                    "landmark, or give the records unique labels before rerunning."
                )
        folder_to_cluster[folder] = chosen["clusterId"]
        folder_to_landmark[folder] = chosen["metadata"]
    print(f"Resolved {len(folder_to_cluster)} folder->cluster mappings")
    return folder_to_cluster, folder_to_landmark
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
    Returns:
      (processed_count, missing_label_count)
    """
    print(f"  [{class_name}] class_id={class_id}, selected_images={len(keys)}")
    count = 0
    missing_label_count = 0
    for image_key in keys:
        filename = image_key.rsplit("/", 1)[-1]
        split = stable_split(f"{class_name}/{filename}", TRAIN_SPLIT)
        out_filename = safe_name(class_name, filename)
        out_stem = os.path.splitext(out_filename)[0]
        dest_img_key = f"{dest_prefix}images/{split}/{out_filename}"
        dest_lbl_key = f"{dest_prefix}labels/{split}/{out_stem}.txt"
        copy_object(bucket, image_key, dest_img_key, content_type="image/jpeg")
        src_lbl_key = label_key_for_image(src_prefix, class_name, image_key)
        try:
            original_label = read_text(bucket, src_lbl_key)
        except Exception as exc:
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
    cluster_summaries: List[dict],
    skipped_clusters: List[dict]
):
    manifest = {
        "trainingRunId": training_run_id,
        "sourceDatasetPrefix": SRC_PREFIX,
        "outputDatasetPrefix": run_dest_root,
        "balanceStrategy": BALANCE_STRATEGY,
        "maxImagesPerClass": MAX_IMAGES_PER_CLASS,
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
        landmarks[str(class_index)] = {
            "classIndex": class_index,
            "landmarkId": metadata["landmarkId"],
            "datasetClassName": folder,
            "label": metadata["label"],
            "shortDescription": metadata.get("shortDescription", ""),
            "positiveImageCount": int(per_class_positive_counts.get(folder, 0) or 0)
        }
    manifest = {
        "schemaVersion": 1,
        "clusterId": cluster_id,
        "trainingRunId": training_run_id,
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
    print("=== LookSee YOLO Dataset Packager NegTest ===")
    print(f"BUCKET={BUCKET}")
    print(f"SRC_PREFIX={src_prefix}")
    print(f"DEST_PREFIX={dest_prefix}")
    print(f"TRAINING_RUN_ID={TRAINING_RUN_ID}")
    print(f"TRAIN_SPLIT={TRAIN_SPLIT}")
    print(f"CLEAR_DEST={CLEAR_DEST}")
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
    folder_to_cluster, folder_to_landmark = load_cluster_metadata()
    cluster_to_folders: Dict[int, List[str]] = defaultdict(list)
    for folder, cluster_id in folder_to_cluster.items():
        cluster_to_folders[int(cluster_id)].append(folder)
    if not cluster_to_folders:
        print("No cluster mappings found — nothing to package.")
        write_manifest(
            bucket=BUCKET,
            run_dest_root=run_dest_root,
            training_run_id=TRAINING_RUN_ID,
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
            print(f"  WARNING: Cluster {cluster_id} has no positive images. data.yaml will still be written, but this cluster may not train successfully.")
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
            class_names=folders,
            folder_to_landmark=folder_to_landmark,
            per_class_positive_counts=per_class_positive_counts
        )
        total_image_count = total_pos + global_negative_count + hard_negative_count
        cluster_summary = {
            "clusterId": cluster_id,
            "datasetPrefix": cluster_dest,
            "classNames": folders,
            "landmarkManifestKey": landmark_manifest_key,
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
        cluster_summaries=cluster_summaries,
        skipped_clusters=skipped_clusters
    )
    print("\nAll clusters packaged successfully.")
if __name__ == "__main__":
    main()



