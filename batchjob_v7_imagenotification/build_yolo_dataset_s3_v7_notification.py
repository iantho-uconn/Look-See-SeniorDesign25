import os
import json
import hashlib
import math
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
SRC_PREFIX = os.environ.get("SRC_PREFIX", "dataset/")
DEST_PREFIX = os.environ.get("DEST_PREFIX", "dataset-yolo/")
TRAINING_RUN_ID = os.environ.get("TRAINING_RUN_ID", "")
TRAIN_SPLIT = float(os.environ.get("TRAIN_SPLIT", "0.8"))
CLEAR_DEST = os.environ.get("CLEAR_DEST", "true").lower() == "true"

INCLUDE_NEGATIVES = os.environ.get("INCLUDE_NEGATIVES", "true").lower() == "true"
NEG_PREFIX = os.environ.get("NEG_PREFIX", f"{SRC_PREFIX.rstrip('/')}/negatives/")
GLOBAL_NEG_PREFIX = os.environ.get("GLOBAL_NEG_PREFIX", f"{SRC_PREFIX.rstrip('/')}/negatives/global/")
NEG_RATIO = float(os.environ.get("NEG_RATIO", "1.0"))
NEG_MAX = int(os.environ.get("NEG_MAX", "500"))
ALLOW_LEGACY_NEGATIVE_FALLBACK = os.environ.get("ALLOW_LEGACY_NEGATIVE_FALLBACK", "true").lower() == "true"

INCLUDE_HARD_NEGATIVES = os.environ.get("INCLUDE_HARD_NEGATIVES", "false").lower() == "true"
HARD_NEG_ROOT = os.environ.get("HARD_NEG_ROOT", f"{SRC_PREFIX.rstrip('/')}/negatives/by-landmark/")
HARD_NEG_RATIO = float(os.environ.get("HARD_NEG_RATIO", "0.25"))
HARD_NEG_MAX_PER_LANDMARK = int(os.environ.get("HARD_NEG_MAX_PER_LANDMARK", "25"))

BALANCE_STRATEGY = os.environ.get("BALANCE_STRATEGY", "none").lower()
MAX_IMAGES_PER_CLASS = int(os.environ.get("MAX_IMAGES_PER_CLASS", "2200"))
MIN_IMAGES_PER_CLASS = int(os.environ.get("MIN_IMAGES_PER_CLASS", "2000"))

LANDMARKS_TABLE = os.environ.get("LANDMARKS_TABLE", "LookSeeLandmarks")
PUBLISHED_CLUSTER_MAPPINGS_TABLE = os.environ.get("PUBLISHED_CLUSTER_MAPPINGS_TABLE", "LookSeePublishedClusterMappings")
MAPPING_VERSION = os.environ.get("MAPPING_VERSION", "").strip()
SOURCE_MAPPING_REVISION = os.environ.get("SOURCE_MAPPING_REVISION", "").strip()
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
        s3.delete_objects(Bucket=bucket, Delete={"Objects": [{"Key": k} for k in chunk]})

def copy_object(bucket: str, src_key: str, dest_key: str, content_type: str = None):
    extra = {}
    if content_type:
        extra["ContentType"] = content_type
        extra["MetadataDirective"] = "REPLACE"
    s3.copy_object(Bucket=bucket, Key=dest_key, CopySource={"Bucket": bucket, "Key": src_key}, **extra)

def read_text(bucket: str, key: str) -> str:
    obj = s3.get_object(Bucket=bucket, Key=key)
    return obj["Body"].read().decode("utf-8")

def write_text(bucket: str, key: str, text: str, content_type: str = "text/plain"):
    s3.put_object(Bucket=bucket, Key=key, Body=text.encode("utf-8"), ContentType=content_type)

# ---------------------------------------------------------------------------
# DynamoDB helpers
# ---------------------------------------------------------------------------

def label_to_s3_folder(label: str) -> str:
    return label.replace(" ", "_")

def scan_all(table, scan_kwargs: dict) -> List[dict]:
    items = []
    while True:
        response = table.scan(**scan_kwargs)
        items.extend(response.get("Items", []))
        last = response.get("LastEvaluatedKey")
        if not last: break
        scan_kwargs["ExclusiveStartKey"] = last
    return items

