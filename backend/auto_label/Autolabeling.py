import os
import cv2
import torch
import boto3
import json
from autodistill_grounding_dino import GroundingDINO
from autodistill.detection import CaptionOntology

# -----------------------------------------
# AWS SETUP
# -----------------------------------------
s3 = boto3.client('s3')
BUCKET_NAME = 'looksee-models'

# Get the folder name passed from the Lambda override
SUBMISSION_FOLDER = os.environ.get('SUBMISSION_FOLDER')
INPUT_PREFIX = f"clean-frames/{SUBMISSION_FOLDER}/"

if not SUBMISSION_FOLDER:
    print("Error: No SUBMISSION_FOLDER environment variable found.")
    exit(1)

# -----------------------------------------
# 1. LOAD SUBMISSION METADATA
# -----------------------------------------
metadata_key = f"{INPUT_PREFIX}metadata.json"
local_metadata_path = "/tmp/metadata.json"

print(f"Downloading metadata: {metadata_key}")
s3.download_file(BUCKET_NAME, metadata_key, local_metadata_path)

with open(local_metadata_path, 'r') as f:
    metadata = json.load(f)

class_name = metadata.get('class_name', 'UnknownObject').replace(" ", "_")
prompt = metadata.get('prompt', class_name)

print(f"Processing submission: {SUBMISSION_FOLDER}")
print(f"Target Object: {class_name} | AI Prompt: {prompt}")

# -----------------------------------------
# 2. SETUP MODEL
# -----------------------------------------
device = "cuda" if torch.cuda.is_available() else "cpu"
ontology = CaptionOntology({prompt: class_name})
model = GroundingDINO(ontology=ontology)

# -----------------------------------------
# 3. PROCESS FOLDER
# -----------------------------------------
response = s3.list_objects_v2(Bucket=BUCKET_NAME, Prefix=INPUT_PREFIX)
if 'Contents' not in response:
    print("No frames found in S3 folder.")
    exit(0)

count = 0
for obj in response['Contents']:
    img_key = obj['Key']
    if not img_key.lower().endswith((".jpg", ".png", ".jpeg")):
        continue

    img_name = os.path.basename(img_key)
    local_img_path = f"/tmp/{img_name}"
    
    # Download image from S3
    s3.download_file(BUCKET_NAME, img_key, local_img_path)
    image = cv2.imread(local_img_path)
    detections = model.predict(image)

    # Skip frame if AI finds nothing
    if len(detections.xyxy) == 0:
        os.remove(local_img_path)
        continue

    # Convert to YOLO format
    h, w, _ = image.shape
    yolo_lines = []
    for box in detections.xyxy:
        x_min, y_min, x_max, y_max = box
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
    count += 1

print(f"DONE: Successfully labeled {count} images for {class_name}.")