import os
import json
import math
import time
import gc
import sys
import signal
import threading
import tempfile
import traceback
import cv2
import numpy as np
import boto3
from botocore.exceptions import ClientError
from boto3.dynamodb.types import TypeSerializer

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

REGION = os.environ.get("AWS_REGION", "us-east-1")
s3 = boto3.client("s3", region_name=REGION)
sqs = boto3.client("sqs", region_name=REGION)
dynamodb = boto3.resource("dynamodb", region_name=REGION)
ddb_client = boto3.client("dynamodb", region_name=REGION)
BUCKET_NAME = os.environ.get("MODEL_BUCKET", "looksee-models")
QUEUE_URL = os.environ.get("QUEUE_URL")
LANDMARKS_TABLE_NAME = os.environ.get("LANDMARKS_TABLE", "LookSeeLandmarks")
DELETIONS_TABLE_NAME = os.environ.get("DELETIONS_TABLE", "LookSeeLandmarkDeletions")
DEFAULT_REQUIRED_FRAMES = int(os.environ.get("MIN_IMAGES_PER_CLASS", "1500"))
STOP = threading.Event()


class ProcessingStopped(Exception):
    """Terminal deletion/missing-landmark outcome; safe to acknowledge."""


class RetryWork(Exception):
    """Keep the message for retry; do not acknowledge."""


class MessageLease:
    """Renew visibility on elapsed time, including model load and no-detection frames."""
    def __init__(self, receipt):
        self.receipt = receipt
        self.done = threading.Event()
        self.failed = threading.Event()
        self.started = time.monotonic()
        self.thread = None

    def renew(self):
        if time.monotonic() - self.started > 11 * 3600:
            raise RetryWork("message_processing_deadline")
        sqs.change_message_visibility(
            QueueUrl=QUEUE_URL,
            ReceiptHandle=self.receipt,
            VisibilityTimeout=900,
        )

    def __enter__(self):
        self.renew()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()
        return self

    def _run(self):
        while not self.done.wait(60):
            try:
                self.renew()
            except Exception:
                self.failed.set()
                traceback.print_exc()
                return

    def check(self):
        if self.failed.is_set():
            raise RetryWork("message_visibility_renewal_failed")

    def __exit__(self, *args):
        self.done.set()
        if self.thread:
            self.thread.join(timeout=2)


