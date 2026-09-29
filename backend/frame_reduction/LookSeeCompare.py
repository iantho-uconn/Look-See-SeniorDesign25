import cv2
import os
import boto3
import numpy as np
import urllib.parse
import json
import decimal
import uuid
import math

# to read decimals to prevent errors
class DecimalEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, decimal.Decimal):
            return float(obj)
        return super(DecimalEncoder, self).default(obj)

s3_client = boto3.client("s3")
dynamodb = boto3.resource("dynamodb")
sqs_client = boto3.client("sqs")
bedrock_client = boto3.client("bedrock-runtime", region_name=os.environ.get("AWS_REGION", "us-east-1"))

QUEUE_URL = os.environ.get('QUEUE_URL')


from boto3.dynamodb.types import TypeSerializer
from botocore.exceptions import ClientError

DELETIONS_TABLE = os.environ.get("DELETIONS_TABLE", "LookSeeLandmarkDeletions")
LANDMARKS_TABLE = os.environ.get("LANDMARKS_TABLE", "LookSeeLandmarks")
SUBMISSIONS_TABLE = os.environ.get("TABLE_NAME", "LookSeeSubmissions")
OUTPUT_BUCKET = os.environ.get("OUTPUT_BUCKET", "looksee-models")
ddb_client = boto3.client("dynamodb")
_serializer = TypeSerializer()

class ProcessingStopped(Exception):
    pass

class ProcessingGuard:
    def __init__(self, submission_id, bucket, key):
        self.submission_id, self.bucket, self.key = submission_id, bucket, key
        self.item = dynamodb.Table(SUBMISSIONS_TABLE).get_item(
            Key={"submissionId": submission_id}, ConsistentRead=True).get("Item")
        if not self.item:
            raise ProcessingStopped("submission_missing")
        self.landmark_id = self.item.get("boundLandmarkId") or self.item.get("landmarkId")
        if not self.landmark_id:
            raise ProcessingStopped("landmark_binding_missing")
        if self.item.get("landmarkId") not in (None, self.landmark_id):
            raise ProcessingStopped("landmark_binding_mismatch")
        self.bucket_field = "sourceBucket" if self.item.get("sourceBucket") else "bucket"
        if self.item.get(self.bucket_field) != bucket or self.item.get("s3Key") != key:
            raise ProcessingStopped("source_binding_mismatch")
        self.binding_field = "boundLandmarkId" if self.item.get("boundLandmarkId") else "landmarkId"
        self.check()

    def check(self):
        marker = dynamodb.Table(DELETIONS_TABLE).get_item(
            Key={"landmarkId": self.landmark_id}, ConsistentRead=True).get("Item")
        if marker is not None:
            raise ProcessingStopped("deletion_requested")
        landmark = dynamodb.Table(LANDMARKS_TABLE).get_item(
            Key={"landmarkId": self.landmark_id}, ConsistentRead=True).get("Item")
        if not landmark:
            raise ProcessingStopped("landmark_missing")
        if "deletionStatus" in landmark or landmark.get("status") in ("DELETING", "DELETED"):
            raise ProcessingStopped("deletion_requested")
        current = dynamodb.Table(SUBMISSIONS_TABLE).get_item(
            Key={"submissionId": self.submission_id}, ConsistentRead=True).get("Item")
        if not current:
            raise ProcessingStopped("submission_missing")
        if (current.get(self.binding_field) != self.landmark_id or
                current.get(self.bucket_field) != self.bucket or current.get("s3Key") != self.key):
            raise ProcessingStopped("source_binding_changed")

    def update(self, expression, values, names=None):
        self.check()
        def av(data):
            return {k: _serializer.serialize(v) for k, v in data.items()}
        sn = dict(names or {})
        sn.update({"#binding": self.binding_field, "#bucket": self.bucket_field})
        sv = dict(values)
        sv.update({":guardLandmark": self.landmark_id, ":guardBucket": self.bucket, ":guardKey": self.key})
        ln = dict(names or {})
        ln["#guardStatus"] = "status"
        lv = dict(values)
        lv.update({":guardDeleting": "DELETING", ":guardDeleted": "DELETED"})
        actions = [
            {"ConditionCheck": {"TableName": DELETIONS_TABLE,
                "Key": av({"landmarkId": self.landmark_id}),
                "ConditionExpression": "attribute_not_exists(landmarkId)"}},
            {"Update": {"TableName": SUBMISSIONS_TABLE,
                "Key": av({"submissionId": self.submission_id}),
                "UpdateExpression": expression,
                "ConditionExpression": "attribute_exists(submissionId) AND #binding = :guardLandmark AND #bucket = :guardBucket AND s3Key = :guardKey",
                "ExpressionAttributeNames": sn, "ExpressionAttributeValues": av(sv)}},
            {"Update": {"TableName": LANDMARKS_TABLE,
                "Key": av({"landmarkId": self.landmark_id}),
                "UpdateExpression": expression,
                "ConditionExpression": "attribute_exists(landmarkId) AND attribute_not_exists(deletionStatus) AND (attribute_not_exists(#guardStatus) OR (#guardStatus <> :guardDeleting AND #guardStatus <> :guardDeleted))",
                "ExpressionAttributeNames": ln, "ExpressionAttributeValues": av(lv)}}]
        try:
            ddb_client.transact_write_items(TransactItems=actions)
        except ClientError:
            self.check()  # Convert deletion races to a clean skip; retain real AWS failures.
            raise


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

