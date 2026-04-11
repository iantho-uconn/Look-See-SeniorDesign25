import os
import io
import json
import hashlib
from typing import Dict, List, Tuple

import boto3

s3 = boto3.client("s3")

BUCKET = os.environ.get("BUCKET", "looksee-models")
SRC_PREFIX = os.environ.get("SRC_PREFIX", "dataset/")
DEST_PREFIX = os.environ.get("DEST_PREFIX", "dataset-yolo/")
TRAIN_SPLIT = float(os.environ.get("TRAIN_SPLIT", "0.8"))
CLEAR_DEST = os.environ.get("CLEAR_DEST", "true").lower() == "true"

NEGATIVE_NAMES = {"negative", "negatives"}
IMAGE_EXTS = (".jpg", ".jpeg", ".png")


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
        chunk = keys[i:i+1000]
        s3.delete_objects(
            Bucket=bucket,
            Delete={"Objects": [{"Key": k} for k in chunk]}
        )


def get_class_names(bucket: str, src_prefix: str) -> List[str]:
    class_prefixes = list_common_prefixes(bucket, src_prefix)
    class_names = []

    for cp in class_prefixes:
        name = cp[len(src_prefix):].strip("/")
        if not name:
            continue
        if name in NEGATIVE_NAMES:
            continue
        class_names.append(name)

    class_names = sorted(class_names)
    print("Detected class folders:", class_names)
    return class_names


def stable_split(name: str, train_split: float) -> str:
    digest = hashlib.md5(name.encode("utf-8")).hexdigest()
    value = int(digest[:8], 16) / 0xFFFFFFFF
    return "train" if value < train_split else "val"


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


def process_real_class(bucket: str, src_prefix: str, dest_prefix: str, class_name: str, class_id: int):
    keys = image_keys_for_class(bucket, src_prefix, class_name)
    print(f"Processing class '{class_name}' with {len(keys)} images")

    for image_key in keys:
        filename = image_key.rsplit("/", 1)[-1]
        stem = os.path.splitext(filename)[0]
        split = stable_split(f"{class_name}/{filename}", TRAIN_SPLIT)

        dest_img_key = f"{dest_prefix}images/{split}/{filename}"
        dest_lbl_key = f"{dest_prefix}labels/{split}/{stem}.txt"

        copy_object(bucket, image_key, dest_img_key, content_type="image/jpeg")

        src_lbl_key = label_key_for_image(src_prefix, class_name, image_key)
        try:
            original_label = read_text(bucket, src_lbl_key)
        except s3.exceptions.NoSuchKey:
            original_label = ""
        except Exception:
            original_label = ""

        rewritten = rewrite_label_contents(original_label, class_id)
        write_text(bucket, dest_lbl_key, rewritten, content_type="text/plain")


def process_negative_folder(bucket: str, src_prefix: str, dest_prefix: str, negative_name: str):
    img_prefix = f"{src_prefix}{negative_name}/images/"
    keys = list_objects(bucket, img_prefix)
    image_keys = sorted([k for k in keys if k.lower().endswith(IMAGE_EXTS)])

    if not image_keys:
        return

    print(f"Processing negative folder '{negative_name}' with {len(image_keys)} images")

    for image_key in image_keys:
        filename = image_key.rsplit("/", 1)[-1]
        stem = os.path.splitext(filename)[0]
        split = stable_split(f"{negative_name}/{filename}", TRAIN_SPLIT)

        dest_img_key = f"{dest_prefix}images/{split}/{filename}"
        dest_lbl_key = f"{dest_prefix}labels/{split}/{stem}.txt"

        copy_object(bucket, image_key, dest_img_key, content_type="image/jpeg")
        write_text(bucket, dest_lbl_key, "", content_type="text/plain")


def write_data_yaml(bucket: str, dest_prefix: str, class_names: List[str]):
    yaml_text = "\n".join([
        f"train: s3://{bucket}/{dest_prefix}images/train",
        f"val: s3://{bucket}/{dest_prefix}images/val",
        "",
        f"nc: {len(class_names)}",
        f"names: {json.dumps(class_names)}"
    ])
    write_text(bucket, f"{dest_prefix}data.yaml", yaml_text, content_type="text/yaml")
    print("Wrote data.yaml")


def main():
    print(f"BUCKET={BUCKET}")
    print(f"SRC_PREFIX={SRC_PREFIX}")
    print(f"DEST_PREFIX={DEST_PREFIX}")
    print(f"TRAIN_SPLIT={TRAIN_SPLIT}")
    print(f"CLEAR_DEST={CLEAR_DEST}")

    if CLEAR_DEST:
        delete_prefix(BUCKET, DEST_PREFIX)

    class_names = get_class_names(BUCKET, SRC_PREFIX)
    class_id_map = {name: idx for idx, name in enumerate(class_names)}

    print("Class -> ID mapping:")
    for name, idx in class_id_map.items():
        print(f"  {name}: {idx}")

    for class_name, class_id in class_id_map.items():
        process_real_class(BUCKET, SRC_PREFIX, DEST_PREFIX, class_name, class_id)

    for neg_name in NEGATIVE_NAMES:
        process_negative_folder(BUCKET, SRC_PREFIX, DEST_PREFIX, neg_name)

    write_data_yaml(BUCKET, DEST_PREFIX, class_names)
    print("YOLO dataset packaging complete.")


if __name__ == "__main__":
    main()