def coerce_optional_float(value, field_name: str, landmark_id: str) -> Optional[float]:
    if value is None: return None
    if isinstance(value, bool): raise ValueError(f"Invalid {field_name}: {value}")
    try:
        numeric = float(value) if isinstance(value, Decimal) else float(Decimal(str(value)))
    except Exception as exc:
        raise ValueError(f"Invalid {field_name}: {value}") from exc
    if numeric != numeric or numeric in (float("inf"), float("-inf")):
        raise ValueError(f"Non-finite {field_name}: {value}")
    return numeric

def validate_required_coordinates(metadata: dict) -> Tuple[float, float]:
    landmark_id = metadata.get("landmarkId", "<unknown>")
    raw_latitude = metadata.get("latitude")
    raw_longitude = metadata.get("longitude")
    if raw_latitude is None or raw_longitude is None:
        raise ValueError(f"Missing coordinates for {landmark_id}")
    latitude = coerce_optional_float(raw_latitude, "latitude", landmark_id)
    longitude = coerce_optional_float(raw_longitude, "longitude", landmark_id)
    return latitude, longitude

def query_published_mapping_snapshot(mapping_version: str) -> Tuple[List[dict], dict]:
    table = dynamodb.Table(PUBLISHED_CLUSTER_MAPPINGS_TABLE)
    items: List[dict] = []
    query_kwargs = {"KeyConditionExpression": Key("mappingVersion").eq(mapping_version), "ConsistentRead": True}
    while True:
        response = table.query(**query_kwargs)
        items.extend(response.get("Items", []))
        last = response.get("LastEvaluatedKey")
        if not last: break
        query_kwargs["ExclusiveStartKey"] = last

    metadata = next((i for i in items if i.get("landmarkId") == SNAPSHOT_METADATA_LANDMARK_ID), None)
    if not metadata: raise ValueError("No metadata record.")
    
    mapping_items = [i for i in items if i.get("landmarkId") != SNAPSHOT_METADATA_LANDMARK_ID and i.get("recordType") in (None, "LANDMARK_MAPPING")]
    return mapping_items, metadata

def load_cluster_metadata() -> Tuple[Dict[str, int], Dict[str, dict], dict]:
    landmarks_table = dynamodb.Table(LANDMARKS_TABLE)
    mapping_items, snapshot_metadata = query_published_mapping_snapshot(MAPPING_VERSION)

    if ONLY_DIRTY:
        dirty_cluster_ids = {int(item["clusterId"]) for item in mapping_items if bool(item.get("isDirtyForTraining"))}
        mapping_items = [item for item in mapping_items if int(item["clusterId"]) in dirty_cluster_ids]

    landmark_items = scan_all(landmarks_table, {
        "ProjectionExpression": "landmarkId, #lbl, shortDescription, latitude, longitude, isActive, createdAt, updatedAt",
        "ExpressionAttributeNames": {"#lbl": "label"}
    })

    landmark_id_to_metadata: Dict[str, dict] = {}
    for item in landmark_items:
        landmark_id = item.get("landmarkId")
        label = str(item.get("label", "")).strip()
        if not landmark_id or not label or label.lower() in NEGATIVE_NAMES: continue
        folder = label_to_s3_folder(label)
        landmark_id_to_metadata[landmark_id] = {
            "landmarkId": landmark_id, "label": label, "shortDescription": str(item.get("shortDescription", "")).strip(),
            "latitude": item.get("latitude"), "longitude": item.get("longitude"), "folderName": folder,
            "isActive": item.get("isActive")
        }

    mapped_candidates_by_folder: Dict[str, List[dict]] = defaultdict(list)
    for item in mapping_items:
        landmark_id = item.get("landmarkId")
        cluster_id = item.get("clusterId")
        metadata = landmark_id_to_metadata.get(landmark_id)
        if metadata: mapped_candidates_by_folder[metadata["folderName"]].append({"clusterId": int(cluster_id), "metadata": metadata})

    folder_to_cluster: Dict[str, int] = {}
    folder_to_landmark: Dict[str, dict] = {}

    for folder, raw_candidates in mapped_candidates_by_folder.items():
        candidates = list({(c["metadata"]["landmarkId"], c["clusterId"]): c for c in raw_candidates}.values())
        chosen = candidates[0]
        chosen_metadata = chosen["metadata"]
        latitude, longitude = validate_required_coordinates(chosen_metadata)
        chosen_metadata["latitude"] = latitude
        chosen_metadata["longitude"] = longitude
        folder_to_cluster[folder] = chosen["clusterId"]
        folder_to_landmark[folder] = chosen_metadata

    return folder_to_cluster, folder_to_landmark, snapshot_metadata