def save_and_upload(frame, output_bucket, folder_path, frame_index, file_prefix, guard):
    unique_name = f"{file_prefix}__frame_{frame_index}.jpg"
    local_path = f"{TEMP_FRAME_DIR}/{unique_name}"
    cv2.imwrite(local_path, frame)
    guard.check()
    s3_client.upload_file(local_path, output_bucket, f"{folder_path}/{unique_name}")
    guard.check()
    os.remove(local_path)

def process_video(input_bucket, video_key, output_bucket, folder_path, file_prefix, guard):
    if not os.path.exists(TEMP_FRAME_DIR): 
        os.makedirs(TEMP_FRAME_DIR)
    guard.check()
    s3_client.download_file(input_bucket, video_key, TEMP_VIDEO_PATH)
    guard.check()
    cap = cv2.VideoCapture(TEMP_VIDEO_PATH)
    previous_frame, frame_index = None, 0
    saved_count = 0 

    try:
        while True:
            if frame_index % 30 == 0:
                guard.check()
            ret, frame = cap.read()
            if not ret: 
                break
            if frame_index % FRAME_SKIP != 0:
                frame_index += 1
                continue
                
            original_height, original_width = frame.shape[:2]
            aspect_ratio = original_width / original_height
            new_width = RESIZE_WIDTH
            new_height = int(RESIZE_WIDTH / aspect_ratio)
            
            frame = cv2.resize(frame, (new_width, new_height))
    
            if previous_frame is None or not are_frames_similar(previous_frame, frame, frame_index):
                save_and_upload(frame, output_bucket, folder_path, frame_index, file_prefix, guard)
                previous_frame = frame
                saved_count += 1 
                
            frame_index += 1
            
    finally:
        cap.release()
        if os.path.exists(TEMP_VIDEO_PATH):
            os.remove(TEMP_VIDEO_PATH)
    return saved_count