class LandmarkGuard:
    def __init__(self, landmark_id, lease=None):
        if not isinstance(landmark_id, str) or not landmark_id:
            raise RetryWork("landmark_binding_missing")
        self.landmark_id = landmark_id
        self.lease = lease

    def check(self):
        if self.lease:
            self.lease.check()
        marker = dynamodb.Table(DELETIONS_TABLE_NAME).get_item(
            Key={"landmarkId": self.landmark_id}, ConsistentRead=True
        ).get("Item")
        if marker is not None:
            raise ProcessingStopped("deletion_requested")
        landmark = dynamodb.Table(LANDMARKS_TABLE_NAME).get_item(
            Key={"landmarkId": self.landmark_id}, ConsistentRead=True
        ).get("Item")
        if not landmark:
            raise ProcessingStopped("landmark_missing")
        if "deletionStatus" in landmark or landmark.get("status") in ("DELETING", "DELETED"):
            raise ProcessingStopped("deletion_requested")
        return landmark

    def finish(self, labeled_count):
        landmark = self.check()
        serializer = TypeSerializer()

        def av(data):
            return {k: serializer.serialize(v) for k, v in data.items()}

        current_status = str(landmark.get("status") or "").strip().upper()
        already_trained = bool(landmark.get("isTrained")) or current_status == "ACTIVE"
        force_train = bool(landmark.get("forceTrainEnabled"))
        required_frames = int(landmark.get("requiredFrames") or DEFAULT_REQUIRED_FRAMES)

        if already_trained:
            # Never downgrade an already-ACTIVE landmark during retraining
            retrain_state = "PENDING_TRAINING" if labeled_count > 0 else "RETRAIN_NO_DETECTIONS"
            update_expr = "SET finalLabeledCount = :c, retrainingStatus = :rs, isProcessing = :proc"
            expr_values = {
                ":c": labeled_count,
                ":rs": retrain_state,
                ":proc": False,
                ":deleting": "DELETING",
                ":deleted": "DELETED",
            }
            print(
                f"[AutoLabeler] Retrain finished for ACTIVE landmark {self.landmark_id}: "
                f"labeled={labeled_count}, retrainingStatus={retrain_state}"
            )
        elif labeled_count == 0 or (labeled_count < required_frames and not force_train):
            missing_frames = max(0, required_frames - labeled_count)
            seconds_needed = math.ceil(missing_frames / 30.0) if missing_frames > 0 else math.ceil(required_frames / 30.0)
            update_expr = (
                "SET #st = :s, isProcessing = :proc, finalLabeledCount = :c, "
                "cleanFrameCount = :c, requiredFrames = :r, secondsNeeded = :sec"
            )
            expr_values = {
                ":s": "NEEDS_MORE_MEDIA",
                ":proc": False,
                ":c": labeled_count,
                ":r": required_frames,
                ":sec": seconds_needed,
                ":deleting": "DELETING",
                ":deleted": "DELETED",
            }
            print(
                f"[AutoLabeler] Shortfall for {self.landmark_id}: "
                f"{labeled_count}/{required_frames} labeled -> NEEDS_MORE_MEDIA"
            )
        else:
            update_expr = (
                "SET #st = :s, isProcessing = :proc, finalLabeledCount = :c, secondsNeeded = :zero"
            )
            expr_values = {
                ":s": "PENDING_TRAINING",
                ":proc": True,
                ":c": labeled_count,
                ":zero": 0,
                ":deleting": "DELETING",
                ":deleted": "DELETED",
            }
            print(
                f"[AutoLabeler] Ready for {self.landmark_id}: "
                f"{labeled_count}/{required_frames} labeled (forceTrain={force_train}) -> PENDING_TRAINING"
            )

        try:
            ddb_client.transact_write_items(TransactItems=[
                {
                    "ConditionCheck": {
                        "TableName": DELETIONS_TABLE_NAME,
                        "Key": av({"landmarkId": self.landmark_id}),
                        "ConditionExpression": "attribute_not_exists(landmarkId)",
                    }
                },
                {
                    "Update": {
                        "TableName": LANDMARKS_TABLE_NAME,
                        "Key": av({"landmarkId": self.landmark_id}),
                        "UpdateExpression": update_expr,
                        "ConditionExpression": (
                            "attribute_exists(landmarkId) AND attribute_not_exists(deletionStatus) "
                            "AND (attribute_not_exists(#st) OR (#st <> :deleting AND #st <> :deleted))"
                        ),
                        "ExpressionAttributeNames": {"#st": "status"},
                        "ExpressionAttributeValues": av(expr_values),
                    }
                },
            ])
        except ClientError:
            self.check()
            raise


def parse_message(body):
    try:
        parsed = json.loads(body)
    except (json.JSONDecodeError, TypeError):
        parsed = body
    if isinstance(parsed, dict):
        folder, landmark_id = parsed.get("folder_name"), parsed.get("landmarkId")
    else:
        folder, landmark_id = parsed, None
    if not isinstance(folder, str) or not folder or "/" in folder or "\\" in folder or folder in (".", ".."):
        raise RetryWork("invalid_folder")
    if landmark_id is not None and (not isinstance(landmark_id, str) or not landmark_id):
        raise RetryWork("invalid_landmark_id")
    if landmark_id and not folder.startswith(landmark_id + "_"):
        raise RetryWork("queue_landmark_mismatch")
    return folder, landmark_id


