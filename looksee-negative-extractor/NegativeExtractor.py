import json
import os
import shutil
import time
from pathlib import Path

import boto3
import cv2
import numpy as np
from botocore.exceptions import ClientError


s3_client = boto3.client("s3")
dynamodb = boto3.resource("dynamodb")

HARD_NEG_TABLE = os.environ.get(
    "HARD_NEG_TABLE",
    "LookSeeHardNegativeSubmissions",
)
HISTORY_TABLE = os.environ.get(
    "HISTORY_TABLE",
    "LookSeeMediaUploadHistory",
)
CLUSTER_MAPPINGS_TABLE = os.environ.get(
    "CLUSTER_MAPPINGS_TABLE", 
    "LookSeeClusterMappings",
)
MARK_DIRTY_FOR_TRAINING = (
    os.environ.get("MARK_DIRTY_FOR_TRAINING", "true").lower() == "true"
)

HISTORY_TABLE = os.environ.get(
    "HISTORY_TABLE",
    "LookSeeMediaUploadHistory",
)

THUMBNAIL_PREFIX = os.environ.get(
    "THUMBNAIL_PREFIX",
    "media-thumbnails/hard-negatives",
)

THUMBNAIL_WIDTH = os.environ.get(
    "THUMBNAIL_WIDTH",
    "640",
)
THUMBNAIL_JPEG_QUALITY = os.environ.get(
    "THUMBNAIL_JPEG_QUALITY",
    "82",
)


FRAME_SKIP = max(1, int(os.environ.get("FRAME_SKIP", "1")))
SIMILARITY_THRESHOLD = float(
    os.environ.get("SIMILARITY_THRESHOLD", "0.85")
)
RESIZE_WIDTH = max(1, int(os.environ.get("RESIZE_WIDTH", "640")))
RESIZE_HEIGHT = max(1, int(os.environ.get("RESIZE_HEIGHT", "360")))
THUMBNAIL_WIDTH = max(
    120,
    int(os.environ.get("THUMBNAIL_WIDTH", "640")),
)
THUMBNAIL_JPEG_QUALITY = min(
    100,
    max(40, int(os.environ.get("THUMBNAIL_JPEG_QUALITY", "82"))),
)
THUMBNAIL_PREFIX = os.environ.get(
    "THUMBNAIL_PREFIX",
    "media-thumbnails/hard-negatives",
).strip("/")

hard_neg_table = dynamodb.Table(HARD_NEG_TABLE)
history_table = dynamodb.Table(HISTORY_TABLE)
cluster_mappings_table = dynamodb.Table(CLUSTER_MAPPINGS_TABLE)

orb = cv2.ORB_create(nfeatures=500)

VIDEO_EXTENSIONS = {".mov", ".mp4", ".m4v", ".avi", ".mkv"}
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".heic"}


class ExtractionError(RuntimeError):
    pass


def now_epoch_int():
    return int(time.time())


def now_epoch_string():
    return str(now_epoch_int())


def history_id_for_negative(negative_id):
    return f"negative#{negative_id}"


def extension_for_key(key):
    return Path(str(key or "")).suffix.lower()


def detect_media_kind(source_bucket, source_key, event):
    explicit_kind = str(event.get("mediaKind") or "").strip().lower()
    if explicit_kind in {"photo", "video"}:
        return explicit_kind

    content_type = str(event.get("contentType") or "").strip().lower()

    if not content_type:
        try:
            metadata = s3_client.head_object(
                Bucket=source_bucket,
                Key=source_key,
            )
            content_type = str(metadata.get("ContentType") or "").lower()
        except ClientError as exc:
            print(
                "WARNING: Could not read source ContentType for "
                f"s3://{source_bucket}/{source_key}: {exc}"
            )

    if content_type.startswith("video/"):
        return "video"
    if content_type.startswith("image/"):
        return "photo"

    extension = extension_for_key(source_key)
    if extension in VIDEO_EXTENSIONS:
        return "video"
    if extension in IMAGE_EXTENSIONS:
        return "photo"

    raise ExtractionError(
        "Could not determine whether the source object is a photo or video."
    )


def dataset_label_key_for_image_key(image_key):
    clean_image_base = image_key.rsplit(".", 1)[0]
    clean_label_base = clean_image_base.replace("/images/", "/labels/")
    return f"{clean_label_base}.txt"