# ---------------------------------------------------------------------------
# Dataset helpers
# ---------------------------------------------------------------------------

def stable_split(name: str, train_split: float) -> str:
    digest = hashlib.md5(name.encode("utf-8")).hexdigest()
    return "train" if (int(digest[:8], 16) / 0xFFFFFFFF) < train_split else "val"

def deterministic_sample(keys: List[str], k: int, salt: str) -> List[str]:
    if k <= 0 or k >= len(keys): return keys
    return sorted(keys, key=lambda x: hashlib.md5((salt + "|" + x).encode("utf-8")).hexdigest())[:k]

def safe_name(prefix: str, filename: str) -> str:
    return f"{prefix}__{filename}"

def image_keys_under(bucket: str, img_prefix: str) -> List[str]:
    keys = list_objects(bucket, img_prefix)
    return sorted([k for k in keys if k.lower().endswith(IMAGE_EXTS)])

def image_keys_for_class(bucket: str, src_prefix: str, class_name: str) -> List[str]:
    return image_keys_under(bucket, f"{normalize_prefix(src_prefix)}{class_name}/images/")

def label_key_for_image(src_prefix: str, class_name: str, image_key: str) -> str:
    stem = os.path.splitext(image_key.rsplit("/", 1)[-1])[0]
    return f"{normalize_prefix(src_prefix)}{class_name}/labels/{stem}.txt"

def rewrite_label_contents(label_text: str, class_id: int) -> str:
    new_lines = []
    for line in label_text.splitlines():
        parts = line.strip().split()
        if len(parts) == 5:
            parts[0] = str(class_id)
            new_lines.append(" ".join(parts))
    return "\n".join(new_lines)

def compute_cap(class_to_keys: Dict[str, List[str]]) -> int:
    counts = [len(v) for v in class_to_keys.values() if v]
    if not counts: return 0
    if BALANCE_STRATEGY == "none": return MAX_IMAGES_PER_CLASS if MAX_IMAGES_PER_CLASS > 0 else 0
    if BALANCE_STRATEGY == "fixed": return MAX_IMAGES_PER_CLASS if MAX_IMAGES_PER_CLASS > 0 else min(counts)
    if BALANCE_STRATEGY == "min": return min(min(counts), MAX_IMAGES_PER_CLASS) if MAX_IMAGES_PER_CLASS > 0 else min(counts)
    return 0

# ---------------------------------------------------------------------------
# 🚀 CORE UPDATE: Process Class with DynamoDB Safeguard
# ---------------------------------------------------------------------------