def process_object(input_bucket, video_key):
    output_bucket = OUTPUT_BUCKET
    path_parts = video_key.split('/')
    if len(path_parts) < 2:
        raise ValueError("Invalid source key")
    submission_id = path_parts[-2]
    guard = ProcessingGuard(submission_id, input_bucket, video_key)
    item = guard.item
    raw_label = item.get('label')
    landmark_id = guard.landmark_id
    
    if not raw_label:
        raw_label = landmark_id if landmark_id else 'Unknown'
        
    class_name = str(raw_label).replace(" ", "_")
    prompt_list = item.get('prompts') 

    # 🚀 UNIFIED S3 FOLDER PATH: Group by landmarkId so all uploads merge!
    folder_identifier = landmark_id if landmark_id else submission_id
    folder_name = f"{folder_identifier}_{class_name}"
    folder_path = f"clean-frames/{folder_name}"

    upload_hash = uuid.uuid4().hex[:6]
    file_prefix = f"{class_name}_{upload_hash}"
    
    new_saved_count = 0

    if "/images/" in video_key:
        original_filename = video_key.split('/')[-1]
        target_filename = f"{file_prefix}__{original_filename}" 
        guard.check()
        s3_client.copy({'Bucket': input_bucket, 'Key': video_key}, output_bucket, f"{folder_path}/{target_filename}")
        guard.check()
        reference_image_key = f"{folder_path}/{target_filename}"
        new_saved_count = 1
    else:
        new_saved_count = process_video(input_bucket, video_key, output_bucket, folder_path, file_prefix, guard)
        reference_image_key = f"{folder_path}/{file_prefix}__frame_0.jpg" 
        
    print(f"Extracted {new_saved_count} frames from the latest upload.")

    guard.check()
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

            guard.update("SET prompts = :p", {":p": prompts_list})
            prompt_data_to_save = prompts_list

        except ProcessingStopped:
            raise
        except ClientError:
            raise
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
    if landmark_id:
        metadata_content["landmarkId"] = landmark_id
    
    guard.check()
    s3_client.put_object(
        Bucket=output_bucket,
        Key=f"{folder_path}/metadata.json",
        Body=json.dumps(metadata_content, cls=DecimalEncoder),
        ContentType='application/json'
    )

    guard.check()

    # ---------------------------------------------------------------------------
    # 🚀 REAL-TIME SHORTFALL CHECK & DYNAMODB STATUS UPDATE 
    # ---------------------------------------------------------------------------
    try:
        print("\n🚀 Scanning S3 Folder to calculate Absolute Total Frame Count...")
        
        total_saved_frames = 0
        paginator = s3_client.get_paginator('list_objects_v2')
        for page in paginator.paginate(Bucket=output_bucket, Prefix=f"{folder_path}/"):
            for obj in page.get('Contents', []):
                # Only count actual image files, ignore metadata.json
                if obj['Key'].lower().endswith(('.jpg', '.jpeg', '.png')):
                    total_saved_frames += 1

        print(f"✅ Absolute Total Frames in S3 for {folder_name}: {total_saved_frames}")

        required_frames = int(item.get('minRequiredFrames', int(os.environ.get('MIN_IMAGES_PER_CLASS', 2000))))
        
        if total_saved_frames < required_frames:
            missing_frames = required_frames - total_saved_frames
            seconds_needed = math.ceil(missing_frames / 30.0)
            status = 'NEEDS_MORE_MEDIA'
            
            print(f"❌ SHORTFALL DETECTED: {total_saved_frames}/{required_frames} clean frames. User needs ~{seconds_needed}s more video.")
            
            update_expr = "SET #st = :s, cleanFrameCount = :c, requiredFrames = :r, secondsNeeded = :sec"
            expr_vals = {
                ':s': status,
                ':c': total_saved_frames,
                ':r': required_frames,
                ':sec': seconds_needed
            }
        else:
            # 🚀 THE FIX: Assign the correct pipeline status 
            status = 'PREPARING_DATA'
            print(f"✅ MEDIA THRESHOLD MET: {total_saved_frames}/{required_frames} clean frames. Sending to data prep!")
            
            update_expr = "SET #st = :s, cleanFrameCount = :c, requiredFrames = :r"
            expr_vals = {
                ':s': status,
                ':c': total_saved_frames,
                ':r': required_frames
            }

        # Update both records atomically with the deletion marker check.
        guard.update(update_expr, expr_vals, {'#st': 'status'})
        if status == 'PREPARING_DATA' and QUEUE_URL:
            guard.check()
            sqs_client.send_message(
                QueueUrl=QUEUE_URL,
                MessageBody=json.dumps({"folder_name": folder_name,
                                        "landmarkId": landmark_id,
                                        "submissionId": submission_id}))
            guard.check()
            print(f"Sent autolabel task for {folder_name}")
    except ProcessingStopped:
        raise
    except Exception:
        # Raise so Lambda/SQS can retry infrastructure failures.
        raise

    return {"statusCode": 200, "body": f"Processing complete. Status: {status}"}

def _objects(event):
    # Only S3 notification keys use form-url encoding. EventBridge/direct keys are literal.
    if event.get("Event") == "s3:TestEvent":
        return
    if "Records" in event:
        for record in event["Records"]:
            if record.get("eventSource") == "aws:sqs":
                yield from _objects(json.loads(record["body"]))
            elif "s3" in record:
                yield (record["s3"]["bucket"]["name"],
                       urllib.parse.unquote_plus(record["s3"]["object"]["key"]))
            else:
                raise ValueError("Unsupported record")
    elif "detail" in event and "bucket" in event["detail"]:
        yield event["detail"]["bucket"]["name"], event["detail"]["object"]["key"]
    elif "s3Key" in event and "bucket" in event:
        yield event["bucket"], event["s3Key"]
    else:
        raise ValueError("No valid bucket or key found")


def lambda_handler(event, context):
    results = []
    for bucket, key in _objects(event):
        try:
            results.append(process_object(bucket, key))
        except ProcessingStopped as exc:
            result = {"status": "SKIPPED", "reason": str(exc), "sourceKey": key}
            print(json.dumps(result))
            results.append({"statusCode": 200, "body": json.dumps(result)})
        finally:
            if os.path.exists(TEMP_VIDEO_PATH):
                os.remove(TEMP_VIDEO_PATH)
            if os.path.isdir(TEMP_FRAME_DIR):
                for filename in os.listdir(TEMP_FRAME_DIR):
                    os.remove(os.path.join(TEMP_FRAME_DIR, filename))
    return results[0] if len(results) == 1 else {"statusCode": 200, "results": results}