def are_frames_similar(frame1, frame2, frame_index):
    gray1 = cv2.cvtColor(frame1, cv2.COLOR_BGR2GRAY)
    gray2 = cv2.cvtColor(frame2, cv2.COLOR_BGR2GRAY)

    # Fast path for identical or nearly identical frames, including black
    # screens and static title cards.
    if cv2.mean(cv2.absdiff(gray1, gray2))[0] < 5.0:
        print(
            f"  -> Frame {frame_index}: "
            "100.0% similar (Fast Pixel Check)"
        )
        return True

    _, descriptors1 = orb.detectAndCompute(gray1, None)
    _, descriptors2 = orb.detectAndCompute(gray2, None)

    if descriptors1 is None and descriptors2 is None:
        print(
            f"  -> Frame {frame_index}: "
            "100.0% similar (Featureless/Blank)"
        )
        return True

    if descriptors1 is None or descriptors2 is None:
        print(
            f"  -> Frame {frame_index}: "
            "0.0% similar (One frame blank)"
        )
        return False

    matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)
    matches = matcher.match(descriptors1, descriptors2)

    if not matches:
        print(f"  -> Frame {frame_index}: 0.0% similar (No matches)")
        return False

    mean_distance = np.mean([match.distance for match in matches])
    similarity = 1 - (mean_distance / 100)

    print(f"  -> Frame {frame_index}: {similarity * 100:.1f}% similar")
    return similarity >= SIMILARITY_THRESHOLD


def write_empty_label(local_label_path):
    Path(local_label_path).write_text("", encoding="utf-8")


def save_and_upload_video_frame(
    frame,
    output_bucket,
    base_key,
    frame_index,
    working_dir,
):
    clean_image_base = base_key.rsplit(".", 1)[0]
    clean_label_base = clean_image_base.replace("/images/", "/labels/")

    image_key = f"{clean_image_base}_frame_{frame_index}.jpg"
    label_key = f"{clean_label_base}_frame_{frame_index}.txt"

    local_image_path = working_dir / f"frame_{frame_index}.jpg"
    local_label_path = working_dir / f"frame_{frame_index}.txt"

    if not cv2.imwrite(str(local_image_path), frame):
        raise ExtractionError(
            f"OpenCV could not write extracted frame {frame_index}."
        )

    write_empty_label(local_label_path)

    try:
        s3_client.upload_file(
            str(local_image_path),
            output_bucket,
            image_key,
            ExtraArgs={"ContentType": "image/jpeg"},
        )
        s3_client.upload_file(
            str(local_label_path),
            output_bucket,
            label_key,
            ExtraArgs={"ContentType": "text/plain"},
        )
    finally:
        local_image_path.unlink(missing_ok=True)
        local_label_path.unlink(missing_ok=True)

    return image_key, label_key


def resize_for_thumbnail(frame):
    height, width = frame.shape[:2]
    if width <= THUMBNAIL_WIDTH:
        return frame

    scale = THUMBNAIL_WIDTH / float(width)
    target_height = max(1, int(height * scale))

    return cv2.resize(
        frame,
        (THUMBNAIL_WIDTH, target_height),
        interpolation=cv2.INTER_AREA,
    )


def thumbnail_candidate_score(frame):
    """
    Prefer a sharp, normally exposed frame. This generally gives a better
    landmark preview than blindly using frame zero, which is often black.
    """
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    brightness = float(np.mean(gray))

    # Strongly discourage black/white transition frames while still allowing
    # a fallback when the whole clip is dark or bright.
    exposure_penalty = 0.2 if brightness < 25 or brightness > 235 else 1.0
    sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    return sharpness * exposure_penalty


