import cv2
import os
import boto3
import numpy as np
import json
import time
from botocore.exceptions import ClientError

s3_client = boto3.client("s3")
dynamodb = boto3.resource("dynamodb")

# Environment vars to match your DB schema
HARD_NEG_TABLE = os.environ.get("HARD_NEG_TABLE", "LookSeeHardNegativeSubmissions")
CLUSTER_MAPPINGS_TABLE = os.environ.get("CLUSTER_MAPPINGS_TABLE", "LookSeeClusterMappings")
MARK_DIRTY_FOR_TRAINING = os.environ.get("MARK_DIRTY_FOR_TRAINING", "true").lower() == "true"

hard_neg_table = dynamodb.Table(HARD_NEG_TABLE)
cluster_mappings_table = dynamodb.Table(CLUSTER_MAPPINGS_TABLE)

FRAME_SKIP = 1
SIMILARITY_THRESHOLD = 0.85
RESIZE_WIDTH = 640
RESIZE_HEIGHT = 360
TEMP_VIDEO_PATH = "/tmp/temp_video.mov"
TEMP_FRAME_DIR = "/tmp/kept_frames"

orb = cv2.ORB_create(nfeatures=500)

def now_epoch_string():
    return str(int(time.time()))

def are_frames_similar(frame1, frame2):
    gray1 = cv2.cvtColor(frame1, cv2.COLOR_BGR2GRAY)
    gray2 = cv2.cvtColor(frame2, cv2.COLOR_BGR2GRAY)
    k1, d1 = orb.detectAndCompute(gray1, None)
    k2, d2 = orb.detectAndCompute(gray2, None)
    if d1 is None or d2 is None: return False
    bf = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)
    matches = bf.match(d1, d2)
    if len(matches) == 0: return False
    score = np.mean([m.distance for m in matches])
    return (1 - (score / 100)) >= SIMILARITY_THRESHOLD

def save_and_upload(frame, output_bucket, base_key, frame_index):
    # base_key example: ".../Leg_extension_machine/images/hneg_123.jpg"
    clean_image_base = base_key.rsplit('.', 1)[0]
    
    # Swap /images/ to /labels/ for the text file path
    clean_label_base = clean_image_base.replace("/images/", "/labels/")
    
    image_key = f"{clean_image_base}_frame_{frame_index}.jpg"
    label_key = f"{clean_label_base}_frame_{frame_index}.txt"
    
    local_image_path = f"{TEMP_FRAME_DIR}/frame_{frame_index}.jpg"
    local_label_path = f"{TEMP_FRAME_DIR}/frame_{frame_index}.txt"
    
    cv2.imwrite(local_image_path, frame)
    with open(local_label_path, 'w') as f:
        pass # Empty txt file for YOLO

    s3_client.upload_file(local_image_path, output_bucket, image_key)
    s3_client.upload_file(local_label_path, output_bucket, label_key)
    
    os.remove(local_image_path)
    os.remove(local_label_path)

def mark_landmark_dirty(landmark_id):
    if not MARK_DIRTY_FOR_TRAINING: return False
    try:
        cluster_mappings_table.update_item(
            Key={"landmarkId": landmark_id},
            UpdateExpression="SET isDirtyForTraining = :dirty, dirtyReason = :reason, updatedAt = :updatedAt",
            ExpressionAttributeValues={":dirty": True, ":reason": "hard_negative_video_extracted", ":updatedAt": now_epoch_string()}
        )
        return True
    except Exception as exc:
        print(f"WARNING: Failed to mark landmark dirty: {landmark_id}: {exc}")
        return False

def lambda_handler(event, context):
    try:
        negative_id = event['negativeId']
        landmark_id = event['landmarkId']
        source_bucket = event['sourceBucket']
        source_key = event['sourceKey']
        dataset_bucket = event['datasetBucket']
        base_dataset_key = event['datasetImageBaseKey']
        
        if not os.path.exists(TEMP_FRAME_DIR): os.makedirs(TEMP_FRAME_DIR)
        
        print(f"Downloading {source_key} from {source_bucket}...")
        s3_client.download_file(source_bucket, source_key, TEMP_VIDEO_PATH)
        
        cap = cv2.VideoCapture(TEMP_VIDEO_PATH)
        previous_frame, frame_index, saved_count = None, 0, 0

        while True:
            ret, frame = cap.read()
            if not ret: break
            
            if frame_index % FRAME_SKIP != 0:
                frame_index += 1
                continue
                
            resized = cv2.resize(frame, (RESIZE_WIDTH, RESIZE_HEIGHT))
            
            if previous_frame is None or not are_frames_similar(previous_frame, resized):
                save_and_upload(frame, dataset_bucket, base_dataset_key, frame_index)
                saved_count += 1
                previous_frame = resized
                
            frame_index += 1
            
        cap.release()
        if os.path.exists(TEMP_VIDEO_PATH): os.remove(TEMP_VIDEO_PATH)

        # Extraction successful! Finalize the database.
        hard_neg_table.update_item(
            Key={"negativeId": negative_id},
            UpdateExpression="SET #status = :status, readyAt = :readyAt",
            ExpressionAttributeNames={"#status": "status"},
            ExpressionAttributeValues={":status": "READY", ":readyAt": now_epoch_string()}
        )
        
        mark_landmark_dirty(landmark_id)
        
        return {"statusCode": 200, "body": f"Successfully extracted {saved_count} frames for negative {negative_id}."}

    except Exception as e:
        print(f"Fatal error during extraction: {e}")
        # Mark as failed if the video process crashed
        if 'negativeId' in event:
            hard_neg_table.update_item(
                Key={"negativeId": event['negativeId']},
                UpdateExpression="SET #status = :status, failureReason = :reason",
                ExpressionAttributeNames={"#status": "status"},
                ExpressionAttributeValues={":status": "FAILED", ":reason": str(e)}
            )
        return {"statusCode": 500, "body": str(e)}
