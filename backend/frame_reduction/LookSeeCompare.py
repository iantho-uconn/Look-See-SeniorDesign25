import cv2
import os
import boto3
import numpy as np
import urllib.parse
import json
import decimal
import uuid

# to read decimals to prevent errors
class DecimalEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, decimal.Decimal):
            return float(obj)
        return super(DecimalEncoder, self).default(obj)

s3_client = boto3.client("s3")
dynamodb = boto3.resource("dynamodb")
sqs_client = boto3.client("sqs")
bedrock_client = boto3.client("bedrock-runtime", region_name="us-east-1")

QUEUE_URL = os.environ.get('QUEUE_URL')

FRAME_SKIP = 1
SIMILARITY_THRESHOLD = 0.85
RESIZE_WIDTH = 640  
TEMP_VIDEO_PATH = "/tmp/temp_video.mp4"
TEMP_FRAME_DIR = "/tmp/kept_frames"

orb = cv2.ORB_create(nfeatures=500)

def are_frames_similar(frame1, frame2, frame_index):
    gray1 = cv2.cvtColor(frame1, cv2.COLOR_BGR2GRAY)
    gray2 = cv2.cvtColor(frame2, cv2.COLOR_BGR2GRAY)
    
    if cv2.mean(cv2.absdiff(gray1, gray2))[0] < 5.0:
        print(f"  -> Frame {frame_index}: 100.0% similar (Fast Pixel Check)")
        return True
        
    k1, d1 = orb.detectAndCompute(gray1, None)
    k2, d2 = orb.detectAndCompute(gray2, None)
    
    if d1 is None and d2 is None: 
        print(f"  -> Frame {frame_index}: 100.0% similar (Featureless/Blank)")
        return True 
    if d1 is None or d2 is None: 
        print(f"  -> Frame {frame_index}: 0.0% similar (One frame blank)")
        return False 
        
    bf = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=True)
    matches = bf.match(d1, d2)
    if len(matches) == 0: 
        print(f"  -> Frame {frame_index}: 0.0% similar (No matches)")
        return False
        
    score = np.mean([m.distance for m in matches])
    similarity_pct = 1 - (score / 100)
    
    print(f"  -> Frame {frame_index}: {similarity_pct * 100:.1f}% similar")
    return similarity_pct >= SIMILARITY_THRESHOLD

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

        if previous_frame is None or not are_frames_similar(previous_frame, frame, frame_index):
            save_and_upload(frame, output_bucket, folder_path, frame_index, file_prefix)
            previous_frame = frame
            
        frame_index += 1
        
    cap.release()
    if os.path.exists(TEMP_VIDEO_PATH): os.remove(TEMP_VIDEO_PATH)


def lambda_handler(event, context):
    input_bucket = None
    video_key = None

    print(f"Received event: {json.dumps(event)}") 

    if 'detail' in event and 'bucket' in event['detail']:
        input_bucket = event['detail']['bucket']['name']
        video_key = event['detail']['object']['key']

    elif 's3Key' in event and 'bucket' in event:
        input_bucket = event['bucket']
        video_key = event['s3Key']

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
    prompt_list = item.get('prompts') # Changed from aiPrompt to handle lists

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

    if not prompt_list:
        try:
            print(f"🤖 Invoking Bedrock Vision AI (Nova Pro) for reference image: {reference_image_key}")
            image_obj = s3_client.get_object(Bucket=output_bucket, Key=reference_image_key)
            image_bytes = image_obj['Body'].read()

            bedrock_prompt = (
                f"Identify the main target object ({class_name.replace('_', ' ')}) in this image. "
                "Provide 3 distinct physical descriptions of ONLY the target object itself as a JSON array of strings:\n"
                "1. A short 2-3 word noun (e.g. 'red tugboat')\n"
                "2. A structural component description (e.g. 'red and black vessel hull')\n"
                "3. A concise physical description (e.g. 'large red wooden tugboat')\n\n"
                "STRICT RULES:\n"
                "- Do NOT describe the background, floor, grass, sky, walls, or surroundings.\n"
                "- Output ONLY a valid JSON array. Do not include markdown formatting like ```json."
            )
            
            messages = [
                {
                    "role": "user",
                    "content": [
                        {"image": {"format": "jpeg", "source": {"bytes": image_bytes}}},
                        {"text": bedrock_prompt}
                    ]
                }
            ]

            response = bedrock_client.converse(
                modelId="amazon.nova-pro-v1:0", 
                messages=messages,
                inferenceConfig={"maxTokens": 150} 
            )
            
            ai_prompt_text = response['output']['message']['content'][0]['text'].strip()
            
            # CRITICAL FIX: STRIP MARKDOWN 
            if ai_prompt_text.startswith("```json"):
                ai_prompt_text = ai_prompt_text.replace("```json", "").replace("```", "").strip()
            elif ai_prompt_text.startswith("```"):
                ai_prompt_text = ai_prompt_text.replace("```", "").strip()

            try:
                prompts_list = json.loads(ai_prompt_text)
                if not isinstance(prompts_list, list):
                    prompts_list = [ai_prompt_text]
            except Exception as e:
                print(f"⚠️ Failed to parse AI JSON, falling back to raw string: {e}")
                prompts_list = [ai_prompt_text]

            print(f"📝 Generated Multi-Prompts: {prompts_list}")

            table.update_item(
                Key={'submissionId': submission_id},
                UpdateExpression="SET prompts = :p",
                ExpressionAttributeValues={':p': prompts_list}
            )
            
            landmark_id = item.get('landmarkId')
            if landmark_id:
                landmarks_table = dynamodb.Table('LookSeeLandmarks')
                landmarks_table.update_item(
                    Key={'landmarkId': landmark_id},
                    UpdateExpression="SET prompts = :p",
                    ExpressionAttributeValues={':p': prompts_list}
                )
            
            prompt_data_to_save = prompts_list

        except Exception as e:
            print(f"⚠️ Bedrock/DynamoDB integration failed, skipping: {str(e)}")
            prompt_data_to_save = [class_name] 
    else:
        print("⏭️ Prompts already exist in DynamoDB, skipping Bedrock.")
        prompt_data_to_save = prompt_list if isinstance(prompt_list, list) else [prompt_list]

    metadata_content = item.copy()
    metadata_content["class_name"] = class_name
    metadata_content["prompts"] = prompt_data_to_save 
    metadata_content["submissionId"] = submission_id
    
    s3_client.put_object(
        Bucket=output_bucket,
        Key=f"{folder_path}/metadata.json",
        Body=json.dumps(metadata_content, cls=DecimalEncoder),
        ContentType='application/json'
    )

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