def upload_video_thumbnail(
    frame,
    dataset_bucket,
    landmark_id,
    negative_id,
    working_dir,
):
    thumbnail_key = (
        f"{THUMBNAIL_PREFIX}/{landmark_id}/{negative_id}.jpg"
    )
    local_thumbnail_path = working_dir / "thumbnail.jpg"
    thumbnail = resize_for_thumbnail(frame)

    success = cv2.imwrite(
        str(local_thumbnail_path),
        thumbnail,
        [cv2.IMWRITE_JPEG_QUALITY, THUMBNAIL_JPEG_QUALITY],
    )
    if not success:
        raise ExtractionError("OpenCV could not write the video thumbnail.")

    try:
        s3_client.upload_file(
            str(local_thumbnail_path),
            dataset_bucket,
            thumbnail_key,
            ExtraArgs={
                "ContentType": "image/jpeg",
                "CacheControl": "private, max-age=86400",
            },
        )
    finally:
        local_thumbnail_path.unlink(missing_ok=True)

    return thumbnail_key


def process_video(
    local_source_path,
    dataset_bucket,
    base_dataset_key,
    landmark_id,
    negative_id,
    working_dir,
):
    capture = cv2.VideoCapture(str(local_source_path))
    if not capture.isOpened():
        raise ExtractionError("OpenCV could not open the uploaded video.")

    previous_frame = None
    frame_index = 0
    saved_count = 0
    best_thumbnail_frame = None
    best_thumbnail_score = float("-inf")

    try:
        while True:
            readable, frame = capture.read()
            if not readable:
                break

            if frame_index % FRAME_SKIP != 0:
                frame_index += 1
                continue

            resized = cv2.resize(
                frame,
                (RESIZE_WIDTH, RESIZE_HEIGHT),
            )

            should_save = (
                previous_frame is None
                or not are_frames_similar(
                    previous_frame,
                    resized,
                    frame_index,
                )
            )

            if should_save:
                save_and_upload_video_frame(
                    frame=frame,
                    output_bucket=dataset_bucket,
                    base_key=base_dataset_key,
                    frame_index=frame_index,
                    working_dir=working_dir,
                )
                saved_count += 1
                previous_frame = resized

                candidate_score = thumbnail_candidate_score(frame)
                if candidate_score > best_thumbnail_score:
                    best_thumbnail_score = candidate_score
                    best_thumbnail_frame = frame.copy()

            frame_index += 1
    finally:
        capture.release()

    if saved_count == 0 or best_thumbnail_frame is None:
        raise ExtractionError(
            "The uploaded video did not contain any readable frames."
        )

    thumbnail_key = upload_video_thumbnail(
        frame=best_thumbnail_frame,
        dataset_bucket=dataset_bucket,
        landmark_id=landmark_id,
        negative_id=negative_id,
        working_dir=working_dir,
    )

    return {
        "savedCount": saved_count,
        "thumbnailBucket": dataset_bucket,
        "thumbnailKey": thumbnail_key,
    }


def process_photo(
    local_source_path,
    dataset_bucket,
    base_dataset_key,
):
    # Validate that OpenCV can read the image before publishing it to the
    # training dataset. The original encoded bytes are retained so PNG/JPEG
    # input remains unchanged.
    image = cv2.imread(str(local_source_path), cv2.IMREAD_COLOR)
    if image is None:
        raise ExtractionError("OpenCV could not decode the uploaded image.")

    label_key = dataset_label_key_for_image_key(base_dataset_key)

    s3_client.upload_file(
        str(local_source_path),
        dataset_bucket,
        base_dataset_key,
    )
    s3_client.put_object(
        Bucket=dataset_bucket,
        Key=label_key,
        Body=b"",
        ContentType="text/plain",
    )

    return {
        "savedCount": 1,
        "thumbnailBucket": None,
        "thumbnailKey": None,
    }


def mark_landmark_dirty(landmark_id, media_kind):
    if not MARK_DIRTY_FOR_TRAINING:
        return False

    reason = (
        "hard_negative_video_extracted"
        if media_kind == "video"
        else "hard_negative_image_ready"
    )

    try:
        cluster_mappings_table.update_item(
            Key={"landmarkId": landmark_id},
            UpdateExpression=(
                "SET isDirtyForTraining = :dirty, "
                "dirtyReason = :reason, "
                "updatedAt = :updatedAt"
            ),
            ExpressionAttributeValues={
                ":dirty": True,
                ":reason": reason,
                ":updatedAt": now_epoch_string(),
            },
        )
        return True
    except Exception as exc:
        print(
            "WARNING: Failed to mark landmark dirty: "
            f"{landmark_id}: {exc}"
        )
        return False


