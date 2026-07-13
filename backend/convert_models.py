import os
import boto3
import tarfile
import shutil
import zipfile
import json
from datetime import datetime, timezone
from ultralytics import YOLO

AWS_REGION = "us-east-1"
BUCKET = "looksee-models"

# --- S3 LINKS AUTO-FILLED FROM YOUR SAGEMAKER RUN ---
TARGET_CLUSTERS = {
    "0": "s3://looksee-models/sagemaker-training-output-negtest/ls-7756c37f-271b-4d64-81fb-330ac3c32edd-c0/output/model.tar.gz",
    "2": "s3://looksee-models/sagemaker-training-output-negtest/ls-7756c37f-271b-4d64-81fb-330ac3c32edd-c2/output/model.tar.gz",
    "3": "s3://looksee-models/sagemaker-training-output-negtest/ls-7756c37f-271b-4d64-81fb-330ac3c32edd-c3/output/model.tar.gz",
}

s3 = boto3.client('s3', region_name=AWS_REGION)

def zip_directory(folder_path, zip_path):
    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zipf:
        for root, _, files in os.walk(folder_path):
            for file in files:
                abs_path = os.path.join(root, file)
                rel_path = os.path.relpath(abs_path, os.path.dirname(folder_path))
                zipf.write(abs_path, rel_path)

def process_cluster(cluster_id_str, s3_tar_uri):
    print(f"\n🚀 Starting processing for cluster-{cluster_id_str}...")
    
    parts = s3_tar_uri.replace("s3://", "").split("/")
    source_bucket = parts[0]
    source_key = "/".join(parts[1:])
    
    local_tar = f"cluster-{cluster_id_str}_model.tar.gz"
    extract_dir = f"cluster-{cluster_id_str}_extracted"
    
    print(f"📥 Downloading {local_tar} from S3...")
    s3.download_file(source_bucket, source_key, local_tar)

    print(f"📦 Extracting files...")
    os.makedirs(extract_dir, exist_ok=True)
    with tarfile.open(local_tar, "r:gz") as tar:
        tar.extractall(path=extract_dir)

    best_pt = os.path.join(extract_dir, "model.pt") # SageMaker outputs as model.pt
    data_yaml = os.path.join(extract_dir, "data.yaml")
    manifest_path = os.path.join(extract_dir, "landmark-manifest.json")

    # 1. Parse Manifest to get exact UUID and info
    with open(manifest_path, 'r') as f:
        manifest = json.load(f)
        
    training_run_id = str(manifest["trainingRunId"])
    manifest_schema_version = int(manifest["schemaVersion"])
    class_count = int(manifest["classCount"])
    coordinate_system = manifest.get("coordinateSystem", "WGS84")

    # 2. Convert to Apple CoreML
    print(f"🧠 Converting to Apple CoreML format (with NMS)...")
    model = YOLO(best_pt)
    export_path = model.export(format='coreml', nms=True, imgsz=640)

    # 3. Rename and Zip
    mlpackage_path = os.path.join(extract_dir, "model.mlpackage") # Default Ultralytics output
    if not os.path.exists(mlpackage_path) and os.path.exists(export_path):
        mlpackage_path = export_path
        
    renamed_mlpackage = os.path.join(extract_dir, f"{cluster_id_str}.mlpackage")
    shutil.copytree(mlpackage_path, renamed_mlpackage, dirs_exist_ok=True)
    
    zip_filename = f"{cluster_id_str}.mlpackage.zip"
    zip_path = os.path.join(extract_dir, zip_filename)
    
    print(f"🗜️ Zipping CoreML package to {zip_filename}...")
    zip_directory(renamed_mlpackage, zip_path)

    # 4. Generate JSON Files
    pt_filename = f"{cluster_id_str}.pt"
    
    release = {
        "schemaVersion": 1,
        "status": "ready",
        "clusterId": cluster_id_str,
        "modelVersion": training_run_id,
        "coremlZip": zip_filename,
        "landmarkManifest": "landmark-manifest.json",
        "manifestSchemaVersion": manifest_schema_version,
        "coordinateSystem": coordinate_system,
        "classCount": class_count,
        "dataYaml": "data.yaml",
        "weightsPt": pt_filename,
        "sourceModelArtifact": s3_tar_uri,
        "conversionVersion": "mac-manual-script",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
    }

    with open(os.path.join(extract_dir, "release.json"), "w") as f:
        json.dump(release, f, ensure_ascii=False, indent=2)

    conversion_manifest = {
        **release,
        "processingJobName": "mac-local-processing",
        "conversionFormat": "coreml",
        "coremlNms": True,
        "imageSize": 640,
        "int8": False,
        "half": False,
        "optimize": True,
    }

    with open(os.path.join(extract_dir, "conversion-manifest.json"), "w") as f:
        json.dump(conversion_manifest, f, ensure_ascii=False, indent=2)

    # 5. Upload all 6 files to the strict S3 Path!
    dest_prefix = f"ml_conversions/cluster-{cluster_id_str}/{training_run_id}/"

    files_to_upload = [
        (zip_path, zip_filename),
        (best_pt, pt_filename),
        (data_yaml, "data.yaml"),
        (manifest_path, "landmark-manifest.json"),
        (os.path.join(extract_dir, "release.json"), "release.json"),
        (os.path.join(extract_dir, "conversion-manifest.json"), "conversion-manifest.json")
    ]

    print(f"☁️ Uploading to strictly formatted path: {dest_prefix}")
    for local_file, target_name in files_to_upload:
        if os.path.exists(local_file):
            print(f"   -> Uploading {target_name}")
            s3.upload_file(local_file, BUCKET, dest_prefix + target_name)

    # 6. Clean up
    print(f"🧹 Cleaning up local files...")
    shutil.rmtree(extract_dir)
    os.remove(local_tar)

    print(f"✅ Cluster {cluster_id_str} is perfectly converted and uploaded!")

if __name__ == "__main__":
    for cluster_id, s3_link in TARGET_CLUSTERS.items():
        try:
            process_cluster(cluster_id, s3_link)
        except Exception as e:
            print(f"❌ Failed processing cluster {cluster_id}: {str(e)}")
            
    print("\n🎉 All ML Conversions Complete! They are now in their exact UUID folders.")