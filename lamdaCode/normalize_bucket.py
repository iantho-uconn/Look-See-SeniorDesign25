import json
import os
import re
import urllib.parse
from datetime import datetime, timezone

import boto3

s3 = boto3.client("s3")

MODEL_BUCKET = os.environ["MODEL_BUCKET"]
RAW_PREFIX_START = os.environ.get("RAW_PREFIX_START", "uploads_raw_")
DEST_ROOT = os.environ.get("DEST_ROOT", "incoming/by-submission")


def _now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _sanitize_label(value: str) -> str:
    value = (value or "").strip().lower()
    value = re.sub(r"[^a-z0-9]+", "-", value)
    value = re.sub(r"-+", "-", value).strip("-")
    return value or "unknown-label"


def _derive_class_key(meta: dict) -> str:
    landmark_id = meta.get("landmarkId")
    if landmark_id:
        return str(landmark_id)

    label = meta.get("label")
    if label:
        return _sanitize_label(str(label))

    return "unknown-class"


def _list_objects(bucket: str, prefix: str):
    paginator = s3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            yield obj["Key"]


def _copy_object(bucket: str, src_key: str, dest_key: str, content_type: str | None = None):
    extra_args = {}
    if content_type:
        extra_args["ContentType"] = content_type
        extra_args["MetadataDirective"] = "REPLACE"

    copy_source = {"Bucket": bucket, "Key": src_key}
    s3.copy_object(
        Bucket=bucket,
        Key=dest_key,
        CopySource=copy_source,
        **extra_args
    )


def _put_json(bucket: str, key: str, payload: dict):
    s3.put_object(
        Bucket=bucket,
        Key=key,
        Body=json.dumps(payload, indent=2).encode("utf-8"),
        ContentType="application/json"
    )


def _normalize_image_name(submission_id: str, original_key: str) -> str:
    filename = original_key.rsplit("/", 1)[-1]

    if filename == "photo.jpg":
        return f"{submission_id}_photo.jpg"

    match = re.match(r"frame_(\d+)\.jpg$", filename)
    if match:
        return f"{submission_id}_frame_{match.group(1)}.jpg"

    return f"{submission_id}_{filename}"


def process_metadata_object(bucket: str, metadata_key: str):
    folder_prefix = metadata_key.rsplit("/", 1)[0] + "/"

    raw_obj = s3.get_object(Bucket=bucket, Key=metadata_key)
    meta = json.loads(raw_obj["Body"].read().decode("utf-8"))

    submission_id = str(meta["submissionId"])
    class_key = _derive_class_key(meta)

    dest_prefix = f"{DEST_ROOT}/{submission_id}/"
    dest_images_prefix = f"{dest_prefix}images/"

    # Copy and normalize all jpg files from the source folder
    copied_files = []
    for key in _list_objects(bucket, folder_prefix):
        if not key.lower().endswith(".jpg"):
            continue

        normalized_name = _normalize_image_name(submission_id, key)
        dest_key = f"{dest_images_prefix}{normalized_name}"

        _copy_object(
            bucket=bucket,
            src_key=key,
            dest_key=dest_key,
            content_type="image/jpeg"
        )
        copied_files.append(dest_key)

    normalized_meta = dict(meta)
    normalized_meta["sourcePrefix"] = folder_prefix
    normalized_meta["normalizedAt"] = _now_iso()
    normalized_meta["classKey"] = class_key
    normalized_meta["normalizedImageCount"] = len(copied_files)
    normalized_meta["normalizedImagesPrefix"] = dest_images_prefix

    _put_json(bucket, f"{dest_prefix}metadata.json", normalized_meta)

    # Optional marker file for debugging/idempotency
    _put_json(bucket, f"{dest_prefix}_normalized.json", {
        "ok": True,
        "submissionId": submission_id,
        "sourcePrefix": folder_prefix,
        "normalizedAt": normalized_meta["normalizedAt"],
        "copiedFiles": copied_files,
        "classKey": class_key
    })

    return {
        "submissionId": submission_id,
        "sourcePrefix": folder_prefix,
        "destPrefix": dest_prefix,
        "copiedFiles": copied_files,
        "classKey": class_key
    }


def normalize_bucket_handler(event, context):
    results = []

    for record in event.get("Records", []):
        if record.get("eventSource") != "aws:s3":
            continue

        bucket = record["s3"]["bucket"]["name"]
        key = urllib.parse.unquote_plus(record["s3"]["object"]["key"])

        if bucket != MODEL_BUCKET:
            continue

        # Only normalize raw upload folders when metadata.json arrives
        if not key.startswith(RAW_PREFIX_START):
            continue
        if not key.endswith("metadata.json"):
            continue

        result = process_metadata_object(bucket, key)
        results.append(result)

    return {
        "ok": True,
        "processed": len(results),
        "results": results
    }