def mark_source_record_ready(
    negative_id,
    media_kind,
    saved_count,
    thumbnail_bucket=None,
    thumbnail_key=None,
):
    ready_at = now_epoch_string()
    set_parts = [
        "#status = :status",
        "readyAt = :readyAt",
        "updatedAt = :updatedAt",
        "mediaKind = :mediaKind",
        "extractedFrameCount = :savedCount",
    ]
    values = {
        ":status": "READY",
        ":readyAt": ready_at,
        ":updatedAt": ready_at,
        ":mediaKind": media_kind,
        ":savedCount": saved_count,
    }

    if thumbnail_bucket and thumbnail_key:
        set_parts.extend(
            [
                "thumbnailBucket = :thumbnailBucket",
                "thumbnailKey = :thumbnailKey",
            ]
        )
        values[":thumbnailBucket"] = thumbnail_bucket
        values[":thumbnailKey"] = thumbnail_key

    hard_neg_table.update_item(
        Key={"negativeId": negative_id},
        UpdateExpression="SET " + ", ".join(set_parts),
        ExpressionAttributeNames={"#status": "status"},
        ExpressionAttributeValues=values,
    )

    return ready_at


def mark_history_record_ready(
    negative_id,
    media_kind,
    saved_count,
    ready_at,
    thumbnail_bucket=None,
    thumbnail_key=None,
):
    set_parts = [
        "#status = :status",
        "mediaKind = :mediaKind",
        "readyAt = :readyAt",
        "historyUpdatedAt = :updatedAt",
        "extractedFrameCount = :savedCount",
    ]
    values = {
        ":status": "READY",
        ":mediaKind": media_kind,
        ":readyAt": int(ready_at),
        ":updatedAt": int(ready_at),
        ":savedCount": saved_count,
    }

    if thumbnail_bucket and thumbnail_key:
        set_parts.extend(
            [
                "thumbnailBucket = :thumbnailBucket",
                "thumbnailKey = :thumbnailKey",
            ]
        )
        values[":thumbnailBucket"] = thumbnail_bucket
        values[":thumbnailKey"] = thumbnail_key

    try:
        history_table.update_item(
            Key={
                "historyId": history_id_for_negative(negative_id),
            },
            UpdateExpression="SET " + ", ".join(set_parts),
            ConditionExpression="attribute_exists(historyId)",
            ExpressionAttributeNames={"#status": "status"},
            ExpressionAttributeValues=values,
        )
        return True
    except ClientError as exc:
        error_code = exc.response.get("Error", {}).get("Code")
        if error_code == "ConditionalCheckFailedException":
            print(
                "WARNING: History record did not exist for negative "
                f"{negative_id}; source submission was still finalized."
            )
            return False
        raise


def mark_source_record_failed(negative_id, reason):
    failed_at = now_epoch_string()
    hard_neg_table.update_item(
        Key={"negativeId": negative_id},
        UpdateExpression=(
            "SET #status = :status, "
            "failureReason = :reason, "
            "updatedAt = :updatedAt"
        ),
        ExpressionAttributeNames={"#status": "status"},
        ExpressionAttributeValues={
            ":status": "FAILED",
            ":reason": reason,
            ":updatedAt": failed_at,
        },
    )


def mark_history_record_failed(negative_id, reason):
    try:
        history_table.update_item(
            Key={
                "historyId": history_id_for_negative(negative_id),
            },
            UpdateExpression=(
                "SET #status = :status, "
                "failureReason = :reason, "
                "historyUpdatedAt = :updatedAt"
            ),
            ConditionExpression="attribute_exists(historyId)",
            ExpressionAttributeNames={"#status": "status"},
            ExpressionAttributeValues={
                ":status": "FAILED",
                ":reason": reason,
                ":updatedAt": now_epoch_int(),
            },
        )
    except ClientError as exc:
        error_code = exc.response.get("Error", {}).get("Code")
        if error_code == "ConditionalCheckFailedException":
            print(
                "WARNING: Could not mark missing history record failed for "
                f"negative {negative_id}."
            )
            return
        raise


