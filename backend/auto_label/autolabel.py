import os
import cv2
import torch
import boto3
import json
import gc
import time 
import sys
import numpy as np
from botocore.exceptions import ClientError

# --- CRITICAL FIX 1: Instant CloudWatch Logging ---
# Forces Python to flush print statements to CloudWatch immediately
sys.stdout.reconfigure(line_buffering=True)

from autodistill_grounding_dino import GroundingDINO
from autodistill.detection import CaptionOntology

# -----------------------------------------
# AWS SETUP
# -----------------------------------------
s3 = boto3.client('s3')
sqs = boto3.client('sqs')

BUCKET_NAME = 'looksee-models'
QUEUE_URL = os.environ.get('QUEUE_URL')

if not QUEUE_URL:
    print("❌ Error: No QUEUE_URL environment variable found.")
    exit(1)

device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"🚀 Booting up LookSee Worker on {device}. Checking SQS queue for tasks...")

# -----------------------------------------
# SQS POLLING LOOP
# -----------------------------------------
while True:
    # 1. PULL MESSAGE FROM SQS
    response = sqs.receive_message(
        QueueUrl=QUEUE_URL,
        MaxNumberOfMessages=1,
        WaitTimeSeconds=10 # Long polling: wait 10s before giving up
    )

    if 'Messages' not in response:
        print("Queue is empty. Waiting for new tasks (30s sleep)...")
        time.sleep(30)
        continue 

    message = response['Messages'][0]
    receipt_handle = message['ReceiptHandle']
    SUBMISSION_FOLDER = message['Body']
    
    # FAILSAFE: Safely unpack JSON if needed
    try:
        parsed = json.loads(SUBMISSION_FOLDER)
        if isinstance(parsed, dict) and "folder_name" in parsed:
            SUBMISSION_FOLDER = parsed["folder_name"]
    except (json.JSONDecodeError, TypeError):
        pass # It's a standard string, which is correct!

    INPUT_PREFIX = f"clean-frames/{SUBMISSION_FOLDER}/"

    print(f"📦 Picked up folder from queue: {SUBMISSION_FOLDER}")

    # -----------------------------------------
    # 2. LOAD SUBMISSION METADATA
    # -----------------------------------------
    metadata_key = f"{INPUT_PREFIX}metadata.json"
    local_metadata_path = f"/tmp/metadata_{SUBMISSION_FOLDER}.json"

    try:
        print(f"Downloading metadata: {metadata_key}")
        s3.download_file(BUCKET_NAME, metadata_key, local_metadata_path)
        with open(local_metadata_path, 'r') as f:
            metadata = json.load(f)
    except Exception as e:
        print(f"❌ Error: Missing or corrupt metadata in {SUBMISSION_FOLDER}. Deleting message.")
        sqs.delete_message(QueueUrl=QUEUE_URL, ReceiptHandle=receipt_handle)
        continue 

    class_name = metadata.get('class_name', 'UnknownObject').replace(" ", "_")
    prompt = metadata.get('prompt', class_name)

    print(f"🎯 Target Object: {class_name} | AI Prompt: {prompt}")

    # -----------------------------------------
    # 3. INITIALIZE MODEL PER TASK
    # -----------------------------------------
    ontology = CaptionOntology({prompt: class_name})
    model = GroundingDINO(
        ontology=ontology,
        box_threshold=0.35,
        text_threshold=0.35
    )

    # -----------------------------------------
    # 4. PROCESS FOLDER 
    # -----------------------------------------
    print("Fetching frame list using S3 Paginator...")
    paginator = s3.get_paginator('list_objects_v2')
    pages = paginator.paginate(Bucket=BUCKET_NAME, Prefix=INPUT_PREFIX)
    
    all_keys = []
    for page in pages:
        for obj in page.get('Contents', []):
            if obj['Key'].lower().endswith((".jpg", ".png", ".jpeg")):
                all_keys.append(obj['Key'])

    if not all_keys:
        print("⚠️ No frames found. Deleting message.")
        del model
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
        sqs.delete_message(QueueUrl=QUEUE_URL, ReceiptHandle=receipt_handle)
        continue

    total_frames = len(all_keys)
    print(f"📸 Found {total_frames} frames to process. Starting Auto-Labeling...")

    count = 0
    labeled_count = 0
    
    for img_key in all_keys:
        img_name = os.path.basename(img_key)
        local_img_path = f"/tmp/{img_name}"
        
        label_name = os.path.splitext(img_name)[0] + ".txt"
        s3_label_out = f"neg-dataset/{class_name}/labels/{label_name}"

        # --- S3 CHECKPOINTING ---
        try:
            s3.head_object(Bucket=BUCKET_NAME, Key=s3_label_out)
            # If no error is thrown, the file exists! Skip this frame.
            count += 1
            continue
        except ClientError as e:
            if e.response['Error']['Code'] != '404':
                print(f"Unexpected S3 error checking checkpoint: {e}")
            pass

        # Download image from S3
        s3.download_file(BUCKET_NAME, img_key, local_img_path)
        image = cv2.imread(local_img_path)
        
        # Run AI Prediction
        detections = model.predict(image)

        # Skip frame if AI finds nothing
        if len(detections.xyxy) == 0:
            os.remove(local_img_path)
            del image
            del detections
            count += 1
            continue

        # --- EXTRACT CONFIDENCE SCORE ---
        try:
            max_conf = max(detections.confidence)
            print(f"  -> Frame {img_name}: Object found with {max_conf * 100:.1f}% confidence")
        except Exception:
            pass

        # --- GOLDILOCKS MASTER BOX MATH ---
        h, w, _ = image.shape
        img_area = w * h
        
        # 1. Pair boxes with confidence and filter out >90% screen size anomalies
        valid_detections = []
        for box, conf in zip(detections.xyxy, detections.confidence):
            box_area = (box[2] - box[0]) * (box[3] - box[1])
            if box_area < (0.9 * img_area):
                valid_detections.append((box, conf))
                
        if not valid_detections:
            # Failsafe: Use highest confidence box if everything was over 90% screen size
            best_idx = np.argmax(detections.confidence)
            valid_detections = [(detections.xyxy[best_idx], detections.confidence[best_idx])]

        # 2. Sort by confidence (Highest first) to set our "Anchor"
        valid_detections.sort(key=lambda x: x[1], reverse=True)
        anchor_box = valid_detections[0][0]
        boxes_to_merge = [anchor_box]

        # 3. Helper function to check if a box touches/is close to the anchor box
        def is_close_to_anchor(b1, b2, threshold):
            b1_expanded = [b1[0]-threshold, b1[1]-threshold, b1[2]+threshold, b1[3]+threshold]
            if b1_expanded[0] > b2[2] or b1_expanded[2] < b2[0]: return False
            if b1_expanded[1] > b2[3] or b1_expanded[3] < b2[1]: return False
            return True

        # 4. Outlier Rejection: Only merge boxes within 10% distance of the Anchor
        proximity_threshold = max(w, h) * 0.10
        for box, conf in valid_detections[1:]:
            if is_close_to_anchor(anchor_box, box, threshold=proximity_threshold):
                boxes_to_merge.append(box)

        # 5. Get the tight boundaries of all valid parts
        x_min = min(b[0] for b in boxes_to_merge)
        y_min = min(b[1] for b in boxes_to_merge)
        x_max = max(b[2] for b in boxes_to_merge)
        y_max = max(b[3] for b in boxes_to_merge)

        # 6. Apply Proportional 4% Padding
        padding_pct = 0.04
        box_w = x_max - x_min
        box_h = y_max - y_min
        
        pad_x = box_w * padding_pct
        pad_y = box_h * padding_pct
        
        final_xmin = max(0, x_min - pad_x)
        final_ymin = max(0, y_min - pad_y)
        final_xmax = min(w, x_max + pad_x)
        final_ymax = min(h, y_max + pad_y)

        # 7. Convert to YOLO format (strictly clamped between 0.0 and 1.0)
        x_center = max(0.0, min(1.0, ((final_xmin + final_xmax) / 2) / w))
        y_center = max(0.0, min(1.0, ((final_ymin + final_ymax) / 2) / h))
        bw = max(0.0, min(1.0, (final_xmax - final_xmin) / w))
        bh = max(0.0, min(1.0, (final_ymax - final_ymin) / h))
        
        yolo_lines = [f"0 {x_center:.6f} {y_center:.6f} {bw:.6f} {bh:.6f}"]

        # Save label file locally
        local_label_path = f"/tmp/{label_name}"
        with open(local_label_path, "w") as f:
            f.write("\n".join(yolo_lines))

        # Upload to final dataset folders
        s3_img_out = f"neg-dataset/{class_name}/images/{img_name}"

        s3.upload_file(local_img_path, BUCKET_NAME, s3_img_out)
        s3.upload_file(local_label_path, BUCKET_NAME, s3_label_out)

        # Clean up /tmp/
        os.remove(local_img_path)
        os.remove(local_label_path)
        
        # --- TRUE MEMORY WIPE ---
        del image
        del detections
            
        count += 1
        labeled_count += 1
        
        # --- DYNAMIC HEARTBEAT ---
        if count % 25 == 0:
            print(f"⏳ Processed {count}/{total_frames} frames. Extending SQS visibility timeout by 15 mins...")
            try:
                sqs.change_message_visibility(
                    QueueUrl=QUEUE_URL,
                    ReceiptHandle=receipt_handle,
                    VisibilityTimeout=900
                )
            except Exception as e:
                print(f"⚠️ Warning: Failed to extend heartbeat: {e}")

    # --- COMPLETE GPU WIPE ---
    del model
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    # --- VERIFICATION LOGS ---
    print(f"🏁 VERIFICATION: Loop finished. Processed {count} out of {total_frames} frames.")
    if count == total_frames:
        print("✅ SUCCESS: 100% of frames ran through the AutoLabeler pipeline.")
    else:
        print(f"⚠️ WARNING: Only {count} out of {total_frames} frames were accounted for.")

    print(f"🏷️ Labeled {labeled_count} objects total for {class_name}.")
    
    # -----------------------------------------
    # 5. MARK MESSAGE AS DONE (DELETE FROM QUEUE)
    # -----------------------------------------
    sqs.delete_message(QueueUrl=QUEUE_URL, ReceiptHandle=receipt_handle)
    print(f"🗑️ SUCCESS: Removed {SUBMISSION_FOLDER} from queue.")