def process_folder(folder, queued_landmark_id, lease, workdir, model_factory=None, ontology_factory=None):
    if queued_landmark_id:
        LandmarkGuard(queued_landmark_id, lease).check()
    metadata_key = f"clean-frames/{folder}/metadata.json"
    response = s3.get_object(Bucket=BUCKET_NAME, Key=metadata_key)
    try:
        metadata = json.loads(response["Body"].read())
    finally:
        response["Body"].close()
    landmark_id = metadata.get("landmarkId")
    if queued_landmark_id and landmark_id != queued_landmark_id:
        raise RetryWork("metadata_landmark_mismatch")
    guard = LandmarkGuard(landmark_id, lease)
    landmark = guard.check()
    class_name = metadata.get("class_name")
    if not isinstance(class_name, str) or not class_name:
        raise RetryWork("class_name_missing")
    class_name = class_name.replace(" ", "_")
    if "/" in class_name or "\\" in class_name or folder != f"{landmark_id}_{class_name}":
        raise RetryWork("metadata_folder_mismatch")
    INPUT_PREFIX = f"clean-frames/{folder}/"
    if model_factory is None:
        from autodistill_grounding_dino import GroundingDINO
        from autodistill.detection import CaptionOntology
        model_factory, ontology_factory = GroundingDINO, CaptionOntology
    model = None
    try:
        # Prefer canonical prompts stored on LookSeeLandmarks so retraining clips never drift prompts
        raw_prompts = landmark.get("prompts") or metadata.get("prompts")
        if isinstance(raw_prompts, list) and len(raw_prompts) > 0:
            prompts = [str(p) for p in raw_prompts]
        elif isinstance(raw_prompts, str) and raw_prompts.strip():
            prompts = [raw_prompts.strip()]
        else:
            single_prompt = metadata.get("prompt", class_name.replace("_", " "))
            prompts = [str(single_prompt)]

        prompts = [
            p.replace("[", "").replace("]", "").replace('"', "").replace("\\", "").strip()
            for p in prompts
        ]
        prompts = [p for p in prompts if p] or [class_name.replace("_", " ")]

        print(f"Target Object: {class_name} | AI Prompts ({len(prompts)}): {prompts}")

        ontology_dict = {prompt_text: class_name for prompt_text in prompts}
        ontology = ontology_factory(ontology_dict)

        guard.check()
        model = model_factory(
            ontology=ontology,
            box_threshold=0.35,
            text_threshold=0.35,
        )

        print("Fetching frame list using S3 Paginator...")
        paginator = s3.get_paginator("list_objects_v2")
        pages = paginator.paginate(Bucket=BUCKET_NAME, Prefix=INPUT_PREFIX)

        all_keys = []
        for page in pages:
            guard.check()
            for obj in page.get("Contents", []):
                if obj["Key"].lower().endswith((".jpg", ".png", ".jpeg")):
                    all_keys.append(obj["Key"])

        if not all_keys:
            guard.finish(0)
            return {"status": "COMPLETE", "landmarkId": landmark_id, "labeledCount": 0}

        total_frames = len(all_keys)
        print(f"Found {total_frames} frames to process. Starting Multi-Prompt Auto-Labeling...")

        count = 0
        labeled_count = 0

        for img_key in all_keys:
            guard.check()
            img_name = os.path.basename(img_key)
            local_img_path = os.path.join(workdir, img_name)

            label_name = os.path.splitext(img_name)[0] + ".txt"
            s3_label_out = f"neg-dataset/{class_name}/labels/{label_name}"

            # S3 Checkpointing: keep existing labeled pairs intact across retrains
            try:
                s3.head_object(Bucket=BUCKET_NAME, Key=s3_label_out)
                s3.head_object(Bucket=BUCKET_NAME, Key=f"neg-dataset/{class_name}/images/{img_name}")
                count += 1
                labeled_count += 1
                continue
            except ClientError as e:
                if e.response["Error"]["Code"] not in ("404", "NoSuchKey", "NotFound"):
                    raise

            s3.download_file(BUCKET_NAME, img_key, local_img_path)
            guard.check()
            image = cv2.imread(local_img_path)
            if image is None:
                print(f"Warning: Skipping unreadable image {img_key}")
                if os.path.exists(local_img_path):
                    os.remove(local_img_path)
                count += 1
                continue

            detections = model.predict(image)
            guard.check()

            if len(detections.xyxy) == 0:
                os.remove(local_img_path)
                del image
                del detections
                count += 1
                continue

            try:
                max_conf = max(detections.confidence)
                print(f"  -> Frame {img_name}: Object found with {max_conf * 100:.1f}% confidence")
            except Exception:
                pass

            # --- GOLDILOCKS MASTER BOX MATH ---
            h, w, _ = image.shape
            img_area = w * h

            valid_detections = []
            for box, conf in zip(detections.xyxy, detections.confidence):
                box_area = (box[2] - box[0]) * (box[3] - box[1])
                if box_area < (0.9 * img_area):
                    valid_detections.append((box, conf))

            if not valid_detections:
                best_idx = np.argmax(detections.confidence)
                valid_detections = [(detections.xyxy[best_idx], detections.confidence[best_idx])]

            valid_detections.sort(key=lambda x: x[1], reverse=True)
            anchor_box = valid_detections[0][0]
            boxes_to_merge = [anchor_box]

            def is_close_to_anchor(b1, b2, threshold):
                b1_expanded = [b1[0] - threshold, b1[1] - threshold, b1[2] + threshold, b1[3] + threshold]
                if b1_expanded[0] > b2[2] or b1_expanded[2] < b2[0]:
                    return False
                if b1_expanded[1] > b2[3] or b1_expanded[3] < b2[1]:
                    return False
                return True

            proximity_threshold = max(w, h) * 0.10
            for box, conf in valid_detections[1:]:
                if is_close_to_anchor(anchor_box, box, threshold=proximity_threshold):
                    boxes_to_merge.append(box)

            x_min = min(b[0] for b in boxes_to_merge)
            y_min = min(b[1] for b in boxes_to_merge)
            x_max = max(b[2] for b in boxes_to_merge)
            y_max = max(b[3] for b in boxes_to_merge)

            padding_pct = 0.04
            box_w = x_max - x_min
            box_h = y_max - y_min

            pad_x = box_w * padding_pct
            pad_y = box_h * padding_pct

            final_xmin = max(0, x_min - pad_x)
            final_ymin = max(0, y_min - pad_y)
            final_xmax = min(w, x_max + pad_x)
            final_ymax = min(h, y_max + pad_y)

            x_center = max(0.0, min(1.0, ((final_xmin + final_xmax) / 2) / w))
            y_center = max(0.0, min(1.0, ((final_ymin + final_ymax) / 2) / h))
            bw = max(0.0, min(1.0, (final_xmax - final_xmin) / w))
            bh = max(0.0, min(1.0, (final_ymax - final_ymin) / h))

            yolo_lines = [f"0 {x_center:.6f} {y_center:.6f} {bw:.6f} {bh:.6f}"]

            local_label_path = os.path.join(workdir, label_name)
            with open(local_label_path, "w", encoding="utf-8") as f:
                f.write("\n".join(yolo_lines))

            s3_img_out = f"neg-dataset/{class_name}/images/{img_name}"
            guard.check()
            s3.upload_file(local_img_path, BUCKET_NAME, s3_img_out)
            guard.check()
            s3.upload_file(local_label_path, BUCKET_NAME, s3_label_out)
            guard.check()

            os.remove(local_img_path)
            os.remove(local_label_path)

            del image
            del detections

            count += 1
            labeled_count += 1

        guard.finish(labeled_count)
        return {"status": "COMPLETE", "landmarkId": landmark_id, "labeledCount": labeled_count}
    finally:
        if model is not None:
            del model
        gc.collect()
        torch_module = sys.modules.get("torch")
        if torch_module is not None and torch_module.cuda.is_available():
            torch_module.cuda.empty_cache()