def process_class(
    bucket: str,
    src_prefix: str,
    dest_prefix: str,
    class_name: str,
    class_id: int,
    keys: List[str],
    landmark_id: str
) -> Tuple[int, int]:
    
    if not keys:
        return 0, 0
        
    target_count = len(keys)
    table = dynamodb.Table(LANDMARKS_TABLE)
    
    # 1. 🚀 FAIL-SAFE: Check if we meet the minimum threshold
    if target_count < MIN_IMAGES_PER_CLASS:
        missing_frames = MIN_IMAGES_PER_CLASS - target_count
        seconds_needed = math.ceil(missing_frames / 30.0)
        
        print(f"  [{class_name}] ❌ SHORTFALL: Only {target_count}/{MIN_IMAGES_PER_CLASS} clean frames. Needs ~{seconds_needed}s more video.")
        
        if landmark_id:
            try:
                table.update_item(
                    Key={'landmarkId': landmark_id},
                    UpdateExpression="SET #st = :s, cleanFrameCount = :c, requiredFrames = :r, secondsNeeded = :sec",
                    ExpressionAttributeNames={'#st': 'status'},
                    ExpressionAttributeValues={
                        ':s': 'NEEDS_MORE_MEDIA',
                        ':c': target_count,
                        ':r': MIN_IMAGES_PER_CLASS,
                        ':sec': seconds_needed
                    }
                )
                print(f"  [{class_name}] 💾 Flagged DynamoDB: NEEDS_MORE_MEDIA")
            except Exception as e:
                print(f"  [{class_name}] ⚠️ DynamoDB update failed: {e}")
                
        # 🚀 ABORT packaging this class to prevent overfitting
        return 0, 0
        
    # 2. 🚀 SUCCESS: We met the threshold, proceed to training!
    else:
        print(f"  [{class_name}] ✅ MEDIA MET: {target_count}/{MIN_IMAGES_PER_CLASS} frames. Proceeding to package.")
        
        if landmark_id:
            try:
                table.update_item(
                    Key={'landmarkId': landmark_id},
                    UpdateExpression="SET #st = :s, cleanFrameCount = :c, requiredFrames = :r",
                    ExpressionAttributeNames={'#st': 'status'},
                    ExpressionAttributeValues={
                        ':s': 'TRAINING',
                        ':c': target_count,
                        ':r': MIN_IMAGES_PER_CLASS
                    }
                )
            except Exception as e:
                pass

    # 3. Proceed with standard copy loop
    count = 0
    missing_label_count = 0

    for i in range(target_count):
        image_key = keys[i]
        original_filename = image_key.rsplit("/", 1)[-1]
        split = stable_split(f"{class_name}/{original_filename}", TRAIN_SPLIT)
        
        out_filename = safe_name(class_name, original_filename)
        out_stem = os.path.splitext(out_filename)[0]

        dest_img_key = f"{dest_prefix}images/{split}/{out_filename}"
        dest_lbl_key = f"{dest_prefix}labels/{split}/{out_stem}.txt"

        copy_object(bucket, image_key, dest_img_key, content_type="image/jpeg")
        src_lbl_key = label_key_for_image(src_prefix, class_name, image_key)

        try:
            original_label = read_text(bucket, src_lbl_key)
        except Exception as exc:
            original_label = ""
            missing_label_count += 1

        rewritten = rewrite_label_contents(original_label, class_id)
        write_text(bucket, dest_lbl_key, rewritten, content_type="text/plain")
        count += 1

    return count, missing_label_count

# ---------------------------------------------------------------------------
# Negative helpers
# ---------------------------------------------------------------------------

def copy_negative_images(bucket: str, dest_prefix: str, keys: List[str], split_salt_prefix: str, output_name_prefix: str) -> int:
    count = 0
    for image_key in keys:
        filename = image_key.rsplit("/", 1)[-1]
        split = stable_split(f"{split_salt_prefix}/{filename}", TRAIN_SPLIT)
        out_filename = safe_name(output_name_prefix, filename)
        out_stem = os.path.splitext(out_filename)[0]
        copy_object(bucket, image_key, f"{dest_prefix}images/{split}/{out_filename}", content_type="image/jpeg")
        write_text(bucket, f"{dest_prefix}labels/{split}/{out_stem}.txt", "", content_type="text/plain")
        count += 1
    return count

def process_global_negatives(bucket: str, dest_prefix: str, total_pos: int, cluster_id: int) -> int:
    if not INCLUDE_NEGATIVES: return 0
    neg_keys = image_keys_under(bucket, f"{normalize_prefix(GLOBAL_NEG_PREFIX)}images/")
    if not neg_keys and ALLOW_LEGACY_NEGATIVE_FALLBACK:
        neg_keys = image_keys_under(bucket, f"{normalize_prefix(NEG_PREFIX)}images/")
    if not neg_keys: return 0
    desired = min(NEG_MAX, int(total_pos * NEG_RATIO))
    if desired <= 0: return 0
    chosen = deterministic_sample(neg_keys, desired, salt=f"global-negatives|cluster-{cluster_id}|{dest_prefix}")
    return copy_negative_images(bucket, dest_prefix, chosen, "global-negatives", "gneg")

def process_hard_negatives_for_cluster(bucket: str, dest_prefix: str, cluster_id: int, folders: List[str], per_class_positive_counts: Dict[str, int]) -> Tuple[int, Dict[str, int]]:
    if not INCLUDE_HARD_NEGATIVES: return 0, {}
    total = 0; per_landmark = {}
    for folder in folders:
        keys = image_keys_under(bucket, f"{normalize_prefix(HARD_NEG_ROOT)}{folder}/images/")
        if not keys:
            per_landmark[folder] = 0; continue
        desired = min(HARD_NEG_MAX_PER_LANDMARK, int((per_class_positive_counts.get(folder, 0) or 0) * HARD_NEG_RATIO))
        if desired <= 0:
            per_landmark[folder] = 0; continue
        chosen = deterministic_sample(keys, desired, salt=f"hard-negatives|cluster-{cluster_id}|{folder}|{dest_prefix}")
        copied = copy_negative_images(bucket, dest_prefix, chosen, f"hard-negatives/{folder}", f"hneg_{folder}")
        per_landmark[folder] = copied
        total += copied
    return total, per_landmark

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------

