import cv2
import os
import boto3
import numpy as np
import urllib.parse
import json
import decimal
import base64
import uuid

# to read decimals to prevent errors
class DecimalEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, decimal.Decimal):
            return float(obj)
        return super(DecimalEncoder, self).default(obj)

s3_client = boto3.client("s3")
dynamodb = boto3.resource("dynamodb")
sqs_client = boto3.client("sqs")  # CHANGED: ECS to SQS
bedrock_client = boto3.client("bedrock-runtime", region_name="us-east-1")

QUEUE_URL = os.environ.get('QUEUE_URL') # ADD THIS TO LAMBDA ENV VARIABLES

FRAME_SKIP = 1
SIMILARITY_THRESHOLD = 0.85
RESIZE_WIDTH = 640  
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

def save_and_upload(frame, output_bucket, folder_path, frame_index, file_prefix):
    unique_name = f"{file_prefix}__frame_{frame_index}.jpg"
    local_path = f"{TEMP_FRAME_DIR}/{unique_name}"
    cv2.imwrite(local_path, frame)
    s3_client.upload_file(local_path, output_bucket, f"{folder_path}/{unique_name}")
    os.remove(local_path)

def process_video(input_bucket, video_key, output_bucket, folder_path, file_prefix):
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
            
        original_height, original_width = frame.shape[:2]
        aspect_ratio = original_width / original_height
        new_width = RESIZE_WIDTH
        new_height = int(RESIZE_WIDTH / aspect_ratio)
        
        frame = cv2.resize(frame, (new_width, new_height))

        if previous_frame is None or not are_frames_similar(previous_frame, frame):
            save_and_upload(frame, output_bucket, folder_path, frame_index, file_prefix)
            previous_frame = frame
            
        frame_index += 1
        
    cap.release()
    if os.path.exists(TEMP_VIDEO_PATH): os.remove(TEMP_VIDEO_PATH)


def lambda_handler(event, context):
    input_bucket = None
    video_key = None

    print(f"Received event: {json.dumps(event)}") 

    # 1. EventBridge Format
    if 'detail' in event and 'bucket' in event['detail']:
        input_bucket = event['detail']['bucket']['name']
        video_key = event['detail']['object']['key']

    # 2. Step Functions Format
    elif 's3Key' in event and 'bucket' in event:
        input_bucket = event['bucket']
        video_key = event['s3Key']

    # 3. Direct S3 or SQS Format
    elif 'Records' in event:
        record = event['Records'][0]
        if record.get('eventSource') == 'aws:sqs':
            sqs_body = json.loads(record['body'])
            if sqs_body.get('Event') == 's3:TestEvent':
                return {"statusCode": 200, "body": "Skipped test event"}
            if 'Records' in sqs_body:
                input_bucket = sqs_body['Records'][0]['s3']['bucket']['name']
                video_key = sqs_body['Records'][0]['s3']['object']['key']
        elif 's3' in record:
            input_bucket = record['s3']['bucket']['name']
            video_key = record['s3']['object']['key']

    if not input_bucket or not video_key:
        print(f"Skipping unrecognized event format: {event}")
        return {"statusCode": 400, "body": "No valid bucket or key found"}

    video_key = urllib.parse.unquote_plus(video_key)
    output_bucket = "looksee-models"
    
    path_parts = video_key.split('/')
    submission_id = path_parts[-2] 

    table = dynamodb.Table('LookSeeSubmissions')
    db_response = table.get_item(Key={'submissionId': submission_id})
    item = db_response.get('Item', {})

    raw_label = item.get('label')
    if not raw_label:
        raw_label = item.get('landmarkId', 'Unknown')
        
    class_name = str(raw_label).replace(" ", "_")
    prompt = item.get('aiPrompt')

    folder_name = f"{submission_id}_{class_name}"
    folder_path = f"clean-frames/{folder_name}"

    upload_hash = uuid.uuid4().hex[:6]
    file_prefix = f"{class_name}_{upload_hash}"
    
    if "/images/" in video_key:
        original_filename = video_key.split('/')[-1]
        target_filename = f"{file_prefix}__{original_filename}" 
        s3_client.copy({'Bucket': input_bucket, 'Key': video_key}, output_bucket, f"{folder_path}/{target_filename}")
        reference_image_key = f"{folder_path}/{target_filename}"
    else:
        process_video(input_bucket, video_key, output_bucket, folder_path, file_prefix)
        reference_image_key = f"{folder_path}/{file_prefix}__frame_0.jpg" 

    if not prompt:
        try:
            print(f"🤖 Invoking Bedrock Vision AI for reference image: {reference_image_key}")
            image_obj = s3_client.get_object(Bucket=output_bucket, Key=reference_image_key)
            image_bytes = image_obj['Body'].read()
            encoded_image = base64.b64encode(image_bytes).decode('utf-8')

            bedrock_prompt = "Describe the main landmark or historical object in this image in high detail. Focus on physical appearance, text on it, materials, and structure. Keep it under two sentences."
            
            body = json.dumps({
                "anthropic_version": "bedrock-2023-05-31",
                "max_tokens": 150,
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "image",
                                "source": {
                                    "type": "base64",
                                    "media_type": "image/jpeg",
                                    "data": encoded_image
                                }
                            },
                            {
                                "type": "text",
                                "text": bedrock_prompt
                            }
                        ]
                    }
                ]
            })

            response = bedrock_client.invoke_model(
                modelId="global.anthropic.claude-haiku-4-5-20251001-v1:0",
                body=body
            )
            
            response_body = json.loads(response.get('body').read())
            ai_prompt_text = response_body['content'][0]['text']
            print(f"📝 Generated aiPrompt description: {ai_prompt_text}")

            table.update_item(
                Key={'submissionId': submission_id},
                UpdateExpression="SET aiPrompt = :p",
                ExpressionAttributeValues={':p': ai_prompt_text}
            )
            
            landmark_id = item.get('landmarkId')
            if landmark_id:
                landmarks_table = dynamodb.Table('LookSeeLandmarks')
                landmarks_table.update_item(
                    Key={'landmarkId': landmark_id},
                    UpdateExpression="SET aiPrompt = :p",
                    ExpressionAttributeValues={':p': ai_prompt_text}
                )
            
            prompt = ai_prompt_text

        except Exception as e:
            print(f"⚠️ Bedrock/DynamoDB integration failed, skipping: {str(e)}")
            prompt = class_name 
    else:
        print("⏭️ aiPrompt already exists in DynamoDB, skipping Bedrock.")

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

    # =========================================================================
    # --- NEW SQS INJECTION LOGIC ---
    # =========================================================================
    if QUEUE_URL:
        try:
            sqs_response = sqs_client.send_message(
                QueueUrl=QUEUE_URL,
                MessageBody=folder_name
            )
            print(f"✅ SUCCESS: Pushed {folder_name} to SQS queue. MessageId: {sqs_response['MessageId']}")
        except Exception as e:
            print(f"❌ ERROR: Failed to push to SQS: {str(e)}")
    else:
        print("⚠️ WARNING: QUEUE_URL environment variable is missing. Folder processed but NOT queued for ECS.")

    return {"statusCode": 200, "body": f"Processing complete for {video_key}"}