import os
import json
import hashlib
from typing import Dict, List, Tuple
from collections import defaultdict

import boto3
from boto3.dynamodb.conditions import Attr

s3 = boto3.client("s3")
dynamodb = boto3.resource("dynamodb")

BUCKET = os.environ.get("BUCKET", "looksee-models")
SRC_PREFIX = os.environ.get("SRC_PREFIX", "dataset/")
DEST_PREFIX = os.environ.get("DEST_PREFIX", "dataset-yolo/")
TRAINING_RUN_ID = os.environ.get("TRAINING_RUN_ID", "")
TRAIN_SPLIT = float(os.environ.get("TRAIN_SPLIT", "0.8"))
CLEAR_DEST = os.environ.get("CLEAR_DEST", "true").lower() == "true"

# --- Negatives ---
INCLUDE_NEGATIVES = os.environ.get("INCLUDE_NEGATIVES", "true").lower() == "true"
NEG_PREFIX = os.environ.get("NEG_PREFIX", "dataset/negatives/")
NEG_RATIO = float(os.environ.get("NEG_RATIO", "1.0"))
NEG_MAX = int(os.environ.get("NEG_MAX", "500"))

# --- Balancing ---
BALANCE_STRATEGY = os.environ.get("BALANCE_STRATEGY", "min").lower()
MAX_IMAGES_PER_CLASS = int(os.environ.get("MAX_IMAGES_PER_CLASS", "0"))

# --- DynamoDB ---
LANDMARKS_TABLE = os.environ.get("LANDMARKS_TABLE", "LookSeeLandmarks")
CLUSTER_MAPPINGS_TABLE = os.environ.get("CLUSTER_MAPPINGS_TABLE", "LookSeeClusterMappings")

NEGATIVE_NAMES = {"negative", "negatives"}
IMAGE_EXTS = (".jpg", ".jpeg", ".png")


# ---------------------------------------------------------------------------
# S3 helpers
# ---------------------------------------------------------------------------

def list_common_prefixes(bucket: str, prefix: str) -> List[str]:
    paginator = s3.get_paginator("list_objects_v2")
    prefixes = []
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix, Delimiter="/"):
        for cp in page.get("CommonPrefixes", []):
            prefixes.append(cp["Prefix"])
    return prefixes


def list_objects(bucket: str, prefix: str) -> List[str]:
    paginator = s3.get_paginator("list_objects_v2")
    keys = []
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            keys.append(obj["Key"])
    return keys


def delete_prefix(bucket: str, prefix: str):
    keys = list_objects(bucket, prefix)
    if not keys:
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
    """Convert a landmark label to its S3 folder name (spaces → underscores)."""
    return label.replace(" ", "_")


def load_cluster_map() -> Dict[str, int]:
    """
    Returns {landmark_folder_name: cluster_id} by joining:
      LookSeeClusterMappings (landmarkId -> clusterId)
      LookSeeLandmarks       (landmarkId -> label)
    """
    landmarks_table = dynamodb.Table(LANDMARKS_TABLE)
    mappings_table = dynamodb.Table(CLUSTER_MAPPINGS_TABLE)

    # Scan LookSeeLandmarks to build landmarkId -> folder name
    print("Loading landmarks from DynamoDB...")
    landmark_id_to_folder: Dict[str, str] = {}
    scan_kwargs = {"ProjectionExpression": "landmarkId, #lbl", "ExpressionAttributeNames": {"#lbl": "label"}}
    while True:
        response = landmarks_table.scan(**scan_kwargs)
        for item in response.get("Items", []):
            landmark_id = item["landmarkId"]
            label = item.get("label", "")
            if label and label.lower() not in NEGATIVE_NAMES:
                landmark_id_to_folder[landmark_id] = label_to_s3_folder(label)
        last = response.get("LastEvaluatedKey")
        if not last:
            break
        scan_kwargs["ExclusiveStartKey"] = last

    print(f"Loaded {len(landmark_id_to_folder)} landmarks from DynamoDB")

    # Scan LookSeeClusterMappings to build landmarkId -> clusterId
    print("Loading cluster mappings from DynamoDB...")
    folder_to_cluster: Dict[str, int] = {}
    scan_kwargs = {"ProjectionExpression": "landmarkId, clusterId"}
    while True:
        response = mappings_table.scan(**scan_kwargs)
        for item in response.get("Items", []):
            landmark_id = item["landmarkId"]
            cluster_id = int(item["clusterId"])
            folder = landmark_id_to_folder.get(landmark_id)
            if folder:
                folder_to_cluster[folder] = cluster_id
        last = response.get("LastEvaluatedKey")
        if not last:
            break
        scan_kwargs["ExclusiveStartKey"] = last

    print(f"Resolved {len(folder_to_cluster)} folder->cluster mappings")
    return folder_to_cluster


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
    """Prevents filename collisions across landmark folders."""
    return f"{prefix}__{filename}"