def write_manifest(bucket: str, run_dest_root: str, training_run_id: str, mapping_version: str, source_mapping_revision: str, published_mapping_count: int, cluster_summaries: List[dict], skipped_clusters: List[dict]):
    manifest = {
        "trainingRunId": training_run_id, "mappingVersion": mapping_version, "sourceMappingRevision": source_mapping_revision,
        "publishedMappingCount": published_mapping_count, "sourceDatasetPrefix": SRC_PREFIX, "outputDatasetPrefix": run_dest_root,
        "balanceStrategy": BALANCE_STRATEGY, "maxImagesPerClass": MAX_IMAGES_PER_CLASS, "minImagesPerClass": MIN_IMAGES_PER_CLASS,
        "includeGlobalNegatives": INCLUDE_NEGATIVES, "globalNegPrefix": GLOBAL_NEG_PREFIX, "negRatio": NEG_RATIO, "negMax": NEG_MAX,
        "includeHardNegatives": INCLUDE_HARD_NEGATIVES, "hardNegRoot": HARD_NEG_ROOT, "hardNegRatio": HARD_NEG_RATIO, "hardNegMaxPerLandmark": HARD_NEG_MAX_PER_LANDMARK,
        "includedClusters": cluster_summaries, "skippedClusters": skipped_clusters
    }
    write_text(bucket, f"{run_dest_root}manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False), content_type="application/json")

def write_data_yaml(bucket: str, dest_prefix: str, class_names: List[str]):
    yaml_text = "\n".join(["train: images/train", "val: images/val", "", f"nc: {len(class_names)}", f"names: {json.dumps(class_names)}"])
    write_text(bucket, f"{dest_prefix}data.yaml", yaml_text, content_type="text/yaml")

def write_landmark_manifest(bucket, dest_prefix, cluster_id, training_run_id, mapping_version, source_mapping_revision, class_names, folder_to_landmark, per_class_positive_counts) -> str:
    landmarks = {}
    for class_index, folder in enumerate(class_names):
        metadata = folder_to_landmark.get(folder)
        lat, lon = validate_required_coordinates(metadata)
        landmarks[str(class_index)] = {
            "classIndex": class_index, "landmarkId": metadata["landmarkId"], "datasetClassName": folder,
            "label": metadata["label"], "shortDescription": metadata.get("shortDescription", ""),
            "latitude": lat, "longitude": lon, "positiveImageCount": int(per_class_positive_counts.get(folder, 0) or 0)
        }
    manifest = {
        "schemaVersion": LANDMARK_MANIFEST_SCHEMA_VERSION, "coordinateSystem": "WGS84", "clusterId": cluster_id,
        "trainingRunId": training_run_id, "mappingVersion": mapping_version, "sourceMappingRevision": source_mapping_revision,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"), "classCount": len(class_names), "landmarks": landmarks
    }
    key = f"{dest_prefix}landmark-manifest.json"
    write_text(bucket, key, json.dumps(manifest, indent=2, ensure_ascii=False), content_type="application/json")
    return key

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    src_prefix = normalize_prefix(SRC_PREFIX)
    dest_prefix = normalize_prefix(DEST_PREFIX)
    run_dest_root = f"{dest_prefix.rstrip('/')}/{TRAINING_RUN_ID}/" if TRAINING_RUN_ID else dest_prefix
    if CLEAR_DEST: delete_prefix(BUCKET, run_dest_root)

    folder_to_cluster, folder_to_landmark, snapshot_metadata = load_cluster_metadata()
    source_mapping_revision = str(snapshot_metadata["sourceRevision"])
    published_mapping_count = int(snapshot_metadata.get("mappingCount", 0) or 0)

    cluster_to_folders: Dict[int, List[str]] = defaultdict(list)
    for folder, cluster_id in folder_to_cluster.items(): cluster_to_folders[int(cluster_id)].append(folder)

    if not cluster_to_folders:
        write_manifest(BUCKET, run_dest_root, TRAINING_RUN_ID, MAPPING_VERSION, source_mapping_revision, published_mapping_count, [], [{"reason": "no_cluster_mappings"}])
        return

    cluster_summaries = []
    skipped_clusters = []

    for cluster_id in sorted(cluster_to_folders.keys()):
        folders = sorted(cluster_to_folders[cluster_id])
        cluster_dest = f"{run_dest_root}cluster-{cluster_id}/"
        class_id_map = {folder: idx for idx, folder in enumerate(folders)}
        class_to_keys: Dict[str, List[str]] = {}
        discovered_positive_counts: Dict[str, int] = {}

        for folder in folders:
            keys = image_keys_for_class(BUCKET, src_prefix, folder)
            discovered_positive_counts[folder] = len(keys)
            if keys: class_to_keys[folder] = keys

        cap = compute_cap(class_to_keys)

        total_pos = 0; total_missing_labels = 0
        per_class_positive_counts: Dict[str, int] = {}
        per_class_missing_label_counts: Dict[str, int] = {}

        for folder in folders:
            class_id = class_id_map[folder]
            keys = class_to_keys.get(folder, [])
            if not keys:
                per_class_positive_counts[folder] = 0; per_class_missing_label_counts[folder] = 0
                continue

            selected_keys = keys
            if cap > 0: selected_keys = deterministic_sample(keys, cap, salt=f"class|{folder}|{cluster_dest}")

            # 🚀 PASS LANDMARK ID TO PROCESS CLASS
            landmark_id = folder_to_landmark.get(folder, {}).get("landmarkId")
            processed_count, missing_label_count = process_class(
                bucket=BUCKET, src_prefix=src_prefix, dest_prefix=cluster_dest,
                class_name=folder, class_id=class_id, keys=selected_keys, landmark_id=landmark_id
            )

            per_class_positive_counts[folder] = processed_count
            per_class_missing_label_counts[folder] = missing_label_count
            total_pos += processed_count
            total_missing_labels += missing_label_count

        if total_pos <= 0:
            skipped_clusters.append({
                "clusterId": cluster_id, "reason": "no_positive_images_or_failed_threshold",
                "classNames": folders, "discoveredPositiveCounts": discovered_positive_counts
            })
            print(f"  WARNING: Cluster {cluster_id} failed the media threshold or is empty. Skipping cluster.")
            continue

        global_negative_count = process_global_negatives(BUCKET, cluster_dest, total_pos, cluster_id)
        hard_negative_count, per_landmark_hard_negative_counts = process_hard_negatives_for_cluster(BUCKET, cluster_dest, cluster_id, folders, per_class_positive_counts)
        write_data_yaml(BUCKET, cluster_dest, folders)

        landmark_manifest_key = write_landmark_manifest(
            bucket=BUCKET, dest_prefix=cluster_dest, cluster_id=cluster_id, training_run_id=TRAINING_RUN_ID,
            mapping_version=MAPPING_VERSION, source_mapping_revision=source_mapping_revision,
            class_names=folders, folder_to_landmark=folder_to_landmark, per_class_positive_counts=per_class_positive_counts
        )

        total_image_count = total_pos + global_negative_count + hard_negative_count
        cluster_summaries.append({
            "clusterId": cluster_id, "trainingRunId": TRAINING_RUN_ID, "mappingVersion": MAPPING_VERSION,
            "sourceMappingRevision": source_mapping_revision, "datasetPrefix": cluster_dest, "classNames": folders,
            "landmarkManifestKey": landmark_manifest_key, "landmarkManifestSchemaVersion": LANDMARK_MANIFEST_SCHEMA_VERSION,
            "imageCount": total_pos, "positiveImageCount": total_pos, "globalNegativeCount": global_negative_count,
            "hardNegativeCount": hard_negative_count, "totalImageCount": total_image_count,
            "discoveredPositiveCounts": discovered_positive_counts, "perClassPositiveCounts": per_class_positive_counts,
            "perClassMissingLabelCounts": per_class_missing_label_counts, "missingLabelCount": total_missing_labels,
            "perLandmarkHardNegativeCounts": per_landmark_hard_negative_counts, "balanceStrategy": BALANCE_STRATEGY,
            "capPerClass": cap, "globalNegPrefix": normalize_prefix(GLOBAL_NEG_PREFIX), "hardNegRoot": normalize_prefix(HARD_NEG_ROOT)
        })

    write_manifest(BUCKET, run_dest_root, TRAINING_RUN_ID, MAPPING_VERSION, source_mapping_revision, published_mapping_count, cluster_summaries, skipped_clusters)

if __name__ == "__main__":
    main()