def handle_message(message, model_factory=None, ontology_factory=None):
    receipt = message["ReceiptHandle"]
    with MessageLease(receipt) as lease:
        try:
            folder, landmark_id = parse_message(message["Body"])
            with tempfile.TemporaryDirectory(prefix="autolabel-") as workdir:
                result = process_folder(
                    folder, landmark_id, lease, workdir,
                    model_factory, ontology_factory,
                )
        except ProcessingStopped as exc:
            result = {"status": "SKIPPED", "reason": str(exc)}
        lease.check()
        sqs.delete_message(QueueUrl=QUEUE_URL, ReceiptHandle=receipt)
        print("[AutoLabeler] " + json.dumps(result))
        return result


def handle_sigterm(*args):
    print("[AutoLabeler] Shutdown requested; finishing current item before exit")
    STOP.set()


def main():
    if not QUEUE_URL:
        raise RuntimeError("QUEUE_URL is required")
    signal.signal(signal.SIGTERM, handle_sigterm)
    signal.signal(signal.SIGINT, handle_sigterm)
    print("[AutoLabeler] gpu-deletion-guard-v2 ready")
    while not STOP.is_set():
        try:
            response = sqs.receive_message(
                QueueUrl=QUEUE_URL,
                MaxNumberOfMessages=1,
                WaitTimeSeconds=10,
                VisibilityTimeout=900,
            )
            if not response.get("Messages"):
                print("[AutoLabeler] Queue is empty. Shutting down worker container so EC2 can terminate.")
                return
            for message in response["Messages"]:
                try:
                    handle_message(message)
                except Exception:
                    print("[AutoLabeler] Work failed; message retained for retry")
                    traceback.print_exc()
        except Exception:
            traceback.print_exc()
            STOP.wait(5)


if __name__ == "__main__":
    main()