def validate_event(event):
    required_fields = (
        "negativeId",
        "landmarkId",
        "sourceBucket",
        "sourceKey",
        "datasetBucket",
        "datasetImageBaseKey",
    )

    missing = [
        field
        for field in required_fields
        if not str(event.get(field) or "").strip()
    ]
    if missing:
        raise ExtractionError(
            "Missing required extractor fields: " + ", ".join(missing)
        )


def lambda_handler(event, context):
    negative_id = str(event.get("negativeId") or "").strip()
    working_dir = None

    try:
        validate_event(event)

        negative_id = str(event["negativeId"]).strip()
        landmark_id = str(event["landmarkId"]).strip()
        source_bucket = str(event["sourceBucket"]).strip()
        source_key = str(event["sourceKey"]).strip()
        dataset_bucket = str(event["datasetBucket"]).strip()
        base_dataset_key = str(event["datasetImageBaseKey"]).strip()

        media_kind = detect_media_kind(
            source_bucket=source_bucket,
            source_key=source_key,
            event=event,
        )

        working_dir = Path(
            f"/tmp/looksee-negative-extractor/{negative_id}"
        )
        shutil.rmtree(working_dir, ignore_errors=True)
        working_dir.mkdir(parents=True, exist_ok=True)

        source_extension = extension_for_key(source_key)
        if not source_extension:
            source_extension = ".mov" if media_kind == "video" else ".jpg"

        local_source_path = working_dir / f"source{source_extension}"

        print(
            f"Downloading {media_kind} source "
            f"s3://{source_bucket}/{source_key}..."
        )
        s3_client.download_file(
            source_bucket,
            source_key,
            str(local_source_path),
        )

        if media_kind == "video":
            result = process_video(
                local_source_path=local_source_path,
                dataset_bucket=dataset_bucket,
                base_dataset_key=base_dataset_key,
                landmark_id=landmark_id,
                negative_id=negative_id,
                working_dir=working_dir,
            )
        else:
            result = process_photo(
                local_source_path=local_source_path,
                dataset_bucket=dataset_bucket,
                base_dataset_key=base_dataset_key,
            )

        saved_count = int(result["savedCount"])
        thumbnail_bucket = result.get("thumbnailBucket")
        thumbnail_key = result.get("thumbnailKey")

        # Finalize the operational record first. The history projection then
        # mirrors the same READY state and thumbnail information.
        ready_at = mark_source_record_ready(
            negative_id=negative_id,
            media_kind=media_kind,
            saved_count=saved_count,
            thumbnail_bucket=thumbnail_bucket,
            thumbnail_key=thumbnail_key,
        )

        history_updated = mark_history_record_ready(
            negative_id=negative_id,
            media_kind=media_kind,
            saved_count=saved_count,
            ready_at=ready_at,
            thumbnail_bucket=thumbnail_bucket,
            thumbnail_key=thumbnail_key,
        )

        dirty_marked = mark_landmark_dirty(
            landmark_id=landmark_id,
            media_kind=media_kind,
        )

        response_body = {
            "message": "Hard-negative media processed successfully.",
            "negativeId": negative_id,
            "landmarkId": landmark_id,
            "mediaKind": media_kind,
            "savedCount": saved_count,
            "status": "READY",
            "historyUpdated": history_updated,
            "dirtyMarked": dirty_marked,
            "thumbnailBucket": thumbnail_bucket,
            "thumbnailKey": thumbnail_key,
        }

        print(json.dumps(response_body))
        return {
            "statusCode": 200,
            "body": json.dumps(response_body),
        }

    except Exception as exc:
        reason = str(exc)
        print(f"Fatal error during extraction: {reason}")

        if negative_id:
            try:
                mark_source_record_failed(negative_id, reason)
            except Exception as source_update_error:
                print(
                    "ERROR marking hard-negative submission failed: "
                    f"{source_update_error}"
                )

            try:
                mark_history_record_failed(negative_id, reason)
            except Exception as history_update_error:
                print(
                    "ERROR marking media-history record failed: "
                    f"{history_update_error}"
                )

        return {
            "statusCode": 500,
            "body": json.dumps(
                {
                    "message": "Hard-negative extraction failed.",
                    "negativeId": negative_id or None,
                    "error": reason,
                }
            ),
        }

    finally:
        if working_dir is not None:
            shutil.rmtree(working_dir, ignore_errors=True)