def image_keys_for_class(bucket: str, src_prefix: str, class_name: str) -> List[str]:
    img_prefix = f"{src_prefix}{class_name}/images/"
    keys = list_objects(bucket, img_prefix)
    return sorted([k for k in keys if k.lower().endswith(IMAGE_EXTS)])


def label_key_for_image(src_prefix: str, class_name: str, image_key: str) -> str:
    filename = image_key.rsplit("/", 1)[-1]
    stem = os.path.splitext(filename)[0]
    return f"{src_prefix}{class_name}/labels/{stem}.txt"


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
    if not counts:
        return 0
    if BALANCE_STRATEGY == "none":
        return MAX_IMAGES_PER_CLASS if MAX_IMAGES_PER_CLASS > 0 else 0
    if BALANCE_STRATEGY == "fixed":
        return MAX_IMAGES_PER_CLASS if MAX_IMAGES_PER_CLASS > 0 else min(counts)
    # default "min"
    cap = min(counts)
    if MAX_IMAGES_PER_CLASS > 0:
        cap = min(cap, MAX_IMAGES_PER_CLASS)
    return cap


def process_class(bucket: str, src_prefix: str, dest_prefix: str,
                  class_name: str, class_id: int, keys: List[str]) -> int:
    print(f"  [{class_name}] class_id={class_id}, images={len(keys)}")
    count = 0
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
        except Exception:
            original_label = ""

        rewritten = rewrite_label_contents(original_label, class_id)
        write_text(bucket, dest_lbl_key, rewritten, content_type="text/plain")
        count += 1

    return count


def process_negatives(bucket: str, dest_prefix: str, total_pos: int, cluster_id: int):
    if not INCLUDE_NEGATIVES:
        print("  Negatives disabled.")
        return

    neg_img_prefix = f"{NEG_PREFIX.rstrip('/')}/images/"
    all_neg_keys = list_objects(bucket, neg_img_prefix)
    neg_keys = sorted([k for k in all_neg_keys if k.lower().endswith(IMAGE_EXTS)])

    if not neg_keys:
        print(f"  No negatives found at s3://{bucket}/{neg_img_prefix}")
        return

    desired = min(NEG_MAX, int(total_pos * NEG_RATIO))
    if desired <= 0:
        print(f"  Negatives desired=0 (total_pos={total_pos}, NEG_RATIO={NEG_RATIO})")
        return

    chosen = deterministic_sample(neg_keys, desired, salt=f"negatives|cluster-{cluster_id}|{dest_prefix}")
    print(f"  Adding {len(chosen)} negatives (desired={desired}, available={len(neg_keys)})")

    for image_key in chosen:
        filename = image_key.rsplit("/", 1)[-1]
        split = stable_split(f"negatives/{filename}", TRAIN_SPLIT)

        out_filename = safe_name("neg", filename)
        out_stem = os.path.splitext(out_filename)[0]

        dest_img_key = f"{dest_prefix}images/{split}/{out_filename}"
        dest_lbl_key = f"{dest_prefix}labels/{split}/{out_stem}.txt"

        copy_object(bucket, image_key, dest_img_key, content_type="image/jpeg")
        write_text(bucket, dest_lbl_key, "", content_type="text/plain")


def write_manifest(bucket: str, run_dest_root: str, training_run_id: str,
                   cluster_summaries: List[dict]):
    manifest = {
        "trainingRunId": training_run_id,
        "includedClusters": cluster_summaries,
        "skippedClusters": []
    }
    manifest_key = f"{run_dest_root}manifest.json"
    write_text(bucket, manifest_key, json.dumps(manifest, indent=2), content_type="application/json")
    print(f"Wrote manifest.json → s3://{bucket}/{manifest_key}")


