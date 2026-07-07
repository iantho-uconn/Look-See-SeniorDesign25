import os
import cv2
import torch
import boto3
import json
import gc
import time 

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
    print("Error: No QUEUE_URL environment variable found.")
    exit(1)

device = "cuda" if torch.cuda.is_available() else "cpu"
print("Booting up LookSee Worker. Checking SQS queue...")

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
        time.sleep(30) # <--- FIX: Pause for 30 seconds
        continue # <--- FIX: Loop back to check the queue again instead of breaking

    message = response['Messages'][0]
    receipt_handle = message['ReceiptHandle']
    SUBMISSION_FOLDER = message['Body']
    INPUT_PREFIX = f"clean-frames/{SUBMISSION_FOLDER}/"

    print(f"Picked up folder from queue: {SUBMISSION_FOLDER}")

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
        print(f"Error: Missing or corrupt metadata in {SUBMISSION_FOLDER}. Deleting message and skipping.")
        sqs.delete_message(QueueUrl=QUEUE_URL, ReceiptHandle=receipt_handle)
        continue # Move to the next message in the queue

    class_name = metadata.get('class_name', 'UnknownObject').replace(" ", "_")
    prompt = metadata.get('prompt', class_name)

    print(f"Processing submission: {SUBMISSION_FOLDER}")
    print(f"Target Object: {class_name} | AI Prompt: {prompt}")

    # -----------------------------------------
    # 3. SETUP MODEL
    # -----------------------------------------
    ontology = CaptionOntology({prompt: class_name})
    
    # --- UPGRADE: LOOSER THRESHOLDS ---
    model = GroundingDINO(
        ontology=ontology,
        box_threshold=0.15,
        text_threshold=0.15
    )

    # -----------------------------------------
    # 4. PROCESS FOLDER (BULLETPROOF EDITION)
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
        print("No frames found. Deleting message.")
        sqs.delete_message(QueueUrl=QUEUE_URL, ReceiptHandle=receipt_handle)
        continue

    count = 0
    for img_key in all_keys:
        img_name = os.path.basename(img_key)
        local_img_path = f"/tmp/{img_name}"
        
        # Download image from S3
        s3.download_file(BUCKET_NAME, img_key, local_img_path)
        image = cv2.imread(local_img_path)
        
        # Run AI Prediction
        detections = model.predict(image)

        # Skip frame if AI finds nothing
        if len(detections.xyxy) == 0:
            os.remove(local_img_path)
            # --- MEMORY LEAK FIX (SKIPPED FRAMES) ---
            del image
            del detections
            gc.collect()
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
            continue

        # --- UPGRADE: MASTER BOX MATH ---
        h, w, _ = image.shape
        yolo_lines = []
        
        if len(detections.xyxy) > 0:
            # Grab the absolute outermost coordinates across ALL detected boxes
            x_min = min(box[0] for box in detections.xyxy)
            y_min = min(box[1] for box in detections.xyxy)
            x_max = max(box[2] for box in detections.xyxy)
            y_max = max(box[3] for box in detections.xyxy)
            
            # Calculate YOLO coordinates for the single Master Box
            x_center = ((x_min + x_max) / 2) / w
            y_center = ((y_min + y_max) / 2) / h
            bw = (x_max - x_min) / w
            bh = (y_max - y_min) / h
            
            # Use '0' as class index for single-class training
            yolo_lines.append(f"0 {x_center:.6f} {y_center:.6f} {bw:.6f} {bh:.6f}")

        # Save label file locally
        label_name = os.path.splitext(img_name)[0] + ".txt"
        local_label_path = f"/tmp/{label_name}"
        with open(local_label_path, "w") as f:
            f.write("\n".join(yolo_lines))

        # Upload to final dataset folders
        s3_img_out = f"neg-dataset/{class_name}/images/{img_name}"
        s3_label_out = f"neg-dataset/{class_name}/labels/{label_name}"

        s3.upload_file(local_img_path, BUCKET_NAME, s3_img_out)
        s3.upload_file(local_label_path, BUCKET_NAME, s3_label_out)

        # Clean up /tmp/
        os.remove(local_img_path)
        os.remove(local_label_path)
        
        # --- THE TRUE MEMORY WIPE ---
        del image
        del detections
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()  # Forces the GPU to release the hoarding memory
            
        count += 1
        
        # Print a heartbeat so CloudWatch logs show it hasn't frozen
        if count % 50 == 0:
            print(f"Heartbeat: Processed {count}/{len(all_keys)} frames...")

    print(f"DONE: Successfully labeled {count} images for {class_name}.")
    
    # -----------------------------------------
    # 5. MARK MESSAGE AS DONE (DELETE FROM QUEUE)
    # -----------------------------------------
    sqs.delete_message(QueueUrl=QUEUE_URL, ReceiptHandle=receipt_handle)
    print(f"SUCCESS: Removed {SUBMISSION_FOLDER} from queue.")