import cv2
import os
import boto3
import numpy as np
import urllib.parse
import json
import decimal

#to read decimals to prevent errors
class DecimalEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, decimal.Decimal):
            return float(obj)
        return super(DecimalEncoder, self).default(obj)

s3_client = boto3.client("s3")
dynamodb = boto3.resource("dynamodb")
ecs_client = boto3.client("ecs")

FRAME_SKIP = 1
SIMILARITY_THRESHOLD = 0.85
RESIZE_WIDTH = 640
RESIZE_HEIGHT = 360
TEMP_VIDEO_PATH = "/tmp/temp_video.mp4"
TEMP_FRAME_DIR = "/tmp/kept_frames"

orb = cv2.ORB_create(nfeatures=500)

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

def save_and_upload(frame, output_bucket, folder_path, frame_index):
    unique_name = f"frame_{frame_index}.jpg"
    local_path = f"{TEMP_FRAME_DIR}/{unique_name}"
    cv2.imwrite(local_path, frame)
    s3_client.upload_file(local_path, output_bucket, f"{folder_path}/{unique_name}")
    os.remove(local_path)

def process_video(input_bucket, video_key, output_bucket, folder_path):
    if not os.path.exists(TEMP_FRAME_DIR): os.makedirs(TEMP_FRAME_DIR)
    s3_client.download_file(input_bucket, video_key, TEMP_VIDEO_PATH)
    cap = cv2.VideoCapture(TEMP_VIDEO_PATH)
    previous_frame, frame_index = None, 0

    while True:
        ret, frame = cap.read()
        if not ret: break
        if frame_index % FRAME_SKIP != 0:
            frame_index += 1
            continue
        resized = cv2.resize(frame, (RESIZE_WIDTH, RESIZE_HEIGHT))
        if previous_frame is None or not are_frames_similar(previous_frame, resized):
            save_and_upload(frame, output_bucket, folder_path, frame_index)
            previous_frame = resized
        frame_index += 1
    cap.release()
    if os.path.exists(TEMP_VIDEO_PATH): os.remove(TEMP_VIDEO_PATH)

def lambda_handler(event, context):
    # Handle Trigger Type (EventBridge vs S3)
    if 'detail' in event:
        # EventBridge Format
        input_bucket = event['detail']['bucket']['name']
        video_key = event['detail']['object']['key']
    elif 'Records' in event:
        # Direct S3 Format
        input_bucket = event['Records'][0]['s3']['bucket']['name']
        video_key = event['Records'][0]['s3']['object']['key']
    else:
        print(f"Error: Unsupported event format. Event: {json.dumps(event)}")
        return {"statusCode": 400, "body": "Unsupported event format"}

    # Ensure key is decoded 
    video_key = urllib.parse.unquote_plus(video_key)
    output_bucket = "looksee-models"
    
    # Extract ID from path
    path_parts = video_key.split('/')
    submission_id = path_parts[-2] 

    # Fetch metadata from DynamoDB
    table = dynamodb.Table('LookSeeSubmissions')
    db_response = table.get_item(Key={'submissionId': submission_id})
    item = db_response.get('Item', {})

    # Match DynamoDB columns. Use label if it exists, otherwise fall back to landmarkId.
    raw_label = item.get('label')
    if not raw_label:
        raw_label = item.get('landmarkId', 'Unknown')
        
    class_name = str(raw_label).replace(" ", "_")
    prompt = item.get('shortDescription', class_name)

    # Define paths
    folder_name = f"{submission_id}_{class_name}"
    folder_path = f"clean-frames/{folder_name}"

    # Process Video or Copy Image
    if "/images/" in video_key:
        target_filename = video_key.split('/')[-1]
        s3_client.copy({'Bucket': input_bucket, 'Key': video_key}, output_bucket, f"{folder_path}/{target_filename}")
    else:
        process_video(input_bucket, video_key, output_bucket, folder_path)

    # Upload metadata.json for ECS
    metadata_content = item.copy()
    metadata_content["class_name"] = class_name
    metadata_content["prompt"] = prompt
    metadata_content["submissionId"] = submission_id
    
    s3_client.put_object(
        Bucket=output_bucket,
        Key=f"{folder_path}/metadata.json",
        Body=json.dumps(metadata_content, cls=DecimalEncoder),
        ContentType='application/json'
    )

    # TRIGGER ECS 
    ecs_client.run_task(
        cluster='LookSee-Cluster',
        taskDefinition='LookSee-AutoLabeler-Task',
        launchType='FARGATE',
        overrides={
            'containerOverrides': [{
                'name': 'autolabeler-container',
                'environment': [
                    {'name': 'SUBMISSION_FOLDER', 'value': folder_name}
                ]
            }]
        },
        networkConfiguration={
            'awsvpcConfiguration': {
                'subnets': ['subnet-0aa9a7252bc916371'],
                'assignPublicIp': 'ENABLED'
            }
        }
    )

    return {"statusCode": 200, "body": f"Triggered labeling for {class_name}"}