def write_data_yaml(bucket: str, dest_prefix: str, class_names: List[str]):
    yaml_text = "\n".join([
        "train: images/train",
        "val: images/val",
        "",
        f"nc: {len(class_names)}",
        f"names: {json.dumps(class_names)}"
    ])
    write_text(bucket, f"{dest_prefix}data.yaml", yaml_text, content_type="text/yaml")
    print(f"  Wrote data.yaml ({len(class_names)} classes)")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    print(f"BUCKET={BUCKET}")
    print(f"SRC_PREFIX={SRC_PREFIX}")
    print(f"DEST_PREFIX={DEST_PREFIX}")
    print(f"TRAINING_RUN_ID={TRAINING_RUN_ID}")
    print(f"TRAIN_SPLIT={TRAIN_SPLIT}")
    print(f"CLEAR_DEST={CLEAR_DEST}")
    print(f"BALANCE_STRATEGY={BALANCE_STRATEGY}")
    print(f"MAX_IMAGES_PER_CLASS={MAX_IMAGES_PER_CLASS}")
    print(f"INCLUDE_NEGATIVES={INCLUDE_NEGATIVES}")
    print(f"NEG_PREFIX={NEG_PREFIX}")
    print(f"NEG_RATIO={NEG_RATIO}")
    print(f"NEG_MAX={NEG_MAX}")

    # Build run-level dest root: e.g. dataset-yolo/mtr007/
    run_dest_root = f"{DEST_PREFIX.rstrip('/')}/{TRAINING_RUN_ID}/" if TRAINING_RUN_ID else f"{DEST_PREFIX.rstrip('/')}/"

    if CLEAR_DEST:
        delete_prefix(BUCKET, run_dest_root)

    # Load cluster assignments from DynamoDB
    folder_to_cluster = load_cluster_map()

    # Group landmark folders by cluster
    cluster_to_folders: Dict[int, List[str]] = defaultdict(list)
    for folder, cluster_id in folder_to_cluster.items():
        cluster_to_folders[cluster_id].append(folder)

    if not cluster_to_folders:
        print("No cluster mappings found — nothing to package.")
        return

    print(f"\nFound {len(cluster_to_folders)} clusters: {sorted(cluster_to_folders.keys())}")

    # Process each cluster
    cluster_summaries = []
    for cluster_id in sorted(cluster_to_folders.keys()):
        folders = sorted(cluster_to_folders[cluster_id])
        cluster_dest = f"{run_dest_root}cluster-{cluster_id}/"

        print(f"\n=== Cluster {cluster_id} -> {cluster_dest} ===")
        print(f"  Landmarks: {folders}")

        class_id_map = {folder: idx for idx, folder in enumerate(folders)}

        class_to_keys: Dict[str, List[str]] = {}
        for folder in folders:
            keys = image_keys_for_class(BUCKET, SRC_PREFIX, folder)
            if keys:
                class_to_keys[folder] = keys
            else:
                print(f"  WARNING: No images found for '{folder}' in s3://{BUCKET}/{SRC_PREFIX}")

        cap = compute_cap(class_to_keys)
        if cap > 0:
            print(f"  Cap per class: {cap} (strategy={BALANCE_STRATEGY})")
        else:
            print(f"  No per-class cap applied.")

        total_pos = 0
        for folder in folders:
            class_id = class_id_map[folder]
            keys = class_to_keys.get(folder, [])
            if not keys:
                continue
            if cap > 0:
                keys = deterministic_sample(keys, cap, salt=f"class|{folder}|{cluster_dest}")
            total_pos += process_class(BUCKET, SRC_PREFIX, cluster_dest, folder, class_id, keys)

        process_negatives(BUCKET, cluster_dest, total_pos, cluster_id)
        write_data_yaml(BUCKET, cluster_dest, folders)

        cluster_summaries.append({
            "clusterId": cluster_id,
            "datasetPrefix": cluster_dest,
            "classNames": folders,
            "imageCount": total_pos
        })

    write_manifest(BUCKET, run_dest_root, TRAINING_RUN_ID, cluster_summaries)
    print("\nAll clusters packaged successfully.")


if __name__ == "__main__":
    main()