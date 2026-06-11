import os
import re
import shutil
import json
import subprocess
from pathlib import Path

INPUT_DIR = "/opt/ml/processing/input/weights/"
OUTPUT_DIR = "/opt/ml/processing/output/"

def extract_cluster_number(cluster_id: str) -> str:
    s = (cluster_id or "").strip()
    m = re.search(r"-c-?(\d+)$", s)
    if m:
        return m.group(1)
    if s.isdigit():
        return s
    digits = "".join(ch for ch in s if ch.isdigit())
    return digits if digits else "0"

def ensure_ultralytics():
    try:
        import ultralytics
        import coremltools
        return
    except ImportError:
        pass

    subprocess.check_call([
        "pip", "install", "-q", "--root-user-action=ignore",
        "typing_extensions>=4.14.0", "coremltools>=7.0", "ultralytics"
    ])

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    
    # Locate any .pt file in the input directory
    pt_files = [f for f in os.listdir(INPUT_DIR) if f.endswith('.pt')]
    if not pt_files:
        raise FileNotFoundError(f"No .pt files found in {INPUT_DIR}")
    
    input_pt_path = os.path.join(INPUT_DIR, pt_files[0])
    
    # Handle Cluster ID from Environment or Filename
    raw_cluster = os.environ.get("CLUSTER_ID", "default_region")
    if raw_cluster == "default_region":
        raw_cluster = pt_files[0].replace(".pt", "")
        
    cluster_num = extract_cluster_number(raw_cluster)
    
    ensure_ultralytics()
    from ultralytics import YOLO 

    model = YOLO(input_pt_path)
    export_path = model.export(
        format="coreml",
        nms=True,
        imgsz=640,
        int8=False,
        half=False,
        optimize=True,
    )

    export_dir = os.path.dirname(export_path)
    export_name = os.path.basename(export_path) 

    base_name = f"{cluster_num}.mlpackage"
    zip_name = f"{base_name}.zip"

    shutil.make_archive(base_name, "zip", root_dir=export_dir, base_dir=export_name)
    shutil.move(zip_name, os.path.join(OUTPUT_DIR, zip_name))

    pt_out = f"{cluster_num}.pt"
    shutil.copyfile(input_pt_path, os.path.join(OUTPUT_DIR, pt_out))

    manifest = {
        "clusterId": raw_cluster,
        "clusterNumber": cluster_num,
        "coremlZip": zip_name,
        "weightsPt": pt_out
    }
    with open(os.path.join(OUTPUT_DIR, "manifest.json"), "w") as f:
        json.dump(manifest, f)

if __name__ == "__main__":
    main()