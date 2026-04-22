import os
os.system('pip install ultralytics')
import shutil
import json
from ultralytics import YOLO

input_path = '/opt/ml/processing/input/weights/best.pt'
output_dir = '/opt/ml/processing/output/'

def sanitize(s: str) -> str:
    return "".join(c if (c.isalnum() or c in "-_") else "_" for c in (s or "")) or "default_region"

cluster_id = sanitize(os.environ.get("CLUSTER_ID", "default_region"))

model = YOLO(input_path)
export_path = model.export(format='coreml', nms=True, imgsz=640, int8=False, half=False, optimize=True)

# Name should be: <cluster_id>.mlpackage.zip
base_name = f"{cluster_id}.mlpackage"
final_filename = f"{base_name}.zip"

# Zip the .mlpackage folder correctly (keeps bundle structure)
export_dir = os.path.dirname(export_path)
export_name = os.path.basename(export_path)

shutil.make_archive(base_name, "zip", root_dir=export_dir, base_dir=export_name)
shutil.move(final_filename, os.path.join(output_dir, final_filename))

manifest_data = {
    "clusterId": cluster_id,
    "filename": final_filename
}

with open(os.path.join(output_dir, 'manifest.json'), 'w') as f:
    json.dump(manifest_data, f)