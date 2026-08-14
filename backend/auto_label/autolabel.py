import os
import cv2
import torch
import boto3
import json
import gc
import time 
import sys
import math
import numpy as np
from botocore.exceptions import ClientError

# --- CRITICAL FIX 1: Instant CloudWatch Logging ---
sys.stdout.reconfigure(line_buffering=True)

from autodistill_grounding_dino import GroundingDINO
from autodistill.detection import CaptionOntology

# -----------------------------------------
# AWS SETUP
# -----------------------------------------
s3 = boto3.client('s3')
sqs = boto3.client('sqs')
dynamodb = boto3.resource('dynamodb', region_name=os.environ.get("AWS_REGION", "us-east-1"))

BUCKET_NAME = 'looksee-models'
QUEUE_URL = os.environ.get('QUEUE_URL')
LANDMARKS_TABLE_NAME = os.environ.get('LANDMARKS_TABLE', 'LookSeeLandmarks')

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
        WaitTimeSeconds=10 
    )

    if 'Messages' not in response:
        print("Queue is empty. Waiting for new tasks (30s sleep)...")
        time.sleep(30)
        continue 

    message = response['Messages'][0]
    receipt_handle = message['ReceiptHandle']
    SUBMISSION_FOLDER = message['Body']
    
    try:
        parsed = json.loads(SUBMISSION_FOLDER)
        if isinstance(parsed, dict) and "folder_name" in parsed:
            SUBMISSION_FOLDER = parsed["folder_name"]
    except (json.JSONDecodeError, TypeError):
        pass 

    INPUT_PREFIX = f"clean-frames/{SUBMISSION_FOLDER}/"
    print(f"\n📦 Picked up folder from queue: {SUBMISSION_FOLDER}")

    # -----------------------------------------
    # 2. LOAD SUBMISSION METADATA & MULTI-PROMPTS
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
    landmark_id = metadata.get('landmarkId')

    raw_prompts = metadata.get('prompts')
    if isinstance(raw_prompts, list) and len(raw_prompts) > 0:
        prompts = [str(p) for p in raw_prompts]
    else:
        single_prompt = metadata.get('prompt', class_name)
        prompts = [str(single_prompt)]

    # --- 🧼 PROMPT SANITIZATION STEP ---
    prompts = [
        p.replace('[', '').replace(']', '').replace('"', '').replace('\\', '').strip() 
        for p in prompts
    ]
    prompts = [p for p in prompts if p]

    print(f"🎯 Target Object: {class_name} | AI Prompts ({len(prompts)}): {prompts}")

    # -----------------------------------------
    # 3. INITIALIZE MULTI-PROMPT ONTOLOGY
    # -----------------------------------------
    ontology_dict = {prompt_text: class_name for prompt_text in prompts}
    ontology = CaptionOntology(ontology_dict)
    
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
    print(f"📸 Found {total_frames} frames to process. Starting Multi-Prompt Auto-Labeling...")

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
            count += 1
            labeled_count += 1
            continue
        except ClientError as e:
            if e.response['Error']['Code'] != '404':
                print(f"Unexpected S3 error checking checkpoint: {e}")
            pass

        s3.download_file(BUCKET_NAME, img_key, local_img_path)
        image = cv2.imread(local_img_path)
        
        # Run AI Prediction across all candidate prompts simultaneously
        detections = model.predict(image)

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
            b1_expanded = [b1[0]-threshold, b1[1]-threshold, b1[2]+threshold, b1[3]+threshold]
            if b1_expanded[0] > b2[2] or b1_expanded[2] < b2[0]: return False
            if b1_expanded[1] > b2[3] or b1_expanded[3] < b2[1]: return False
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

        local_label_path = f"/tmp/{label_name}"
        with open(local_label_path, "w") as f:
            f.write("\n".join(yolo_lines))

        s3_img_out = f"neg-dataset/{class_name}/images/{img_name}"
        s3.upload_file(local_img_path, BUCKET_NAME, s3_img_out)
        s3.upload_file(local_label_path, BUCKET_NAME, s3_label_out)

        os.remove(local_img_path)
        os.remove(local_label_path)
        
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
    # 🚀 FINAL DYNAMODB STATUS UPDATE (POST-LABELING)
    # -----------------------------------------
    if landmark_id:
        landmarks_table = dynamodb.Table(LANDMARKS_TABLE_NAME)
        
        # Frame math was already validated by the Lambda. 
        # Now we just mark it ready for SageMaker.
        print(f"✅ AUTOLABELING COMPLETE: {labeled_count} frames successfully labeled. Ready for training!")
        try:
            landmarks_table.update_item(
                Key={'landmarkId': landmark_id},
                UpdateExpression="SET #st = :s, finalLabeledCount = :c",
                ExpressionAttributeNames={'#st': 'status'},
                ExpressionAttributeValues={
                    ':s': 'READY_FOR_TRAINING',
                    ':c': labeled_count
                }
            )
            print("💾 Successfully updated DynamoDB landmark status to 'READY_FOR_TRAINING'.")
        except Exception as e:
            print(f"⚠️ Failed to update DynamoDB success status: {e}")
    else:
        print("ℹ️ No landmarkId found in metadata; skipping DynamoDB status update.")
    
    # -----------------------------------------
    # 5. MARK MESSAGE AS DONE (DELETE FROM QUEUE)
    # -----------------------------------------
    sqs.delete_message(QueueUrl=QUEUE_URL, ReceiptHandle=receipt_handle)
    print(f"🗑️ SUCCESS: Removed {SUBMISSION_FOLDER} from queue.")